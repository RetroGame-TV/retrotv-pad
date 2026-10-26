package org.retrotv.pad
import kotlin.math.roundToInt

/**
 * Bluetooth HID gamepad, written from the USB HID 1.11 spec and the HID Usage Tables.
 * One input report (ID 3, 8 bytes): 16 buttons, a reserved byte, a hat switch and four signed 8-bit axes.
 * Bytes 0..3 are buttons and hat only, which is what [ReportBuffer] compares to keep press/release edges.
 *
 * Android's hid-input turns gamepad button n into BTN_GAMEPAD+(n-1), and Generic.kl into
 * BUTTON_A, B, C, X, Y, Z, L1, R1, L2, R2, SELECT, START, MODE, THUMBL, THUMBR.
 */
object HidGamepad {
    const val REPORT_ID=3
    const val REPORT_SIZE=8
    const val HAT_NEUTRAL=0x0F
    val DESCRIPTOR=bytes(
        0x05,0x01, 0x09,0x05, 0xA1,0x01,            // Generic Desktop / Game Pad / Application
        0x85,REPORT_ID,
        0x05,0x09, 0x19,0x01, 0x29,0x10,            // Buttons 1..16
        0x15,0x00, 0x25,0x01, 0x75,0x01, 0x95,0x10, 0x81,0x02,
        0x75,0x08, 0x95,0x01, 0x81,0x03,            // reserved byte
        0x05,0x01, 0x09,0x39,                       // Hat switch, 0..7 clockwise from up, null outside
        0x15,0x00, 0x25,0x07, 0x35,0x00, 0x46,0x3B,0x01, 0x65,0x14,
        0x75,0x04, 0x95,0x01, 0x81,0x42,
        0x65,0x00, 0x45,0x00,                       // no unit or physical range for what follows
        0x75,0x04, 0x95,0x01, 0x81,0x03,            // hat padding
        0x09,0x30, 0x09,0x31, 0x09,0x32, 0x09,0x35, // X Y (left stick), Z Rz (right stick)
        0x15,0x81, 0x25,0x7F, 0x75,0x08, 0x95,0x04, 0x81,0x02,
        0xC0
    )
    /** Pad bit -> HID button number (A B X Y L1 R1 L2 R2 Select Start L3 R3, then Home on MODE). */
    private val buttons=intArrayOf(1,2,4,5,7,8,9,10,11,12,14,15)
    private const val HOME_BIT=1 shl 16
    private const val MODE_BUTTON=13

    fun report(state:PadState):ByteArray{
        var pressed=0
        buttons.forEachIndexed{bit,button->if(state.buttons and (1 shl bit)!=0)pressed=pressed or (1 shl (button-1))}
        if(state.buttons and HOME_BIT!=0)pressed=pressed or (1 shl (MODE_BUTTON-1))
        return byteArrayOf(pressed.toByte(),(pressed shr 8).toByte(),0,hat(state.dpad).toByte(),
            axis(state.lx),axis(state.ly),axis(state.rx),axis(state.ry))
    }
    /** Pad d-pad bits (1 up, 2 down, 4 left, 8 right) to a hat value; opposite directions cancel. */
    fun hat(dpad:Int):Int{
        val y=(if(dpad and 2!=0)1 else 0)-(if(dpad and 1!=0)1 else 0)
        val x=(if(dpad and 8!=0)1 else 0)-(if(dpad and 4!=0)1 else 0)
        return when{
            y<0&&x==0->0; y<0&&x>0->1; y==0&&x>0->2; y>0&&x>0->3
            y>0&&x==0->4; y>0&&x<0->5; y==0&&x<0->6; y<0&&x<0->7
            else->HAT_NEUTRAL
        }
    }
    private fun axis(value:Float)=(value.coerceIn(-1f,1f)*127).roundToInt().toByte()
    private fun bytes(vararg values:Int)=ByteArray(values.size){values[it].toByte()}
}
