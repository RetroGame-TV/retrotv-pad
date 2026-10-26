package org.retrotv.pad

/** User sizing of the built-in pads: controls are resized per group, around the centre of the group. */
object PadLayout{
    const val MIN=.7f
    const val MAX=1.4f
    val groups=listOf("dpad","face","shoulder","system","sticks")
    fun label(group:String)=when(group){"dpad"->"Direcional";"face"->"Botões";"shoulder"->"Gatilhos";"system"->"Start e Select";else->"Analógicos"}
    fun groupOf(control:PadControl)=when{
        control.kind=="dpad"||control.id=="ok"->"dpad"
        control.kind=="leftStick"||control.kind=="rightStick"->"sticks"
        control.id in setOf("select","start","pause","home","back","options","volume")->"system"
        !control.round&&control.id in setOf("l","r","l1","r1","l2","r2")->"shoulder"
        else->"face"
    }
    fun clamp(scale:Float)=scale.coerceIn(MIN,MAX)
    fun applyScales(controls:List<PadControl>,scales:Map<String,Float>):List<PadControl>{
        val anchors=controls.groupBy(::groupOf).mapValues{(_,items)->
            val x0=items.minOf{it.box.x};val y0=items.minOf{it.box.y};val x1=items.maxOf{it.box.x+it.box.w};val y1=items.maxOf{it.box.y+it.box.h}
            (x0+x1)/2 to (y0+y1)/2}
        return controls.map{control->
            val group=groupOf(control);val s=clamp(scales[group]?:1f)
            if(s==1f)return@map control
            val (ax,ay)=anchors.getValue(group);val b=control.box;val cx=ax+(b.x+b.w/2-ax)*s;val cy=ay+(b.y+b.h/2-ay)*s
            control.copy(box=Box(cx-b.w*s/2,cy-b.h*s/2,b.w*s,b.h*s))
        }
    }
    /** Moves whole groups by the user's drag offsets (in the same units as the boxes). */
    fun applyOffsets(controls:List<PadControl>,offsets:Map<String,Pair<Float,Float>>)=controls.map{c->
        val (dx,dy)=offsets[groupOf(c)]?:(0f to 0f);if(dx==0f&&dy==0f)c else c.copy(box=c.box.copy(x=c.box.x+dx,y=c.box.y+dy))}
    /** Moves each group back inside the screen when a larger size pushed it past an edge. */
    fun fitInside(controls:List<PadControl>,width:Float,height:Float,margin:Float):List<PadControl>{
        val shifts=controls.groupBy(::groupOf).mapValues{(_,items)->
            val x0=items.minOf{it.box.x};val y0=items.minOf{it.box.y};val x1=items.maxOf{it.box.x+it.box.w};val y1=items.maxOf{it.box.y+it.box.h}
            fun shift(a:Float,b:Float,limit:Float)=if(b-a>limit-2*margin)(limit-a-b)/2 else maxOf(0f,margin-a)+minOf(0f,limit-margin-b)
            shift(x0,x1,width) to shift(y0,y1,height)}
        return controls.map{c->val (dx,dy)=shifts.getValue(groupOf(c));if(dx==0f&&dy==0f)c else c.copy(box=c.box.copy(x=c.box.x+dx,y=c.box.y+dy))}
    }
}
