package org.retrotv.pad
import org.junit.Assert.*
import org.junit.Test
class ReportBufferTest{
    @Test fun quickPressAndReleaseBothReachTransport(){
        val q=ReportBuffer();q.offer(byteArrayOf(1,0,0,15,0));q.offer(byteArrayOf(0,0,0,15,0))
        assertEquals(1,q.take()!![0].toInt());assertEquals(0,q.take()!![0].toInt());assertNull(q.take())
    }
    @Test fun axesCoalesceWithoutRemovingButtonEdges(){
        val q=ReportBuffer();q.offer(byteArrayOf(1,0,0,15,10));q.offer(byteArrayOf(1,0,0,15,30));q.offer(byteArrayOf(0,0,0,15,40))
        val first=q.take()!!;assertEquals(1,first[0].toInt());assertEquals(30,first[4].toInt());assertEquals(0,q.take()!![0].toInt())
    }
    @Test fun fullQueueReportsBackpressureAndClearRemovesState(){
        val q=ReportBuffer(2);assertTrue(q.offer(byteArrayOf(1,0,0,15)));assertTrue(q.offer(byteArrayOf(0,0,0,15)));assertFalse(q.offer(byteArrayOf(1,0,0,15)))
        q.clear();assertNull(q.take());assertFalse(q.isNotEmpty())
    }
}