package org.retrotv.pad
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Android HID transport for the [HidGamepad] descriptor and reports. */
@SuppressLint("MissingPermission")
class HidSession(private val context:Context,private val listener:Listener):AutoCloseable {
    interface Listener { fun status(text:String);fun ready();fun connected(device:BluetoothDevice?);fun unsupported() }
    private val adapter=context.getSystemService(BluetoothManager::class.java)?.adapter
    private val main=Handler(Looper.getMainLooper())
    private var hid:BluetoothHidDevice?=null
    private var registered=false
    @Volatile private var closed=false
    private var target:BluetoothDevice?=null
    @Volatile private var device:BluetoothDevice?=null
    private val executor=Executors.newSingleThreadExecutor{r->Thread(r,"RetroTV-HID").apply{isDaemon=true}}
    private val pending=ReportBuffer()
    private val sending=AtomicBoolean(false)
    private val neutral=HidGamepad.report(PadState())
    @Volatile private var current=neutral.copyOf()
    private var connectionSerial=0
    // Registration stages, kept so the phone can show exactly where HID stopped.
    private val started=android.os.SystemClock.elapsedRealtime()
    private val events=ArrayDeque<String>()
    private var proxyConnected=false
    private var registerAttempts=0
    private var failure:String?=null
    private fun note(text:String){synchronized(events){events.addLast("+%.1fs %s".format((android.os.SystemClock.elapsedRealtime()-started)/1000f,text));while(events.size>40)events.removeFirst()}}
    fun diagnostics():String{
        val head="Serviço HID aberto: ${if(proxyConnected)"sim" else "não"}\nRegistrado: ${if(registered)"sim" else "não"} · tentativas: $registerAttempts\nConectado: ${device?.name?:"não"}"+(failure?.let{"\nÚltima falha: $it"}?:"")
        return head+"\n\nEventos:\n"+synchronized(events){events.joinToString("\n")}
    }
    private var attempts=0
    private var waiting:BluetoothDevice?=null
    private var bonding:BluetoothDevice?=null
    private var watching=false
    // Follows the adapter (Bluetooth switched on later) and the pairing started from the phone.
    private val system=object:BroadcastReceiver(){
        override fun onReceive(c:Context?,intent:Intent?){
            if(closed)return
            when(intent?.action){
                BluetoothAdapter.ACTION_STATE_CHANGED->when(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,-1)){
                    BluetoothAdapter.STATE_ON->openProfile()
                    BluetoothAdapter.STATE_OFF->{registered=false;device=null;main.removeCallbacks(heartbeat);pending.clear();listener.connected(null);listener.status("Bluetooth desligado. Ative para conectar.")}
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED->{
                    val host=if(Build.VERSION.SDK_INT>=33)intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if(host==null||host.address!=bonding?.address)return
                    when(intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,BluetoothDevice.BOND_NONE)){
                        BluetoothDevice.BOND_BONDING->listener.status("Confirme o mesmo código na TV e no celular…")
                        BluetoothDevice.BOND_BONDED->{bonding=null;listener.status("Pareado com "+(host.name?:"a TV")+". Conectando…");main.postDelayed({if(!closed)connect(host)},600)}
                        BluetoothDevice.BOND_NONE->{bonding=null;target=null;listener.status("Pareamento recusado ou cancelado. Tente de novo.")}
                    }
                }
            }
        }
    }
    private val callback=object:BluetoothHidDevice.Callback(){
        override fun onAppStatusChanged(pluggedDevice:BluetoothDevice?,isRegistered:Boolean){
            if(closed)return
            registered=isRegistered
            note("onAppStatusChanged registered=$isRegistered")
            if(!isRegistered&&!registered&&registerAttempts in 1..3){failure="O Android cancelou o registro do controle.";retryRegister();return}
            if(isRegistered){failure=null;listener.status("Pronto para conectar à TV");listener.ready()
                val next=waiting;waiting=null
                (next?:pluggedDevice?.takeIf{it.bondState==BluetoothDevice.BOND_BONDED})?.let{connect(it)}
            }else{device=null;main.removeCallbacks(heartbeat);pending.clear();current=neutral.copyOf();listener.connected(null);listener.status("HID liberado pelo Android. Toque em Conectar para tentar novamente.")}
        }
        override fun onConnectionStateChanged(host:BluetoothDevice,state:Int){
            if(closed)return
            when(state){
                BluetoothProfile.STATE_CONNECTED->{
                    if(host.bondState!=BluetoothDevice.BOND_BONDED||target?.address?.let{it!=host.address}==true){hid?.disconnect(host);return}
                    device=host;target=host;attempts=0;current=neutral.copyOf();listener.status("Conectado a "+(host.name?:"TV"));listener.connected(host);send(PadState());main.removeCallbacks(heartbeat);main.post(heartbeat)
                }
                BluetoothProfile.STATE_CONNECTING->listener.status("Conectando à TV…")
                BluetoothProfile.STATE_DISCONNECTED->if(device?.address==host.address||target?.address==host.address){
                    device=null;main.removeCallbacks(heartbeat);pending.clear();current=neutral.copyOf();listener.connected(null);listener.status("TV desconectada. Toque em Conectar para retomar.")}
            }
        }
        override fun onGetReport(host:BluetoothDevice,type:Byte,id:Byte,bufferSize:Int){
            if(closed||host.address!=device?.address)return
            val report=if(id.toInt()==HidGamepad.REPORT_ID)current.copyOf() else null
            if(type!=BluetoothHidDevice.REPORT_TYPE_INPUT||report==null)hid?.reportError(host,BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
            else hid?.replyReport(host,type,id,if(bufferSize>0)report.copyOf(minOf(bufferSize,report.size))else report)
        }
        override fun onSetReport(host:BluetoothDevice,type:Byte,id:Byte,data:ByteArray){
            if(!closed&&host.address==device?.address)hid?.reportError(host,BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
        }
        override fun onVirtualCableUnplug(host:BluetoothDevice){if(!closed){device=null;target=null;main.removeCallbacks(heartbeat);listener.connected(null);listener.status("A TV removeu o pareamento do controle.")}}
    }
    private val proxy=object:BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile:Int,service:BluetoothProfile){
            if(closed){adapter?.closeProfileProxy(profile,service);return}
            hid=service as? BluetoothHidDevice;proxyConnected=hid!=null;note("serviço HID aberto: ${hid!=null}")
            register()
        }
        override fun onServiceDisconnected(profile:Int){note("serviço HID fechado pelo Android");if(!closed){hid=null;registered=false;device=null;listener.connected(null);listener.status("Serviço Bluetooth desconectado.")}}
    }
    /** Android allows one HID app at a time; a registration still being released by a previous session is refused, so retry. */
    private fun register(){
        val profile=hid?:return
        if(closed||registered)return
        registerAttempts++
        val accepted=try{
            val sdp=BluetoothHidDeviceAppSdpSettings("RetroTV Pad","Controle por console","RetroTV",BluetoothHidDevice.SUBCLASS2_GAMEPAD,HidGamepad.DESCRIPTOR)
            profile.registerApp(sdp,null,null,context.mainExecutor,callback)
        }catch(e:Exception){note("registerApp exceção: ${e.javaClass.simpleName} ${e.message}");failure="Exceção ao registrar: ${e.message}";false}
        note("registerApp tentativa $registerAttempts aceito=$accepted")
        if(!accepted){if(failure==null||failure!!.startsWith("O Android"))failure="O Android recusou o registro: outro app de controle Bluetooth está ativo ou o registro anterior ainda não foi liberado.";retryRegister()}
    }
    private fun retryRegister(){
        if(closed)return
        if(registerAttempts>=4){listener.status("Não foi possível ativar o modo controle. Feche outros apps de controle/teclado Bluetooth, desligue e ligue o Bluetooth e tente de novo.");return}
        listener.status("Liberando o Bluetooth para o controle… (tentativa ${registerAttempts+1} de 4)")
        runCatching{hid?.unregisterApp()}
        main.postDelayed({if(!closed&&!registered)register()},1500)
    }
    fun retry(){if(closed||registered)return;if(hid==null)openProfile() else{registerAttempts=0;failure=null;register()}}
    private val heartbeat=object:Runnable{override fun run(){if(closed)return;if(device!=null){pending.offer(current);drain()};main.postDelayed(this,250)}}
    fun start(){
        if(closed)return
        if(!watching){val filter=IntentFilter().apply{addAction(BluetoothAdapter.ACTION_STATE_CHANGED);addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)}
            runCatching{if(Build.VERSION.SDK_INT>=33)context.registerReceiver(system,filter,Context.RECEIVER_EXPORTED) else context.registerReceiver(system,filter);watching=true}}
        openProfile()
    }
    private fun openProfile(){
        if(closed||hid!=null)return
        when{
            adapter==null->listener.status("Este aparelho não tem adaptador Bluetooth.")
            !adapter.isEnabled->listener.status("Ative o Bluetooth do celular para conectar.")
            else->{
                listener.status("Preparando controle Bluetooth…")
                val opened=try{adapter.getProfileProxy(context,proxy,BluetoothProfile.HID_DEVICE)}catch(e:Exception){note("getProfileProxy exceção: ${e.message}");false}
                note("getProfileProxy=$opened")
                if(!opened){failure="O Android não abriu o serviço HID Device.";listener.status("Bluetooth HID Device indisponível neste aparelho.");return}
                // Report the stage that actually stalled instead of a generic message.
                main.postDelayed({if(!closed&&!registered&&failure==null&&!proxyConnected){note("serviço HID não abriu: perfil indisponível");listener.unsupported()} else if(!closed&&!registered&&failure==null)listener.status(if(!proxyConnected)"O Android não abriu o serviço HID (perfil possivelmente desativado pelo fabricante). Veja Diagnóstico Bluetooth." else "O Android não confirmou o registro do controle. Desligue e ligue o Bluetooth e tente de novo.")},10000)
            }
        }
    }
    /** Pairs first when needed (the TV shows a code), then connects with up to three attempts. */
    fun connect(host:BluetoothDevice){
        if(closed)return
        if(!registered){waiting=host;listener.status("Preparando o controle… a conexão começa em seguida.");return}
        if(device?.address==host.address){listener.connected(host);return}
        val previous=device
        if(previous!=null&&previous.address!=host.address){listener.status("Desconecte a TV atual antes de escolher outra.");return}
        target=host
        if(host.bondState!=BluetoothDevice.BOND_BONDED){
            bonding=host;runCatching{adapter?.cancelDiscovery()}
            val started=runCatching{host.bondState==BluetoothDevice.BOND_BONDING||host.createBond()}.getOrDefault(false)
            listener.status(if(started)"Pareando com "+(host.name?:"a TV")+"… confirme o código na TV." else "Não foi possível iniciar o pareamento. Tente parear pela TV.")
            if(!started)bonding=null
            return
        }
        attempts=0;attempt(host)
    }
    private fun attempt(host:BluetoothDevice){
        if(closed||device!=null)return
        attempts++;val serial=++connectionSerial
        listener.status(if(attempts==1)"Conectando a "+(host.name?:"TV")+"…" else "Conectando a "+(host.name?:"TV")+" (tentativa $attempts de 3)…")
        val accepted=runCatching{hid?.connect(host)==true}.getOrDefault(false)
        main.postDelayed({
            if(closed||device!=null||serial!=connectionSerial)return@postDelayed
            if(attempts<3){runCatching{hid?.disconnect(host)};main.postDelayed({if(!closed&&device==null&&serial==connectionSerial)attempt(host)},900)}
            else listener.status("A TV não aceitou a conexão. Na TV, remova o RetroTV Pad dos dispositivos Bluetooth e pareie de novo.")
        },if(accepted)7000L else 1500L)
    }
    fun send(state:PadState){
        if(closed)return
        current=HidGamepad.report(state)
        if(device==null)return
        if(!pending.offer(current)){listener.status("Fila Bluetooth cheia. Reconecte o controle.");runCatching{device?.let{hid?.disconnect(it)}};return};drain()
    }
    private fun drain(){
        if(!sending.compareAndSet(false,true))return
        runCatching{executor.execute{
            try{while(!closed){val report=pending.take()?:break;val host=device?:break
                var accepted=false
                for(attempt in 0..2){if(closed||device?.address!=host.address)break;accepted=runCatching{hid?.sendReport(host,HidGamepad.REPORT_ID,report)==true}.getOrDefault(false);if(accepted)break;Thread.sleep(10)}
                if(!accepted){pending.clear();main.post{if(!closed){runCatching{hid?.disconnect(host)};listener.status("Envio interrompido. Reconecte o controle.")}};break}
            }}finally{sending.set(false);if(!closed&&pending.isNotEmpty())drain()}
        }}
    }
    fun release(){send(PadState())}
    override fun close(){
        if(closed)return
        closed=true;main.removeCallbacksAndMessages(null);pending.clear()
        if(watching)runCatching{context.unregisterReceiver(system)};watching=false
        val host=device;val profile=hid;device=null;hid=null;registered=false
        runCatching{if(host!=null){profile?.sendReport(host,HidGamepad.REPORT_ID,neutral);profile?.disconnect(host)}}
        runCatching{profile?.unregisterApp()}
        runCatching{if(profile!=null)adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE,profile)}
        executor.shutdown()
    }
}