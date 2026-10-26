package org.retrotv.pad

import android.graphics.*
import kotlin.math.*
import java.util.Random

/** Original, resolution-independent materials for the four reference pads.
 * Only draws: all geometry comes from ControllerView's existing touch controls. */
internal class RealisticPadArt {
    private val art = PadArt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    // A 16 KiB procedural material tile, never a rasterized controller or button.
    // One shader fill avoids thousands of point primitives on every touch redraw.
    private val grain by lazy {
        val random = Random(13)
        val pixels = IntArray(64 * 64) {
            a(if (random.nextBoolean()) Color.WHITE else Color.BLACK, random.nextInt(13))
        }
        BitmapShader(Bitmap.createBitmap(pixels, 64, 64, Bitmap.Config.ARGB_8888),
            Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            setLocalMatrix(Matrix().apply { setScale(.5f, .5f) })
        }
    }
    private fun c(value: String) = Color.parseColor(value)
    private fun a(color: Int, alpha: Int) = art.alpha(color, alpha)
    private fun vertical(top: Float, bottom: Float, vararg colors: Int) =
        LinearGradient(0f, top, 0f, bottom, colors, null, Shader.TileMode.CLAMP)
    private fun inset(r: RectF, d: Float) = RectF(r).apply { inset(d, d) }
    private fun round(canvas: Canvas, r: RectF, radius: Float, vararg colors: Int) {
        paint.alpha = 255
        paint.shader = vertical(r.top, r.bottom, *colors)
        canvas.drawRoundRect(r, radius, radius, paint)
        paint.shader = null
    }
    private fun rim(canvas: Canvas, r: RectF, radius: Float, width: Float, dark: Boolean = false) {
        stroke.alpha = 255
        stroke.strokeWidth = width
        stroke.shader = vertical(r.top, r.bottom,
            a(Color.WHITE, if (dark) 145 else 225), a(Color.WHITE, 16), a(Color.BLACK, 175))
        canvas.drawRoundRect(r, radius, radius, stroke)
        stroke.shader = null
    }
    private fun shadow(canvas: Canvas, r: RectF, radius: Float, depth: Float, opacity: Int = 140) {
        paint.color = c("#111216")
        paint.setShadowLayer(depth * 2, 0f, depth, a(Color.BLACK, opacity))
        canvas.drawRoundRect(r, radius, radius, paint)
        paint.clearShadowLayer()
    }
    /** Fixed procedural grain: one draw call, no frame-time noise generation or timers. */
    private fun texture(canvas: Canvas, w: Float, h: Float) {
        paint.alpha = 255
        paint.shader = grain
        paint.alpha = 100
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        paint.alpha = 255
    }
    fun background(canvas: Canvas, w: Float, h: Float, console: Console, controls: List<PadControl>) {
        val dark = console == Console.MD
        paint.shader = LinearGradient(0f, 0f, w * .6f, h,
            if (dark) intArrayOf(c("#24272E"), c("#14171C"), c("#0D1015"))
            else intArrayOf(c("#E1E1E9"), c("#C9CAD5"), c("#B8BBC8")),
            floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint); paint.shader = null
        texture(canvas, w, h)
        fun control(id: String) = controls.firstOrNull { it.id == id }
        fun center(id: String) = control(id)?.box?.let { PointF(it.x + it.w / 2, it.y + it.h / 2) }
        fun radius(id: String) = control(id)?.let { faceRadius(it, console) } ?: 0f
        fun row(first: String, last: String) {
            val p = center(first) ?: return; val q = center(last) ?: return
            val r = max(radius(first), radius(last)) + 8f
            val cx = (p.x + q.x) / 2; val cy = (p.y + q.y) / 2
            val length = hypot(q.x - p.x, q.y - p.y) + r * 2
            canvas.save(); canvas.rotate(Math.toDegrees(atan2(q.y - p.y, q.x - p.x).toDouble()).toFloat(), cx, cy)
            val box = RectF(cx - length / 2, cy - r, cx + length / 2, cy + r)
            if (dark) {
                shadow(canvas, box, r, 4f)
                round(canvas, box, r, c("#0A0C0F"), c("#030405"))
                rim(canvas, inset(box, 1f), r - 1f, 1.5f, true)
                round(canvas, inset(box, 4f), r - 4f, c("#35383D"), c("#13151A"), c("#22252B"))
                rim(canvas, inset(box, 4f), r - 4f, .8f, true)
            } else {
                round(canvas, box, r, c("#F0F0F5"), c("#B5B6C2"))
                rim(canvas, inset(box, .6f), r, 1f)
            }
            canvas.restore()
        }
        when (console) {
            Console.NES -> {
                val outer = RectF(5f, 58f, w - 5f, h - 5f)
                shadow(canvas, outer, 22f, 6f)
                round(canvas, outer, 22f, c("#ECECEA"), c("#C7C7C6"), c("#A6A7AA"))
                rim(canvas, inset(outer, 2f), 20f, 2f)
                val plate = RectF(16f, 72f, w - 16f, h - 19f)
                round(canvas, plate, 12f, c("#0C0E11"), c("#55565A"))
                round(canvas, inset(plate, 2f), 10f, c("#24262A"), c("#17191C"))
                rim(canvas, inset(plate, 2f), 10f, .9f, true)
                val select = control("select")?.box; val start = control("start")?.box
                if (select != null && start != null) {
                    val left = select.x - 7f; val right = start.x + start.w + 7f
                    val sy = select.y + select.h / 2
                    // Keep the panel aligned with the editable system group, including its pause key.
                    val barsTop = plate.top + 18f
                    val barsBottom = sy - 56f
                    if (barsBottom > barsTop + 24f) for (i in 0..2) {
                        val y = barsTop + i * (barsBottom - barsTop) / 3f
                        val bar = RectF(left, y, right, y + min(15f, (barsBottom - barsTop) / 4f))
                        round(canvas, bar, 4f, c("#77797B"), c("#5B5D60")); rim(canvas, bar, 4f, .7f)
                    }
                    val legend = RectF(left, sy - 57f, right, sy - 31f)
                    round(canvas, legend, 4f, c("#7A7C7E"), c("#626467")); rim(canvas, legend, 4f, .7f)
                    listOf("select", "start").forEach { id -> center(id)?.let {
                        art.label(canvas, id.uppercase(), it.x, sy - 39f, 11f, c("#BA1922"), letter = .1f)
                    } }
                    val slot = RectF(left, sy - 25f, right, sy + 24f)
                    round(canvas, slot, 5f, c("#F0F0EC"), c("#9D9FA2")); rim(canvas, inset(slot, 1f), 4f, 1.5f)
                    round(canvas, inset(slot, 3f), 3f, c("#B7B8B8"), c("#D2D3D1"))
                }
                listOf("b", "a").forEach { id -> center(id)?.let { p ->
                    val r = radius(id) + 9f; val box = RectF(p.x-r,p.y-r,p.x+r,p.y+r)
                    shadow(canvas, box, 5f, 2f)
                    round(canvas, box, 5f, c("#EBEBE7"), c("#B2B2B0")); rim(canvas, inset(box, .8f), 4f, 1.2f)
                } }
            }
            Console.SNES -> {
                val x = center("x"); val a = center("a")
                if (x != null && a != null) socket(canvas, x.x, a.y, a.x-x.x+radius("a")+12f, false)
                row("y", "x"); row("b", "a")
            }
            Console.MD -> { row("a", "c"); row("x", "z") }
            Console.PS1 -> {
                val t = center("triangle"); val o = center("circle")
                if (t != null && o != null) socket(canvas, t.x, o.y, o.x-t.x+radius("circle")+12f, false)
            }
            else -> Unit
        }
    }
    /** Sunken mounting disc with a shadowed upper lip and illuminated lower lip. */
    private fun socket(canvas: Canvas, x: Float, y: Float, r: Float, dark: Boolean) {
        val box = RectF(x-r,y-r,x+r,y+r)
        round(canvas, box, r, if(dark)c("#050608") else c("#737680"), if(dark)c("#7C8089") else c("#F5F5FC"))
        round(canvas, inset(box, 2f), r-2f, if(dark)c("#191C22") else c("#9C9FAA"), if(dark)c("#090B0F") else c("#C5C7D2"))
        stroke.strokeWidth=.7f; stroke.color=a(Color.WHITE,55)
        canvas.drawCircle(x,y,r-3f,stroke)
    }
    private fun faceRadius(control: PadControl, console: Console) = control.box.w/2 - when(console) {
        Console.SNES, Console.PS1 -> 8f
        Console.NES -> 6f
        else -> 4f
    }
    private fun bevelCircle(canvas: Canvas, x: Float, y: Float, r: Float) {
        val box=RectF(x-r,y-r,x+r,y+r)
        round(canvas,box,r,c("#101217"),c("#777B84"),c("#111318"))
        rim(canvas,inset(box,1f),r-1f,1f,true)
        round(canvas,inset(box,3f),r-3f,c("#030407"),c("#555861"))
    }
    private fun pressOffset(r:Float,pressed:Boolean)=if(pressed)(r*.13f).coerceIn(2.5f,4.5f) else 0f
    private fun button(canvas: Canvas,x:Float,y:Float,r:Float,color:Int,pressed:Boolean) {
        bevelCircle(canvas,x,y,r+3.5f)
        // The socket stays fixed. The cap drops and loses height inside it.
        val cy=y+pressOffset(r,pressed)
        val cap=if(pressed)r*.93f else r
        if(!pressed)shadow(canvas,RectF(x-r,cy-r,x+r,cy+r),r,3.5f,180)
        round(canvas,RectF(x-cap,cy-cap,x+cap,cy+cap),cap,art.light(color,.18f),art.dark(color,.6f))
        val face=if(pressed)art.dark(color,.22f) else color
        paint.alpha=255;paint.shader=RadialGradient(x-cap*.38f,cy-cap*.55f,cap*1.65f,
            intArrayOf(art.light(face,.47f),face,art.dark(face,.5f)),floatArrayOf(0f,.4f,1f),Shader.TileMode.CLAMP)
        canvas.drawCircle(x,cy,cap*.9f,paint);paint.shader=null
        paint.shader=vertical(cy-cap*.86f,cy+cap*.12f,a(Color.WHITE,if(pressed)25 else 65),a(Color.WHITE,0))
        canvas.drawOval(RectF(x-cap*.67f,cy-cap*.86f,x+cap*.39f,cy+cap*.03f),paint);paint.shader=null
        stroke.alpha=255;stroke.strokeWidth=1f;stroke.shader=vertical(cy-cap,cy+cap,a(Color.WHITE,180),a(Color.WHITE,0),a(Color.BLACK,100))
        canvas.drawCircle(x,cy,cap*.9f,stroke);stroke.shader=null
        if(pressed){
            // A dark upper inner lip reveals the depth left above the lowered cap.
            stroke.color=a(Color.BLACK,170);stroke.strokeWidth=2f
            canvas.drawArc(RectF(x-r,y-r,x+r,y+r),205f,130f,false,stroke)
        }
    }
    private fun cross(x:Float,y:Float,arm:Float,thick:Float):Path {
        val h=Path().apply{addRoundRect(RectF(x-arm,y-thick/2,x+arm,y+thick/2),4f,4f,Path.Direction.CW)}
        val v=Path().apply{addRoundRect(RectF(x-thick/2,y-arm,x+thick/2,y+arm),4f,4f,Path.Direction.CW)}
        h.op(v,Path.Op.UNION);return h
    }
    private fun dpad(canvas:Canvas,x:Float,y:Float,size:Float,bits:Int,console:Console) {
        val md=console==Console.MD;val arm=size/2;val thick=size*.35f
        if(console!=Console.NES)socket(canvas,x,y,size*(if(md).69f else .64f),md)
        if(md){
            bevelCircle(canvas,x,y,size*.65f)
            socket(canvas,x,y,size*.59f,true)
            for(i in 0..3){val t=PI.toFloat()*(.25f+i*.5f);paint.color=c("#D5DAE4");canvas.drawCircle(x+cos(t)*size*.63f,y+sin(t)*size*.63f,1.3f,paint)}
        }
        val nes=console==Console.NES
        val bezel=cross(x,y,arm+if(nes)4f else 2f,thick+if(nes)8f else 4f)
        paint.alpha=255;paint.shader=vertical(y-arm,y+arm,if(nes)c("#C8CBD1") else c("#7D8189"),c("#292C32"),if(nes)c("#969AA3") else c("#15181E"));canvas.drawPath(bezel,paint);paint.shader=null
        val dx=((if(bits and 8!=0)1 else 0)-(if(bits and 4!=0)1 else 0))*2.2f
        val dy=((if(bits and 2!=0)1 else 0)-(if(bits and 1!=0)1 else 0))*2.2f
        val body=cross(x+dx,y+dy+1f,arm,thick)
        paint.color=c("#08090C");paint.setShadowLayer(4f,0f,3f,a(Color.BLACK,190));canvas.drawPath(body,paint);paint.clearShadowLayer()
        paint.shader=vertical(y-arm,y+arm,c("#484B52"),c("#24272C"),c("#111318"));canvas.drawPath(body,paint);paint.shader=null
        paint.shader=grain;paint.alpha=170;canvas.drawPath(body,paint);paint.shader=null;paint.alpha=255
        stroke.alpha=255;stroke.strokeWidth=1.2f;stroke.shader=vertical(y-arm,y+arm,c("#90949C"),c("#373A41"),c("#080A0D"));canvas.drawPath(body,stroke);stroke.shader=null
        val inner=cross(x+dx,y+dy,arm-3f,thick-6f)
        stroke.color=a(Color.BLACK,120);stroke.strokeWidth=.8f;canvas.drawPath(inner,stroke)
        paint.shader=RadialGradient(x+dx-thick*.16f,y+dy-thick*.18f,thick*.38f,c("#111318"),c("#34373D"),Shader.TileMode.CLAMP)
        canvas.drawCircle(x+dx,y+dy,thick*.32f,paint);paint.shader=null
        for(i in 0..3){
            val bit=intArrayOf(1,8,2,4)[i];val angle=i*90f
            canvas.save();canvas.rotate(angle,x,y)
            val ay=y-arm*.69f;val s=thick*.26f
            val arrow=Path().apply{moveTo(x,ay-s*.6f);lineTo(x+s*.62f,ay+s*.4f);lineTo(x-s*.62f,ay+s*.4f);close()}
            paint.color=a(Color.WHITE,48);canvas.save();canvas.translate(0f,1f);canvas.drawPath(arrow,paint);canvas.restore()
            paint.color=if(bits and bit!=0)c("#727984") else c("#0C0E12");canvas.drawPath(arrow,paint)
            if(md){val outer=Path().apply{moveTo(x,y-size*.77f);lineTo(x+4,y-size*.71f);lineTo(x-4,y-size*.71f);close()};paint.color=c("#CCD2DD");canvas.drawPath(outer,paint)}
            canvas.restore()
        }
    }
    private fun keycap(canvas:Canvas,r:RectF,pressed:Boolean,color:Int,radius:Float) {
        round(canvas,inset(r,-3f),radius+3f,c("#666A74"),c("#E2E4EE"))
        round(canvas,inset(r,-1.5f),radius+1.5f,c("#06080B"),c("#393D45"))
        val top=RectF(r).apply{if(pressed){inset(.7f,.7f);offset(0f,pressOffset(r.height()/2,true))}}
        if(!pressed)shadow(canvas,top,radius,3f)
        val face=if(pressed)art.dark(color,.18f) else color
        round(canvas,top,radius,art.light(face,.42f),face,art.dark(face,.38f))
        rim(canvas,inset(top,1f),max(1f,radius-1f),1.1f)
        paint.shader=vertical(top.top,top.centerY(),a(Color.WHITE,75),a(Color.WHITE,0))
        canvas.drawRoundRect(inset(top,3f),max(1f,radius-3f),max(1f,radius-3f),paint);paint.shader=null
    }
    private fun stick(canvas:Canvas,x:Float,y:Float,r:Float,dx:Float,dy:Float) {
        socket(canvas,x,y,r+7f,false);bevelCircle(canvas,x,y,r+3f)
        round(canvas,RectF(x-r,y-r,x+r,y+r),r,c("#11141A"),c("#030508"))
        val cx=x+dx*r*.42f;val cy=y+dy*r*.42f;val cap=r*.74f
        shadow(canvas,RectF(cx-cap,cy-cap,cx+cap,cy+cap),cap,4f)
        round(canvas,RectF(cx-cap,cy-cap,cx+cap,cy+cap),cap,c("#6B707A"),c("#242830"),c("#13161D"))
        rim(canvas,RectF(cx-cap+1,cy-cap+1,cx+cap-1,cy+cap-1),cap-1,.8f,true)
        socket(canvas,cx,cy,cap*.66f,true)
        for(i in 0 until 24){val t=i*PI.toFloat()/12;val px=cx+cos(t)*cap*.83f;val py=cy+sin(t)*cap*.83f
            paint.color=a(Color.WHITE,65);canvas.drawCircle(px,py+.7f,1f,paint)
            paint.color=c("#15181F");canvas.drawCircle(px,py,1.1f,paint)}
    }
    fun control(canvas:Canvas,control:PadControl,state:PadState,console:Console,pressed:Boolean) {
        val b=control.box;val x=b.x+b.w/2;val y=b.y+b.h/2;val dark=console==Console.MD
        when {
            control.kind=="dpad" -> dpad(canvas,x,y,b.w*.74f,state.dpad,console)
            control.kind=="leftStick" -> stick(canvas,x,y,b.w/2-6f,state.lx,state.ly)
            control.kind=="rightStick" -> stick(canvas,x,y,b.w/2-6f,state.rx,state.ry)
            PadLayout.groupOf(control)=="shoulder" -> {
                val r=RectF(b.x+2f,b.y+5f,b.x+b.w-2f,b.y+b.h-8f)
                keycap(canvas,r,pressed,if(console==Console.SNES)c("#ABACC5") else c("#B2B6C5"),r.height()/2)
                art.label(canvas,control.label,x,r.centerY()+r.height()*.22f+pressOffset(r.height()/2,pressed),r.height()*.61f,c("#555A68"),emboss=a(Color.WHITE,130))
            }
            control.id=="select" || control.id=="start" -> {
                val k=b.w/84f;val w=if(console==Console.NES)46f*k else 58f*k
                val h=15f*k;val cy=y-if(console==Console.NES)0f else 6f
                canvas.save();canvas.rotate(when(console){Console.SNES -> -26f;Console.MD -> -18f;else -> 0f},x,cy)
                keycap(canvas,RectF(x-w/2,cy-h/2,x+w/2,cy+h/2),pressed,
                    if(console==Console.SNES)c("#AAABC6") else if(dark)c("#333943") else c("#292D34"),h/2)
                canvas.restore()
                if(console!=Console.NES)art.label(canvas,control.label,x,y+20f*k,10f*k,if(dark)c("#BDC4D0") else c("#545B6E"),italic=console==Console.SNES,letter=.09f)
            }
            control.id in setOf("home","pause","back") -> {
                val r=min(b.w,b.h)/2-9f
                button(canvas,x,y,r,if(dark)c("#343A44") else c("#BDC1CF"),pressed)
                val cy=y+pressOffset(r,pressed);val s=r*.42f
                paint.color=if(dark)c("#D5DFEF") else c("#515968")
                when(control.id){
                    "pause" -> {canvas.drawRoundRect(x-s,cy-s,x-s*.3f,cy+s,2f,2f,paint);canvas.drawRoundRect(x+s*.3f,cy-s,x+s,cy+s,2f,2f,paint)}
                    "home" -> {val house=Path().apply{moveTo(x-s*1.1f,cy);lineTo(x,cy-s);lineTo(x+s*1.1f,cy);lineTo(x+s*.7f,cy);lineTo(x+s*.7f,cy+s*.85f);lineTo(x+s*.2f,cy+s*.85f);lineTo(x+s*.2f,cy+s*.25f);lineTo(x-s*.2f,cy+s*.25f);lineTo(x-s*.2f,cy+s*.85f);lineTo(x-s*.7f,cy+s*.85f);lineTo(x-s*.7f,cy);close()};canvas.drawPath(house,paint)}
                    else -> {stroke.color=paint.color;stroke.strokeWidth=2.2f;val arrow=Path().apply{moveTo(x+s*.8f,cy-s*.5f);quadTo(x+s*1.2f,cy+s*.5f,x-s*.8f,cy+s*.15f);moveTo(x-s*.35f,cy-s*.3f);lineTo(x-s*.8f,cy+s*.15f);lineTo(x-s*.3f,cy+s*.6f)};canvas.drawPath(arrow,stroke)}
                }
            }
            else -> {
                val r=faceRadius(control,console)
                val color=when(console){Console.PS1,Console.MD -> c("#20252E");else -> control.color}
                button(canvas,x,y,r,color,pressed)
                val cy=y+pressOffset(r,pressed)
                if(console==Console.PS1)art.symbol(canvas,control.id,x,cy,r*.43f,when(control.id){"triangle"->c("#6DE1C8");"circle"->c("#F37D97");"cross"->c("#7FAAF5");else->c("#E998DA")})
                else if(console==Console.NES)art.label(canvas,control.label,x+r*.7f,y+r+27f,15f,c("#D12230"))
                else art.label(canvas,control.label,x,cy+r*.34f,r*.95f,if(dark)c("#C0CAD9") else a(Color.WHITE,220),emboss=a(Color.BLACK,70),letter=.02f)
            }
        }
    }
    fun chip(canvas:Canvas,b:Box,dark:Boolean,live:Boolean) {
        val box=RectF(b.x,b.y,b.x+b.w,b.y+b.h)
        round(canvas,box,b.h/2,if(dark)c("#030509") else c("#868B98"),if(dark)c("#6A707B") else c("#F5F5FC"))
        round(canvas,inset(box,2f),b.h/2-2,if(dark)c("#090C11") else c("#A0A5B0"),if(dark)c("#151A22") else c("#C1C5D0"))
        val x=b.x+20;val y=b.y+b.h/2;val color=if(live)c("#64EA93") else c("#FFB52B")
        paint.shader=RadialGradient(x,y,10f,a(color,75),a(color,0),Shader.TileMode.CLAMP);canvas.drawCircle(x,y,10f,paint);paint.shader=null
        paint.color=c("#6D5221");canvas.drawCircle(x,y,5f,paint)
        paint.shader=RadialGradient(x-1.4f,y-1.8f,6f,intArrayOf(c("#FFFFE1"),color,art.dark(color,.35f)),floatArrayOf(0f,.4f,1f),Shader.TileMode.CLAMP)
        canvas.drawCircle(x,y,4.3f,paint);paint.shader=null
    }
    companion object {
        fun supports(console:Console)=console in setOf(Console.NES,Console.SNES,Console.PS1,Console.MD)
    }
}
