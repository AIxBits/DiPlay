package local.airuize.receiver
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/** Audio-only optimization; pairing, control crypto and packet authentication are unchanged. */
class LegacyAudioCrypto(private val key:ByteArray) {
    init {require(key.size==32)}
    private val cipher=ChaCha20Poly1305()
    private val keyParameter=KeyParameter(key)
    val backend:String get()=if(nativeLoaded) "ARM-native RFC8439" else "BC-reused RFC8439"
    private external fun openNative(key:ByteArray,wire:ByteArray):ByteArray?
    fun open(wire:ByteArray):ByteArray {
        require(wire.size in 36..4096)
        if(nativeLoaded) return openNative(key,wire) ?: throw java.io.IOException("Audio authentication rejected")
        val nonce=ByteArray(12);wire.copyInto(nonce,4,wire.size-8,wire.size)
        cipher.init(false,AEADParameters(keyParameter,128,nonce,wire.copyOfRange(4,12)))
        val result=ByteArray(wire.size-24);wire.copyInto(result,0,0,12)
        val processed=cipher.processBytes(wire,12,wire.size-20,result,12)
        val finished=cipher.doFinal(result,12+processed)
        check(processed+finished==result.size-12)
        return result
    }
    companion object {
        private val nativeLoaded:Boolean=try {System.loadLibrary("diplay_usb");true} catch(_:LinkageError) {false}
    }
}
