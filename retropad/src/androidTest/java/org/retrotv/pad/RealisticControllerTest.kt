package org.retrotv.pad

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import java.io.File

/** Exercises the actual View and renderer, not a duplicate of its layout/input implementation. */
@RunWith(AndroidJUnit4::class)
class RealisticControllerTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val consoles = Console.entries
    private fun actionControl(controls:List<PadControl>)=controls.firstOrNull{PadLayout.groupOf(it)=="face"}
        ?:controls.first{it.kind=="button"&&it.id!="ok"}
    private var savedAppearance:Boolean?=null
    private val prefs get()=ins.targetContext.getSharedPreferences("pad",android.content.Context.MODE_PRIVATE)
    @Before fun isolateAppearancePreference(){
        savedAppearance=if(prefs.contains("appearance.realistic"))prefs.getBoolean("appearance.realistic",false) else null
        prefs.edit().remove("appearance.realistic").commit()
    }
    @After fun restoreAppearancePreference(){
        val editor=prefs.edit();val saved=savedAppearance
        if(saved==null)editor.remove("appearance.realistic") else editor.putBoolean("appearance.realistic",saved)
        editor.commit()
    }
    private fun view(console: Console, width: Int = 2160, height: Int = 1080, realistic:Boolean?=true): ControllerView {
        return ControllerView(ins.targetContext, object : ControllerView.Callback {
            override fun send(state: PadState) = Unit
            override fun connect() = Unit
            override fun select(console: Console) = Unit
            override fun auto() = Unit
            override fun options() = Unit
        }).apply { this.console = console;realistic?.let(::setRealisticAppearance); layout(0, 0, width, height); controlsForTest() }
    }
    private data class Finger(val id: Int, val x: Float, val y: Float)
    private fun at(control: PadControl, id: Int, nx: Float = .5f, ny: Float = .5f) =
        Finger(id, control.box.x + control.box.w * nx, control.box.y + control.box.h * ny)
    private fun send(view: ControllerView, action: Int, vararg fingers: Finger) {
        val scale = view.scaleForTest(); val now = SystemClock.uptimeMillis()
        val properties = fingers.map { p -> MotionEvent.PointerProperties().apply {
            id = p.id; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }.toTypedArray()
        val coordinates = fingers.map { p -> MotionEvent.PointerCoords().apply {
            x = p.x * scale; y = p.y * scale; pressure = 1f; size = 1f
        } }.toTypedArray()
        val event = MotionEvent.obtain(now, now, action, fingers.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }
    private val secondDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    private val secondUp = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    @Test fun classicIsDefaultAndAppearancePersistsWithoutChangingHitboxes() = ins.runOnMainSync {
        for(console in consoles){
            val view=view(console,realistic=null)
            assertFalse(view.realisticAppearance)
            val controls=view.controlsForTest().toList()
            val before=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            val after=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            try{
                view.draw(Canvas(before))
                val finger=at(actionControl(controls),42)
                send(view,MotionEvent.ACTION_DOWN,finger)
                view.setRealisticAppearance(true)
                assertEquals(PadState(),view.stateForTest())
                assertEquals(controls,view.controlsForTest())
                assertTrue(view(console,realistic=null).realisticAppearance)
                view.draw(Canvas(after));assertFalse(before.sameAs(after))
                view.setRealisticAppearance(false)
                assertFalse(view(console,realistic=null).realisticAppearance)
                view.draw(Canvas(after));assertTrue("Classic appearance changed for $console",before.sameAs(after))
            }finally{before.recycle();after.recycle()}
        }
    }

    @Test fun everyButtonKeepsItsMappingAndReleases() = ins.runOnMainSync {
        for (console in consoles) {
            val view = view(console)
            for (control in view.controlsForTest().filter { it.kind == "button" }) {
                val finger = at(control, 42)
                send(view, MotionEvent.ACTION_DOWN, finger)
                assertEquals("$console/${control.id}", console.mask(control.id), view.stateForTest().buttons)
                send(view, MotionEvent.ACTION_UP, finger)
                assertEquals(PadState(), view.stateForTest())
            }
        }
    }
    @Test fun diagonalAndFaceButtonSurviveIndependentReleaseAndCancel() = ins.runOnMainSync {
        for (console in consoles) {
            val view = view(console); val controls = view.controlsForTest()
            val dpad = at(controls.first { it.kind == "dpad" }, 7, .85f, .15f)
            val face = actionControl(controls)
            val button = at(face, 42)
            send(view, MotionEvent.ACTION_DOWN, dpad)
            send(view, secondDown, dpad, button)
            assertEquals(9, view.stateForTest().dpad)
            assertEquals(face.mask, view.stateForTest().buttons)
            send(view, secondUp, dpad, button)
            assertEquals(9, view.stateForTest().dpad); assertEquals(0, view.stateForTest().buttons)
            send(view, MotionEvent.ACTION_CANCEL, dpad)
            assertEquals(PadState(), view.stateForTest())
        }
    }
    @Test fun ps1SupportsTwoSticksAndAThirdFinger() = ins.runOnMainSync {
        val view = view(Console.PS1); val controls = view.controlsForTest()
        val left = at(controls.first { it.kind == "leftStick" }, 7, .85f)
        val right = at(controls.first { it.kind == "rightStick" }, 42, .5f, .15f)
        val face = at(controls.first { it.id == "cross" }, 99)
        send(view, MotionEvent.ACTION_DOWN, left)
        send(view, secondDown, left, right)
        send(view, MotionEvent.ACTION_POINTER_DOWN or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), left, right, face)
        val state = view.stateForTest()
        assertTrue(state.lx > .9f); assertTrue(state.ry < -.9f)
        assertEquals(Console.PS1.mask("cross"), state.buttons)
        send(view, MotionEvent.ACTION_CANCEL, left, right, face)
        assertEquals(PadState(), view.stateForTest())
    }
    @Test fun resizeAndEditorKeepControlBoundsAndReleaseHeldInput() = ins.runOnMainSync {
        // 20:9, 16:9, 4:3 and a portrait-shaped window. The Activity retains sensorLandscape.
        for (console in consoles) for ((width, height) in listOf(2400 to 1080,1920 to 1080,1440 to 1080,1080 to 1920)) {
            val view = view(console, width, height)
            val scale = view.scaleForTest()
            for (control in view.controlsForTest()) {
                val b = control.box
                assertTrue("$console/${control.id} outside $width x $height", b.x >= 0 && b.y >= 0 &&
                    (b.x+b.w)*scale <= width+.1f && (b.y+b.h)*scale <= height+.1f)
            }
            val face = at(actionControl(view.controlsForTest()), 7)
            send(view, MotionEvent.ACTION_DOWN, face)
            view.startEditing(); assertEquals(PadState(), view.stateForTest())
            view.finishEditing(); view.controlsForTest()
            send(view, MotionEvent.ACTION_DOWN, face)
            view.layout(0,0,width+20,height)
            assertEquals(PadState(), view.stateForTest())
        }
    }
    @Test fun pressedFramesChangeAndRenderingDoesNotLeakPaintState() = ins.runOnMainSync {
        for (console in consoles) {
            val view = view(console)
            val baseline = Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            val active = Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            val restored = Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(baseline))
                val face = at(actionControl(view.controlsForTest()), 7)
                send(view,MotionEvent.ACTION_DOWN,face); view.draw(Canvas(active))
                assertFalse("Pressed appearance missing for $console",baseline.sameAs(active))
                if(InstrumentationRegistry.getArguments().getString("exportFrames")=="1"){
                    val dir=File(ins.targetContext.filesDir,"realistic-review");dir.mkdirs()
                    for((suffix,bitmap) in listOf("neutral" to baseline,"pressed" to active)){
                        File(dir,"${console.name.lowercase()}-$suffix.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                    }
                }
                send(view,MotionEvent.ACTION_CANCEL,face); view.draw(Canvas(restored))
                assertTrue("Paint state leaked after release for $console",baseline.sameAs(restored))
            } finally { baseline.recycle(); active.recycle(); restored.recycle() }
        }
    }
    @Test fun directionalPressAndReleaseAreVisibleInEveryModel() = ins.runOnMainSync {
        for(console in consoles){
            val view=view(console)
            val baseline=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            val frame=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            try{
                view.draw(Canvas(baseline))
                val finger=at(view.controlsForTest().first{it.kind=="dpad"},7,.85f,.15f)
                send(view,MotionEvent.ACTION_DOWN,finger)
                assertEquals(9,view.stateForTest().dpad)
                view.draw(Canvas(frame));assertFalse("Directional feedback missing for $console",baseline.sameAs(frame))
                send(view,MotionEvent.ACTION_UP,finger)
                view.draw(Canvas(frame));assertTrue("Directional release changed neutral frame for $console",baseline.sameAs(frame))
            }finally{baseline.recycle();frame.recycle()}
        }
    }
}
