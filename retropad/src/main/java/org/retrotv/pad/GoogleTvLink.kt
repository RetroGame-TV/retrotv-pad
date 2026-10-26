package org.retrotv.pad
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/** Minimal protobuf writer/reader for the Android TV remote messages (varints and length-delimited fields only). */
class Pb{
    private val out=ByteArrayOutputStream()
    fun int(field:Int,value:Long)=apply{tag(field,0);varint(out,value)}
    fun bytes(field:Int,value:ByteArray)=apply{tag(field,2);varint(out,value.size.toLong());out.write(value)}
    fun str(field:Int,value:String)=bytes(field,value.toByteArray())
    fun msg(field:Int,value:Pb)=bytes(field,value.toBytes())
    fun toBytes():ByteArray=out.toByteArray()
    private fun tag(field:Int,wire:Int)=varint(out,((field shl 3) or wire).toLong())
    companion object{
        fun varint(out:OutputStream,value:Long){var v=value;while(v and 0x7FL.inv()!=0L){out.write(((v and 0x7F) or 0x80).toInt());v=v ushr 7};out.write(v.toInt())}
        /** Top-level fields of a message: varints as Long, length-delimited as ByteArray. */
        fun parse(data:ByteArray):Map<Int,List<Any>>{
            val fields=mutableMapOf<Int,MutableList<Any>>();var i=0
            fun read():Long{var shift=0;var result=0L;while(true){require(i<data.size);val b=data[i++].toInt() and 0xFF;result=result or ((b and 0x7F).toLong() shl shift);if(b<0x80)return result;shift+=7;require(shift<64)}}
            while(i<data.size){val key=read();val field=(key ushr 3).toInt()
                when((key and 7).toInt()){0->fields.getOrPut(field){mutableListOf()}.add(read())
                    2->{val n=read().toInt();require(n>=0&&i+n<=data.size);fields.getOrPut(field){mutableListOf()}.add(data.copyOfRange(i,i+n));i+=n}
                    5->i+=4;1->i+=8;else->throw IllegalArgumentException("wire type")}}
            return fields
        }
        fun writeFrame(out:OutputStream,message:ByteArray){val b=ByteArrayOutputStream();varint(b,message.size.toLong());b.write(message);out.write(b.toByteArray());out.flush()}
        fun readFrame(input:InputStream):ByteArray?{
            var shift=0;var size=0L
            while(true){val b=input.read();if(b<0)return null;size=size or ((b and 0x7F).toLong() shl shift);if(b<0x80)break;shift+=7;if(shift>28)throw java.io.IOException("frame")}
            if(size>65536)throw java.io.IOException("frame too large")
            val data=ByteArray(size.toInt());var read=0;while(read<data.size){val n=input.read(data,read,data.size-read);if(n<0)return null;read+=n};return data
        }
    }
}

/** Pad state → Android TV remote keys (KeyEvent codes). Navigation only: d-pad, OK, Back, Menu and Home. */
object GoogleTvKeys{
    /** Atomic SHORT key injection: one step, with no held key to release on disconnect. */
    fun volumeMessage(increase:Boolean)=Pb().msg(10,Pb().int(1,if(increase)24 else 25).int(2,3)).toBytes()
    private val dpad=listOf(1 to 19,2 to 20,4 to 21,8 to 22)
    private val buttons=listOf((1 shl 0) to 23,(1 shl 1) to 4,(1 shl 9) to 82,(1 shl 16) to 3,(1 shl 17) to 4)
    /** (key code, direction) with direction 1 = press (START_LONG) and 2 = release (END_LONG). */
    fun events(before:PadState,after:PadState):List<Pair<Int,Int>>{
        val out=mutableListOf<Pair<Int,Int>>()
        dpad.forEach{(bit,key)->val was=before.dpad and bit!=0;val now=after.dpad and bit!=0;if(was!=now)out+=key to if(now)1 else 2}
        buttons.forEach{(bit,key)->val was=before.buttons and bit!=0;val now=after.buttons and bit!=0;if(was!=now)out+=key to if(now)1 else 2}
        return out
    }
    /** Pairing secret: SHA-256 over client and server RSA modulus/exponent and the last 4 hex digits of the TV code. */
    fun secret(client:RSAPublicKey,server:RSAPublicKey,code:String):ByteArray?{
        val digest=MessageDigest.getInstance("SHA-256")
        fun hex(value:BigInteger,pad:Boolean):ByteArray{var h=(if(pad)"0" else "")+value.toString(16);if(h.length%2==1)h="0$h";return h.chunked(2).map{it.toInt(16).toByte()}.toByteArray()}
        digest.update(hex(client.modulus,false));digest.update(hex(client.publicExponent,true))
        digest.update(hex(server.modulus,false));digest.update(hex(server.publicExponent,true))
        digest.update(code.substring(2).chunked(2).map{it.toInt(16).toByte()}.toByteArray())
        val hash=digest.digest()
        return if((hash[0].toInt() and 0xFF)==code.substring(0,2).toInt(16))hash else null
    }
    /**
     * Self-signed client certificate in the shape Google TV trusts after pairing: X.509 v3, SHA256withRSA,
     * BasicConstraints CA=true. AndroidKeyStore's own certificate lacks the extension and the TV then drops port 6466.
     */
    fun selfSigned(publicKey:java.security.PublicKey,sign:(ByteArray)->ByteArray,now:java.util.Date,serial:BigInteger):ByteArray{
        fun der(tag:Int,vararg parts:ByteArray):ByteArray{
            val body=parts.fold(ByteArray(0)){a,b->a+b};val n=body.size
            val length=if(n<128)byteArrayOf(n.toByte()) else BigInteger.valueOf(n.toLong()).toByteArray().dropWhile{it==0.toByte()}.toByteArray().let{byteArrayOf((0x80 or it.size).toByte())+it}
            return byteArrayOf(tag.toByte())+length+body
        }
        fun time(date:java.util.Date):ByteArray{
            val utc=java.util.TimeZone.getTimeZone("UTC");val year=java.util.Calendar.getInstance(utc).apply{time=date}.get(java.util.Calendar.YEAR)
            val short=year in 1950..2049
            val text=java.text.SimpleDateFormat(if(short)"yyMMddHHmmss'Z'" else "yyyyMMddHHmmss'Z'",java.util.Locale.US).apply{timeZone=utc}.format(date)
            return der(if(short)0x17 else 0x18,text.toByteArray())
        }
        val algorithm=der(0x30,byteArrayOf(0x06,0x09,0x2A,0x86.toByte(),0x48,0x86.toByte(),0xF7.toByte(),0x0D,0x01,0x01,0x0B),byteArrayOf(0x05,0x00))
        val name=der(0x30,der(0x31,der(0x30,byteArrayOf(0x06,0x03,0x55,0x04,0x03),der(0x0C,"RetroTV Pad".toByteArray()))))
        val until=java.util.Date(now.time+10L*365*24*3600*1000)
        val basicConstraints=der(0x30,byteArrayOf(0x06,0x03,0x55,0x1D,0x13),der(0x04,der(0x30,byteArrayOf(0x01,0x01,0xFF.toByte()),byteArrayOf(0x02,0x01,0x00))))
        val tbs=der(0x30,der(0xA0,byteArrayOf(0x02,0x01,0x02)),der(0x02,serial.toByteArray()),algorithm,name,
            der(0x30,time(java.util.Date(now.time-24L*3600*1000)),time(until)),name,publicKey.encoded,der(0xA3,der(0x30,basicConstraints)))
        return der(0x30,tbs,algorithm,der(0x03,byteArrayOf(0)+sign(tbs)))
    }
}

/**
 * Google TV / Android TV remote (protocol v2, the one used by Google's own remote app): pairing on port 6467 with a
 * code shown on the TV, then key events on port 6466 over TLS. The client key lives in AndroidKeyStore; the TV
 * certificate is pinned at pairing. Works on TVs that ship the Android TV Remote Service.
 */
class GoogleTvLink(override val label:String,private val host:String,private val prefs:SharedPreferences,private val events:Events):PadLink{
    interface Events{
        fun linkChanged(link:GoogleTvLink,live:Boolean,text:String)
        fun askGoogleCode(link:GoogleTvLink,retry:Boolean,answer:(String?)->Unit)
    }
    companion object{
        // TLS with RSA-PSS makes Conscrypt run raw RSA (NoPadding) on the key, so it needs DECRYPT as well as SIGN.
        // The key stays in AndroidKeyStore; its certificate is replaced by GoogleTvKeys.selfSigned.
        private const val ALIAS="retrotv-pad-remote-3"
        private const val FEATURES=1L or 2L or 32L or 64L
        private fun keyStore()=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        private fun ensureKey(){
            val store=keyStore();if(store.containsAlias(ALIAS))return
            val end=Calendar.getInstance().apply{add(Calendar.YEAR,25)}.time
            val pair=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore").apply{initialize(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048).setCertificateSubject(X500Principal("CN=RetroTV Pad")).setCertificateNotAfter(end)
                .setDigests(KeyProperties.DIGEST_NONE,KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA384,KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(false).build())}.generateKeyPair()
            try{
                val der=GoogleTvKeys.selfSigned(pair.public,{data->java.security.Signature.getInstance("SHA256withRSA").run{initSign(pair.private);update(data);sign()}},java.util.Date(),BigInteger(63,java.security.SecureRandom()))
                val certificate=java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
                store.setKeyEntry(ALIAS,pair.private,null,arrayOf(certificate))
            }catch(e:Exception){runCatching{store.deleteEntry(ALIAS)};throw e}
        }
        private fun clientCertificate()=keyStore().getCertificate(ALIAS) as X509Certificate
    }
    val key="gtv:$host:$label"
    @Volatile override var live=false
        private set
    private val generation=AtomicInteger(0)
    private val main=Handler(Looper.getMainLooper())
    private val writer=Executors.newSingleThreadExecutor{r->Thread(r,"RetroTV-RemoteOut").apply{isDaemon=true}}
    @Volatile private var socket:SSLSocket?=null
    @Volatile private var previous=PadState()
    @Volatile private var cancelled=false
    private fun tls(port:Int):SSLSocket{
        ensureKey()
        val keys=object:X509ExtendedKeyManager(){
            override fun chooseClientAlias(keyType:Array<out String>?,issuers:Array<out Principal>?,s:Socket?)=ALIAS
            override fun getCertificateChain(alias:String?)=arrayOf(clientCertificate())
            override fun getPrivateKey(alias:String?)=keyStore().getKey(ALIAS,null) as PrivateKey
            override fun getClientAliases(keyType:String?,issuers:Array<out Principal>?)=arrayOf(ALIAS)
            override fun getServerAliases(keyType:String?,issuers:Array<out Principal>?)=null
            override fun chooseServerAlias(keyType:String?,issuers:Array<out Principal>?,s:Socket?)=null
        }
        // The TV certificate is self-signed: it is pinned after pairing and checked on every remote connection.
        val trust=object:X509TrustManager{
            override fun checkClientTrusted(chain:Array<out X509Certificate>?,authType:String?){}
            override fun checkServerTrusted(chain:Array<out X509Certificate>?,authType:String?){}
            override fun getAcceptedIssuers()=arrayOf<X509Certificate>()
        }
        val context=SSLContext.getInstance("TLS").apply{init(arrayOf(keys),arrayOf(trust),null)}
        val raw=Socket();raw.tcpNoDelay=true;raw.connect(InetSocketAddress(host,port),5000)
        return (context.socketFactory.createSocket(raw,host,port,true) as SSLSocket).apply{soTimeout=20000;startHandshake()}
    }
    private fun fingerprint(s:SSLSocket)=MessageDigest.getInstance("SHA-256").digest(s.session.peerCertificates[0].encoded).joinToString(""){"%02x".format(it)}
    fun start():GoogleTvLink{
        val token=generation.incrementAndGet()
        Thread({
            while(token==generation.get()&&!cancelled){
                var failure:String?=null
                try{
                    if(prefs.getString("gtv.pin.$host",null)==null)pair(token)
                    remote(token)
                }catch(e:Exception){android.util.Log.w("RetroTV-Pad","Google TV link: $label",e);failure=when(e){is java.net.ConnectException,is java.net.SocketTimeoutException->"$label não respondeu. Confira se a TV está ligada e na mesma rede Wi-Fi.";is SecurityException->e.message;else->if(cancelled)null else "Conexão com $label perdida."}}
                finally{live=false;runCatching{socket?.close()};socket=null}
                if(token!=generation.get()||cancelled)break
                main.post{if(token==generation.get())events.linkChanged(this,false,failure?:"Reconectando a $label…")}
                try{Thread.sleep(3000)}catch(_:InterruptedException){break}
            }
        },"RetroTV-GoogleTv").apply{isDaemon=true;start()}
        return this
    }
    private fun outer()=Pb().int(1,2).int(2,200)
    private fun pair(token:Int){
        val s=tls(6467);socket=s;val input=s.inputStream;val output=s.outputStream
        fun send(p:Pb)=Pb.writeFrame(output,p.toBytes())
        fun expect(field:Int){
            val reply=Pb.parse(Pb.readFrame(input)?:throw java.io.EOFException())
            val status=(reply[2]?.firstOrNull() as? Long)?:200L
            if(status!=200L)throw SecurityException(if(status==402L)"Código incorreto. Tente parear de novo." else "A TV recusou o pareamento ($status).")
            if(reply[field]==null)throw java.io.IOException("Unexpected pairing message")
        }
        val encoding=Pb().int(1,3).int(2,6)
        send(outer().msg(10,Pb().str(1,"atvremote").str(2,"RetroTV Pad")));expect(11)
        send(outer().msg(20,Pb().msg(1,encoding).int(3,1)));expect(20)
        send(outer().msg(30,Pb().msg(1,encoding).int(2,1)));expect(31)
        val server=s.session.peerCertificates[0].publicKey as RSAPublicKey;val client=clientCertificate().publicKey as RSAPublicKey
        var retry=false
        repeat(3){
            val latch=CountDownLatch(1);var code:String?=null
            main.post{if(token==generation.get())events.askGoogleCode(this,retry){code=it;latch.countDown()} else latch.countDown()}
            s.soTimeout=0;latch.await(120,TimeUnit.SECONDS);s.soTimeout=20000
            val typed=code?.trim()?.uppercase()?.takeIf{it.matches(Regex("[0-9A-F]{6}"))}
            if(typed==null){cancelled=true;throw java.io.IOException("Pairing cancelled")}
            val secret=GoogleTvKeys.secret(client,server,typed)
            if(secret==null){retry=true;return@repeat}
            send(outer().msg(40,Pb().bytes(1,secret)));expect(41)
            prefs.edit().putString("gtv.pin.$host",fingerprint(s)).apply();runCatching{s.close()};return
        }
        cancelled=true;throw SecurityException("Código incorreto. Tente parear de novo.")
    }
    private fun remote(token:Int){
        val s=tls(6466);socket=s
        if(fingerprint(s)!=prefs.getString("gtv.pin.$host",null)){prefs.edit().remove("gtv.pin.$host").apply();throw SecurityException("O certificado da TV mudou. Pareie de novo.")}
        s.soTimeout=0;val input=s.inputStream;val output=s.outputStream
        while(token==generation.get()){
            val message=Pb.parse(Pb.readFrame(input)?:break)
            when{
                message.containsKey(1)->{
                    val offered=(Pb.parse(message[1]!!.first() as ByteArray)[1]?.firstOrNull() as? Long)?:FEATURES
                    val info=Pb().str(1,android.os.Build.MODEL).str(2,android.os.Build.MANUFACTURER).int(3,1).str(4,"1").str(5,"atvremote").str(6,"1.0.0")
                    synchronized(output){Pb.writeFrame(output,Pb().msg(1,Pb().int(1,offered and FEATURES).msg(2,info)).toBytes())}
                }
                message.containsKey(2)->{synchronized(output){Pb.writeFrame(output,Pb().msg(2,Pb().int(1,FEATURES)).toBytes())}
                    if(!live){live=true;main.post{if(token==generation.get())events.linkChanged(this,true,"Só menus: $label. Para jogar, ative na TV RetroTV > Ajustes > Controle pelo celular e conecte em Jogar.")}}}
                message.containsKey(8)->{val v=(Pb.parse(message[8]!!.first() as ByteArray)[1]?.firstOrNull() as? Long)?:0L;synchronized(output){Pb.writeFrame(output,Pb().msg(9,Pb().int(1,v)).toBytes())}}
                message.containsKey(40)->if(!live){live=true;main.post{if(token==generation.get())events.linkChanged(this,true,"Só menus: $label. Para jogar, ative na TV RetroTV > Ajustes > Controle pelo celular e conecte em Jogar.")}}
            }
        }
    }
    override fun send(state:PadState){
        val changes=GoogleTvKeys.events(previous,state);previous=state
        val s=socket;if(!live||s==null||changes.isEmpty())return
        writer.execute{runCatching{val out=s.outputStream;changes.forEach{(key,direction)->synchronized(out){Pb.writeFrame(out,Pb().msg(10,Pb().int(1,key.toLong()).int(2,direction.toLong())).toBytes())}}}}
    }
    /** Volume uses the remote service directly, independently of the active game/HID route. */
    fun adjustVolume(increase:Boolean):Boolean{
        val s=socket;if(!live||s==null||writer.isShutdown)return false
        val token=generation.get();val message=GoogleTvKeys.volumeMessage(increase)
        return try{
            writer.execute{
                if(live&&socket===s&&generation.get()==token)runCatching{
                    val out=s.outputStream;synchronized(out){Pb.writeFrame(out,message)}
                }
            };true
        }catch(_:java.util.concurrent.RejectedExecutionException){false}
    }
    override fun close(){generation.incrementAndGet();cancelled=true;live=false;runCatching{socket?.close()};socket=null;writer.shutdown()}
}
