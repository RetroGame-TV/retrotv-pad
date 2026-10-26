package org.retrotv.pad
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
@RunWith(AndroidJUnit4::class)
class ReferenceDeltaTest {
    /** Explicit developer-only reference validation; external artwork is never bundled. */
    @Test fun optionalStandardSnesSkinFromOriginalDelta(){
        val ins=InstrumentationRegistry.getInstrumentation()
        if(InstrumentationRegistry.getArguments().getString("referenceSkin")!="1")return
        val file=File(ins.targetContext.filesDir,"reference.deltaskin")
        assertTrue("Push the original reference to the emulator first",file.exists())
        val skin=DeltaSkin.loadFile(file,ins.targetContext.cacheDir)
        try{
            assertEquals(Console.SNES,skin.console);assertTrue(skin.name.contains("SNES"))
            for(orientation in listOf("portrait","landscape")){
                val rep=skin.views.getValue(orientation);assertTrue(rep.controls.size>=8)
                assertNotNull(rep.bitmap);assertTrue(rep.bitmap!!.width>100)
                assertTrue(rep.controls.any{it.kind=="dpad"});assertTrue(rep.controls.any{it.mask==Console.SNES.mask("a")})
            }
        }finally{skin.close()}
    }
}