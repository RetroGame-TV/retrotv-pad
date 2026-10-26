package org.retrotv.pad
import android.graphics.*
import kotlin.math.*

/**
 * Vector parts of the built-in pads. Shapes follow the anatomy of the open Delta controller skins (embossed d-pad arrows,
 * recessed wells, convex buttons with engraved letters, angled pills) but are redrawn here; no Delta artwork is bundled.
 */
internal class PadArt{
    enum class Pad{SNES,NES,MD,PS1,GB}
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
    private val line=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    private val type=Paint(Paint.ANTI_ALIAS_FLAG).apply{textAlign=Paint.Align.CENTER}
    private val path=Path()
    fun mix(a:Int,b:Int,t:Float):Int{
        val u=t.coerceIn(0f,1f)
        return listOf(24,16,8,0).fold(0){out,s->out or ((((a shr s) and 255)*(1-u)+((b shr s) and 255)*u).roundToInt() shl s)}
    }
    fun light(c:Int,t:Float)=mix(c,c or 0x00FFFFFF,t)
    fun dark(c:Int,t:Float)=mix(c,c and 0xFF000000.toInt(),t)
    fun alpha(c:Int,a:Int)=(c and 0x00FFFFFF) or (a.coerceIn(0,255) shl 24)
    private fun reset(p:Paint){p.shader=null;p.clearShadowLayer();p.alpha=255}
    private fun vertical(top:Float,bottom:Float,vararg colors:Int)=LinearGradient(0f,top,0f,bottom,colors,null,Shader.TileMode.CLAMP)
    private fun shadow(p:Paint,blur:Float,dy:Float,a:Int){p.setShadowLayer(blur,0f,dy,alpha(Color.BLACK,a))}

    /** Body of the pad: soft drop shadow, vertical sheen and a lit upper rim. */
    fun shell(canvas:Canvas,r:RectF,radius:Float,top:Int,bottom:Int,dark:Boolean){
        reset(fill);fill.color=bottom;shadow(fill,22f,10f,if(dark)170 else 120);canvas.drawRoundRect(r,radius,radius,fill);reset(fill)
        fill.shader=vertical(r.top,r.bottom,top,bottom);canvas.drawRoundRect(r,radius,radius,fill)
        fill.shader=vertical(r.top,r.top+r.height()*.42f,alpha(Color.WHITE,if(dark)26 else 60),alpha(Color.WHITE,0));canvas.drawRoundRect(r,radius,radius,fill);reset(fill)
        line.shader=vertical(r.top,r.bottom,alpha(Color.WHITE,if(dark)70 else 200),alpha(Color.WHITE,0),alpha(Color.BLACK,if(dark)120 else 50));line.strokeWidth=1.6f
        canvas.drawRoundRect(RectF(r.left+.8f,r.top+.8f,r.right-.8f,r.bottom-.8f),radius-.8f,radius-.8f,line);line.shader=null
    }
    /** Recessed area: darker floor, shadowed upper lip and a lit lower lip. */
    fun well(canvas:Canvas,r:RectF,radius:Float,base:Int,depth:Float=.16f){
        reset(fill);fill.shader=vertical(r.top,r.bottom,dark(base,depth),dark(base,depth*.35f));canvas.drawRoundRect(r,radius,radius,fill);reset(fill)
        line.strokeWidth=2.2f;line.shader=vertical(r.top,r.bottom,alpha(Color.BLACK,90),alpha(Color.BLACK,0),alpha(Color.WHITE,120));canvas.drawRoundRect(RectF(r.left+1.1f,r.top+1.1f,r.right-1.1f,r.bottom-1.1f),radius,radius,line);line.shader=null
    }
    fun circleWell(canvas:Canvas,cx:Float,cy:Float,radius:Float,base:Int,depth:Float=.16f)=well(canvas,RectF(cx-radius,cy-radius,cx+radius,cy+radius),radius,base,depth)
    /** Engraved or printed text with a one-pixel lit edge underneath. */
    fun label(canvas:Canvas,text:String,x:Float,y:Float,size:Float,color:Int,bold:Boolean=true,italic:Boolean=false,emboss:Int=0,letter:Float=.06f,center:Boolean=true){
        type.shader=null;type.textSize=size;type.letterSpacing=letter;type.textAlign=if(center)Paint.Align.CENTER else Paint.Align.LEFT;type.typeface=Typeface.create("sans-serif-condensed",when{bold&&italic->Typeface.BOLD_ITALIC;bold->Typeface.BOLD;italic->Typeface.ITALIC;else->Typeface.NORMAL})
        if(emboss!=0){type.color=emboss;canvas.drawText(text,x,y+1f,type)}
        type.color=color;canvas.drawText(text,x,y,type)
    }
    private fun cross(cx:Float,cy:Float,arm:Float,thick:Float):Path{
        val p=Path();val r=thick*.2f
        p.addRoundRect(RectF(cx-arm,cy-thick/2,cx+arm,cy+thick/2),r,r,Path.Direction.CW)
        val v=Path();v.addRoundRect(RectF(cx-thick/2,cy-arm,cx+thick/2,cy+arm),r,r,Path.Direction.CW);p.op(v,Path.Op.UNION);return p
    }
    private fun arrow(canvas:Canvas,cx:Float,cy:Float,size:Float,dir:Int,color:Int){
        path.reset();val s=size
        when(dir){1->{path.moveTo(cx,cy-s*.6f);path.lineTo(cx+s*.62f,cy+s*.4f);path.lineTo(cx-s*.62f,cy+s*.4f)}
            2->{path.moveTo(cx,cy+s*.6f);path.lineTo(cx+s*.62f,cy-s*.4f);path.lineTo(cx-s*.62f,cy-s*.4f)}
            4->{path.moveTo(cx-s*.6f,cy);path.lineTo(cx+s*.4f,cy-s*.62f);path.lineTo(cx+s*.4f,cy+s*.62f)}
            else->{path.moveTo(cx+s*.6f,cy);path.lineTo(cx-s*.4f,cy-s*.62f);path.lineTo(cx-s*.4f,cy+s*.62f)}}
        path.close();reset(fill);fill.color=color;canvas.drawPath(path,fill)
    }
    /** Cross d-pad. `dpad` uses the HID bits: 1 up, 2 down, 4 left, 8 right. */
    fun dpad(canvas:Canvas,cx:Float,cy:Float,size:Float,dpad:Int,style:Pad,shell:Int,accent:Int){
        val arm=size*.5f;val thick=size*.35f
        val tx=(if(dpad and 8!=0)1.6f else 0f)-(if(dpad and 4!=0)1.6f else 0f);val ty=(if(dpad and 2!=0)1.6f else 0f)-(if(dpad and 1!=0)1.6f else 0f)
        when(style){
            Pad.SNES->circleWell(canvas,cx,cy,size*.64f,shell,.07f)
            Pad.PS1->circleWell(canvas,cx,cy,size*.62f,shell,.1f)
            Pad.MD->{circleWell(canvas,cx,cy,size*.68f,0xFF0B0B0D.toInt(),.0f)
                line.strokeWidth=2.4f;line.shader=SweepGradient(cx,cy,intArrayOf(0xFF6E6D74.toInt(),0xFF2A2A2E.toInt(),0xFF8C8B93.toInt(),0xFF2A2A2E.toInt(),0xFF6E6D74.toInt()),null);canvas.drawCircle(cx,cy,size*.64f,line);line.shader=null
                reset(fill);fill.shader=RadialGradient(cx,cy-size*.15f,size*.6f,0xFF3A3A3F.toInt(),0xFF1A1A1D.toInt(),Shader.TileMode.CLAMP);canvas.drawCircle(cx+tx*.5f,cy+ty*.5f,size*.56f,fill);reset(fill)
                for(i in 0..3){val a=i*PI.toFloat()/2+PI.toFloat()/4;fill.color=0xFFD9D8DE.toInt();canvas.drawCircle(cx+cos(a)*size*.6f,cy+sin(a)*size*.6f,1.6f,fill)}
                for((dir,dx,dy) in listOf(Triple(1,0f,-1f),Triple(2,0f,1f),Triple(4,-1f,0f),Triple(8,1f,0f)))arrow(canvas,cx+dx*size*.75f,cy+dy*size*.75f,size*.07f,dir,if(dpad and dir!=0)accent else 0xFFE4E3EA.toInt())}
            Pad.NES->{reset(fill);fill.shader=vertical(cy-arm,cy+arm,0xFFE4E4E7.toInt(),0xFF9C9CA1.toInt());val bezel=cross(cx,cy,arm+size*.06f,thick+size*.12f);canvas.drawPath(bezel,fill);reset(fill)}
            Pad.GB->{}
        }
        val body=cross(cx+tx,cy+ty,arm,thick)
        reset(fill);fill.color=0xFF0B0B0D.toInt();shadow(fill,6f,3.5f,if(style==Pad.MD)200 else 150);canvas.drawPath(body,fill);reset(fill)
        val top=if(style==Pad.MD)0xFF35353A.toInt() else 0xFF3A393F.toInt();val bottom=if(style==Pad.MD)0xFF141416.toInt() else 0xFF151418.toInt()
        fill.shader=vertical(cy-arm,cy+arm,top,bottom);canvas.drawPath(body,fill);reset(fill)
        // Pressed arm sinks: darker face on that side.
        for((dir,dx,dy) in listOf(Triple(1,0f,-1f),Triple(2,0f,1f),Triple(4,-1f,0f),Triple(8,1f,0f)))if(dpad and dir!=0){
            val r=if(dx==0f)RectF(cx+tx-thick/2,cy+ty+(if(dy<0)-arm else thick/2),cx+tx+thick/2,cy+ty+(if(dy<0)-thick/2 else arm)) else RectF(cx+tx+(if(dx<0)-arm else thick/2),cy+ty-thick/2,cx+tx+(if(dx<0)-thick/2 else arm),cy+ty+thick/2)
            fill.color=alpha(Color.BLACK,85);canvas.save();canvas.clipPath(body);canvas.drawRect(r,fill);canvas.restore()}
        line.strokeWidth=1.3f;line.shader=vertical(cy-arm,cy+arm,alpha(Color.WHITE,90),alpha(Color.WHITE,0));canvas.drawPath(body,line);line.shader=null
        if(style!=Pad.MD){reset(fill);fill.shader=RadialGradient(cx+tx,cy+ty-1,thick*.34f,0xFF0F0F12.toInt(),0xFF2C2B31.toInt(),Shader.TileMode.CLAMP);canvas.drawCircle(cx+tx,cy+ty,thick*.32f,fill);reset(fill)}
        for((dir,dx,dy) in listOf(Triple(1,0f,-1f),Triple(2,0f,1f),Triple(4,-1f,0f),Triple(8,1f,0f))){
            val ax=cx+tx+dx*arm*.66f;val ay=cy+ty+dy*arm*.66f;val s=thick*.3f
            if(dpad and dir!=0)arrow(canvas,ax,ay,s,dir,accent) else{arrow(canvas,ax,ay+1f,s,dir,alpha(Color.WHITE,38));arrow(canvas,ax,ay,s,dir,0xFF0C0C0E.toInt())}
        }
    }
    /** Convex round button: cast shadow, darker rim, top-left lit dome, specular cap and an engraved letter. */
    fun button(canvas:Canvas,cx:Float,cy:Float,r:Float,color:Int,label:String,pressed:Boolean,accent:Int,ink:Int?=null,symbol:String?=null,symbolColor:Int=0){
        val y=cy+(if(pressed)1.6f else 0f)
        if(!pressed){reset(fill);fill.color=dark(color,.5f);shadow(fill,r*.28f,r*.16f,150);canvas.drawCircle(cx,y,r,fill);reset(fill)}
        fill.color=dark(color,.38f);canvas.drawCircle(cx,y,r,fill)
        val face=if(pressed)dark(color,.14f) else color
        fill.shader=RadialGradient(cx-r*.32f,y-r*.38f,r*1.45f,intArrayOf(light(face,.32f),face,dark(face,.26f)),floatArrayOf(0f,.5f,1f),Shader.TileMode.CLAMP);canvas.drawCircle(cx,y,r*.9f,fill);reset(fill)
        fill.shader=vertical(y-r*.82f,y-r*.05f,alpha(Color.WHITE,if(pressed)40 else 95),alpha(Color.WHITE,0));canvas.drawOval(RectF(cx-r*.56f,y-r*.8f,cx+r*.56f,y-r*.08f),fill);reset(fill)
        if(pressed){line.strokeWidth=2.4f;line.color=alpha(accent,190);canvas.drawCircle(cx,y,r+3.2f,line)}
        if(symbol!=null)symbol(canvas,symbol,cx,y,r*.42f,symbolColor)
        else if(label.isNotEmpty())label(canvas,label,cx,y+r*.33f,r*.92f,ink?:dark(color,.42f),emboss=alpha(light(color,.6f),110))
    }
    /** PlayStation-style face symbols drawn as strokes rather than font glyphs. */
    fun symbol(canvas:Canvas,kind:String,cx:Float,cy:Float,s:Float,color:Int){
        line.shader=null;line.color=color;line.strokeWidth=max(2f,s*.24f);path.reset()
        when(kind){
            "triangle"->{path.moveTo(cx,cy-s*.95f);path.lineTo(cx+s*.95f,cy+s*.68f);path.lineTo(cx-s*.95f,cy+s*.68f);path.close();canvas.drawPath(path,line)}
            "circle"->canvas.drawCircle(cx,cy,s*.82f,line)
            "cross"->{canvas.drawLine(cx-s*.75f,cy-s*.75f,cx+s*.75f,cy+s*.75f,line);canvas.drawLine(cx+s*.75f,cy-s*.75f,cx-s*.75f,cy+s*.75f,line)}
            else->canvas.drawRect(cx-s*.72f,cy-s*.72f,cx+s*.72f,cy+s*.72f,line)
        }
    }
    /** Angled rubber pill (select/start) sitting in its own slot. */
    fun pill(canvas:Canvas,cx:Float,cy:Float,w:Float,h:Float,angle:Float,color:Int,slot:Int,pressed:Boolean,accent:Int){
        canvas.save();canvas.rotate(angle,cx,cy)
        well(canvas,RectF(cx-w/2-3.5f,cy-h/2-3.5f,cx+w/2+3.5f,cy+h/2+3.5f),h/2+3.5f,slot,.22f)
        val y=cy+(if(pressed)1.2f else 0f);val r=RectF(cx-w/2,y-h/2,cx+w/2,y+h/2)
        if(!pressed){reset(fill);fill.color=dark(color,.5f);shadow(fill,3f,1.6f,130);canvas.drawRoundRect(r,h/2,h/2,fill);reset(fill)}
        val face=if(pressed)dark(color,.15f) else color
        fill.shader=vertical(r.top,r.bottom,light(face,.35f),face,dark(face,.2f));canvas.drawRoundRect(r,h/2,h/2,fill);reset(fill)
        fill.shader=vertical(r.top,r.centerY(),alpha(Color.WHITE,110),alpha(Color.WHITE,0));canvas.drawRoundRect(RectF(r.left+h*.3f,r.top+1.2f,r.right-h*.3f,r.centerY()),h/4,h/4,fill);reset(fill)
        if(pressed){line.strokeWidth=2f;line.color=alpha(accent,190);canvas.drawRoundRect(RectF(r.left-3,r.top-3,r.right+3,r.bottom+3),h/2+3,h/2+3,line)}
        canvas.restore()
    }
    /** Shoulder tab hugging the top edge of the pad. */
    fun shoulder(canvas:Canvas,r:RectF,color:Int,text:String,pressed:Boolean,accent:Int,outline:Int){
        val y=if(pressed)1.5f else 0f;val box=RectF(r.left,r.top+y,r.right,r.bottom+y);val rad=r.height()*.48f
        if(!pressed){reset(fill);fill.color=dark(color,.5f);shadow(fill,6f,3f,120);canvas.drawRoundRect(box,rad,rad,fill);reset(fill)}
        fill.color=outline;canvas.drawRoundRect(box,rad,rad,fill)
        val inner=RectF(box.left+1.6f,box.top+1.6f,box.right-1.6f,box.bottom-1.6f);val face=if(pressed)dark(color,.14f) else color
        fill.shader=vertical(inner.top,inner.bottom,light(face,.4f),face,dark(face,.12f));canvas.drawRoundRect(inner,rad-1.6f,rad-1.6f,fill);reset(fill)
        if(pressed){line.strokeWidth=2f;line.color=alpha(accent,200);canvas.drawRoundRect(RectF(box.left-3,box.top-3,box.right+3,box.bottom+3),rad+3,rad+3,line)}
        label(canvas,text,box.centerX(),box.centerY()+box.height()*.2f,box.height()*.56f,dark(color,.36f),emboss=alpha(Color.WHITE,120))
    }
    /** Analog stick: dark pit, concave rubber cap with a grip ring, travelling with the input. */
    fun stick(canvas:Canvas,cx:Float,cy:Float,r:Float,dx:Float,dy:Float,shell:Int,accent:Int){
        circleWell(canvas,cx,cy,r+6f,shell,.12f)
        reset(fill);fill.shader=RadialGradient(cx,cy,r,0xFF0E0E11.toInt(),0xFF2B2A30.toInt(),Shader.TileMode.CLAMP);canvas.drawCircle(cx,cy,r,fill);reset(fill)
        val x=cx+dx*r*.42f;val y=cy+dy*r*.42f;val cap=r*.74f;val active=dx!=0f||dy!=0f
        fill.color=Color.BLACK;shadow(fill,7f,4f,170);canvas.drawCircle(x,y,cap,fill);reset(fill)
        fill.shader=RadialGradient(x-cap*.3f,y-cap*.35f,cap*1.4f,0xFF5A5960.toInt(),0xFF1D1D21.toInt(),Shader.TileMode.CLAMP);canvas.drawCircle(x,y,cap,fill);reset(fill)
        fill.shader=RadialGradient(x+cap*.15f,y+cap*.2f,cap*.7f,0xFF34333A.toInt(),0xFF18181B.toInt(),Shader.TileMode.CLAMP);canvas.drawCircle(x,y,cap*.68f,fill);reset(fill)
        fill.color=alpha(Color.WHITE,40);for(i in 0 until 16){val a=i*PI.toFloat()/8;canvas.drawCircle(x+cos(a)*cap*.84f,y+sin(a)*cap*.84f,1.3f,fill)}
        if(active){line.strokeWidth=2.4f;line.color=alpha(accent,190);canvas.drawCircle(x,y,cap+3f,line)}
    }
    /** Ball-top arcade lever seen from above, leaning with the direction pressed. */
    fun lever(canvas:Canvas,cx:Float,cy:Float,r:Float,dpad:Int,ball:Int,accent:Int){
        circleWell(canvas,cx,cy,r,0xFF2A2733.toInt(),.4f)
        reset(fill);fill.shader=RadialGradient(cx,cy,r*.5f,0xFF07070A.toInt(),0xFF26232D.toInt(),Shader.TileMode.CLAMP);canvas.drawCircle(cx,cy,r*.48f,fill);reset(fill)
        val lean=r*.32f;val x=cx+(if(dpad and 8!=0)lean else 0f)-(if(dpad and 4!=0)lean else 0f);val y=cy+(if(dpad and 2!=0)lean else 0f)-(if(dpad and 1!=0)lean else 0f)
        line.strokeWidth=r*.16f;line.shader=LinearGradient(cx-r*.1f,0f,cx+r*.1f,0f,0xFFE2E1E8.toInt(),0xFF77767E.toInt(),Shader.TileMode.CLAMP);canvas.drawLine(cx,cy,x,y-r*.1f,line);line.shader=null
        val ballR=r*.44f;fill.color=Color.BLACK;shadow(fill,8f,5f,170);canvas.drawCircle(x,y-r*.12f,ballR,fill);reset(fill)
        fill.shader=RadialGradient(x-ballR*.35f,y-r*.12f-ballR*.4f,ballR*1.5f,intArrayOf(light(ball,.55f),ball,dark(ball,.45f)),floatArrayOf(0f,.45f,1f),Shader.TileMode.CLAMP);canvas.drawCircle(x,y-r*.12f,ballR,fill);reset(fill)
        fill.color=alpha(Color.WHITE,120);canvas.drawOval(RectF(x-ballR*.5f,y-r*.12f-ballR*.78f,x+ballR*.1f,y-r*.12f-ballR*.4f),fill)
        if(dpad!=0){line.strokeWidth=2.4f;line.color=alpha(accent,190);canvas.drawCircle(x,y-r*.12f,ballR+3f,line)}
    }
    /** Arcade push button: black bezel ring around a glossy coloured plunger. */
    fun arcadeButton(canvas:Canvas,cx:Float,cy:Float,r:Float,color:Int,text:String,pressed:Boolean,accent:Int,above:Boolean=false){
        reset(fill);fill.color=Color.BLACK;shadow(fill,6f,3f,160);canvas.drawCircle(cx,cy,r,fill);reset(fill)
        fill.shader=vertical(cy-r,cy+r,0xFF3C3A44.toInt(),0xFF0E0D12.toInt());canvas.drawCircle(cx,cy,r,fill);reset(fill)
        button(canvas,cx,cy,r*.8f,color,"",pressed,accent)
        label(canvas,text,cx,if(above)cy-r-5f else cy+r+12f,10f,0xFFA9A3B6.toInt(),letter=.1f)
    }
    /** Pad-shaped plate behind a row of buttons (Mega Drive kidney pad, SNES rails). */
    fun rail(canvas:Canvas,x0:Float,y0:Float,x1:Float,y1:Float,thick:Float,color:Int,depth:Float){
        val cx=(x0+x1)/2;val cy=(y0+y1)/2;val len=hypot(x1-x0,y1-y0)+thick;val a=Math.toDegrees(atan2(y1-y0,x1-x0).toDouble()).toFloat()
        canvas.save();canvas.rotate(a,cx,cy);well(canvas,RectF(cx-len/2,cy-thick/2,cx+len/2,cy+thick/2),thick/2,color,depth);canvas.restore()
    }
    /** Small round system key (menu / pause). */
    /** Round key with a house glyph: "back to the main menu". */
    fun homeKey(canvas:Canvas,cx:Float,cy:Float,r:Float,color:Int,pressed:Boolean,accent:Int,glyph:Int){
        button(canvas,cx,cy,r,color,"",pressed,accent)
        val y=cy+(if(pressed)1.6f else 0f);val s=r*.42f
        val roof=Path().apply{moveTo(cx,y-s);lineTo(cx+s*1.05f,y-s*.05f);lineTo(cx-s*1.05f,y-s*.05f);close()}
        reset(fill);fill.color=glyph;canvas.drawPath(roof,fill)
        canvas.drawRoundRect(RectF(cx-s*.72f,y-s*.12f,cx+s*.72f,y+s*.8f),2f,2f,fill)
        fill.color=color;canvas.drawRoundRect(RectF(cx-s*.2f,y+s*.25f,cx+s*.2f,y+s*.8f),1.5f,1.5f,fill)
    }
    /** TV-remote ring: one convex disc with four arrows; the pressed side lights up. */
    fun remoteRing(canvas:Canvas,cx:Float,cy:Float,r:Float,dpad:Int,base:Int,accent:Int){
        circleWell(canvas,cx,cy,r+6f,dark(base,.3f),.18f)
        reset(fill);fill.color=dark(base,.5f);shadow(fill,10f,4f,150);canvas.drawCircle(cx,cy,r,fill);reset(fill)
        fill.shader=RadialGradient(cx-r*.3f,cy-r*.35f,r*1.6f,intArrayOf(light(base,.2f),base,dark(base,.25f)),floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP);canvas.drawCircle(cx,cy,r*.96f,fill);reset(fill)
        for((dir,dx,dy) in listOf(Triple(1,0f,-1f),Triple(2,0f,1f),Triple(4,-1f,0f),Triple(8,1f,0f))){
            val on=dpad and dir!=0
            if(on){fill.color=alpha(accent,60);canvas.drawCircle(cx+dx*r*.68f,cy+dy*r*.68f,r*.26f,fill)}
            arrow(canvas,cx+dx*r*.7f,cy+dy*r*.7f,r*.1f,dir,if(on)accent else 0xFFE4E3EA.toInt())
        }
    }
    fun key(canvas:Canvas,cx:Float,cy:Float,r:Float,color:Int,pressed:Boolean,accent:Int,glyph:Int){
        button(canvas,cx,cy,r,color,"",pressed,accent)
        reset(fill);fill.color=glyph;val y=cy+(if(pressed)1.6f else 0f);canvas.drawRoundRect(RectF(cx-r*.34f,y-r*.38f,cx-r*.1f,y+r*.38f),2f,2f,fill);canvas.drawRoundRect(RectF(cx+r*.1f,y-r*.38f,cx+r*.34f,y+r*.38f),2f,2f,fill)
    }
}
