package org.retrotv.pad

/** Foreground phone volume buttons. Each accepted DOWN sends one atomic TV step, including OS repeats. */
internal class TvVolumeKeys(private val adjust:(Boolean)->Boolean){
    private val held=mutableSetOf<Int>()
    fun down(keyCode:Int):Boolean{
        val increase=when(keyCode){24->true;25->false;else->return false}
        val accepted=adjust(increase)
        if(accepted)held+=keyCode
        // Once a hold belongs to the TV, do not suddenly change phone volume after a disconnect.
        return accepted||keyCode in held
    }
    fun up(keyCode:Int)=held.remove(keyCode)
    fun clear(){held.clear()}
}
