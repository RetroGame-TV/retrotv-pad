package org.retrotv.pad
import org.junit.Assert.*
import org.junit.Test
class HidGamepadTest{
    /** Walks the descriptor's short items: collections balance and input main items add up to the 8-byte report. */
    @Test fun descriptorIsWellFormedAndMatchesReportSize(){
        val d=HidGamepad.DESCRIPTOR;var i=0;var depth=0;var size=0;var count=0;var bits=0;var reportId=-1
        while(i<d.size){
            val prefix=d[i].toInt() and 0xFF;val length=intArrayOf(0,1,2,4)[prefix and 3]
            var value=0;for(k in 0 until length)value=value or ((d[i+1+k].toInt() and 0xFF) shl (8*k))
            when(prefix and 0xFC){0xA0->depth++;0xC0->depth--;0x74->size=value;0x94->count=value;0x80->bits+=size*count;0x84->reportId=value}
            assertTrue("collection closed early",depth>=0);i+=1+length
        }
        assertEquals(0,depth);assertEquals(HidGamepad.REPORT_ID,reportId);assertEquals(HidGamepad.REPORT_SIZE*8,bits)
        assertEquals(HidGamepad.REPORT_SIZE,HidGamepad.report(PadState()).size)
    }
    @Test fun neutralReportHasNoButtonsCentredHatAndAxes(){
        assertArrayEquals(byteArrayOf(0,0,0,HidGamepad.HAT_NEUTRAL.toByte(),0,0,0,0),HidGamepad.report(PadState()))
    }
    @Test fun padButtonsLandOnAndroidGamepadButtons(){
        fun pressed(bit:Int):Int{val r=HidGamepad.report(PadState(buttons=1 shl bit));val v=(r[0].toInt() and 0xFF) or ((r[1].toInt() and 0xFF) shl 8);return Integer.numberOfTrailingZeros(v)+1}
        // HID button n -> BTN_GAMEPAD+(n-1): 1 A, 2 B, 4 X, 5 Y, 7 L1, 8 R1, 9 L2, 10 R2, 11 Select, 12 Start, 13 Mode, 14 L3, 15 R3.
        assertEquals(listOf(1,2,4,5,7,8,9,10,11,12,14,15),(0..11).map{pressed(it)})
        assertEquals(13,pressed(16))
        assertArrayEquals(HidGamepad.report(PadState()),HidGamepad.report(PadState(buttons=1 shl 17)))
    }
    @Test fun dpadBitsBecomeHatValuesAndOppositesCancel(){
        val cases=mapOf(1 to 0,9 to 1,8 to 2,10 to 3,2 to 4,6 to 5,4 to 6,5 to 7,0 to 15,3 to 15,12 to 15)
        cases.forEach{(dpad,hat)->assertEquals("dpad $dpad",hat,HidGamepad.hat(dpad))}
    }
    @Test fun axesAreSignedAndClamped(){
        val r=HidGamepad.report(PadState(lx=-1f,ly=1f,rx=.5f,ry=-3f))
        assertEquals(listOf(-127,127,64,-127),(4..7).map{r[it].toInt()})
    }
    @Test fun dpadTouchSectors(){
        assertEquals(8,dpadBits(95f,50f,100f,100f));assertEquals(1,dpadBits(50f,5f,100f,100f))
        assertEquals(9,dpadBits(90f,5f,100f,100f));assertEquals(6,dpadBits(10f,95f,100f,100f))
        assertEquals(0,dpadBits(52f,51f,100f,100f))
    }
}
