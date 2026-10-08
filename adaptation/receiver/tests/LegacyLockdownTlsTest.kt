package local.airuize.receiver

import com.shilapi.xcertplay.transport.*
import org.bouncycastle.asn1.pkcs.RSAPublicKey
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.tls.*
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Vector

class LegacyLockdownTlsTest {
    private fun pair():LockdownPairRecord {
        val key=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as java.security.interfaces.RSAPublicKey
        val encoded=org.bouncycastle.util.encoders.Base64.toBase64String(RSAPublicKey(key.modulus,key.publicExponent).encoded)
        return LockdownPairRecordGenerator.generate("-----BEGIN RSA PUBLIC KEY-----\n$encoded\n-----END RSA PUBLIC KEY-----".toByteArray(),"02:00:00:00:00:01","test-host","test-buid")
    }
    private fun der(bytes:ByteArray):ByteArray=org.bouncycastle.util.encoders.Base64.decode(String(bytes).lineSequence().filter { !it.startsWith("-----") }.joinToString(""))
    private class Peer(val record:LockdownPairRecord,val legacy:Boolean=false):BlockingDuplexByteStream {
        val protocol=TlsServerProtocol()
        var closed=0
        var rootAuthenticated=false
        init {
            val crypto=BcTlsCrypto(SecureRandom())
            val certificate=org.bouncycastle.util.encoders.Base64.decode(String(record.rootCertificatePem).lineSequence().filter { !it.startsWith("-----") }.joinToString(""))
            val key=PrivateKeyFactory.createKey(org.bouncycastle.util.encoders.Base64.decode(String(record.rootPrivateKeyPem).lineSequence().filter { !it.startsWith("-----") }.joinToString("")))
            val chain=Certificate(arrayOf(crypto.createCertificate(certificate)))
            protocol.accept(object:DefaultTlsServer(crypto) {
                override fun getSupportedVersions()=arrayOf(if(legacy) ProtocolVersion.TLSv10 else ProtocolVersion.TLSv12)
                override fun getRSASignerCredentials()=BcDefaultTlsCredentialedSigner(TlsCryptoParameters(context),crypto,key,chain,SignatureAndHashAlgorithm(HashAlgorithm.sha256,SignatureAlgorithm.rsa))
                override fun getCertificateRequest()=CertificateRequest(shortArrayOf(ClientCertificateType.rsa_sign),Vector<SignatureAndHashAlgorithm>().apply { add(SignatureAndHashAlgorithm(HashAlgorithm.sha256,SignatureAlgorithm.rsa)) },Vector<Any>())
                override fun notifyClientCertificate(client:Certificate) {
                    rootAuthenticated=client.length==1 && client.getCertificateAt(0).encoded.contentEquals(certificate)
                }
            })
        }
        override fun send(data:ByteArray) {
            if(protocol.isClosed) return // Transport may still accept the reciprocal close_notify.
            // Fragment every TLS record to exercise incremental parsing, not only full packets.
            for(offset in data.indices step 37) protocol.offerInput(data,offset,minOf(37,data.size-offset))
            while(protocol.availableInputBytes>0) {
                val plain=ByteArray(protocol.availableInputBytes)
                val size=protocol.readInput(plain,0,plain.size)
                protocol.writeApplicationData(plain,0,size)
            }
        }
        override fun recv(maxBytes:Int,timeoutMillis:Long):ByteArray? {
            if(protocol.availableOutputBytes==0) return null
            val data=ByteArray(minOf(maxBytes,31,protocol.availableOutputBytes))
            val count=protocol.readOutput(data,0,data.size)
            return data.copyOf(count)
        }
        override fun close() { closed++ }
    }
    @Test fun realTls12AuthenticatesRootIdentityAndPreservesFragmentedPlaintext() {
        val record=pair();val peer=Peer(record)
        val tls=LegacyLockdownTls.open(peer,record,5000)
        assertTrue(peer.rootAuthenticated)
        val plain=ByteArray(40000) { (it % 251).toByte() }
        tls.send(plain)
        val result=java.io.ByteArrayOutputStream()
        while(result.size()<plain.size) result.write(tls.recv(113,1000) ?: error("Missing echo"))
        assertArrayEquals(plain,result.toByteArray())
        assertNull(tls.recv(64,10))
        // Getters return copies: opening TLS must not erase persisted pairing material.
        assertTrue(record.rootPrivateKeyPem.isNotEmpty())
        peer.protocol.close()
        assertArrayEquals(ByteArray(0),tls.recv(64,1000))
        tls.close();tls.close();assertEquals(1,peer.closed)
    }
    @Test fun handshakeTimeoutClosesTransport() {
        var closed=0
        val dead=object:BlockingDuplexByteStream {
            override fun send(data:ByteArray) {}
            override fun recv(maxBytes:Int,timeoutMillis:Long):ByteArray?=null
            override fun close() { closed++ }
        }
        try { LegacyLockdownTls.open(dead,pair(),10);fail("Timeout accepted") } catch(_:IphoneUsbException.TimedOut) {}
        assertEquals(1,closed)
    }
    @Test fun tls10OnlyPeerIsRejectedWithoutDowngrade() {
        val record=pair();val peer=Peer(record,true)
        try { LegacyLockdownTls.open(peer,record,5000);fail("TLS1.0 accepted") } catch(_:IphoneUsbException.Protocol) {}
        assertEquals(1,peer.closed)
    }
}
