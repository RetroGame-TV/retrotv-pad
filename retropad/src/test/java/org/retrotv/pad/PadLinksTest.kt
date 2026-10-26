package org.retrotv.pad
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
class PadLinksTest{
    @Test fun retroTvLineCarriesButtonsDpadAndClampedAxes(){
        assertEquals("I 201 9 127 -127 0 64",PadWire.input(PadState(buttons=0x201,dpad=9,lx=1.5f,ly=-2f,rx=0f,ry=.5f)))
        assertEquals("I 0 0 0 0 0 0",PadWire.input(PadState()))
        assertEquals("I 10000 0 0 0 0 0",PadWire.input(PadState(buttons=Console.TV.mask("home"))))
        assertEquals("I 0 0 0 0 0 0",PadWire.input(PadState(buttons=0xF000)))
    }
    @Test fun remoteKeysMapToTvActions(){
        assertEquals(1 shl 0,Console.TV.mask("ok"));assertEquals(1 shl 17,Console.TV.mask("back"));assertEquals(1 shl 9,Console.TV.mask("options"))
        assertEquals("I 20000 0 0 0 0 0",PadWire.input(PadState(buttons=Console.NES.mask("back"))))
        assertEquals(listOf(4 to 1),GoogleTvKeys.events(PadState(),PadState(buttons=1 shl 17)))
        assertEquals(1 shl 16,Console.SNES.mask("home"))
        assertEquals(listOf(3 to 1),GoogleTvKeys.events(PadState(),PadState(buttons=1 shl 16)))
    }
    @Test fun sticksArePacedButButtonsAreNot(){
        val p=StickPacer(8)
        assertEquals(0L,p.delay(PadState(lx=.1f),100))
        assertEquals(5L,p.delay(PadState(lx=.2f),103))
        assertEquals(0L,p.delay(PadState(buttons=1,lx=.2f),104))
        assertEquals(0L,p.delay(PadState(dpad=2,lx=.2f),105))
        p.sentLate(PadState(dpad=2,lx=.3f),110)
        assertEquals(4L,p.delay(PadState(dpad=2,lx=.4f),114))
        assertEquals(0L,p.delay(PadState(dpad=2,lx=.5f),118))
    }
    @Test fun googleTvKeysPressAndReleaseOnlyWhatChanged(){
        val down=GoogleTvKeys.events(PadState(),PadState(buttons=1,dpad=8))
        assertEquals(setOf(22 to 1,23 to 1),down.toSet())
        assertEquals(listOf(22 to 2),GoogleTvKeys.events(PadState(buttons=1,dpad=8),PadState(buttons=1)))
        assertEquals(listOf(4 to 1),GoogleTvKeys.events(PadState(),PadState(buttons=2)))
        assertTrue(GoogleTvKeys.events(PadState(buttons=4),PadState(buttons=8)).isEmpty())
    }
    @Test fun pairingRequestMatchesTheWireFormat(){
        val message=Pb().int(1,2).int(2,200).msg(10,Pb().str(1,"atvremote").str(2,"RetroTV Pad")).toBytes()
        val expected=byteArrayOf(0x08,0x02,0x10,0xC8.toByte(),0x01,0x52,0x18,0x0A,0x09)+"atvremote".toByteArray()+byteArrayOf(0x12,0x0B)+"RetroTV Pad".toByteArray()
        assertArrayEquals(expected,message)
        val parsed=Pb.parse(message);assertEquals(200L,parsed[2]!!.first());assertEquals("atvremote",String(Pb.parse(parsed[10]!!.first() as ByteArray)[1]!!.first() as ByteArray))
        val framed=java.io.ByteArrayOutputStream().also{Pb.writeFrame(it,message)}.toByteArray()
        assertArrayEquals(message,Pb.readFrame(framed.inputStream()))
    }
    @Test fun clientCertificateIsAValidSelfSignedCa(){
        val pair=java.security.KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair()
        val now=java.util.Date()
        val der=GoogleTvKeys.selfSigned(pair.public,{java.security.Signature.getInstance("SHA256withRSA").run{initSign(pair.private);update(it);sign()}},now,BigInteger("1234567890123"))
        val cert=java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as java.security.cert.X509Certificate
        cert.verify(pair.public);cert.checkValidity(now)
        assertEquals(3,cert.version);assertEquals("SHA256withRSA",cert.sigAlgName);assertEquals(BigInteger("1234567890123"),cert.serialNumber)
        assertEquals("CN=RetroTV Pad",cert.subjectX500Principal.name);assertEquals(cert.subjectX500Principal,cert.issuerX500Principal)
        assertEquals(0,cert.basicConstraints);assertArrayEquals(pair.public.encoded,cert.publicKey.encoded)
        assertTrue(cert.notBefore.before(now));assertTrue(cert.notAfter.after(java.util.Date(now.time+9L*365*24*3600*1000)))
    }
    @Test fun pairingSecretUsesMinimalModulusAndPaddedExponent(){
        fun key(n:String,e:Long)=object:RSAPublicKey{override fun getModulus()=BigInteger(n,16);override fun getPublicExponent()=BigInteger.valueOf(e);override fun getAlgorithm()="RSA";override fun getFormat()=null;override fun getEncoded()=null}
        val client=key("C0FFEE",65537);val server=key("BEEF",3)
        val bytes=listOf("C0FFEE","010001","BEEF","03","1234").joinToString("").chunked(2).map{it.toInt(16).toByte()}.toByteArray()
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes)
        val code="%02X1234".format(hash[0].toInt() and 0xFF)
        assertArrayEquals(hash,GoogleTvKeys.secret(client,server,code))
        val wrong="%02X1234".format((hash[0].toInt()+1) and 0xFF)
        assertNull(GoogleTvKeys.secret(client,server,wrong))
    }
}
