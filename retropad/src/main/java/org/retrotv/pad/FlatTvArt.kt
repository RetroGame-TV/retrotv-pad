package org.retrotv.pad

import android.graphics.*

/** TV remote is always flat: solid fills, simple strokes, no material shaders or cast shadows. */
internal class FlatTvArt {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style=Paint.Style.STROKE;strokeWidth=2f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND
    }
    private fun c(hex:String)=Color.parseColor(hex)
    fun background(canvas:Canvas){canvas.drawColor(c("#121820"))}
    private fun label(canvas:Canvas,text:String,x:Float,y:Float,size:Float,center:Boolean=false){
        paint.color=c("#E0E7EF");paint.textSize=size;paint.typeface=Typeface.create("sans-serif",Typeface.NORMAL)
        paint.textAlign=if(center)Paint.Align.CENTER else Paint.Align.LEFT
        canvas.drawText(text,x,y,paint)
    }
    fun control(canvas:Canvas,control:PadControl,state:PadState,pressed:Boolean){
        val b=control.box;val x=b.x+b.w/2;val y=b.y+b.h/2
        if(control.kind=="dpad"){
            val r=b.w/2-6f;paint.color=c("#232D39");canvas.drawCircle(x,y,r,paint)
            stroke.color=c("#3A4859");canvas.drawCircle(x,y,r,stroke)
            for((i,bit) in intArrayOf(1,8,2,4).withIndex()){
                canvas.save();canvas.rotate(i*90f,x,y)
                val active=state.dpad and bit!=0
                if(active){paint.color=c("#405877");canvas.drawArc(RectF(x-r,y-r,x+r,y+r),235f,70f,true,paint)}
                val ay=y-r*.7f;val s=r*.10f
                val arrow=Path().apply{moveTo(x,ay-s*.6f);lineTo(x+s*.62f,ay+s*.4f);lineTo(x-s*.62f,ay+s*.4f);close()}
                paint.color=c("#DFE8F3");canvas.drawPath(arrow,paint);canvas.restore()
            }
            return
        }
        paint.color=if(pressed)c("#46658B") else c("#2E3B4C")
        if(control.id=="ok"){
            canvas.drawCircle(x,y,b.w/2-8f,paint)
            label(canvas,"OK",x,y+7f,20f,true);return
        }
        val r=RectF(b.x+6f,b.y+4f,b.x+b.w-6f,b.y+b.h-4f)
        canvas.drawRoundRect(r,12f,12f,paint)
        stroke.color=c("#DFE8F3");stroke.strokeWidth=2f
        val s=10f;val path=Path()
        when(control.id){
            "back"->{path.moveTo(x+s,y-s*.55f);path.quadTo(x+s*1.2f,y+s*.5f,x-s,y+s*.1f);path.moveTo(x-s*.4f,y-s*.5f);path.lineTo(x-s,y+s*.1f);path.lineTo(x-s*.4f,y+s*.7f);canvas.drawPath(path,stroke)}
            "home"->{path.moveTo(x-s,y);path.lineTo(x,y-s);path.lineTo(x+s,y);path.moveTo(x-s*.65f,y-s*.25f);path.lineTo(x-s*.65f,y+s);path.lineTo(x+s*.65f,y+s);path.lineTo(x+s*.65f,y-s*.25f);canvas.drawPath(path,stroke)}
            "options"->for(i in -1..1)canvas.drawLine(x-s,y+i*6f,x+s,y+i*6f,stroke)
            "volume"->{
                path.moveTo(x-s,y-4f);path.lineTo(x-4f,y-4f);path.lineTo(x+3f,y-s);path.lineTo(x+3f,y+s);path.lineTo(x-4f,y+4f);path.lineTo(x-s,y+4f);path.close();canvas.drawPath(path,stroke)
                canvas.drawArc(RectF(x,y-9f,x+15f,y+9f),-60f,120f,false,stroke)
            }
        }
        label(canvas,when(control.id){"back"->"Retornar";"home"->"Início";"options"->"Menu";else->"Volume"},b.x+b.w+12f,y+5f,14f)
    }
}
