package org.retrotv.pad
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor
class CompactInputTest{
    @Test fun fixedFixtureIncludesHomeBackDiagonalsAndSignedAxes(){
        assertArrayEquals(byteArrayOf(0xF1.toByte(),0xA5.toByte(),15,3,9,(-127).toByte(),127,(-1).toByte(),0),PadWire.binary(PadState(buttons=0x30FA5,dpad=9,lx=-1f,ly=1f,rx=-1f/127)))
    }
    @Test fun randomBinaryStatesHaveExactlyTheLegacyMapping(){
        val random=java.util.Random(91)
        repeat(10000){
            val state=PadState(buttons=random.nextInt() and 0x30FFF,dpad=random.nextInt(16),lx=random.nextFloat()*4-2,ly=random.nextFloat()*4-2,rx=random.nextFloat()*4-2,ry=random.nextFloat()*4-2)
            val b=PadWire.binary(state);assertEquals(9,b.size)
            val buttons=(b[1].toInt() and 255) or ((b[2].toInt() and 255) shl 8) or ((b[3].toInt() and 255) shl 16)
            assertEquals(PadWire.input(state),"I ${buttons.toString(16)} ${b[4]} ${b[5]} ${b[6]} ${b[7]} ${b[8]}")
        }
    }
    @Test fun quantizedDuplicatesDoNotWakeWriterButEveryTapStillDoes(){
        val tasks=java.util.ArrayDeque<Runnable>();val sent=mutableListOf<PadState>()
        val sender=PadStateSender(Executor{tasks.add(it)},{sent+=it},{fail("failure")},equivalent=PadWire::sameInput)
        fun send(s:PadState){sender.offer(s);while(tasks.isNotEmpty())tasks.removeFirst().run()}
        send(PadState());repeat(10000){send(PadState(lx=.00001f*(it%100)))}
        assertEquals(1,sent.size)
        repeat(12){send(PadState(buttons=1 shl it));send(PadState())}
        assertEquals(25,sent.size)
        repeat(12){assertEquals(1 shl it,sent[1+it*2].buttons);assertEquals(0,sent[2+it*2].buttons)}
    }
}
