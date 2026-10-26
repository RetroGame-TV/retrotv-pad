// Original RetroTV metadata protocol. SPDX-License-Identifier: MIT
package org.retrotv.pad
import java.io.InputStream
object MetadataProtocol {
    const val UUID_STRING="8a421b90-d104-4ba2-8c72-50a34191ce01"
    const val MAX_LINE=2048
    fun readLine(input:InputStream):String?{
        val bytes=java.io.ByteArrayOutputStream()
        while(true){val b=input.read();if(b==-1)return if(bytes.size()==0)null else throw java.io.EOFException("Truncated metadata")
            if(b==10)return bytes.toString("UTF-8");if(bytes.size()>=MAX_LINE)throw java.io.IOException("Metadata too long");bytes.write(b)}
    }
}