package org.retrotv.pad
import org.junit.Assert.*
import org.junit.Test
class PadLayoutTest{
    private val controls=listOf(PadControl("dpad",Box(0f,0f,100f,100f),"dpad"),PadControl("a",Box(300f,40f,40f,40f)),PadControl("b",Box(360f,40f,40f,40f)),
        PadControl("start",Box(200f,150f,80f,40f),round=false),PadControl("l",Box(0f,-60f,120f,40f),round=false))
    @Test fun groupsFollowControlRoles(){
        assertEquals(listOf("dpad","face","face","system","shoulder"),controls.map(PadLayout::groupOf))
        assertEquals("face",PadLayout.groupOf(PadControl("l",Box(0f,0f,1f,1f))))
    }
    @Test fun scalingKeepsGroupCentreAndOnlyTouchesItsGroup(){
        val out=PadLayout.applyScales(controls,mapOf("face" to 1.5f))
        assertEquals(controls[0],out[0]);assertEquals(controls[3],out[3])
        val a=out[1].box;val b=out[2].box
        assertEquals(56f,a.w,1e-4f);assertEquals(56f,b.w,1e-4f)
        assertEquals(350f,(a.x+a.w/2+b.x+b.w/2)/2,1e-4f);assertEquals(60f,a.y+a.h/2,1e-4f)
        assertTrue(b.x-(a.x+a.w)>controls[2].box.x-(controls[1].box.x+controls[1].box.w)-1e-4f)
    }
    @Test fun scaleIsClampedToUsableRange(){
        val big=PadLayout.applyScales(controls,mapOf("dpad" to 9f))[0].box;assertEquals(100f*PadLayout.MAX,big.w,1e-4f)
        val small=PadLayout.applyScales(controls,mapOf("dpad" to .1f))[0].box;assertEquals(100f*PadLayout.MIN,small.w,1e-4f)
    }
    @Test fun dragMovesTheWholeGroupOnly(){
        val out=PadLayout.applyOffsets(controls,mapOf("face" to (-40f to 25f)))
        assertEquals(260f,out[1].box.x,1e-4f);assertEquals(65f,out[2].box.y,1e-4f);assertEquals(60f,out[2].box.x-out[1].box.x,1e-4f)
        assertEquals(controls[0],out[0]);assertEquals(controls[3],out[3])
        val kept=PadLayout.fitInside(PadLayout.applyOffsets(controls,mapOf("system" to (500f to 0f))),390f,300f,6f)[3].box
        assertEquals(384f,kept.x+kept.w,1e-3f)
    }
    @Test fun groupsPushedPastAnEdgeAreMovedBackInside(){
        val out=PadLayout.fitInside(PadLayout.applyScales(controls,mapOf("face" to 1.4f)),390f,300f,6f)
        val face=out.filter{PadLayout.groupOf(it)=="face"};assertEquals(384f,face.maxOf{it.box.x+it.box.w},1e-3f)
        assertEquals(controls[3],out[3]);assertEquals(6f,out[0].box.x,1e-3f);assertEquals(84f,face[1].box.x-face[0].box.x,1e-3f)
    }
}
