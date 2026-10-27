package org.retrotv.pad

import org.junit.Assert.*
import org.junit.Test

class TvVolumeTest {
    @Test fun volumeCommandsUseRemoteKeysAndAtomicShortPresses(){
        assertArrayEquals(byteArrayOf(0x52,0x04,0x08,0x18,0x10,0x03),GoogleTvKeys.volumeMessage(true))
        assertArrayEquals(byteArrayOf(0x52,0x04,0x08,0x19,0x10,0x03),GoogleTvKeys.volumeMessage(false))
        for(increase in listOf(false,true)){
            val bytes=java.io.ByteArrayOutputStream().also{Pb.writeFrame(it,GoogleTvKeys.volumeMessage(increase))}.toByteArray()
            val frame=Pb.parse(Pb.readFrame(bytes.inputStream())!!)
            assertEquals(setOf(10),frame.keys)
            val key=Pb.parse(frame.getValue(10).first() as ByteArray)
            assertEquals(if(increase)24L else 25L,key.getValue(1).first())
            assertEquals(3L,key.getValue(2).first())
        }
    }
    @Test fun physicalKeysAdjustTvForEveryRepeatAndConsumeMatchingRelease(){
        val sent=mutableListOf<Boolean>();val keys=TvVolumeKeys{sent+=it;true}
        assertFalse(keys.down(19));assertTrue(sent.isEmpty())
        assertTrue(keys.down(24));assertTrue(keys.down(24));assertTrue(keys.up(24));assertFalse(keys.up(24))
        assertTrue(keys.down(25));assertTrue(keys.up(25))
        assertEquals(listOf(true,true,false),sent)
    }
    @Test fun disconnectedPhoneKeepsItsOwnVolumeAndConnectionLossDoesNotRedirectAHold(){
        var available=false;val keys=TvVolumeKeys{available}
        assertFalse(keys.down(24));assertFalse(keys.up(24))
        available=true;assertTrue(keys.down(25))
        available=false;assertTrue(keys.down(25));assertTrue(keys.up(25))
        assertFalse(keys.down(25));assertFalse(keys.up(25))
    }
    @Test fun leavingTheActivityClearsPhysicalKeyOwnership(){
        val keys=TvVolumeKeys{true}
        assertTrue(keys.down(24));keys.clear();assertFalse(keys.up(24))
    }
}
