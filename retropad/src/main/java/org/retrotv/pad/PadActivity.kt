package org.retrotv.pad
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.WindowManager
import android.view.KeyEvent
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/**
 * Routes the pad to the best way the phone and TV allow: Bluetooth HID only where the phone really offers it, the
 * RetroTV Lite channel (Bluetooth RFCOMM or Wi-Fi) for games, and the Google TV remote protocol to move around the TV.
 */
@SuppressLint("MissingPermission")
class PadActivity:Activity(),HidSession.Listener,RetroTvLink.Events,GoogleTvLink.Events {
    internal lateinit var controllerView:ControllerView
    private val prefs by lazy{getSharedPreferences("pad",MODE_PRIVATE)}
    private var hid:HidSession?=null
    private var ready=false
    private var wantsPicker=false
    private var stopped=true
    private var destroyed=false
    private var connected:BluetoothDevice?=null
    private var chosen:BluetoothDevice?=null
    private var retro:RetroTvLink?=null
    private var google:GoogleTvLink?=null
    private var volumeDialog:TvVolumeDialog?=null
    private val volumeKeys=TvVolumeKeys{increase->google?.adjustVolume(increase)?:false}
    private var wifiLock:WifiManager.WifiLock?=null
    private val imports=Executors.newSingleThreadExecutor()
    private val hidAllowed get()=prefs.getString("hid",null)!="no"
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility=5894
        controllerView=ControllerView(this,object:ControllerView.Callback{
            override fun send(state:PadState){route(state)}
            override fun connect(){connectionMenu()}
            override fun select(console:Console){controllerView.automatic=false;prefs.edit().putBoolean("auto",false).apply();this@PadActivity.select(console,true)}
            override fun auto(){controllerView.release();controllerView.automatic=!controllerView.automatic;prefs.edit().putBoolean("auto",controllerView.automatic).apply();controllerView.invalidate()}
            override fun options(){this@PadActivity.options()}
            override fun volume(){showTvVolume()}
        })
        controllerView.automatic=prefs.getBoolean("auto",true)
        controllerView.console=runCatching{Console.valueOf(prefs.getString("console","SNES")!!)}.getOrDefault(Console.SNES)
        if(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0)intent.getStringExtra("previewConsole")?.let{runCatching{controllerView.console=Console.valueOf(it)}}
        setContentView(controllerView)
        if(prefs.getBoolean("custom",false)&&File(filesDir,"custom.deltaskin").exists())loadSkin(File(filesDir,"custom.deltaskin"),false)
    }
    /** HID first (it reaches every app on the TV), then the RetroTV channel for games, then the Google TV remote. */
    private fun route(state:PadState){
        when{connected!=null->hid?.send(state);retro?.live==true->retro?.send(state);else->google?.send(state)}
        if(connected==null&&retro?.live==true)google?.send(PadState())
    }
    private fun refreshRoute(){
        controllerView.connectedName=connected?.name?:retro?.takeIf{it.live}?.label?:google?.takeIf{it.live}?.label?:""
        controllerView.metadataLive=retro?.live==true;controllerView.invalidate()
        holdWifi(connected==null&&(retro?.live==true&&retro?.key?.startsWith("wifi:")==true||google?.live==true))
    }
    /** Phones batch Wi-Fi packets to save power; a low-latency lock keeps button presses prompt while a Wi-Fi link is in use. */
    @Suppress("DEPRECATION")
    private fun holdWifi(on:Boolean){
        if(on==(wifiLock?.isHeld==true))return
        if(!on){runCatching{wifiLock?.release()};wifiLock=null;return}
        val wifi=applicationContext.getSystemService(WifiManager::class.java)?:return
        val mode=if(Build.VERSION.SDK_INT>=29)WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock=runCatching{wifi.createWifiLock(mode,"RetroTV Pad").apply{setReferenceCounted(false);acquire()}}.getOrNull()
    }
    private fun select(console:Console,remember:Boolean){
        controllerView.release();controllerView.console=console;controllerView.relayout()
        if(remember)prefs.edit().putString("console",console.name).apply()
    }
    private fun permitted()=Build.VERSION.SDK_INT<31||checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)==PackageManager.PERMISSION_GRANTED
    private fun startHid(){if(stopped||hid!=null||!permitted()||!hidAllowed)return;ready=false;hid=HidSession(this,this).also{it.start()}}
    override fun onResume(){super.onResume();stopped=false;if(permitted())startHid();restoreLinks()}
    private fun restoreLinks(){
        if(retro==null)prefs.getString("lastRetro",null)?.let{openRetro(it)}
        if(google==null)prefs.getString("lastGoogle",null)?.split('|',limit=2)?.takeIf{it.size==2}?.let{openGoogle(it[0],it[1])}
        findGameChannel()
    }
    private var autoFind:TvDiscovery?=null
    private val autoTried=mutableSetOf<String>()
    /** Buttons, held arrows and AUTO need the RetroTV channel: join it by itself when RetroTV shows up on the Wi-Fi. */
    private fun findGameChannel(){
        if(autoFind!=null)return
        autoFind=TvDiscovery(this){service->
            if(service.google||retro?.live==true||retro?.key?.contains(":${service.host}:")==true||!autoTried.add(service.host))return@TvDiscovery
            openRetro("wifi:${service.host}:${service.port}:${service.name}")
        }.also{it.start()}
    }
    @Deprecated("Activity back dispatch")
    override fun onBackPressed(){if(controllerView.editing)controllerView.finishEditing() else super.onBackPressed()}
    override fun onStop(){
        volumeKeys.clear();volumeDialog?.dismiss();volumeDialog=null
        controllerView.release();stopped=true;autoFind?.stop();autoFind=null;hid?.close();hid=null;retro?.close();retro=null;google?.close();google=null
        connected=null;ready=false;controllerView.connectedName="";controllerView.metadataLive=false;holdWifi(false);super.onStop()
    }
    override fun onDestroy(){destroyed=true;imports.shutdown();controllerView.customSkin?.close();super.onDestroy()}
    override fun onWindowFocusChanged(hasFocus:Boolean){super.onWindowFocusChanged(hasFocus);if(!hasFocus&&::controllerView.isInitialized)controllerView.release()}
    private fun handleTvVolumeKey(event:KeyEvent)=when(event.action){
        KeyEvent.ACTION_DOWN->if(event.isCanceled)false else volumeKeys.down(event.keyCode)
        KeyEvent.ACTION_UP->volumeKeys.up(event.keyCode)
        else->false
    }
    override fun dispatchKeyEvent(event:KeyEvent):Boolean=handleTvVolumeKey(event)||super.dispatchKeyEvent(event)
    private fun showTvVolume(){
        controllerView.release();volumeDialog?.dismiss()
        volumeDialog=TvVolumeDialog(this,{google?.live==true},{increase->google?.adjustVolume(increase)?:false},
            {connectVolumeTv()},::handleTvVolumeKey).also{it.show()}
    }
    /** Wi-Fi remote setup needs no Bluetooth permission and can coexist with the game connection. */
    private fun connectVolumeTv(){
        val labels=mutableListOf("Digitar endereço da TV…");val actions=mutableListOf<()->Unit>({
            val field=EditText(this).apply{hint="Endereço IP da TV";setSingleLine()}
            AlertDialog.Builder(this).setTitle("Conectar controle da TV")
                .setMessage("Digite o endereço IP da TV na mesma rede Wi-Fi. A TV mostrará um código para parear.").setView(field)
                .setPositiveButton("Conectar"){_,_->val host=field.text.toString().trim()
                    if(host.matches(Regex("[A-Za-z0-9.-]{3,64}")))openGoogle(host,host) else status("Endereço inválido.")}
                .setNegativeButton("Cancelar",null).show()
        })
        val seen=mutableSetOf<String>();val adapter=ArrayAdapter(this,android.R.layout.simple_list_item_1,labels)
        val discovery=TvDiscovery(this){service->if(service.google&&seen.add(service.host)){
            labels+=service.name;actions+={openGoogle(service.host,service.name)};adapter.notifyDataSetChanged()
        }}
        discovery.start()
        AlertDialog.Builder(this).setTitle("Conectar controle da TV").setAdapter(adapter){_,index->actions.getOrNull(index)?.invoke()}
            .setNegativeButton("Cancelar",null).setOnDismissListener{discovery.stop()}.show()
    }

    // ---- HID (only where the phone offers it) ----
    override fun status(text:String){controllerView.connectionStatus=text;controllerView.invalidate()}
    override fun ready(){
        ready=true;prefs.edit().putString("hid","yes").apply()
        val pick=chosen;chosen=null;if(pick!=null){hid?.connect(pick);return}
        if(wantsPicker){wantsPicker=false;devices();return}
        prefs.getString("lastHost",null)?.let{address->getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.firstOrNull{it.address==address}?.let{hid?.connect(it)}}
    }
    override fun unsupported(){
        prefs.edit().putString("hid","no").apply();hid?.close();hid=null;ready=false
        status("Este celular não oferece controle Bluetooth HID. Use o RetroTV (Bluetooth ou Wi-Fi) ou o Google TV em Conectar.")
        val pick=chosen;chosen=null;if(pick!=null)openRetro("bt:"+pick.address)
        if(wantsPicker){wantsPicker=false;devices()}
    }
    override fun connected(device:BluetoothDevice?){
        connected=device;if(device!=null)prefs.edit().putString("lastHost",device.address).apply();controllerView.release()
        // With HID carrying the buttons, the RetroTV channel only brings the console context for AUTO.
        if(device!=null&&retro==null)openRetro("bt:"+device.address)
        refreshRoute()
    }

    // ---- RetroTV channel and Google TV remote ----
    private fun openRetro(key:String){
        val parts=key.split(':')
        val link=when{
            parts[0]=="bt"&&parts.size>=2->{val adapter=getSystemService(BluetoothManager::class.java)?.adapter?:return;if(!permitted())return
                RetroTvLink.bluetooth(adapter.getRemoteDevice(key.removePrefix("bt:")),prefs,this)}
            parts[0]=="wifi"&&parts.size>=4->RetroTvLink.wifi(parts.drop(3).joinToString(":"),parts[1],parts[2].toIntOrNull()?:PadWire.WIFI_PORT,prefs,this)
            else->return
        }
        retro?.close();retro=link.start();prefs.edit().putString("lastRetro",link.key).apply()
        status("Conectando a ${link.label}…")
    }
    private fun openGoogle(host:String,label:String){
        google?.close();google=GoogleTvLink(label,host,prefs,this).start();prefs.edit().putString("lastGoogle","$host|$label").apply()
        status("Conectando ao controle remoto de $label…")
    }
    override fun linkChanged(link:RetroTvLink,live:Boolean,text:String){if(link===retro){status(text);refreshRoute()}}
    override fun linkChanged(link:GoogleTvLink,live:Boolean,text:String){if(link===google){status(text);refreshRoute();volumeDialog?.refresh()}}
    override fun metadata(system:String,title:String,mode:String){
        val changed=controllerView.metadataTitle!=title||controllerView.playMode!=mode
        controllerView.metadataTitle=title;controllerView.playMode=mode
        // The TV repeats context every second. Rebuilding the same console releases every held contact.
        Console.fromSystem(system)?.let{if(controllerView.automatic&&controllerView.console!=it)select(it,false)}
        if(changed)controllerView.invalidate()
    }
    override fun askCode(link:RetroTvLink,retry:Boolean,answer:(String?)->Unit){
        if(link!==retro){answer(null);return}
        codeDialog("Código da TV",(if(retry)"Código incorreto. " else "")+"Digite o código de 4 números que apareceu na TV.",4,true,answer)
    }
    override fun askGoogleCode(link:GoogleTvLink,retry:Boolean,answer:(String?)->Unit){
        if(link!==google){answer(null);return}
        codeDialog("Parear controle remoto",(if(retry)"Código não confere. " else "")+"Digite o código de 6 caracteres que apareceu na TV.",6,false,answer)
    }
    private fun codeDialog(title:String,text:String,length:Int,numeric:Boolean,answer:(String?)->Unit){
        if(destroyed){answer(null);return}
        var done=false;fun finish(value:String?){if(!done){done=true;answer(value)}}
        val field=EditText(this).apply{inputType=if(numeric)InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS;filters=arrayOf(InputFilter.LengthFilter(length));textSize=28f;setSingleLine()}
        AlertDialog.Builder(this).setTitle(title).setMessage(text).setView(field)
            .setPositiveButton("Conectar"){_,_->finish(field.text.toString())}.setNegativeButton("Cancelar"){_,_->finish(null)}
            .setOnDismissListener{finish(null)}.show()
    }
    private fun disconnectAll(){
        prefs.edit().remove("lastHost").remove("lastRetro").remove("lastGoogle").apply()
        val device=connected;hid?.close();hid=null;ready=false;connected=null
        retro?.close();retro=null;google?.close();google=null;status("Desconectado");refreshRoute();startHid()
    }

    // ---- Connect menu: paired TVs, TVs found on Wi-Fi, manual address ----
    private fun connectionMenu(){
        controllerView.release()
        if(!permitted()){wantsPicker=true;requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE),50);return}
        if(hidAllowed&&hid!=null&&!ready){wantsPicker=true;hid?.retry();status("Preparando o controle Bluetooth…");return}
        devices()
    }
    private fun devices(){
        if(stopped)return
        val labels=mutableListOf<String>();val actions=mutableListOf<()->Unit>()
        fun add(label:String,action:()->Unit){labels+=label;actions+=action}
        val route=connected?.name?:retro?.takeIf{it.live}?.label?:google?.takeIf{it.live}?.label
        if(route!=null||retro!=null||google!=null)add("Desconectar"+(route?.let{" ($it)"}?:"")){disconnectAll()}
        val adapter=getSystemService(BluetoothManager::class.java)?.adapter
        if(adapter!=null&&!adapter.isEnabled)add("Ligar o Bluetooth"){startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),61)}
        if(adapter?.isEnabled==true){
            val last=prefs.getString("lastHost",null)
            adapter.bondedDevices.sortedWith(compareBy({it.address!=last},{it.bluetoothClass?.majorDeviceClass!=BluetoothClass.Device.Major.AUDIO_VIDEO},{it.name?:""}))
                .forEach{device->add((device.name?:"Dispositivo pareado")+" · Bluetooth"){connectBluetooth(device)}}
            add("Procurar TV Bluetooth próxima…"){findTv()}
        }
        add("Digitar endereço da TV (Wi-Fi)…"){addressDialog()}
        if(hidAllowed&&ready)add("Parear pela TV (Adicionar acessório)"){
            message("Parear pela TV","No próximo aviso, permita que o celular fique visível por 3 minutos. Na TV, abra Configurações > Controles e acessórios > Adicionar acessório, escolha RetroTV Pad e confirme o código nas duas telas. O controle conecta sozinho.",{
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,180))})}
        val list=ArrayAdapter(this,android.R.layout.simple_list_item_1,labels)
        val seen=mutableSetOf<String>()
        var games=0;var remotes=0
        // TVs on the same Wi-Fi appear while the menu is open; "play" entries (RetroTV channel) always above "menus only".
        val discovery=TvDiscovery(this){service->
            if(!seen.add((if(service.google)"g" else "r")+service.host))return@TvDiscovery
            val base=labels.size.coerceAtMost(if(route!=null)1 else 0)
            val index=if(service.google)base+games+remotes++ else base+games++
            val label=service.name+if(service.google)" · Só menus (controle remoto da TV)" else " · Jogar (RetroTV)"
            labels.add(index,label);actions.add(index){if(service.google)openGoogle(service.host,service.name) else openRetro("wifi:${service.host}:${service.port}:${service.name}")}
            list.notifyDataSetChanged()
        }
        discovery.start()
        AlertDialog.Builder(this).setTitle("Conectar à TV").setAdapter(list){_,index->actions.getOrNull(index)?.invoke()}
            .setNegativeButton("Cancelar",null).setOnDismissListener{discovery.stop()}.show()
    }
    private fun connectBluetooth(device:BluetoothDevice){
        val session=hid
        when{
            session!=null&&ready->session.connect(device)
            hidAllowed&&session!=null->{chosen=device;session.retry();status("Preparando o controle Bluetooth…")}
            else->openRetro("bt:"+device.address)
        }
    }
    private fun addressDialog(){
        val field=EditText(this).apply{hint="192.168.0.20";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI;setSingleLine()}
        AlertDialog.Builder(this).setTitle("Endereço da TV").setMessage("Na TV: RetroTV > Ajustes > Controle pelo celular mostra o endereço Wi-Fi.").setView(field)
            .setPositiveButton("Conectar"){_,_->
                val text=field.text.toString().trim();val host=text.substringBefore(':');val port=text.substringAfter(':',"").toIntOrNull()?:PadWire.WIFI_PORT
                if(host.matches(Regex("[A-Za-z0-9.-]{3,64}")))openRetro("wifi:$host:$port:$host") else status("Endereço inválido.")}
            .setNegativeButton("Cancelar",null).show()
    }
    /** Android's own nearby-device chooser: no location or scan permission is needed. */
    private fun findTv(){
        val manager=if(packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP))getSystemService(CompanionDeviceManager::class.java) else null
        if(manager==null){message("Procurar TV","Este celular não oferece o seletor de dispositivos. Pareie a TV nas configurações Bluetooth do celular ou use o Wi-Fi.");return}
        val request=AssociationRequest.Builder().addDeviceFilter(BluetoothDeviceFilter.Builder().build()).setSingleDevice(false).build()
        val open:(IntentSender)->Unit={sender->runCatching{startIntentSenderForResult(sender,62,null,0,0,0)}.onFailure{status("Não foi possível abrir o seletor de dispositivos.")}}
        val callback=object:CompanionDeviceManager.Callback(){
            @Deprecated("Replaced by onAssociationPending on Android 13")
            override fun onDeviceFound(chooserLauncher:IntentSender){open(chooserLauncher)}
            override fun onAssociationPending(intentSender:IntentSender){open(intentSender)}
            override fun onFailure(error:CharSequence?){status("Seletor fechado"+(error?.let{": $it"}?:"")+".")}
        }
        status("Procurando TVs próximas… deixe a TV ligada.")
        runCatching{if(Build.VERSION.SDK_INT>=33)manager.associate(request,mainExecutor,callback) else @Suppress("DEPRECATION") manager.associate(request,callback,null)}
            .onFailure{status("Não foi possível procurar dispositivos: "+(it.message?:"erro do Android"))}
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==50){if(permitted()){startHid();if(!hidAllowed||hid==null){wantsPicker=false;devices()}}else{wantsPicker=false;status("Permissão de dispositivos próximos negada. O Wi-Fi continua disponível em Conectar.");devices()}}
    }
    private fun options(){
        controllerView.release()
        AlertDialog.Builder(this).setTitle("RetroTV Pad").setItems(arrayOf("Diagnóstico de conexão","Importar skin Delta (.deltaskin)","Usar os layouts originais","Como trocar automaticamente","Créditos e licença","Aparência dos controles")){_,i->
            when(i){
                0->connectionReport()
                1->startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),60)
                2->{controllerView.customSkin?.close();controllerView.customSkin=null;prefs.edit().putBoolean("custom",false).apply();controllerView.relayout()}
                3->message("Skins automáticas","Na TV, abra o RetroTV Lite e ative Ajustes > Controle pelo celular. No celular, conecte pelo RetroTV (Bluetooth ou Wi-Fi) e deixe o modo automático ligado. Ao abrir ou escolher um jogo, o console informa o layout.")
                4->{val view=TextView(this).apply{text=assets.open("licenses/NOTICES.txt").bufferedReader().use{it.readText()}+"\n\n"+assets.open("licenses/GPL-3.0.txt").bufferedReader().use{it.readText()};setPadding(28,20,28,20);textSize=14f}
                    AlertDialog.Builder(this).setTitle("Fontes e créditos").setView(ScrollView(this).apply{addView(view)}).setPositiveButton("Fechar",null).show()}
                5->appearance()
            }
        }.setNegativeButton("Voltar",null).show()
    }
    private fun appearance(){
        AlertDialog.Builder(this).setTitle("Aparência dos controles")
            .setSingleChoiceItems(arrayOf("Clássica (padrão)","Realista (mais detalhes e profundidade)"),if(controllerView.realisticAppearance)1 else 0){dialog,index->
                controllerView.setRealisticAppearance(index==1);dialog.dismiss()
            }.setNegativeButton("Voltar",null).show()
    }
    /** Everything needed to tell which route works on this phone; can be copied and sent. */
    private fun connectionReport(){
        val adapter=getSystemService(BluetoothManager::class.java)?.adapter
        fun granted(p:String)=if(checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED)"sim" else "não"
        val text="Aparelho: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n"+
            "Bluetooth: ${if(adapter==null)"sem adaptador" else if(adapter.isEnabled)"ligado" else "desligado"}\n"+
            (if(Build.VERSION.SDK_INT>=31)"Permissões: conectar ${granted(Manifest.permission.BLUETOOTH_CONNECT)} · anunciar ${granted(Manifest.permission.BLUETOOTH_ADVERTISE)}\n" else "")+
            "HID Bluetooth: ${when(prefs.getString("hid",null)){"yes"->"disponível";"no"->"indisponível neste celular";else->"ainda não testado"}}\n"+
            "RetroTV: ${retro?.let{(if(it.live)"conectado · " else "tentando · ")+it.label}?:"não configurado"}\n"+
            "Google TV: ${google?.let{(if(it.live)"conectado · " else "tentando · ")+it.label}?:"não configurado"}\n\n"+
            (hid?.diagnostics()?:"Sessão HID não iniciada.")
        AlertDialog.Builder(this).setTitle("Diagnóstico de conexão").setMessage(text)
            .setPositiveButton("Copiar"){_,_->getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("RetroTV Pad",text))}
            .setNeutralButton("Testar HID de novo"){_,_->prefs.edit().remove("hid").apply();val session=hid;if(session==null)startHid() else session.retry()}
            .setNegativeButton("Fechar",null).show()
    }
    private fun message(title:String,text:String,positive:(()->Unit)?=null){AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton(if(positive==null)"Entendi" else "Continuar"){_,_->positive?.invoke()}.setNegativeButton(if(positive==null)null else "Cancelar",null).show()}
    @Deprecated("Android activity result bridge")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==61&&resultCode==RESULT_OK){startHid();return}
        if(requestCode==62){
            if(resultCode!=RESULT_OK||data==null){status("Nenhuma TV escolhida.");return}
            @Suppress("DEPRECATION") val picked=data.getParcelableExtra<android.os.Parcelable>(CompanionDeviceManager.EXTRA_DEVICE) as? BluetoothDevice
            val host=picked?:if(Build.VERSION.SDK_INT>=33)data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION,android.companion.AssociationInfo::class.java)?.deviceMacAddress?.toString()?.uppercase()?.let{getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(it)} else null
            if(host==null){status("O Android não informou o dispositivo escolhido. Tente de novo.");return}
            val session=hid
            when{session!=null&&ready->session.connect(host);hidAllowed&&permitted()->{chosen=host;startHid()};else->openRetro("bt:"+host.address)}
            return
        }
        if(requestCode!=60||resultCode!=RESULT_OK)return
        val uri=data?.data?:return
        imports.execute{
            val temp=File(filesDir,"import.tmp")
            try{
                contentResolver.openInputStream(uri).use{input->require(input!=null);temp.outputStream().use{output->
                    val buffer=ByteArray(8192);var total=0;while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=DeltaSkin.MAX_ARCHIVE){"Skin grande demais"};output.write(buffer,0,n)}
                }}
                val skin=DeltaSkin.loadFile(temp,cacheDir)
                val target=File(filesDir,"custom.deltaskin");android.system.Os.rename(temp.path,target.path)
                runOnUiThread{if(destroyed){skin.close();return@runOnUiThread};applySkin(skin)}
            }catch(e:Exception){temp.delete();runOnUiThread{if(!destroyed)message("Skin não importada",e.message?:"Arquivo incompatível")}}
        }
    }
    private fun loadSkin(file:File,showError:Boolean){
        imports.execute{try{val skin=DeltaSkin.loadFile(file,cacheDir);runOnUiThread{if(destroyed)skin.close()else applySkin(skin)}}
        catch(e:Exception){if(showError)runOnUiThread{if(!destroyed)message("Skin indisponível",e.message?:"Arquivo inválido")}}}
    }
    private fun applySkin(skin:DeltaSkin){
        controllerView.release();controllerView.customSkin?.close();controllerView.customSkin=skin;select(skin.console,true)
        prefs.edit().putBoolean("custom",true).apply()
    }
}
