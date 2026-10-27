package org.retrotv.pad

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomizationAndroidTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val prefs get()=ins.targetContext.getSharedPreferences("pad",Context.MODE_PRIVATE)
    private val ids=listOf("left","right","home","back")
    private var saved:Map<String,*> = emptyMap<String,Any>()
    private fun owned(key:String)=key.startsWith("scale.PS1.")||key.startsWith("legacy.scale.PS1.")||key.startsWith("layout.PS1.independent.")
    private fun clear(){val edit=prefs.edit();prefs.all.keys.filter(::owned).forEach{edit.remove(it)};edit.commit()}
    @Before fun isolate(){saved=prefs.all.filterKeys(::owned);clear()}
    @After fun restore(){
        clear();val edit=prefs.edit()
        saved.forEach{(key,value)->when(value){is Float->edit.putFloat(key,value);is Boolean->edit.putBoolean(key,value);is Int->edit.putInt(key,value);is Long->edit.putLong(key,value);is String->edit.putString(key,value)}}
        edit.commit()
    }
    private fun view()=ControllerView(ins.targetContext,object:ControllerView.Callback{
        override fun send(state:PadState)=Unit;override fun connect()=Unit;override fun select(console:Console)=Unit
        override fun auto()=Unit;override fun options()=Unit
    }).apply{console=Console.PS1;layout(0,0,2160,1080);controlsForTest()}
    private fun boxes(view:ControllerView)=view.controlsForTest().associate{it.id to it.box}
    private fun same(want:Box,actual:Box){
        assertEquals(want.x,actual.x,1e-3f);assertEquals(want.y,actual.y,1e-3f)
        assertEquals(want.w,actual.w,1e-3f);assertEquals(want.h,actual.h,1e-3f)
    }
    private fun send(view:ControllerView,action:Int,vararg points:Pair<Float,Float>){
        val scale=view.scaleForTest();val now=SystemClock.uptimeMillis()
        val properties=points.mapIndexed{i,_->MotionEvent.PointerProperties().apply{id=if(i==0)7 else 42;toolType=MotionEvent.TOOL_TYPE_FINGER}}.toTypedArray()
        val coordinates=points.map{p->MotionEvent.PointerCoords().apply{x=p.first*scale;y=p.second*scale;pressure=1f;size=1f}}.toTypedArray()
        val event=MotionEvent.obtain(now,now,action,points.size,properties,coordinates,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
        try{view.dispatchTouchEvent(event)}finally{event.recycle()}
    }
    private fun center(b:Box)=b.x+b.w/2 to b.y+b.h/2
    private fun drag(view:ControllerView,id:String,dx:Float,dy:Float){
        val start=center(boxes(view).getValue(id));val end=start.first+dx to start.second+dy
        send(view,MotionEvent.ACTION_DOWN,start);send(view,MotionEvent.ACTION_MOVE,end);send(view,MotionEvent.ACTION_UP,end)
    }
    private fun pinch(view:ControllerView,id:String){
        val p=center(boxes(view).getValue(id));val second=p.first+20f to p.second;val moved=p.first+30f to p.second
        send(view,MotionEvent.ACTION_DOWN,p)
        send(view,MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),p,second)
        send(view,MotionEvent.ACTION_MOVE,p,moved)
        send(view,MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),p,moved)
        send(view,MotionEvent.ACTION_UP,p)
    }
    @Test fun bothSticksHomeAndBackDragSeparatelyAndSurviveRecreation()=ins.runOnMainSync{
        val pad=view();pad.startEditing()
        for(id in ids){
            val before=boxes(pad);drag(pad,id,16f,-10f);val after=boxes(pad)
            same(before.getValue(id).copy(x=before.getValue(id).x+16f,y=before.getValue(id).y-10f),after.getValue(id))
            before.filterKeys{it!=id}.forEach{(key,box)->same(box,after.getValue(key))}
        }
        pad.finishEditing();val restored=boxes(view());boxes(pad).forEach{(id,box)->same(box,restored.getValue(id))}
        assertEquals(PadState(),pad.stateForTest())
    }
    @Test fun bothSticksHomeAndBackResizeSeparatelyAndSurviveRecreation()=ins.runOnMainSync{
        val pad=view();pad.startEditing()
        for(id in ids){
            val before=boxes(pad);pinch(pad,id);val after=boxes(pad)
            assertEquals(before.getValue(id).w*1.4f,after.getValue(id).w,1e-3f)
            assertEquals(before.getValue(id).h*1.4f,after.getValue(id).h,1e-3f)
            before.filterKeys{it!=id}.forEach{(key,box)->same(box,after.getValue(key))}
        }
        pad.finishEditing();val restored=boxes(view());boxes(pad).forEach{(id,box)->same(box,restored.getValue(id))}
    }
    private fun seedLegacy(){prefs.edit().putFloat("scale.PS1.sticks",1.2f).putFloat("scale.PS1.sticks.x",.02f).putFloat("scale.PS1.sticks.y",.01f)
        .putFloat("scale.PS1.system",1.1f).putFloat("scale.PS1.system.x",-.01f).putFloat("scale.PS1.system.y",-.02f).commit()}
    @Test fun savedSharedSettingsMigrateOnceWithoutMovingOrResizingTheirControls()=ins.runOnMainSync{
        val base=view();val original=base.controlsForTest().toList();val scale=base.scaleForTest();val w=2160f/scale;val h=1080f/scale
        clear();seedLegacy();val migrated=view();val actual=boxes(migrated)
        for((group,s,offset) in listOf(Triple("sticks",1.2f,.02f*w to .01f*h),Triple("system",1.1f,-.01f*w to -.02f*h))){
            val members=original.filter{if(group=="sticks")it.kind in listOf("leftStick","rightStick") else it.id in listOf("select","start","pause","home","back")}
            val ax=(members.minOf{it.box.x}+members.maxOf{it.box.x+it.box.w})/2
            val ay=(members.minOf{it.box.y}+members.maxOf{it.box.y+it.box.h})/2
            for(control in members){val b=control.box
                val cx=ax+(b.x+b.w/2-ax)*s+offset.first;val cy=ay+(b.y+b.h/2-ay)*s+offset.second
                same(Box(cx-b.w*s/2,cy-b.h*s/2,b.w*s,b.h*s),actual.getValue(control.id))
            }
            assertTrue(prefs.getBoolean("layout.PS1.independent.$group",false))
            assertEquals(s,prefs.getFloat("legacy.scale.PS1.$group",0f),1e-4f)
        }
        val again=boxes(view());actual.forEach{(id,box)->same(box,again.getValue(id))}
        val savedHome=prefs.getFloat("scale.PS1.home.x",0f)
        prefs.edit().putFloat("scale.PS1.home.x",savedHome+.04f).commit()
        val edited=boxes(view());assertEquals(actual.getValue("home").x+.04f*w,edited.getValue("home").x,1e-3f)
        assertEquals(savedHome+.04f,prefs.getFloat("scale.PS1.home.x",0f),1e-4f)
    }
    @Test fun resetClearsBothNewAdjustmentsAndLegacySettings()=ins.runOnMainSync{
        val defaults=boxes(view());clear();seedLegacy();val pad=view();pad.startEditing();pinch(pad,"left");drag(pad,"back",12f,-6f)
        val at=2160f/pad.scaleForTest()/2+72f to 34f
        send(pad,MotionEvent.ACTION_DOWN,at);send(pad,MotionEvent.ACTION_UP,at)
        pad.finishEditing();val restored=boxes(view());defaults.forEach{(id,box)->same(box,restored.getValue(id))}
        assertFalse(prefs.contains("scale.PS1.sticks"));assertFalse(prefs.contains("legacy.scale.PS1.system"))
    }
}
