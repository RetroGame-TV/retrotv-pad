package org.retrotv.pad
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
class PadInputTest {
    private fun pad()=PadInput().apply{controls=listOf(PadControl("dpad",Box(0f,0f,100f,100f),"dpad"),PadControl("a",Box(120f,0f,50f,50f),mask=1),PadControl("b",Box(180f,0f,50f,50f),mask=2),PadControl("stick",Box(0f,140f,100f,100f),"leftStick"))}
    @Test fun twoFingersHoldDpadAndButtonIndependently(){
        val p=pad();p.down(7,95f,50f);p.down(42,140f,20f);assertEquals(8,p.state().dpad);assertEquals(1,p.state().buttons)
        p.up(42);assertEquals(8,p.state().dpad);assertEquals(0,p.state().buttons)
        p.move(7,50f,5f);assertEquals(1,p.state().dpad);p.up(7);assertEquals(PadState(),p.state())
    }
    @Test fun releasingOneFingerDoesNotReleaseAnotherOnSameButton(){val p=pad();p.down(2,130f,10f);p.down(8,140f,20f);p.up(2);assertEquals(1,p.state().buttons);p.up(8);assertEquals(0,p.state().buttons)}
    @Test fun dragOutsideReleasesAndSlidingChangesButton(){val p=pad();p.down(0,130f,10f);p.move(0,160f,90f);assertEquals(0,p.state().buttons);p.move(0,190f,20f);assertEquals(2,p.state().buttons)}
    @Test fun stickIsClampedAndClearNeutralizesEverything(){val p=pad();p.down(10,50f,190f);p.move(10,500f,500f);val s=p.state();assertTrue(s.lx*s.lx+s.ly*s.ly<=1.00001f);p.down(11,130f,10f);p.clear();assertEquals(PadState(),p.state())}
    @Test fun skinChangeClearsHeldInputs(){val p=pad();p.down(0,130f,10f);p.controls=emptyList();assertEquals(PadState(),p.state())}
    @Test fun dpadAllowsDiagonalsAndNeutralizesOpposites(){val p=pad();p.down(0,90f,5f);assertEquals(9,p.state().dpad);p.move(0,50f,50f);assertEquals(0,p.state().dpad)}
    @Test fun consoleMappingsMatchLibretroPhysicalPositions(){
        assertEquals(1 shl 3,Console.MD.mask("a"));assertEquals(1 shl 1,Console.MD.mask("b"));assertEquals(1,Console.MD.mask("c"))
        assertEquals(1 shl 1,Console.PS1.mask("cross"));assertEquals(1,Console.PS1.mask("circle"));assertEquals(1 shl 3,Console.PS1.mask("square"));assertEquals(1 shl 2,Console.PS1.mask("triangle"))
        assertEquals((1 shl 8)or(1 shl 9),Console.NES.mask("pause"))
    }
    @Test fun metadataReadsUtf8AndRejectsUnboundedAndTruncatedFrames(){
        assertEquals("ação",MetadataProtocol.readLine(ByteArrayInputStream("ação\n".toByteArray())))
        assertNull(MetadataProtocol.readLine(ByteArrayInputStream(byteArrayOf())))
        try{MetadataProtocol.readLine(ByteArrayInputStream(ByteArray(2049){65}));fail("oversized metadata accepted")}catch(_:java.io.IOException){}
        try{MetadataProtocol.readLine(ByteArrayInputStream("partial".toByteArray()));fail("truncated metadata accepted")}catch(_:java.io.EOFException){}
    }
    @Test fun unknownSystemDoesNotSelectUnimplementedLayout(){assertNull(Console.fromSystem("Wii"));assertEquals(Console.MD,Console.fromSystem("Master System"))}
}