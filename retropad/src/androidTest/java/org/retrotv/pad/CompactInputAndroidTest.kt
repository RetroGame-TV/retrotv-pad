package org.retrotv.pad
import android.content.Context
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class CompactInputAndroidTest{
    @Test fun realLinkNegotiatesThenPreservesEveryButtonAndRelease(){
        val ins=InstrumentationRegistry.getInstrumentation();val server=ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))
        val packets=LinkedBlockingQueue<ByteArray>();val capability=CountDownLatch(1);val error=AtomicReference<Throwable?>();val peer=AtomicReference<java.net.Socket?>()
        val reader=Thread{
            try{server.accept().use{s->
                peer.set(s);s.soTimeout=10000;val input=s.getInputStream().buffered(8192);val out=s.getOutputStream()
                check(MetadataProtocol.readLine(input)!!.startsWith("H "));out.write("OK\n".toByteArray());out.flush()
                check(MetadataProtocol.readLine(input)==PadWire.input(PadState()))
                out.write("{\"version\":1,\"inputBinary\":1,\"system\":\"SNES\",\"mode\":\"playing\"}\n".toByteArray());out.flush()
                while(true){val first=input.read();if(first<0)break
                    if(first==75){check(input.read()==10);continue}
                    check(first==0xF1);val b=ByteArray(9);b[0]=first.toByte();var n=1
                    while(n<9){val got=input.read(b,n,9-n);check(got>0);n+=got};packets.offer(b)
                }
            }}catch(e:Exception){if(!server.isClosed)error.set(e)}
        }.apply{isDaemon=true;start()}
        val prefs=ins.targetContext.getSharedPreferences("compact-link-test",Context.MODE_PRIVATE)
        val link=RetroTvLink.wifi("compact-test","127.0.0.1",server.localPort,prefs,object:RetroTvLink.Events{
            override fun linkChanged(link:RetroTvLink,live:Boolean,text:String){}
            override fun metadata(system:String,title:String,mode:String){capability.countDown()}
            override fun askCode(link:RetroTvLink,retry:Boolean,answer:(String?)->Unit){answer(null)}
        })
        try{
            link.start();assertTrue(capability.await(10,TimeUnit.SECONDS))
            val states=(0..11).map{PadState(buttons=1 shl it)}+listOf(PadState(buttons=1 shl 16),PadState(buttons=1 shl 17),PadState(buttons=0x301,dpad=9,lx=-1f,ry=1f))+
                listOf(1,9,8,10,2,6,4,5).map{PadState(dpad=it)}
            for(s in states)for(state in listOf(s,PadState())){link.send(state);assertArrayEquals(PadWire.binary(state),packets.poll(5,TimeUnit.SECONDS))}
            repeat(10000){link.send(PadState(lx=.00001f*(it%100)))}
            assertNull(packets.poll(200,TimeUnit.MILLISECONDS));assertNull(error.get())
        }finally{server.close();link.close();peer.get()?.close();reader.join(2000);prefs.edit().clear().commit()}
    }
    @Test fun serializationBenchmark(){
        val states=List(256){PadState(buttons=it,dpad=it%16,lx=(it-128)/128f,ly=.3f,rx=-.7f,ry=.9f)};var sum=0
        fun measure(binary:Boolean):Long{val before=System.nanoTime();repeat(5000){val s=states[it%256];val b=if(binary)PadWire.binary(s) else (PadWire.input(s)+"\n").toByteArray(Charsets.US_ASCII);sum=sum xor b.contentHashCode()};return (System.nanoTime()-before)/5000}
        repeat(4){measure(false);measure(true)};val text=mutableListOf<Long>();val compact=mutableListOf<Long>()
        repeat(7){if(it%2==0){text+=measure(false);compact+=measure(true)}else{compact+=measure(true);text+=measure(false)}}
        InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply{putString("stream","Encode ns/state median: text=${text.sorted()[3]}, binary=${compact.sorted()[3]}; checksum=$sum. CPU only, excludes radio/TV.\n")})
    }
}
