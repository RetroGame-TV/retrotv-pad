package org.retrotv.pad
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Independent reader for Delta's public info.json representation/items/frame format.
 * Reference: Riley Testut, DeltaCore/Model/ControllerSkin.swift; no Swift/UIKit code is compiled.
 * Supports standard iPhone portrait/landscape button and d-pad representations, PDF/PNG/JPEG.
 */
class DeltaSkin(val name:String,val console:Console,val views:Map<String,Representation>):AutoCloseable{
    data class Representation(val width:Float,val height:Float,val controls:List<PadControl>,val bitmap:Bitmap?)
    override fun close(){views.values.mapNotNull{it.bitmap}.distinct().forEach{it.recycle()}}
    companion object {
        const val MAX_ARCHIVE=32*1024*1024
        fun loadArchive(input:InputStream,cache:File):DeltaSkin{
            val archive=File.createTempFile("delta-archive-",".zip",cache)
            try{
                archive.outputStream().use{out->val buffer=ByteArray(8192);var total=0
                    while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=MAX_ARCHIVE){"Skin excede 32 MiB"};out.write(buffer,0,n)}}
                return loadFile(archive,cache)
            }finally{archive.delete()}
        }
        fun loadFile(archive:File,cache:File):DeltaSkin{
            require(archive.length()<=MAX_ARCHIVE){"Skin excede 32 MiB"}
            return java.util.zip.ZipFile(archive).use{zip->
                val entries=zip.entries().asSequence().toList();require(entries.size<=128){"Skin com arquivos demais"}
                val names=hashSetOf<String>()
                entries.forEach{e->require(!e.name.startsWith("/")&&!e.name.contains("\\")&&e.name.split('/').none{it==".."}&&e.name.length<=512){"Caminho inválido na skin"}
                    require(names.add(e.name)){"Arquivo duplicado"}}
                fun read(name:String,max:Int):ByteArray {
                    val entry=zip.getEntry(name)?:error("Arquivo ausente na skin: "+name)
                    require(!entry.isDirectory&&entry.size in 0..max.toLong()){"Arquivo da skin grande demais"}
                    val out=java.io.ByteArrayOutputStream()
                    zip.getInputStream(entry).use{input->val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=max);out.write(buffer,0,n)}}
                    return out.toByteArray()
                }
                parse(read("info.json",256*1024),{name->read(name,8*1024*1024)},cache)
            }
        }
        private fun parse(info:ByteArray,assetBytes:(String)->ByteArray,cache:File):DeltaSkin{
            require(info.size<=256*1024){"info.json grande demais"}
            val raw=String(info,Charsets.UTF_8);validateDepth(raw);val json=JSONObject(raw)
            val type=json.optString("gameTypeIdentifier").substringAfterLast('.')
            val console=when(type){"nes"->Console.NES;"snes"->Console.SNES;"gbc","gb"->Console.GB;"genesis","md"->Console.MD;"ps1"->Console.PS1;else->error("Console não suportado nesta skin")}
            val reps=json.getJSONObject("representations").getJSONObject("iphone")
            val standard=reps.optJSONObject("standard")?:reps.optJSONObject("edgeToEdge")?:error("Skin sem representação iPhone compatível")
            val result=linkedMapOf<String,Representation>()
            try{
                for(orientation in listOf("portrait","landscape")){
                    val rep=standard.optJSONObject(orientation)?:continue
                    val size=rep.getJSONObject("mappingSize");val w=size.getDouble("width").toFloat();val h=size.getDouble("height").toFloat()
                    require(w.isFinite()&&h.isFinite()&&w in 1f..8192f&&h in 1f..8192f){"Dimensões inválidas"}
                    val controls=mutableListOf<PadControl>();val items=rep.getJSONArray("items");require(items.length()<=64)
                    for(i in 0 until items.length()){
                        val item=items.getJSONObject(i);val f=item.getJSONObject("frame")
                        val box=Box(f.getDouble("x").toFloat(),f.getDouble("y").toFloat(),f.getDouble("width").toFloat(),f.getDouble("height").toFloat())
                        require(listOf(box.x,box.y,box.w,box.h).all{it.isFinite()}&&kotlin.math.abs(box.x)<=8192f&&kotlin.math.abs(box.y)<=8192f&&box.w in .01f..8192f&&box.h in .01f..8192f)
                        val inputs=item.get("inputs")
                        if(inputs is JSONObject&&inputs.has("up")&&inputs.has("right"))controls+=PadControl("delta-$i",box,"dpad")
                        else if(inputs is JSONArray){
                            var mask=0;val names=(0 until inputs.length()).map{inputs.getString(it)};names.forEach{mask=mask or console.mask(it)}
                            if(mask!=0)controls+=PadControl("delta-$i",box,mask=mask,label=names.joinToString("+").uppercase())
                        }
                    }
                    require(controls.isNotEmpty()){"Skin sem botões compatíveis"}
                    val assets=rep.getJSONObject("assets");val asset=(listOf("resizable","large","medium","small").firstNotNullOfOrNull{assets.optString(it).takeIf{v->v.isNotEmpty()}})?:error("Imagem ausente")
                    val bitmap=decode(assetBytes(asset),asset,cache)
                    result[orientation]=Representation(w,h,controls,bitmap)
                }
                require(result.isNotEmpty()){"Representação da skin indisponível"}
                return DeltaSkin(json.optString("name","Skin Delta").take(60),console,result)
            }catch(e:Exception){result.values.forEach{it.bitmap?.recycle()};throw e}
        }
        private fun validateDepth(raw:String){
            var depth=0;var quoted=false;var escaped=false
            for(ch in raw){
                if(quoted){if(escaped)escaped=false else if(ch=='\\')escaped=true else if(ch=='"')quoted=false}
                else if(ch=='"')quoted=true else if(ch=='{'||ch=='['){depth++;require(depth<=32){"Skin com estrutura profunda demais"}}
                else if(ch=='}'||ch==']'){depth--;require(depth>=0)}
            }
            require(depth==0&&!quoted){"JSON incompleto"}
        }
        private fun decode(bytes:ByteArray,name:String,cache:File):Bitmap{
            if(name.endsWith(".pdf",true)){
                val file=File.createTempFile("delta-skin-",".pdf",cache)
                try{file.writeBytes(bytes);ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use{fd->PdfRenderer(fd).use{pdf->
                    require(pdf.pageCount>0);pdf.openPage(0).use{p->val scale=minOf(2f,2048f/maxOf(p.width,p.height))
                        val bmp=Bitmap.createBitmap(maxOf(1,(p.width*scale).toInt()),maxOf(1,(p.height*scale).toInt()),Bitmap.Config.ARGB_8888);p.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);return bmp}}}}
                finally{file.delete()}
            }
            val opts=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts)
            require(opts.outWidth>0&&opts.outHeight>0&&opts.outWidth.toLong()*opts.outHeight<=16_000_000){"Imagem inválida ou grande demais"}
            var sample=1;while(maxOf(opts.outWidth,opts.outHeight)/sample>2048)sample*=2
            return BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample})?:error("Imagem inválida")
        }
    }
}