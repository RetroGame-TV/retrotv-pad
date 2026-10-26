package org.retrotv.pad
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
@RunWith(AndroidJUnit4::class)
class PadAndroidTest{
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private fun archive(info:String,path:String="info.json"):ByteArray{
        val image=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888);image.eraseColor(android.graphics.Color.GRAY)
        val png=ByteArrayOutputStream();image.compress(Bitmap.CompressFormat.PNG,100,png);image.recycle()
        return ByteArrayOutputStream().also{out->ZipOutputStream(out).use{zip->
            zip.putNextEntry(ZipEntry(path));zip.write(info.toByteArray());zip.closeEntry()
            zip.putNextEntry(ZipEntry("skin.png"));zip.write(png.toByteArray());zip.closeEntry()
        }}.toByteArray()
    }
    private val json="""{"name":"Teste original","gameTypeIdentifier":"com.rileytestut.delta.game.snes","representations":{"iphone":{"standard":{"portrait":{"mappingSize":{"width":320,"height":240},"assets":{"resizable":"skin.png"},"items":[{"inputs":["a"],"frame":{"x":250,"y":96,"width":51,"height":51}},{"inputs":{"up":"up","down":"down","left":"left","right":"right"},"frame":{"x":6,"y":56,"width":132,"height":132}}]}}}}}"""
    @Test fun deltaFormatUsesRealCoordinatesAndDecodesImage(){
        val skin=DeltaSkin.loadArchive(archive(json).inputStream(),ins.targetContext.cacheDir)
        try{assertEquals(Console.SNES,skin.console);val r=skin.views.getValue("portrait");assertEquals(320f,r.width);assertEquals(2,r.controls.size)
            assertEquals(250f,r.controls[0].box.x);assertEquals(1,r.controls[0].mask);assertEquals("dpad",r.controls[1].kind);assertNotNull(r.bitmap)}
        finally{skin.close()}
    }
    @Test fun deltaFormatRejectsTraversalAndUnsupportedSystems(){
        for(bytes in listOf(archive(json,"../info.json"),archive(json.replace(".snes",".wii")),archive("[".repeat(40)+"0"+"]".repeat(40)))){
            try{DeltaSkin.loadArchive(bytes.inputStream(),ins.targetContext.cacheDir);fail("unsafe skin accepted")}catch(_:IllegalArgumentException){}catch(_:IllegalStateException){}
        }
    }
    @Test fun nativeViewSupportsTwoPointersAndCancel(){
        val activity=ins.startActivitySync(Intent(ins.targetContext,PadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("previewConsole","SNES")) as PadActivity
        ins.waitForIdleSync()
        ins.runOnMainSync{
            val view=activity.controllerView;val regions=view.controlsForTest();val dpad=regions.first{it.kind=="dpad"}.box;val a=regions.first{it.id=="a"}.box
            val sx=view.scaleForTest()
            val down=SystemClock.uptimeMillis()
            fun send(action:Int,points:List<Triple<Int,Float,Float>>){
                val props=points.map{p->MotionEvent.PointerProperties().apply{id=p.first;toolType=MotionEvent.TOOL_TYPE_FINGER}}.toTypedArray()
                val coords=points.map{p->MotionEvent.PointerCoords().apply{x=p.second*sx;y=p.third*sx;pressure=1f;size=1f}}.toTypedArray()
                val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,points.size,props,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
                view.dispatchTouchEvent(event);event.recycle()
            }
            val p1=Triple(7,dpad.x+dpad.w*.90f,dpad.y+dpad.h/2)
            val p2=Triple(42,a.x+a.w/2,a.y+a.h/2)
            send(MotionEvent.ACTION_DOWN,listOf(p1));send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),listOf(p1,p2))
            assertEquals(8,view.stateForTest().dpad);assertEquals(Console.SNES.mask("a"),view.stateForTest().buttons)
            send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),listOf(p1,p2))
            assertEquals(8,view.stateForTest().dpad);assertEquals(0,view.stateForTest().buttons)
            send(MotionEvent.ACTION_CANCEL,listOf(p1));assertEquals(PadState(),view.stateForTest())
            activity.finish()
        }
    }
}