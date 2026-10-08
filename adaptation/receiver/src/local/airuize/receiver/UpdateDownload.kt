package local.airuize.receiver

/** Streaming download verification; no publication or log-upload credential is used. */
object UpdateDownload {
    fun copy(input:java.io.InputStream,output:java.io.OutputStream,size:Long?,hash:String,
        cancelled:java.util.concurrent.atomic.AtomicBoolean,progress:(Long)->Unit):Long {
        require(hash.matches(Regex("[0-9a-fA-F]{64}")))
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        val buffer=ByteArray(16384);var count=0L
        while(true) {
            if(cancelled.get())throw java.io.InterruptedIOException("Download cancelled")
            val n=input.read(buffer);if(n<0)break;if(n==0)continue
            count+=n
            require(count<=33554432L && (size==null || count<=size)) {"Download exceeds expected limit"}
            digest.update(buffer,0,n);output.write(buffer,0,n);progress(count)
        }
        require(count>0 && (size==null || count==size)) {"Incomplete download"}
        val actual=digest.digest().joinToString("") {"%02x".format(it.toInt() and 255)}
        require(actual.equals(hash,true)) {"Download checksum mismatch"}
        return count
    }
}
