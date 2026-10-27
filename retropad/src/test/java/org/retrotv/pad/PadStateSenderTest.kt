package org.retrotv.pad

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class PadStateSenderTest{
    private class Worker:Executor{
        val tasks=java.util.ArrayDeque<Runnable>()
        override fun execute(command:Runnable){tasks.addLast(command)}
        fun run(){while(tasks.isNotEmpty())tasks.removeFirst().run()}
    }
    @Test fun idleWriterSchedulesAxesImmediatelyAndRearmsAfterDraining(){
        val worker=Worker();val sent=mutableListOf<PadState>()
        val sender=PadStateSender(worker,{sent+=it},{fail("transport failed")})
        for(x in listOf(.1f,.2f,0f)){
            val state=PadState(lx=x);sender.offer(state)
            assertEquals(1,worker.tasks.size);worker.run();assertEquals(state,sent.last())
        }
        assertEquals(3,sent.size)
    }
    @Test fun busyWriterKeepsNewestAxesAndEveryDigitalTransition(){
        val worker=Worker();val sent=mutableListOf<PadState>()
        val sender=PadStateSender(worker,{sent+=it},{fail("transport failed")})
        repeat(1000){sender.offer(PadState(lx=it/1000f))}
        sender.offer(PadState(buttons=1,lx=1f));sender.offer(PadState(buttons=1,lx=.4f))
        sender.offer(PadState(buttons=1,dpad=8));sender.offer(PadState(dpad=8));sender.offer(PadState())
        assertEquals(1,worker.tasks.size);worker.run()
        assertEquals(listOf(PadState(lx=.999f),PadState(buttons=1,lx=.4f),PadState(buttons=1,dpad=8),PadState(dpad=8),PadState()),sent)
    }
    @Test fun saturationFailsOnceInsteadOfDroppingARelease(){
        val worker=Worker();var failures=0;val sent=mutableListOf<PadState>()
        val sender=PadStateSender(worker,{sent+=it},{failures++},2)
        sender.offer(PadState());sender.offer(PadState(buttons=1));sender.offer(PadState())
        sender.offer(PadState(buttons=2));worker.run()
        assertEquals(1,failures);assertTrue(sent.isEmpty())
    }
    @Test fun closingAConnectionDiscardsItsPendingStates(){
        val worker=Worker();val sent=mutableListOf<PadState>()
        val old=PadStateSender(worker,{sent+=it},{fail("transport failed")})
        old.offer(PadState(buttons=1));old.close();old.offer(PadState(buttons=2))
        val replacement=PadStateSender(worker,{sent+=it},{fail("transport failed")})
        replacement.offer(PadState());worker.run()
        assertEquals(listOf(PadState()),sent)
    }
    @Test fun updatesArrivingDuringWriteDrainWithoutAnotherTask(){
        val worker=Worker();val sent=mutableListOf<PadState>()
        lateinit var sender:PadStateSender
        sender=PadStateSender(worker,{sent+=it;if(it.buttons==1)sender.offer(PadState())},{fail("transport failed")})
        sender.offer(PadState(buttons=1));worker.run()
        assertEquals(listOf(PadState(buttons=1),PadState()),sent);assertTrue(worker.tasks.isEmpty())
    }
    @Test fun writeAndExecutorFailuresCloseOnce(){
        val worker=Worker();var failures=0
        val broken=PadStateSender(worker,{throw java.io.IOException()},{failures++})
        broken.offer(PadState(buttons=1));broken.offer(PadState());worker.run();broken.offer(PadState())
        assertEquals(1,failures)
        val rejected=PadStateSender(Executor{throw java.util.concurrent.RejectedExecutionException()},{fail("write after rejection")},{failures++})
        rejected.offer(PadState());rejected.offer(PadState());assertEquals(2,failures)
    }
}
