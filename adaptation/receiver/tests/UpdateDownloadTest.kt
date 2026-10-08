package local.airuize.receiver
import org.junit.Test
import org.junit.Assert.*
class UpdateDownloadTest {
    private val body=ByteArray(40000){it.toByte()}
    private val hash=java.security.MessageDigest.getInstance("SHA-256").digest(body).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun copy(bytes:ByteArray=body,size:Long?=body.size.toLong(),checksum:String=hash,cancel:Boolean=false):ByteArray {
        val output=java.io.ByteArrayOutputStream()
        UpdateDownload.copy(bytes.inputStream(),output,size,checksum,java.util.concurrent.atomic.AtomicBoolean(cancel)) {}
        return output.toByteArray()
    }
    @Test fun streamsAndVerifiesWithOrWithoutDeclaredSize() {assertArrayEquals(body,copy());assertArrayEquals(body,copy(size=null))}
    @Test fun rejectsTruncatedCorruptAndOversizeData() {
        for(action in listOf<()->Unit>({copy(body.copyOf(100))},{copy(checksum="0".repeat(64))},{copy(size=100)})) {
            try {action();fail("invalid update accepted")} catch(_:IllegalArgumentException) {}
        }
    }
    @Test fun cancellationStopsTransfer() {try {copy(cancel=true);fail("cancel ignored")}catch(_:java.io.InterruptedIOException){}}
}
