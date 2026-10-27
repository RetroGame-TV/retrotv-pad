package org.retrotv.pad

import java.util.concurrent.Executor

/** One connection's output. No timer: idle writers wake immediately, busy writers keep the newest axes.
 * Digital transitions remain FIFO. Saturation/failure closes the connection instead of losing a release.
 */
internal class PadStateSender(
    private val executor:Executor,
    private val write:(PadState)->Unit,
    private val failed:()->Unit,
    private val capacity:Int=32
):AutoCloseable{
    private val queue=java.util.ArrayDeque<PadState>()
    private var running=false
    private var closed=false
    init{require(capacity>0)}
    fun offer(state:PadState){
        var start=false;var overflow=false
        synchronized(this){
            if(closed)return
            val tail=queue.peekLast()
            if(tail!=null&&tail.buttons==state.buttons&&tail.dpad==state.dpad)queue.removeLast()
            if(queue.size>=capacity){closed=true;queue.clear();overflow=true}
            else{queue.addLast(state);if(!running){running=true;start=true}}
        }
        if(overflow){failed();return}
        if(start)try{executor.execute(::drain)}catch(_:java.util.concurrent.RejectedExecutionException){fail()}
    }
    private fun drain(){
        try{
            while(true){
                val state=synchronized(this){
                    if(closed||queue.isEmpty()){running=false;return}
                    queue.removeFirst()
                }
                write(state)
            }
        }catch(_:Exception){fail()}
    }
    private fun fail(){
        val notify=synchronized(this){if(closed)false else{closed=true;queue.clear();true}}
        if(notify)failed()
    }
    override fun close(){synchronized(this){closed=true;queue.clear()}}
}
