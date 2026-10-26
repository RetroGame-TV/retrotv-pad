package org.retrotv.pad
/** Preserve button press/release edges; coalesce only consecutive axis motion.
 * Bounded backpressure is reported to the transport, never silently dropping a release.
 */
class ReportBuffer(private val capacity:Int=32){
    private val queue=java.util.ArrayDeque<ByteArray>()
    @Synchronized fun offer(report:ByteArray):Boolean{
        require(report.size>=4)
        val previous=queue.peekLast()
        if(previous!=null&&(0..3).all{previous[it]==report[it]})queue.removeLast()
        if(queue.size>=capacity)return false
        queue.addLast(report.copyOf());return true
    }
    @Synchronized fun take():ByteArray?=queue.pollFirst()
    @Synchronized fun isNotEmpty()=queue.isNotEmpty()
    @Synchronized fun clear(){queue.clear()}
}