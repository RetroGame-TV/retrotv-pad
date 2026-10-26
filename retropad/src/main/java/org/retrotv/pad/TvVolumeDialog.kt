package org.retrotv.pad

import android.app.AlertDialog
import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Remote volume panel. Does not touch the phone's AudioManager or invent a TV volume percentage. */
internal class TvVolumeDialog(context:Context,private val available:()->Boolean,
    private val adjust:(Boolean)->Boolean,connect:()->Unit,keys:(KeyEvent)->Boolean){
    private val unit=context.resources.displayMetrics.density
    private fun dp(value:Int)=(value*unit).toInt()
    private val status=TextView(context).apply{textSize=15f;setPadding(0,0,0,dp(12))}
    private val down=Button(context).apply{text="−  Diminuir";contentDescription="Diminuir volume da TV";tag="volume.down";minHeight=dp(56)}
    private val up=Button(context).apply{text="+  Aumentar";contentDescription="Aumentar volume da TV";tag="volume.up";minHeight=dp(56)}
    private val connectButton=Button(context).apply{text="Conectar controle da TV";tag="volume.connect"}
    private val content=LinearLayout(context).apply{
        orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(8),dp(24),dp(8));addView(status)
        addView(LinearLayout(context).apply{
            orientation=LinearLayout.HORIZONTAL
            addView(down,LinearLayout.LayoutParams(0,dp(64),1f))
            addView(up,LinearLayout.LayoutParams(0,dp(64),1f).apply{marginStart=dp(12)})
        })
        addView(connectButton)
    }
    val dialog:AlertDialog=AlertDialog.Builder(context).setTitle("Volume da TV").setView(content)
        .setPositiveButton("Fechar",null).create()
    init{
        down.setOnClickListener{if(!adjust(false))refresh()}
        up.setOnClickListener{if(!adjust(true))refresh()}
        connectButton.setOnClickListener{dialog.dismiss();connect()}
        dialog.setOnKeyListener{_,_,event->keys(event)}
    }
    fun refresh(){
        val connected=available();down.isEnabled=connected;up.isEnabled=connected
        status.text=if(connected)"Ajuste o volume da TV. Os botões de volume do celular também funcionam aqui."
            else "Conecte ao controle remoto do Google TV, na mesma rede Wi-Fi, para ajustar o volume da TV."
        connectButton.visibility=if(connected)View.GONE else View.VISIBLE
    }
    fun show(){refresh();dialog.show()}
    fun dismiss(){dialog.dismiss()}
}
