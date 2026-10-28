package org.retrotv.pad
import android.content.Context
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Explicit local integration fixture. Requires a running TV game and its test-only pairing token. */
@RunWith(AndroidJUnit4::class)
class LiveTvInputTest{
    @Test fun commandsReachActiveTvThroughNegotiatedBinaryLink(){
        val args=InstrumentationRegistry.getArguments();assumeTrue(args.getString("liveTv")=="1")
        val ins=InstrumentationRegistry.getInstrumentation();val host=requireNotNull(args.getString("tvHost"));val token=requireNotNull(args.getString("tvToken"))
        val prefs=ins.targetContext.getSharedPreferences("live-tv-input-test",Context.MODE_PRIVATE)
        prefs.edit().putString("phoneId","rfcomm-e2e").putString("token.$host:47800",token).commit()
        val ready=CountDownLatch(1);val status=AtomicReference("")
        val link=RetroTvLink.wifi("active-tv",host,47800,prefs,object:RetroTvLink.Events{
            override fun linkChanged(link:RetroTvLink,live:Boolean,text:String){status.set(text)}
            override fun metadata(system:String,title:String,mode:String){if(system=="SNES"&&mode=="running")ready.countDown()}
            override fun askCode(link:RetroTvLink,retry:Boolean,answer:(String?)->Unit){answer(null);status.set("test token rejected")}
        })
        fun image(name:String){val shot=ins.uiAutomation.takeScreenshot();java.io.File(ins.targetContext.filesDir,"live-tv-$name.png").outputStream().use{shot.compress(Bitmap.CompressFormat.PNG,100,it)};shot.recycle()}
        fun pulse(s:PadState){link.send(s);Thread.sleep(150);link.send(PadState());Thread.sleep(100)}
        try{
            link.start();assertTrue("TV context: ${status.get()}",ready.await(10,TimeUnit.SECONDS));assertTrue(link.live);image("before")
            for(dpad in listOf(4,8,2,1,9,10))pulse(PadState(dpad=dpad))
            for(button in listOf(1,2,4,8,16,32))pulse(PadState(buttons=button))
            for(dpad in listOf(2,10,8))pulse(PadState(dpad=dpad));pulse(PadState(buttons=8))
            link.send(PadState(dpad=8));repeat(12000){link.send(PadState(dpad=8,lx=(it%255-127)/127f))};link.send(PadState());Thread.sleep(300)
            assertTrue("link dropped: ${status.get()}",link.live);image("after")
        }finally{link.close();prefs.edit().clear().commit()}
    }
}
