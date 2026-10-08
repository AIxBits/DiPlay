package local.airuize.receiver

import com.shilapi.xcertplay.transport.*
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.tls.*
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/** Dedicated USB Lockdown TLS; never use its pairing trust policy for internet connections. */
class LegacyLockdownTls private constructor(private val underlying:BlockingDuplexByteStream,
    private val protocol:TlsClientProtocol) : BlockingDuplexByteStream {
    private val engineLock=Any()
    private val readLock=Any()
    private val closed=AtomicBoolean(false)
    @Volatile private var failure:Exception?=null
    private fun checkOpen() { failure?.let { throw it }; check(!closed.get()) { "USB TLS closed" } }
    private fun flush() {
        while(protocol.availableOutputBytes>0) {
            val buffer=ByteArray(minOf(16384,protocol.availableOutputBytes))
            val size=protocol.readOutput(buffer,0,buffer.size)
            check(size>0) { "USB TLS output made no progress" }
            underlying.send(if(size==buffer.size) buffer else buffer.copyOf(size))
        }
    }
    private fun fail(error:Exception):Exception {
        val result=if(error is IphoneUsbException) error else IphoneUsbException.Protocol("USB TLS failure: ${error.javaClass.simpleName}: ${error.message?.take(160)}",error)
        failure=result
        return result
    }
    override fun send(data:ByteArray) { synchronized(engineLock) {
        checkOpen()
        if(data.isEmpty()) return
        try { protocol.writeApplicationData(data,0,data.size); flush() } catch(error:Exception) { throw fail(error) }
    } }
    override fun recv(maxBytes:Int,timeoutMillis:Long):ByteArray? = synchronized(readLock) read@ {
        require(maxBytes>0 && timeoutMillis>0)
        val deadline=System.nanoTime()+timeoutMillis.coerceAtMost(86400000L)*1000000
        try {
            while(true) {
                synchronized(engineLock) {
                    checkOpen()
                    if(protocol.availableInputBytes>0) {
                        val bytes=ByteArray(minOf(maxBytes,protocol.availableInputBytes))
                        val count=protocol.readInput(bytes,0,bytes.size)
                        return@read if(count==bytes.size) bytes else bytes.copyOf(count)
                    }
                    if(protocol.isClosed) return@read ByteArray(0)
                    Unit
                }
                val remaining=(deadline-System.nanoTime())/1000000
                if(remaining<=0) return@read null
                val incoming=underlying.recv(16384,remaining) ?: return@read null
                if(incoming.isEmpty()) throw IphoneUsbException.Protocol("USB TLS transport ended without close_notify")
                synchronized(engineLock) { checkOpen(); protocol.offerInput(incoming); flush() }
            }
            @Suppress("UNREACHABLE_CODE") null
        } catch(error:Exception) { throw fail(error) }
    }
    override fun close() {
        if(!closed.compareAndSet(false,true)) return
        try { synchronized(engineLock) { runCatching { protocol.close(); flush() } } }
        finally { underlying.close() }
    }
    companion object {
        fun open(underlying:BlockingDuplexByteStream,pairRecord:LockdownPairRecord,handshakeTimeoutMillis:Long):BlockingDuplexByteStream {
            require(handshakeTimeoutMillis>0)
            val protocol=TlsClientProtocol()
            val channel=LegacyLockdownTls(underlying,protocol)
            val keyPem=pairRecord.rootPrivateKeyPem
            val certPem=pairRecord.rootCertificatePem
            var keyDer:ByteArray?=null
            var certDer:ByteArray?=null
            try {
                fun decode(bytes:ByteArray,label:String):ByteArray {
                    val text=String(bytes,Charsets.US_ASCII)
                    val begin="-----BEGIN $label-----";val end="-----END $label-----"
                    require(text.contains(begin) && text.contains(end)) { "Invalid USB pairing PEM" }
                    return org.bouncycastle.util.encoders.Base64.decode(text.substringAfter(begin).substringBefore(end))
                }
                keyDer=decode(keyPem,"PRIVATE KEY");certDer=decode(certPem,"CERTIFICATE")
                val key=PrivateKeyFactory.createKey(keyDer)
                val crypto=BcTlsCrypto(SecureRandom())
                val chain=Certificate(arrayOf(crypto.createCertificate(certDer)))
                val client=object:DefaultTlsClient(crypto) {
                    override fun getSupportedVersions()=arrayOf(ProtocolVersion.TLSv12)
                    override fun getAuthentication()=object:TlsAuthentication {
                        // Same dedicated USB peer trust boundary as upstream LockdownTlsEngineFactory.
                        override fun notifyServerCertificate(serverCertificate:TlsServerCertificate) = Unit
                        override fun getClientCredentials(request:CertificateRequest):TlsCredentials? {
                            if(!request.certificateTypes.contains(ClientCertificateType.rsa_sign)) return null
                            val offered=request.supportedSignatureAlgorithms
                            val candidates=listOf(HashAlgorithm.sha256,HashAlgorithm.sha384,HashAlgorithm.sha512).map { SignatureAndHashAlgorithm(it,SignatureAlgorithm.rsa) }
                            val algorithm=candidates.firstOrNull { offered==null || offered.contains(it) }
                                ?: throw IphoneUsbException.Protocol("USB TLS peer offered no compatible RSA signature")
                            return BcDefaultTlsCredentialedSigner(TlsCryptoParameters(context),crypto,key,chain,algorithm)
                        }
                    }
                }
                val deadline=System.nanoTime()+handshakeTimeoutMillis.coerceAtMost(86400000L)*1000000
                protocol.connect(client);channel.flush()
                while(!protocol.isConnected) {
                    val remaining=(deadline-System.nanoTime())/1000000
                    if(remaining<=0) throw IphoneUsbException.TimedOut("USB TLS handshake timed out")
                    val incoming=underlying.recv(16384,remaining) ?: throw IphoneUsbException.TimedOut("USB TLS handshake timed out")
                    if(incoming.isEmpty()) throw IphoneUsbException.Protocol("USB TLS transport ended during handshake")
                    protocol.offerInput(incoming);channel.flush()
                }
                return channel
            } catch(error:Exception) {
                runCatching { channel.close() }
                throw channel.fail(error)
            } finally { keyPem.fill(0);certPem.fill(0);keyDer?.fill(0);certDer?.fill(0) }
        }
    }
}
