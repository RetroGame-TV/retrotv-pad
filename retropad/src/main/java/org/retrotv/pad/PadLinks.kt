// Original RetroTV Pad links. SPDX-License-Identifier: MIT
package org.retrotv.pad
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/** Controller line understood by RetroTV Lite: "I <buttons hex> <dpad> <lx> <ly> <rx> <ry>", axes -127..127. */
object PadWire{
    const val WIFI_PORT=47800
    val KEEPALIVE="K\n".toByteArray()
    // Locale-independent and avoids constructing a Formatter on the touch/output path.
    fun input(state:PadState)="I ${(state.buttons and 0x30FFF).toString(16)} ${state.dpad and 15} ${axis(state.lx)} ${axis(state.ly)} ${axis(state.rx)} ${axis(state.ry)}"
    private fun axis(value:Float)=(value.coerceIn(-1f,1f)*127).roundToInt()
}

/** Any way of reaching the TV. */
interface PadLink:AutoCloseable{
    val label:String
    val live:Boolean
    fun send(state:PadState)
}

/**
 * RetroTV Lite channel: console context arrives as JSON lines, controller state leaves as PadWire lines.
 * Bluetooth uses secure RFCOMM (connecting to an unpaired TV starts Android pairing); Wi-Fi asks for the code shown
 * on the TV once and then keeps the TV's token. Reconnects every few seconds until closed.
 */
@SuppressLint("MissingPermission")
class RetroTvLink private constructor(override val label:String,private val open:()->Channel,private val wifiKey:String?,private val prefs:SharedPreferences,private val events:Events):PadLink{
    interface Events{
        fun linkChanged(link:RetroTvLink,live:Boolean,text:String)
        fun metadata(system:String,title:String,mode:String)
        /** Shows a code prompt on the UI thread; answer(null) cancels. */
        fun askCode(link:RetroTvLink,retry:Boolean,answer:(String?)->Unit)
    }
    class Channel(val input:InputStream,val output:OutputStream,val close:()->Unit)
    companion object{
        fun bluetooth(device:BluetoothDevice,prefs:SharedPreferences,events:Events)=RetroTvLink((device.name?:"TV")+" · Bluetooth",{
            val socket=device.createRfcommSocketToServiceRecord(UUID.fromString(MetadataProtocol.UUID_STRING));socket.connect()
            Channel(socket.inputStream,socket.outputStream){runCatching{socket.close()}}
        },null,prefs,events).also{it.key="bt:"+device.address}
        fun wifi(name:String,host:String,port:Int,prefs:SharedPreferences,events:Events)=RetroTvLink("$name · Wi-Fi",{
            val socket=Socket();socket.tcpNoDelay=true;socket.connect(InetSocketAddress(host,port),5000)
            Channel(socket.getInputStream(),socket.getOutputStream()){runCatching{socket.close()}}
        },"$host:$port",prefs,events).also{it.key="wifi:$host:$port:$name"}
    }
    var key=""
        private set
    @Volatile override var live=false
        private set
    private val generation=AtomicInteger(0)
    private val main=Handler(Looper.getMainLooper())
    private val writer=Executors.newSingleThreadScheduledExecutor{r->Thread(r,"RetroTV-PadOut").apply{isDaemon=true}}
    // The TV drops a silent phone after 15 s; "K" keeps an idle controller connected and is otherwise ignored.
    init{writer.scheduleWithFixedDelay({out?.let{o->runCatching{o.write(PadWire.KEEPALIVE);o.flush()}}},5,5,TimeUnit.SECONDS)}
    @Volatile private var out:OutputStream?=null
    @Volatile private var channel:Channel?=null
    @Volatile private var sender:PadStateSender?=null
    @Volatile private var last=PadState()
    @Volatile private var cancelled=false
    fun start():RetroTvLink{
        val token=generation.incrementAndGet()
        Thread({
            while(token==generation.get()&&!cancelled){
                var opened:Channel?=null;var output:PadStateSender?=null;var failed:String?=null
                try{
                    opened=open();channel=opened
                    if(token!=generation.get())break
                    wifiKey?.let{handshake(opened,it,token)}
                    if(token!=generation.get()||cancelled)break
                    val active=opened
                    output=PadStateSender(writer,{state->
                        active.output.write((PadWire.input(state)+"\n").toByteArray(Charsets.US_ASCII));active.output.flush()
                    },{active.close()})
                    synchronized(this){
                        // Queue the initial state before publishing this connection to touch events.
                        output.offer(last);sender=output;out=opened.output;live=true
                    }
                    main.post{if(token==generation.get())events.linkChanged(this,true,"Conectado a $label")}
                    while(token==generation.get()){
                        val line=MetadataProtocol.readLine(opened.input)?:break
                        if(!line.startsWith("{"))continue
                        val json=JSONObject(line);if(json.optInt("version")!=1)continue
                        val system=json.optString("system").take(64);val title=json.optString("title").take(160);val mode=json.optString("mode").take(24)
                        main.post{if(token==generation.get())events.metadata(system,title,mode)}
                    }
                }catch(e:Exception){
                    if(!cancelled)failed=failure(e)
                }finally{
                    output?.close();if(sender===output)sender=null
                    live=false;out=null;opened?.close?.invoke();if(channel===opened)channel=null
                    val text=failed?:"Reconectando a $label…";main.post{if(token==generation.get()&&!cancelled)events.linkChanged(this,false,text)}
                }
                if(token!=generation.get()||cancelled)break
                try{Thread.sleep(3000)}catch(_:InterruptedException){break}
            }
        },"RetroTV-PadLink").apply{isDaemon=true;start()}
        return this
    }
    private fun failure(e:Exception)=when{
        wifiKey==null->"RetroTV não respondeu por Bluetooth. Na TV, abra o RetroTV e ative Ajustes > Controle pelo celular."
        e is java.net.ConnectException||e is java.net.SocketTimeoutException->"RetroTV não respondeu no Wi-Fi. Confira se a TV está na mesma rede e com Controle pelo celular ativado."
        else->"Conexão com $label perdida."
    }
    /** Wi-Fi only: a saved token is accepted; otherwise the TV shows a 4-digit code that the user types here. */
    private fun handshake(channel:Channel,key:String,token:Int){
        val id=prefs.getString("phoneId",null)?:UUID.randomUUID().toString().also{prefs.edit().putString("phoneId",it).apply()}
        val saved=prefs.getString("token.$key",null)?:"-"
        fun send(line:String){channel.output.write((line+"\n").toByteArray());channel.output.flush()}
        send("H $id $saved")
        var reply=MetadataProtocol.readLine(channel.input)?:throw java.io.EOFException()
        if(reply=="OK")return
        if(reply!="C")throw java.io.IOException("Unexpected reply")
        var retry=false
        repeat(3){
            val latch=CountDownLatch(1);var code:String?=null
            main.post{if(token==generation.get())events.askCode(this,retry){code=it;latch.countDown()} else latch.countDown()}
            latch.await(95,TimeUnit.SECONDS)
            val typed=code?.trim()?.takeIf{it.matches(Regex("\\d{4}"))}
            if(typed==null){cancelled=true;throw java.io.IOException("Pairing cancelled")}
            send("P $typed")
            reply=MetadataProtocol.readLine(channel.input)?:throw java.io.EOFException()
            if(reply.startsWith("T ")){prefs.edit().putString("token.$key",reply.substring(2).trim().take(64)).apply();return}
            retry=true
        }
        cancelled=true;throw java.io.IOException("Pairing refused")
    }
    @Synchronized override fun send(state:PadState){
        last=state;sender?.offer(state)
    }
    override fun close(){generation.incrementAndGet();cancelled=true;live=false;sender?.close();sender=null;out=null;channel?.close?.invoke();channel=null;writer.shutdown()}
}
