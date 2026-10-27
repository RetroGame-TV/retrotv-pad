package org.retrotv.pad

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutoMetadataAndroidTest{
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private data class Finger(val id:Int,val x:Float,val y:Float)
    private fun send(view:ControllerView,action:Int,vararg fingers:Finger){
        val now=SystemClock.uptimeMillis();val scale=view.scaleForTest()
        val properties=fingers.map{p->MotionEvent.PointerProperties().apply{id=p.id;toolType=MotionEvent.TOOL_TYPE_FINGER}}.toTypedArray()
        val coords=fingers.map{p->MotionEvent.PointerCoords().apply{x=p.x*scale;y=p.y*scale;pressure=1f;size=1f}}.toTypedArray()
        MotionEvent.obtain(now,now,action,fingers.size,properties,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0).also{
            try{view.dispatchTouchEvent(it)}finally{it.recycle()}
        }
    }
    private fun withActivity(test:(PadActivity)->Unit){
        val activity=ins.startActivitySync(Intent(ins.targetContext,PadActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("previewConsole","SNES")) as PadActivity
        ins.waitForIdleSync()
        try{ins.runOnMainSync{test(activity)}}finally{ins.runOnMainSync{activity.finish()};ins.waitForIdleSync()}
    }
    @Test fun repeatedAutoPacketsKeepTheDpadAndSecondFingerHeld()=withActivity{activity->
        val pad=activity.controllerView;pad.automatic=true
        val controls=pad.controlsForTest();val b=controls.first{it.kind=="dpad"}.box
        val a=controls.first{it.id=="a"}.box
        val direction=Finger(7,b.x+b.w*.9f,b.y+b.h/2)
        val button=Finger(42,a.x+a.w/2,a.y+a.h/2)
        send(pad,MotionEvent.ACTION_DOWN,direction)
        send(pad,MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),direction,button)
        val held=pad.stateForTest();assertEquals(8,held.dpad);assertEquals(Console.SNES.mask("a"),held.buttons)
        repeat(8){packet->
            activity.metadata("SNES",if(packet<4)"Mesmo jogo" else "Novo título","game")
            assertEquals("AUTO packet $packet released a held direction",held,pad.stateForTest())
            assertSame("duplicate AUTO rebuilt the touch layout",controls,pad.controlsForTest())
        }
        send(pad,MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),direction,button)
        assertEquals(8,pad.stateForTest().dpad);assertEquals(0,pad.stateForTest().buttons)
        send(pad,MotionEvent.ACTION_UP,direction);assertEquals(PadState(),pad.stateForTest())
    }
    @Test fun autoReleasesOnlyForAnActualConsoleChangeAndManualModeKeepsItsLayout()=withActivity{activity->
        val pad=activity.controllerView;pad.automatic=true
        val b=pad.controlsForTest().first{it.kind=="dpad"}.box
        val finger=Finger(7,b.x+b.w*.9f,b.y+b.h/2)
        send(pad,MotionEvent.ACTION_DOWN,finger);assertEquals(8,pad.stateForTest().dpad)
        activity.metadata("NES","Outro console","game")
        assertEquals(Console.NES,pad.console);assertEquals(PadState(),pad.stateForTest())
        val nes=pad.controlsForTest();val box=nes.first{it.kind=="dpad"}.box
        val next=Finger(7,box.x+box.w*.9f,box.y+box.h/2)
        pad.automatic=false
        send(pad,MotionEvent.ACTION_DOWN,next)
        activity.metadata("PS1","Manual","game");activity.metadata("unknown","Sem console","library")
        assertEquals(Console.NES,pad.console);assertEquals(8,pad.stateForTest().dpad);assertSame(nes,pad.controlsForTest())
        send(pad,MotionEvent.ACTION_CANCEL,next);assertEquals(PadState(),pad.stateForTest())
    }
    @Test fun repeatedPlayStationContextKeepsBothSticksAndAThirdFingerActive()=withActivity{activity->
        val pad=activity.controllerView;pad.automatic=true
        activity.metadata("PlayStation","PS1","running")
        val controls=pad.controlsForTest()
        fun finger(kind:String,id:Int,nx:Float,ny:Float):Finger{
            val box=controls.first{it.kind==kind}.box
            return Finger(id,box.x+box.w*nx,box.y+box.h*ny)
        }
        val left=finger("leftStick",7,.85f,.5f);val right=finger("rightStick",42,.5f,.15f)
        val a=controls.first{it.id=="cross"}.box;val button=Finger(3,a.x+a.w/2,a.y+a.h/2)
        send(pad,MotionEvent.ACTION_DOWN,left)
        send(pad,MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),left,right)
        send(pad,MotionEvent.ACTION_POINTER_DOWN or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),left,right,button)
        val held=pad.stateForTest();assertTrue(held.lx>.8f);assertTrue(held.ry<-.8f);assertEquals(Console.PS1.mask("cross"),held.buttons)
        repeat(8){activity.metadata("PlayStation","PS1","running");assertEquals(held,pad.stateForTest())}
        send(pad,MotionEvent.ACTION_CANCEL,left,right,button);assertEquals(PadState(),pad.stateForTest())
    }
}
