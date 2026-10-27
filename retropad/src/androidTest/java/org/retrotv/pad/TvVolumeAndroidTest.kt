package org.retrotv.pad

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvVolumeAndroidTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    @Test fun volumeTileOpensOnlyOnCompletedTapAndNeverBecomesAGameButton()=ins.runOnMainSync{
        var opened=0;val sent=mutableListOf<PadState>()
        val view=ControllerView(ins.targetContext,object:ControllerView.Callback{
            override fun send(state:PadState){sent+=state};override fun connect()=Unit
            override fun select(console:Console)=Unit;override fun auto()=Unit;override fun options()=Unit
            override fun volume(){opened++}
        }).apply{console=Console.TV;layout(0,0,1920,886)}
        val tile=view.controlsForTest().first{it.id=="volume"};val scale=view.scaleForTest()
        assertEquals("action",tile.kind);assertEquals(0,tile.mask)
        fun touch(action:Int){val now=SystemClock.uptimeMillis()
            val event=MotionEvent.obtain(now,now,action,(tile.box.x+tile.box.w/2)*scale,(tile.box.y+tile.box.h/2)*scale,0)
            view.dispatchTouchEvent(event);event.recycle()
        }
        touch(MotionEvent.ACTION_DOWN);assertEquals(0,opened)
        touch(MotionEvent.ACTION_CANCEL);touch(MotionEvent.ACTION_UP);assertEquals(0,opened)
        touch(MotionEvent.ACTION_DOWN);touch(MotionEvent.ACTION_UP);assertEquals(1,opened)
        assertEquals(PadState(),view.stateForTest());assertTrue(sent.all{it==PadState()})
    }
    @Test fun volumePanelSendsBothDirectionsWithoutDismissingAndDisablesAfterDisconnect(){
        val activity=ins.startActivitySync(Intent(ins.targetContext,PadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("previewConsole","TV")) as PadActivity
        ins.waitForIdleSync()
        ins.runOnMainSync{
            var available=true;val sent=mutableListOf<Boolean>();var connects=0
            val keys=TvVolumeKeys{sent+=it;available}
            val panel=TvVolumeDialog(activity,{available},{sent+=it;available},{connects++}){event->
                when(event.action){KeyEvent.ACTION_DOWN->keys.down(event.keyCode);KeyEvent.ACTION_UP->keys.up(event.keyCode);else->false}
            }
            try{
                panel.show()
                val up=panel.dialog.window!!.decorView.findViewWithTag<Button>("volume.up")
                val down=panel.dialog.window!!.decorView.findViewWithTag<Button>("volume.down")
                val connect=panel.dialog.window!!.decorView.findViewWithTag<Button>("volume.connect")
                up.performClick();down.performClick();up.performClick()
                assertEquals(listOf(true,false,true),sent);assertTrue(panel.dialog.isShowing)
                panel.dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_VOLUME_DOWN))
                panel.dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_VOLUME_DOWN))
                assertEquals(false,sent.last())
                available=false;panel.refresh();assertFalse(up.isEnabled);assertFalse(down.isEnabled)
                assertEquals(android.view.View.VISIBLE,connect.visibility)
                connect.performClick();assertEquals(1,connects);assertFalse(panel.dialog.isShowing)
            }finally{panel.dismiss();activity.finish()}
        }
    }
}
