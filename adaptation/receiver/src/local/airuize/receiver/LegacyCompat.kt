package local.airuize.receiver

import java.nio.charset.Charset

object LegacyCharsets {
    val UTF_8: Charset = Charset.forName("UTF-8")
    val US_ASCII: Charset = Charset.forName("US-ASCII")
}
object LegacyBase64 {
    fun getEncoder() = Encoder()
    fun getDecoder() = Decoder()
    fun getMimeDecoder() = Decoder()
    fun getMimeEncoder(lineLength: Int, separator: ByteArray) = Encoder(lineLength, separator)
    class Decoder {
        fun decode(value: ByteArray): ByteArray = android.util.Base64.decode(value, android.util.Base64.DEFAULT)
        fun decode(value: String): ByteArray = android.util.Base64.decode(value, android.util.Base64.DEFAULT) }
    class Encoder(private val lineLength: Int = 0, private val separator: ByteArray = ByteArray(0)) {
        fun encodeToString(bytes: ByteArray): String {
            val plain = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            return if(lineLength > 0) plain.chunked(lineLength).joinToString(separator.toString(Charsets.US_ASCII)) else plain
        }
    }
}
object LegacyNumbers {
    fun unsigned(value: Long): String = if (value >= 0) value.toString()
        else java.math.BigInteger.valueOf(value and Long.MAX_VALUE).setBit(63).toString()
    fun unsigned(value: Int): String = (value.toLong() and 0xffffffffL).toString()
    fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if ((a xor b) < 0 && q * b != a) q - 1 else q
    }
    fun floorMod(a: Long, b: Long): Long = a - floorDiv(a, b) * b
}
object LegacyCloseables {
    fun close(value: Any?) {
        when (value) {
            is java.net.Socket -> value.close()
            is java.net.ServerSocket -> value.close()
            is java.net.DatagramSocket -> value.close()
            is java.io.Closeable -> value.close()
        }
    }
}
