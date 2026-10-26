package org.retrotv.pad
import kotlin.math.*
data class Box(val x:Float,val y:Float,val w:Float,val h:Float) {
    fun contains(px:Float,py:Float,extra:Float=0f)=px>=x-extra&&px<=x+w+extra&&py>=y-extra&&py<=y+h+extra
}
data class PadControl(val id:String,val box:Box,val kind:String="button",val mask:Int=0,val label:String="",val color:Int=0,val round:Boolean=true)
data class PadState(val buttons:Int=0,val dpad:Int=0,val lx:Float=0f,val ly:Float=0f,val rx:Float=0f,val ry:Float=0f)
class PadInput {
    var controls:List<PadControl> = emptyList()
        set(value){clear();field=value}
    private data class Contact(var control:PadControl,var x:Float,var y:Float)
    private val contacts=linkedMapOf<Int,Contact>()
    /** Touch boxes may overlap (they are larger than the drawn parts); the control whose centre is nearest wins. */
    private fun nearest(x:Float,y:Float,filter:(PadControl)->Boolean={true})=controls.filter{filter(it)&&it.box.contains(x,y)}.minByOrNull{val dx=it.box.x+it.box.w/2-x;val dy=it.box.y+it.box.h/2-y;dx*dx+dy*dy}
    fun down(id:Int,x:Float,y:Float){nearest(x,y)?.let{contacts[id]=Contact(it,x,y)}}
    fun move(id:Int,x:Float,y:Float){
        val p=contacts[id]?:return
        if(p.control.kind=="button")nearest(x,y){it.kind=="button"}?.let{p.control=it}
        p.x=x;p.y=y
    }
    fun up(id:Int){contacts.remove(id)}
    fun clear(){contacts.clear()}
    fun state():PadState {
        var b=0;var d=0;var lx=0f;var ly=0f;var rx=0f;var ry=0f
        contacts.values.forEach{p->val c=p.control
            when(c.kind){
                "dpad"->if(c.box.contains(p.x,p.y,24f))d=d or dpadBits(p.x-c.box.x,p.y-c.box.y,c.box.w,c.box.h)
                "leftStick","rightStick"->{
                    var x=(p.x-(c.box.x+c.box.w/2))/(c.box.w*.36f);var y=(p.y-(c.box.y+c.box.h/2))/(c.box.h*.36f)
                    val length=sqrt(x*x+y*y);if(length>1f){x/=length;y/=length};if(length<.12f){x=0f;y=0f}
                    if(c.kind=="leftStick"){lx=x;ly=y}else{rx=x;ry=y}
                }
                else->if(c.box.contains(p.x,p.y))b=b or c.mask
            }
        }
        return PadState(b,d,lx,ly,rx,ry)
    }
}
enum class Console(val label:String,val title:String,val skinId:String){
    NES("NES","Nintendo Entertainment System","nes"),SNES("SNES","Super Nintendo","snes"),
    MD("MEGA","Mega Drive","md"),PS1("PS1","PlayStation","ps1"),GB("GB","Game Boy","gb"),ARCADE("ARCADE","Arcade","arcade"),
    /** Plain TV remote for browsing the gallery: arrows, OK, Back, Home and Menu. */
    TV("TV","Controle da TV","tv");
    fun mask(input:String):Int=when(input.lowercase()){
        "menu","pause"->(1 shl 8) or (1 shl 9)
        // Bit 16 is Home: the Guide button in the HID report; RetroTV and Google TV treat it as "go to the main menu".
        "home"->1 shl 16
        "ok"->1 shl 0;"options"->1 shl 9
        // Bit 17 is "Retornar" (one step back): never a game button, so it also works mid-game (it opens the pause menu).
        "back"->1 shl 17
        "select","mode"->1 shl 8;"start"->1 shl 9
        "l","l1"->1 shl 4;"r","r1"->1 shl 5;"l2"->1 shl 6;"r2"->1 shl 7
        "l3"->1 shl 10;"r3"->1 shl 11
        "cross"->1 shl 1;"circle"->1 shl 0;"square"->1 shl 3;"triangle"->1 shl 2
        "a"->if(this==MD)1 shl 3 else 1 shl 0
        "b"->1 shl 1;"c"->if(this==MD)1 shl 0 else 0
        "x"->if(this==MD)1 shl 4 else 1 shl 2
        "y"->if(this==MD)1 shl 2 else 1 shl 3
        "z"->if(this==MD)1 shl 5 else 0
        else->0
    }
    companion object{
        fun fromSystem(value:String):Console?=when(value){
            "NES"->NES;"SNES"->SNES;"Mega Drive","Master System"->MD;"PlayStation"->PS1;"Game Boy","Game Boy Color"->GB;"Arcade"->ARCADE;else->null}
    }
}
/** 8-way d-pad from a touch inside a w×h box: 45° sectors around the centre, small dead zone; bits 1 up, 2 down, 4 left, 8 right. */
fun dpadBits(x:Float,y:Float,w:Float,h:Float):Int{
    val nx=(x-w/2)/(w/2);val ny=(y-h/2)/(h/2)
    if(nx*nx+ny*ny<.04f)return 0
    val sector=Math.floorMod((atan2(-ny,nx)/(PI/4)).roundToInt(),8)
    return intArrayOf(8,9,1,5,4,6,2,10)[sector]
}
