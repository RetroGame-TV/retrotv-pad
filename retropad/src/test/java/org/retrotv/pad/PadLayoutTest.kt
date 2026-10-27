package org.retrotv.pad
import org.junit.Assert.*
import org.junit.Test
class PadLayoutTest{
    private val controls=listOf(PadControl("dpad",Box(0f,0f,100f,100f),"dpad"),PadControl("a",Box(300f,40f,40f,40f)),PadControl("b",Box(360f,40f,40f,40f)),
        PadControl("start",Box(200f,150f,80f,40f),round=false),PadControl("l",Box(0f,-60f,120f,40f),round=false))
    @Test fun groupsFollowControlRoles(){
        assertEquals(listOf("dpad","face","face","system","shoulder"),controls.map(PadLayout::groupOf))
        assertEquals("face",PadLayout.groupOf(PadControl("l",Box(0f,0f,1f,1f))))
        assertEquals(listOf("leftStick","rightStick","home","back"),listOf(
            PadControl("left",Box(0f,0f,80f,80f),"leftStick"),PadControl("right",Box(0f,0f,80f,80f),"rightStick"),
            PadControl("home",Box(0f,0f,40f,40f)),PadControl("back",Box(0f,0f,40f,40f))).map(PadLayout::groupOf))
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
    private val separate=listOf(
        PadControl("left",Box(100f,120f,80f,80f),"leftStick"),PadControl("right",Box(300f,120f,80f,80f),"rightStick"),
        PadControl("home",Box(100f,300f,40f,40f)),PadControl("select",Box(200f,300f,60f,40f)),
        PadControl("start",Box(300f,300f,60f,40f)),PadControl("back",Box(400f,300f,40f,40f)))
    private fun migrated(group:String,scale:Float,offset:Pair<Float,Float>,w:Float=700f,h:Float=400f):List<PadControl>{
        val settings=PadLayout.splitLegacyGroup(separate,group,scale,offset,w,h,6f)
        return PadLayout.fitInside(PadLayout.applyOffsets(PadLayout.applyScales(separate,settings.mapValues{it.value.scale}),
            settings.mapValues{it.value.dx to it.value.dy}),w,h,6f)
    }
    @Test fun eachStickAndNavigationKeyCanMoveAndResizeWithoutMovingAnyNeighbour(){
        for(group in listOf("leftStick","rightStick","home","back")){
            val scaled=PadLayout.applyScales(separate,mapOf(group to 1.3f))
            val moved=PadLayout.applyOffsets(scaled,mapOf(group to (17f to -13f)))
            for(i in separate.indices){
                if(PadLayout.groupOf(separate[i])==group){
                    assertEquals(separate[i].box.w*1.3f,moved[i].box.w,1e-3f)
                    assertEquals(separate[i].box.x+separate[i].box.w/2+17f,moved[i].box.x+moved[i].box.w/2,1e-3f)
                }else assertEquals(separate[i],moved[i])
            }
        }
    }
    @Test fun oldCombinedStickScaleAndSpacingSurviveSplitting(){
        val out=migrated("sticks",1.25f,20f to -10f)
        assertEquals(Box(85f,100f,100f,100f),out[0].box)
        assertEquals(Box(335f,100f,100f,100f),out[1].box)
        for(i in 2..5)assertEquals(separate[i],out[i])
    }
    @Test fun oldHomeBackAndSystemGroupKeepTheirGeometryWhenSplit(){
        val out=migrated("system",1.2f,15f to -20f)
        val expected=listOf(Box(81f,276f,48f,48f),Box(201f,276f,72f,48f),Box(321f,276f,72f,48f),Box(441f,276f,48f,48f))
        for(i in 2..5){val actual=out[i].box;val want=expected[i-2]
            assertEquals(want.x,actual.x,1e-3f);assertEquals(want.y,actual.y,1e-3f)
            assertEquals(want.w,actual.w,1e-3f);assertEquals(want.h,actual.h,1e-3f)
        }
        assertEquals(separate[0],out[0]);assertEquals(separate[1],out[1])
    }
    @Test fun splitMigrationPreservesTheOldJointEdgeCorrection(){
        val out=migrated("sticks",1.25f,500f to 400f,500f,400f)
        assertEquals(Box(144f,294f,100f,100f),out[0].box)
        assertEquals(Box(394f,294f,100f,100f),out[1].box)
    }
}
