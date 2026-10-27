package org.retrotv.pad

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class PadLatencyAndroidTest{
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    @Test fun touchAndAxisStatesReachTheRealTcpWriterInOrder(){
        val server=ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))
        val peer=AtomicReference<Socket?>();val error=AtomicReference<Throwable?>()
        val status=AtomicReference("")
        val lines=LinkedBlockingQueue<String>();val connected=CountDownLatch(1)
        val prefs=ins.targetContext.getSharedPreferences("latency-test",Context.MODE_PRIVATE)
        val reader=Thread{
            try{server.accept().use{s->
                peer.set(s);s.soTimeout=10000
                val input=s.getInputStream().bufferedReader()
                check(input.readLine().startsWith("H "))
                s.getOutputStream().apply{write("OK\n".toByteArray());flush()}
                while(true){val line=input.readLine()?:break;if(line.startsWith("I "))lines.offer(line)}
            }}catch(e:Exception){if(!server.isClosed)error.set(e)}
        }.apply{isDaemon=true;start()}
        val link=RetroTvLink.wifi("loopback","127.0.0.1",server.localPort,prefs,object:RetroTvLink.Events{
            override fun linkChanged(link:RetroTvLink,live:Boolean,text:String){status.set(text);if(live)connected.countDown()}
            override fun metadata(system:String,title:String,mode:String){}
            override fun askCode(link:RetroTvLink,retry:Boolean,answer:(String?)->Unit){answer(null);fail("unexpected pairing")}
        })
        try{
            link.start();assertTrue("loopback did not connect: ${status.get()} ${error.get()}",connected.await(10,TimeUnit.SECONDS))
            assertEquals(PadWire.input(PadState()),lines.poll(5,TimeUnit.SECONDS))
            // Isolated axis updates cannot wait on a scheduled 8-ms pacing task anymore.
            for(state in listOf(PadState(lx=.2f),PadState(lx=.4f),PadState())){
                link.send(state);assertEquals(PadWire.input(state),lines.poll(5,TimeUnit.SECONDS))
            }
            // Flood axes, then a rapid press/release. Only the axes may be coalesced.
            repeat(500){link.send(PadState(lx=it/500f))}
            link.send(PadState(buttons=1,lx=1f));link.send(PadState())
            val received=mutableListOf<String>()
            while(true){val line=lines.poll(5,TimeUnit.SECONDS)?:throw AssertionError("missing release")
                received+=line;if(line==PadWire.input(PadState()))break}
            assertTrue(received.contains(PadWire.input(PadState(buttons=1,lx=1f))))
            assertEquals(PadWire.input(PadState()),received.last())
            // The actual View publishes down/up/cancel before returning from touch dispatch.
            val emitted=mutableListOf<PadState>()
            ins.runOnMainSync{
                var playing=false
                val view=ControllerView(ins.targetContext,object:ControllerView.Callback{
                    override fun send(state:PadState){if(playing){emitted+=state;link.send(state)}}
                    override fun connect(){};override fun select(console:Console){}
                    override fun auto(){};override fun options(){}
                }).apply{console=Console.SNES;layout(0,0,1920,886)}
                val a=view.controlsForTest().first{it.id=="a"}.box;val scale=view.scaleForTest()
                playing=true
                for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP,MotionEvent.ACTION_DOWN,MotionEvent.ACTION_CANCEL)){
                    val now=SystemClock.uptimeMillis()
                    MotionEvent.obtain(now,now,action,(a.x+a.w/2)*scale,(a.y+a.h/2)*scale,0).also{
                        view.dispatchTouchEvent(it);it.recycle()
                    }
                    assertEquals(if(action==MotionEvent.ACTION_DOWN)Console.SNES.mask("a") else 0,emitted.last().buttons)
                }
            }
            for(state in emitted)assertEquals(PadWire.input(state),lines.poll(5,TimeUnit.SECONDS))
            assertNull(error.get())
        }finally{server.close();link.close();peer.get()?.close();reader.join(2000);prefs.edit().clear().commit()}
    }

    /** Informational microbenchmark; no device-dependent timing assertion or TV latency claim. */
    @Test fun reportSerializationCostOnAndroid(){
        val states=List(256){i->PadState(buttons=i,dpad=i%16,lx=(i-128)/128f,ly=.3f,rx=-.7f,ry=.9f)}
        fun axis(value:Float)=(value.coerceIn(-1f,1f)*127).roundToInt()
        fun old(s:PadState)="I %x %d %d %d %d %d".format(java.util.Locale.ROOT,s.buttons and 0x30FFF,s.dpad and 15,axis(s.lx),axis(s.ly),axis(s.rx),axis(s.ry))
        states.forEach{assertEquals(old(it),PadWire.input(it))}
        repeat(2000){old(states[it%256]);PadWire.input(states[it%256])}
        var checksum=0
        fun measure(format:(PadState)->String):Long{
            val start=System.nanoTime()
            repeat(5000){checksum=checksum xor format(states[it%256]).hashCode()}
            return (System.nanoTime()-start)/5000
        }
        val before=mutableListOf<Long>();val after=mutableListOf<Long>()
        repeat(7){if(it%2==0){before+=measure(::old);after+=measure(PadWire::input)}else{after+=measure(PadWire::input);before+=measure(::old)}}
        val text="Serialization only, emulator ns/state median (7 x 5000): Formatter=${before.sorted()[3]}, PadWire=${after.sorted()[3]}, checksum=$checksum. Excludes radio/network/TV/rendering.\n"
        ins.sendStatus(0,Bundle().apply{putString("stream",text)})
    }
}
