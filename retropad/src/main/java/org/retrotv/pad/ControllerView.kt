package org.retrotv.pad
import android.content.Context
import android.graphics.*
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/**
 * Full-screen landscape pad. Sizes are in dp so a button has the same physical size on every phone; touch boxes are
 * larger than the drawn parts and a touch between two parts goes to the nearest one. The only chrome is a small status
 * chip that opens the layout/connection sheet. No screenshots or proprietary console logos are bundled.
 */
class ControllerView(context:Context,private val callback:Callback):View(context){
    interface Callback{fun send(state:PadState);fun connect();fun select(console:Console);fun auto();fun options()}
    var console=Console.SNES
    var automatic=true
    var connectedName=""
    var connectionStatus="Pronto para conectar à TV"
    var metadataTitle=""
    var metadataLive=false
    var playMode="library"
    var customSkin:DeltaSkin?=null
    private val ink=Paint(Paint.ANTI_ALIAS_FLAG)
    private val art=PadArt()
    private val realisticArt by lazy{RealisticPadArt()}
    private val input=PadInput()
    private var dirty=true
    private var scale=1f
    private var logicalW=860f
    private var logicalH=400f
    private var sheet=false
    private val ui=mutableListOf<Pair<Box,()->Unit>>()
    private var uiDown:Pair<Int,Pair<Box,()->Unit>>?=null
    private var imported:DeltaSkin.Representation?=null
    private var importedRect=RectF()
    private var lastState=PadState()
    // Customise mode: sizes per console and group, kept in the app preferences.
    var editing=false
        private set
    private var editGroup="face"
    private var pinchFrom=0f
    private var pinchScale=1f
    private var dragFrom:Pair<Float,Float>?=null
    private var dragStart=0f to 0f
    private val liveOffset=mutableMapOf<String,Pair<Float,Float>>()
    // Offsets are stored as fractions of the screen so a layout survives a different phone size.
    private fun offsetOf(group:String)=liveOffset[group]?:(prefs.getFloat("${scaleKey(group)}.x",0f)*logicalW to prefs.getFloat("${scaleKey(group)}.y",0f)*logicalH)
    private fun saveOffset(group:String,offset:Pair<Float,Float>){prefs.edit().putFloat("${scaleKey(group)}.x",offset.first/logicalW).putFloat("${scaleKey(group)}.y",offset.second/logicalH).apply()}
    private val prefs by lazy{context.getSharedPreferences("pad",Context.MODE_PRIVATE)}
    var realisticAppearance=prefs.getBoolean("appearance.realistic",false)
        private set
    fun setRealisticAppearance(enabled:Boolean){
        if(realisticAppearance==enabled)return
        release();realisticAppearance=enabled
        prefs.edit().putBoolean("appearance.realistic",enabled).apply()
        invalidate()
    }
    private fun scaleKey(group:String)="scale.${console.name}.$group"
    private fun scaleOf(group:String)=PadLayout.clamp(prefs.getFloat(scaleKey(group),1f))
    private fun setScale(group:String,value:Float){prefs.edit().putFloat(scaleKey(group),PadLayout.clamp(value)).apply();dirty=true;invalidate()}
    fun startEditing(){release();sheet=false;editing=true;dirty=true;invalidate()}
    fun finishEditing(){endDrag();editing=false;dirty=true;invalidate()}
    init{isFocusable=true;contentDescription="Controle Bluetooth RetroTV em tela cheia. Direcional à esquerda, botões à direita e o seletor de console no topo."}
    fun relayout(){release();dirty=true;invalidate()}
    fun release(){input.clear();lastState=PadState();callback.send(lastState);invalidate()}
    internal fun stateForTest()=input.state()
    internal fun controlsForTest():List<PadControl>{if(dirty)build();return input.controls}
    internal fun scaleForTest()=scale
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh);relayout()}
    private fun c(hex:String)=Color.parseColor(hex)
    private val accent get()=c("#BA9BEE")
    private fun text(canvas:Canvas,x:Float,y:Float,value:String,size:Float,color:Int,bold:Boolean=false,limit:Float=1000f,center:Boolean=false){
        ink.shader=null;ink.style=Paint.Style.FILL;ink.color=color;ink.textSize=size;ink.letterSpacing=0f;ink.typeface=Typeface.create("sans-serif",if(bold)Typeface.BOLD else Typeface.NORMAL)
        var s=value.replace('\n',' ');if(ink.measureText(s)>limit){while(s.isNotEmpty()&&ink.measureText(s+"…")>limit)s=s.dropLast(1);s+="…"}
        canvas.drawText(s,if(center)x-ink.measureText(s)/2 else x,y,ink)
    }
    private fun pressed(control:PadControl,state:PadState)=when(control.kind){"dpad"->state.dpad!=0;"leftStick"->state.lx!=0f||state.ly!=0f;"rightStick"->state.rx!=0f||state.ry!=0f;else->control.mask!=0&&(state.buttons and control.mask)==control.mask}
    private val chip get()=Box(logicalW/2-92f,10f,184f,38f)

    private fun build(){
        if(width==0||height==0)return
        scale=resources.displayMetrics.density;logicalW=width/scale;logicalH=height/scale
        ui.clear()
        if(editing)buildEditor() else if(sheet)buildSheet() else ui+=chip to {openSheet()}
        imported=customSkin?.takeIf{it.console==console}?.views?.get("landscape")
        val result=mutableListOf<PadControl>()
        val rep=imported
        if(rep!=null){
            val fit=minOf(logicalW/rep.width,logicalH/rep.height)
            val w=rep.width*fit;val h=rep.height*fit;val x=(logicalW-w)/2;val y=(logicalH-h)/2
            importedRect=RectF(x,y,x+w,y+h)
            result+=rep.controls.map{it.copy(box=Box(x+it.box.x*fit,y+it.box.y*fit,it.box.w*fit,it.box.h*fit))}
        }else{layout(result);val sized=PadLayout.fitInside(PadLayout.applyOffsets(PadLayout.applyScales(result,PadLayout.groups.associateWith(::scaleOf)),PadLayout.groups.associateWith(::offsetOf)),logicalW,logicalH,6f);result.clear();result+=sized}
        input.controls=result;lastState=PadState();dirty=false
    }
    /** Thumb zones: d-pad and face buttons sit low and inward, shoulders hug the top corners, system keys share the bottom centre. */
    private fun layout(result:MutableList<PadControl>){
        val W=logicalW;val H=logicalH;val side=28f
        val cy=H*.57f
        val pad=minOf(H*.42f,172f)
        val dx=side+pad*.62f+6f;val fx=W-dx
        fun button(id:String,label:String,x:Float,y:Float,r:Float,tint:String,slop:Float=8f){
            result+=PadControl(id,Box(x-r-slop,y-r-slop,(r+slop)*2,(r+slop)*2),mask=console.mask(id),label=label,color=c(tint))
        }
        fun key(id:String,label:String,x:Float,y:Float,w:Float,h:Float,tint:String){
            result+=PadControl(id,Box(x-w/2,y-h/2,w,h),mask=console.mask(id),label=label,color=c(tint),round=false)
        }
        if(console==Console.TV){
            // Remote: a big arrow ring with OK in the middle (listed first so it wins the centre), Back/Home/Menu on the right.
            val ring=minOf(H*.40f,170f);val rx=W*.36f;val ry=H*.56f;val ok=ring*.36f
            result+=PadControl("ok",Box(rx-ok,ry-ok,ok*2,ok*2),mask=console.mask("ok"),label="OK",color=c("#4A4752"))
            result+=PadControl("dpad",Box(rx-ring,ry-ring,ring*2,ring*2),"dpad")
            val kx=W*.70f;val gap=minOf(H*.26f,100f)
            button("back","↩",kx,ry-gap,34f,"#3A3842",10f);button("home","",kx,ry,34f,"#3A3842",10f);button("options","≡",kx,ry+gap,34f,"#3A3842",10f)
            return
        }
        result+=PadControl("dpad",Box(dx-pad*.62f,cy-pad*.62f,pad*1.24f,pad*1.24f),"dpad")
        val step=minOf(maxOf(H*.13f,46f),56f);val r=step*.62f
        when(console){
            Console.SNES->{button("x","X",fx,cy-step,r,"#2B57B8");button("a","A",fx+step,cy,r,"#D8323D");button("b","B",fx,cy+step,r,"#EDC232");button("y","Y",fx-step,cy,r,"#1E8F4A")}
            Console.PS1->{button("triangle","△",fx,cy-step,r,"#3A3A41");button("circle","○",fx+step,cy,r,"#3A3A41");button("cross","×",fx,cy+step,r,"#3A3A41");button("square","□",fx-step,cy,r,"#3A3A41")}
            Console.NES->{button("b","B",fx-44f,cy,33f,"#D3242C",6f);button("a","A",fx+44f,cy,33f,"#D3242C",6f)}
            Console.GB->{button("b","B",fx-40f,cy+18f,33f,"#94295C",6f);button("a","A",fx+40f,cy-18f,33f,"#94295C",6f)}
            Console.MD->{listOf("a","b","c").forEachIndexed{i,id->button(id,id.uppercase(),fx-64f+i*64f,cy+32f-i*8f,28f,"#3A3A41",4f)}
                listOf("x","y","z").forEachIndexed{i,id->button(id,id.uppercase(),fx-60f+i*64f,cy-38f-i*8f,22f,"#4C4B54",4f)}}
            Console.ARCADE->{val tints=listOf("#2F86D6","#2FAE72","#E6B52E")
                listOf("y","x","l").forEachIndexed{i,id->button(id,listOf("LP","MP","HP")[i],fx-66f+i*66f,cy-34f-i*6f,28f,tints[i],4f)}
                listOf("b","a","r").forEachIndexed{i,id->button(id,listOf("LK","MK","HK")[i],fx-66f+i*66f,cy+34f-i*6f,28f,tints[i],4f)}}
            Console.TV->{}
        }
        val sw=minOf(W*.19f,168f)
        if(console==Console.SNES){key("l","L",side+sw/2,34f,sw,50f,"#CEC9E8");key("r","R",W-side-sw/2,34f,sw,50f,"#CEC9E8")}
        if(console==Console.PS1){val hw=sw*.62f
            key("l1","L1",side+hw/2,34f,hw,50f,"#BDBCC4");key("l2","L2",side+hw*1.5f+10f,34f,hw,50f,"#BDBCC4")
            key("r1","R1",W-side-hw/2,34f,hw,50f,"#BDBCC4");key("r2","R2",W-side-hw*1.5f-10f,34f,hw,50f,"#BDBCC4")}
        val sy=H-40f
        key("select",if(console==Console.MD)"MODE" else "SELECT",W/2-74f,sy,84f,46f,"#6D6C73")
        key("start","START",W/2+74f,sy,84f,46f,"#6D6C73")
        key("pause","Ⅱ",W/2,sy,52f,52f,"#B2AEB8")
        key("home","",W/2-152f,sy,52f,52f,"#B2AEB8") // back to RetroTV's main menu
        key("back","↩",W/2+152f,sy,52f,52f,"#B2AEB8") // one step back (in a game: the pause menu)
        if(console==Console.PS1)for(i in 0..1){val x=W/2+(if(i==0)-1 else 1)*minOf(W*.15f,128f);val y=H*.66f;val rr=48f
            result+=PadControl(if(i==0)"left" else "right",Box(x-rr,y-rr,rr*2,rr*2),if(i==0)"leftStick" else "rightStick")}
    }

    // ---- Sheet: the only place for layout choice, AUTO, connection and skins ----
    private fun openSheet(){release();sheet=true;dirty=true;invalidate()}
    private fun closeSheet(){sheet=false;dirty=true;invalidate()}
    private val panel get()=RectF(maxOf(24f,logicalW/2-330f),maxOf(16f,logicalH/2-170f),minOf(logicalW-24f,logicalW/2+330f),minOf(logicalH-16f,logicalH/2+170f))
    /** Sheet geometry that adapts to short screens (small phones or enlarged display size). */
    private inner class Sheet(val p:RectF){
        val compact=p.height()<320f
        val titleY=p.top+(if(compact)34f else 44f)
        val connect=Box(p.right-176f,p.top+(if(compact)52f else 72f),152f,if(compact)40f else 44f)
        val tileTop=p.top+(if(compact)106f else 164f);val tileH=if(compact)48f else 64f
        val tileW=(p.width()-48f-(Console.entries.size-1)*10f)/Console.entries.size
        fun tile(i:Int)=Box(p.left+24f+i*(tileW+10f),tileTop,tileW,tileH)
        val pillW=(p.width()-48f-20f)/3f;val pillH=if(compact)40f else 44f
        fun pill(i:Int)=Box(p.left+24f+i*(pillW+10f),if(compact)tileTop+tileH+12f else p.bottom-64f,pillW,pillH)
    }
    private fun buildSheet(){
        val l=Sheet(panel);val p=l.p
        ui+=Box(0f,0f,logicalW,logicalH) to {closeSheet()}
        ui+=Box(p.left,p.top,p.width(),p.height()) to {}
        ui+=Box(p.right-56f,p.top+4f,48f,48f) to {closeSheet()}
        ui+=l.connect to {closeSheet();callback.connect()}
        Console.entries.forEachIndexed{i,item->ui+=l.tile(i) to {callback.select(item);closeSheet()}}
        ui+=l.pill(0) to {callback.auto();dirty=true;invalidate()}
        if(imported==null)ui+=l.pill(1) to {startEditing()}
        ui+=l.pill(2) to {closeSheet();callback.options()}
    }
    private fun pill(canvas:Canvas,b:Box,label:String,filled:Boolean,size:Float=14f){
        ink.shader=null;ink.style=Paint.Style.FILL;ink.color=if(filled)c("#F1EDF5") else c("#2C2833");canvas.drawRoundRect(b.x,b.y,b.x+b.w,b.y+b.h,b.h/2,b.h/2,ink)
        text(canvas,b.x+b.w/2,b.y+b.h/2+size*.36f,label,size,if(filled)c("#211D29") else c("#E9E4F0"),true,b.w-16,true)
    }
    private fun drawSheet(canvas:Canvas){
        ink.shader=null;ink.style=Paint.Style.FILL;ink.color=art.alpha(Color.BLACK,150);canvas.drawRect(0f,0f,logicalW,logicalH,ink)
        val l=Sheet(panel);val p=l.p;ink.color=c("#1C1A21");ink.setShadowLayer(24f,0f,10f,art.alpha(Color.BLACK,160));canvas.drawRoundRect(p,26f,26f,ink);ink.clearShadowLayer()
        text(canvas,p.left+24f,l.titleY,"Controle",if(l.compact)19f else 22f,c("#F4F1F8"),true)
        text(canvas,p.right-32f,p.top+(if(l.compact)36f else 42f),"✕",20f,c("#B8B0C2"),center=true)
        val live=connectedName.isNotEmpty();val cy=l.connect.y+l.connect.h/2
        ink.color=if(live)c("#7FDB8E") else c("#E0A25A");canvas.drawCircle(p.left+30f,cy,5f,ink)
        text(canvas,p.left+44f,cy+5f,if(live)connectedName else connectionStatus,if(l.compact)13f else 15f,c("#D9D3E2"),false,p.width()-240f)
        pill(canvas,l.connect,if(live)"Desconectar" else "Conectar TV",!live)
        if(!l.compact)text(canvas,p.left+24f,p.top+150f,"LAYOUT",11f,c("#9C94A8"),true)
        Console.entries.forEachIndexed{i,item->val b=l.tile(i);val on=item==console
            ink.color=if(on)c("#F1EDF5") else c("#2C2833");canvas.drawRoundRect(b.x,b.y,b.x+b.w,b.y+b.h,16f,16f,ink)
            text(canvas,b.x+b.w/2,b.y+b.h/2+5f,item.label,if(l.compact)13f else 15f,if(on)c("#211D29") else c("#E9E4F0"),true,b.w-10,true)}
        pill(canvas,l.pill(0),if(automatic)"Auto: ligado" else "Auto: desligado",automatic,13f)
        if(imported==null)pill(canvas,l.pill(1),"Personalizar",false,13f)
        pill(canvas,l.pill(2),"Mais opções",false,13f)
    }

    private val toolbar get()=RectF(logicalW/2-234f,10f,logicalW/2+234f,58f)
    private fun groupBounds(group:String)=input.controls.filter{PadLayout.groupOf(it)==group}.takeIf{it.isNotEmpty()}?.let{items->
        RectF(items.minOf{it.box.x},items.minOf{it.box.y},items.maxOf{it.box.x+it.box.w},items.maxOf{it.box.y+it.box.h})}
    private fun buildEditor(){
        val t=toolbar;var x=t.left+138f
        ui+=Box(t.left,t.top,t.width(),t.height()) to {}
        ui+=Box(x,t.top+4f,40f,40f) to {setScale(editGroup,scaleOf(editGroup)-.1f)};x+=44f+56f
        ui+=Box(x,t.top+4f,40f,40f) to {setScale(editGroup,scaleOf(editGroup)+.1f)};x+=48f
        ui+=Box(x,t.top+4f,40f,40f) to {PadLayout.groups.forEach{prefs.edit().remove(scaleKey(it)).remove("${scaleKey(it)}.x").remove("${scaleKey(it)}.y").apply()};liveOffset.clear();dirty=true;invalidate()};x+=48f
        ui+=Box(x,t.top+4f,t.right-x-6f,40f) to {finishEditing()}
    }
    private fun drawEditor(canvas:Canvas){
        val dash=DashPathEffect(floatArrayOf(6f,6f),0f)
        PadLayout.groups.forEach{group->groupBounds(group)?.let{r->r.inset(-8f,-8f);val on=group==editGroup
            ink.shader=null;ink.style=Paint.Style.FILL;ink.color=art.alpha(accent,if(on)46 else 0);canvas.drawRoundRect(r,18f,18f,ink)
            ink.style=Paint.Style.STROKE;ink.strokeWidth=if(on)3f else 1.5f;ink.pathEffect=if(on)null else dash;ink.color=if(on)accent else art.alpha(if(look.dark)Color.WHITE else Color.BLACK,110);canvas.drawRoundRect(r,18f,18f,ink)
            ink.pathEffect=null;ink.style=Paint.Style.FILL}}
        val t=toolbar;ink.color=c("#1C1A21");ink.setShadowLayer(14f,0f,6f,art.alpha(Color.BLACK,150));canvas.drawRoundRect(t,24f,24f,ink);ink.clearShadowLayer()
        text(canvas,t.left+20f,t.top+30f,PadLayout.label(editGroup),15f,c("#F1EDF5"),true,112f)
        var x=t.left+138f
        fun round(label:String,at:Float){ink.color=c("#2C2833");canvas.drawCircle(at+20f,t.top+24f,20f,ink);text(canvas,at+20f,t.top+31f,label,20f,c("#F1EDF5"),true,center=true)}
        round("−",x);x+=44f;text(canvas,x+28f,t.top+30f,"${(scaleOf(editGroup)*100).roundToInt()}%",15f,c("#D9D3E2"),true,center=true);x+=56f
        round("+",x);x+=48f;round("↺",x);x+=48f
        ink.color=c("#F1EDF5");canvas.drawRoundRect(x,t.top+4f,t.right-6f,t.top+44f,20f,20f,ink);text(canvas,(x+t.right-6f)/2,t.top+29f,"Concluir",14f,c("#211D29"),true,center=true)
        val hint="Arraste para mover · dois dedos mudam o tamanho";ink.textSize=12f;val w=ink.measureText(hint)+28f
        ink.color=art.alpha(c("#1C1A21"),210);canvas.drawRoundRect(logicalW/2-w/2,t.bottom+8f,logicalW/2+w/2,t.bottom+34f,13f,13f,ink)
        text(canvas,logicalW/2,t.bottom+26f,hint,12f,c("#CFC8DA"),false,w,true)
    }
    private fun endDrag(){dragFrom=null;liveOffset.remove(editGroup)?.let{saveOffset(editGroup,it);dirty=true;invalidate()}}
    private fun editTouch(event:MotionEvent){
        val action=event.actionMasked;val index=event.actionIndex;val id=event.getPointerId(index)
        val x=event.getX(index)/scale;val y=event.getY(index)/scale
        fun spread()=hypot(event.getX(0)-event.getX(1),event.getY(0)-event.getY(1))
        when(action){
            MotionEvent.ACTION_DOWN->{val item=ui.lastOrNull{it.first.contains(x,y)}
                if(item!=null)uiDown=id to item else input.controls.filter{it.box.contains(x,y,10f)}.minByOrNull{hypot(it.box.x+it.box.w/2-x,it.box.y+it.box.h/2-y)}?.let{editGroup=PadLayout.groupOf(it);dragFrom=x to y;dragStart=offsetOf(editGroup);invalidate()}}
            MotionEvent.ACTION_POINTER_DOWN->if(event.pointerCount==2){uiDown=null;endDrag();pinchFrom=spread();pinchScale=scaleOf(editGroup)}
            MotionEvent.ACTION_MOVE->if(event.pointerCount>=2&&pinchFrom>0f){val next=PadLayout.clamp(pinchScale*spread()/pinchFrom);if(abs(next-scaleOf(editGroup))>.01f)setScale(editGroup,next)}
                else dragFrom?.let{(sx,sy)->liveOffset[editGroup]=dragStart.first+x-sx to dragStart.second+y-sy;dirty=true;invalidate()}
            MotionEvent.ACTION_POINTER_UP->pinchFrom=0f
            MotionEvent.ACTION_UP->{pinchFrom=0f;endDrag();val click=uiDown;uiDown=null;if(click!=null&&click.second.first.contains(x,y)){performClick();click.second.second()}}
            MotionEvent.ACTION_CANCEL->{pinchFrom=0f;endDrag();uiDown=null}
        }
    }
    override fun onDraw(canvas:Canvas){
        if(dirty)build()
        super.onDraw(canvas)
        canvas.save();canvas.scale(scale,scale)
        val state=input.state()
        if(imported!=null)drawImported(canvas,state)else drawConsole(canvas,state)
        if(editing)drawEditor(canvas) else drawChip(canvas)
        if(sheet)drawSheet(canvas)
        canvas.restore()
    }
    private fun drawChip(canvas:Canvas){
        val b=chip;val dark=look.dark;val live=connectedName.isNotEmpty()
        if(realisticAppearance&&imported==null&&RealisticPadArt.supports(console))realisticArt.chip(canvas,b,dark,live)
        else{
            ink.shader=null;ink.style=Paint.Style.FILL;ink.color=if(dark)art.alpha(Color.WHITE,22) else art.alpha(Color.BLACK,26);canvas.drawRoundRect(b.x,b.y,b.x+b.w,b.y+b.h,b.h/2,b.h/2,ink)
            ink.color=if(live)c("#5FCB7A") else c("#E0A25A");canvas.drawCircle(b.x+20f,b.y+b.h/2,4.5f,ink)
        }
        // AUTO only matters once a TV is connected; offline the chip invites to connect instead, so both fit.
        val label=console.label+when{!live->"  ·  conectar";automatic->"  ·  AUTO";else->""}
        text(canvas,b.x+b.w/2+8f,b.y+b.h/2+5f,label,13f,if(dark)c("#D8D2E2") else c("#4A4656"),true,b.w-44f,true)
    }
    private fun drawImported(canvas:Canvas,state:PadState){
        canvas.drawColor(c("#101012"))
        imported?.bitmap?.let{ink.shader=null;ink.color=Color.WHITE;canvas.drawBitmap(it,null,importedRect,ink)}
        input.controls.filter{pressed(it,state)}.forEach{
            ink.color=c("#88B99EFF");ink.style=Paint.Style.FILL;canvas.drawRoundRect(it.box.x,it.box.y,it.box.x+it.box.w,it.box.y+it.box.h,8f,8f,ink)}
    }

    /** Per-console material: shell gradient, printed ink and d-pad style. */
    private data class Look(val top:Int,val bottom:Int,val dark:Boolean,val ink:Int,val pad:PadArt.Pad)
    private val look get()=when(console){
        Console.NES->Look(c("#E3E3E6"),c("#C3C3C8"),false,c("#C9222C"),PadArt.Pad.NES)
        Console.SNES->Look(c("#E9E8EE"),c("#C9C7D0"),false,c("#77738A"),PadArt.Pad.SNES)
        Console.MD->Look(c("#2C2C31"),c("#0E0E11"),true,c("#B4B1BE"),PadArt.Pad.MD)
        Console.PS1->Look(c("#DBDBDF"),c("#BCBCC2"),false,c("#6F6D79"),PadArt.Pad.PS1)
        Console.GB->Look(c("#E0DDD2"),c("#C1BEB1"),false,c("#3D3C78"),PadArt.Pad.GB)
        Console.ARCADE->Look(c("#2D2937"),c("#121016"),true,c("#B3A8CA"),PadArt.Pad.SNES)
        Console.TV->Look(c("#26242C"),c("#111015"),true,c("#B3A8CA"),PadArt.Pad.SNES)
    }
    private fun centerOf(id:String)=input.controls.firstOrNull{it.id==id}?.box?.let{PointF(it.x+it.w/2,it.y+it.h/2)}
    private fun radiusOf(id:String)=input.controls.firstOrNull{it.id==id}?.let{it.box.w/2-(if(console==Console.SNES||console==Console.PS1)8f else if(console==Console.NES||console==Console.GB)6f else if(console==Console.TV)10f else 4f)}?:0f
    private fun drawConsole(canvas:Canvas,state:PadState){
        if(realisticAppearance&&RealisticPadArt.supports(console)){
            realisticArt.background(canvas,logicalW,logicalH,console,input.controls)
            input.controls.sortedBy{if(it.kind=="dpad")0 else 1}.forEach{control->
                realisticArt.control(canvas,control,state,console,pressed(control,state))
            }
            return
        }
        val l=look;val mid=art.mix(l.top,l.bottom,.55f)
        ink.style=Paint.Style.FILL;ink.shader=LinearGradient(0f,0f,0f,logicalH,l.top,l.bottom,Shader.TileMode.CLAMP);canvas.drawRect(0f,0f,logicalW,logicalH,ink);ink.shader=null
        decor(canvas,l,mid)
        // D-pads first: the TV remote's OK key sits on top of its ring.
        input.controls.sortedBy{if(it.kind=="dpad")0 else 1}.forEach{control->
            val p=pressed(control,state);val b=control.box;val x=b.x+b.w/2;val y=b.y+b.h/2
            when{
                control.id=="home"->art.homeKey(canvas,x,y,minOf(b.w,b.h)/2-(if(console==Console.TV)10f else 9f),if(l.dark)c("#3E3C46") else c("#C9C7D0"),p,accent,if(l.dark)c("#D2CEDB") else c("#5D5A68"))
                console==Console.TV->drawRemote(canvas,control,state,p)
                control.id=="back"->art.button(canvas,x,y,minOf(b.w,b.h)/2-9f,if(l.dark)c("#3E3C46") else c("#C9C7D0"),control.label,p,accent,ink=if(l.dark)c("#D2CEDB") else c("#5D5A68"))
                control.kind=="dpad"->if(console==Console.ARCADE)art.lever(canvas,x,y,b.w*.42f,state.dpad,c("#D8344F"),accent) else art.dpad(canvas,x,y,b.w*.74f,state.dpad,l.pad,mid,accent)
                control.kind=="leftStick"->art.stick(canvas,x,y,b.w/2-6f,state.lx,state.ly,mid,accent)
                control.kind=="rightStick"->art.stick(canvas,x,y,b.w/2-6f,state.rx,state.ry,mid,accent)
                (console==Console.SNES||console==Console.PS1)&&control.id in setOf("l","r","l1","r1","l2","r2")->art.shoulder(canvas,RectF(b.x,b.y+4f,b.x+b.w,b.y+b.h-8f),control.color,control.label,p,accent,if(console==Console.SNES)c("#5A566B") else c("#64636B"))
                control.id=="pause"->art.key(canvas,x,y,minOf(b.w,b.h)/2-9f,if(l.dark)c("#3E3C46") else c("#C9C7D0"),p,accent,if(l.dark)c("#D2CEDB") else c("#5D5A68"))
                control.id=="select"||control.id=="start"->drawPill(canvas,control,p,l,mid)
                console==Console.ARCADE->art.arcadeButton(canvas,x,y,radiusOf(control.id),control.color,control.label,p,accent,above=control.id in setOf("y","x","l"))
                console==Console.PS1->art.button(canvas,x,y,radiusOf(control.id),control.color,"",p,accent,symbol=control.id,symbolColor=when(control.id){"triangle"->c("#45C29C");"circle"->c("#F06A79");"cross"->c("#7FA6EA");else->c("#E08FD0")})
                console==Console.NES||console==Console.GB->{val r=radiusOf(control.id);art.button(canvas,x,y,r,control.color,"",p,accent)
                    art.label(canvas,control.label,x+(if(console==Console.NES)r*.7f else 0f),y+r+(if(console==Console.NES)30f else 22f),15f,l.ink)}
                console==Console.MD->art.button(canvas,x,y,radiusOf(control.id),control.color,control.label,p,accent,ink=c("#8E8B99"))
                else->art.button(canvas,x,y,radiusOf(control.id),control.color,control.label,p,accent)
            }
        }
    }
    /** Plates and wells under the controls, after the reference pads' anatomy. */
    private fun decor(canvas:Canvas,l:Look,mid:Int){
        when(console){
            Console.NES->{
                val plate=RectF(14f,70f,logicalW-14f,logicalH-8f);art.well(canvas,plate,14f,c("#222226"),.25f)
                val cx=logicalW/2;val sy=centerOf("start")?.y?:logicalH-40f
                listOf(plate.top+26f,plate.top+52f,plate.top+78f).forEach{y->ink.shader=LinearGradient(0f,y,0f,y+16,c("#8B8B90"),c("#6A6A6F"),Shader.TileMode.CLAMP);canvas.drawRoundRect(cx-120f,y,cx+120f,y+16f,6f,6f,ink);ink.shader=null}
                ink.shader=LinearGradient(0f,sy-58f,0f,sy-32f,c("#8B8B90"),c("#6A6A6F"),Shader.TileMode.CLAMP);canvas.drawRoundRect(cx-120f,sy-58f,cx+120f,sy-32f,6f,6f,ink);ink.shader=null
                centerOf("select")?.let{art.label(canvas,"SELECT",it.x,sy-40f,11f,l.ink,letter=.12f)};centerOf("start")?.let{art.label(canvas,"START",it.x,sy-40f,11f,l.ink,letter=.12f)}
                art.well(canvas,RectF(cx-120f,sy-24f,cx+120f,sy+24f),6f,c("#EDEDEF"),.08f)
                listOf("b","a").forEach{id->centerOf(id)?.let{p->val s=radiusOf(id)+8
                    ink.shader=LinearGradient(0f,p.y-s,0f,p.y+s,c("#F1F1F3"),c("#C6C6CA"),Shader.TileMode.CLAMP);canvas.drawRoundRect(p.x-s,p.y-s,p.x+s,p.y+s,6f,6f,ink);ink.shader=null}}
            }
            Console.SNES->{
                val x=centerOf("x");val a=centerOf("a");val b=centerOf("b");val y=centerOf("y");val r=radiusOf("a")
                if(x!=null&&a!=null&&b!=null&&y!=null){art.circleWell(canvas,x.x,a.y,a.x-x.x+r+12,c("#A3A1AB"),.14f)
                    art.rail(canvas,y.x,y.y,x.x,x.y,r*2+10,c("#DEDCE3"),.06f);art.rail(canvas,b.x,b.y,a.x,a.y,r*2+10,c("#DEDCE3"),.06f)}
            }
            Console.MD->{
                val a=centerOf("a");val cc=centerOf("c");val x=centerOf("x");val z=centerOf("z")
                if(a!=null&&cc!=null)art.rail(canvas,a.x,a.y,cc.x,cc.y,radiusOf("a")*2+16,c("#3A3A40"),.35f)
                if(x!=null&&z!=null)art.rail(canvas,x.x,x.y,z.x,z.y,radiusOf("x")*2+14,c("#3A3A40"),.35f)
            }
            Console.PS1->{val t=centerOf("triangle");val o=centerOf("circle");if(t!=null&&o!=null)art.circleWell(canvas,t.x,o.y,o.x-t.x+radiusOf("circle")+10,mid,.07f)}
            Console.GB->{val b=centerOf("b");val a=centerOf("a");if(b!=null&&a!=null)art.rail(canvas,b.x,b.y,a.x,a.y,radiusOf("a")*2+14,mid,.12f)}
            Console.ARCADE->{}
            Console.TV->{}
        }
        if(console==Console.TV){val r=radiusOf("home")
            listOf("back" to "Retornar","home" to "Início","options" to "Menu").forEach{(id,name)->centerOf(id)?.let{art.label(canvas,name,it.x+r+16f,it.y+5f,14f,l.ink,letter=.04f,center=false)}}}
    }
    private fun drawRemote(canvas:Canvas,control:PadControl,state:PadState,pressed:Boolean){
        val b=control.box;val x=b.x+b.w/2;val y=b.y+b.h/2
        when(control.kind){
            "dpad"->art.remoteRing(canvas,x,y,b.w/2-6f,state.dpad,c("#33313A"),accent)
            else->art.button(canvas,x,y,radiusOf(control.id),control.color,control.label,pressed,accent,ink=c("#D2CEDB"))
        }
    }
    private fun drawPill(canvas:Canvas,control:PadControl,pressed:Boolean,l:Look,mid:Int){
        val b=control.box;val x=b.x+b.w/2;val y=b.y+b.h/2;val k=b.w/84f;val w=58f*k;val h=20f*k
        when(console){
            Console.NES->art.pill(canvas,x,y,w*.8f,h*.75f,0f,c("#1D1D21"),c("#EDEDEF"),pressed,accent)
            Console.SNES->{art.pill(canvas,x,y-6f,w,h*.75f,-26f,c("#BDB8DA"),mid,pressed,accent);art.label(canvas,control.label,x,y+20f*k,10f,l.ink,italic=true,letter=.1f)}
            Console.GB->{art.pill(canvas,x,y-6f,w*.9f,h*.75f,-24f,c("#8E8B92"),mid,pressed,accent);art.label(canvas,control.label,x,y+20f*k,10f,l.ink,italic=true,letter=.1f)}
            Console.MD->{art.pill(canvas,x,y-4f,w*.9f,h*.8f,-16f,if(control.id=="start")c("#C9C8D0") else c("#4A4952"),c("#1A1A1E"),pressed,accent);art.label(canvas,control.label,x,y+20f*k,9.5f,c("#A8A4B3"),letter=.12f)}
            else->{art.pill(canvas,x,y-6f,w*.85f,h*.7f,0f,if(console==Console.ARCADE)c("#3E3A48") else c("#45444B"),if(console==Console.ARCADE)c("#1A1820") else mid,pressed,accent);art.label(canvas,control.label,x,y+18f*k,9.5f,if(l.dark)c("#A8A4B3") else l.ink,letter=.1f)}
        }
    }
    private fun publish(){
        val state=input.state();if(state!=lastState){lastState=state;callback.send(state);invalidate()}
    }
    override fun onTouchEvent(event:MotionEvent):Boolean{
        if(dirty)build()
        if(editing){editTouch(event);return true}
        val action=event.actionMasked;val index=event.actionIndex;val id=event.getPointerId(index)
        val x=event.getX(index)/scale;val y=event.getY(index)/scale
        when(action){
            MotionEvent.ACTION_DOWN,MotionEvent.ACTION_POINTER_DOWN->{
                val item=if(sheet&&event.pointerCount==1)ui.lastOrNull{it.first.contains(x,y)} else if(!sheet)ui.lastOrNull{it.first.contains(x,y)}?.takeIf{event.pointerCount==1&&input.controls.none{c->c.box.contains(x,y)}} else null
                if(item!=null)uiDown=id to item else if(!sheet){input.down(id,x,y);performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)}
            }
            MotionEvent.ACTION_MOVE->if(!sheet)for(i in 0 until event.pointerCount)input.move(event.getPointerId(i),event.getX(i)/scale,event.getY(i)/scale)
            MotionEvent.ACTION_UP,MotionEvent.ACTION_POINTER_UP->{
                input.up(id)
                val click=uiDown
                if(click?.first==id){uiDown=null;if(click.second.first.contains(x,y)){performClick();click.second.second()}}
            }
            MotionEvent.ACTION_CANCEL->{uiDown=null;input.clear()}
        }
        publish();return true
    }
    override fun performClick():Boolean{super.performClick();return true}
}
