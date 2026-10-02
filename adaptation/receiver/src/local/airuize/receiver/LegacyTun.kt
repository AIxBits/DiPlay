package local.airuize.receiver
import java.net.InetAddress
import java.net.Inet6Address
import java.io.IOException
object LegacyBindAddress { fun any(address:InetAddress?):InetAddress=InetAddress.getByName(if(address is Inet6Address) "::" else "0.0.0.0") }
object LegacyTun {
    // VpnService SDK18 produces O_NONBLOCK descriptors; only EAGAIN/EWOULDBLOCK is retried.
    fun isWouldBlock(error:IOException):Boolean {
        var cause:Throwable?=error
        while(cause!=null) {
            if(cause.javaClass.name=="libcore.io.ErrnoException") {
                val errno=runCatching { cause!!.javaClass.getField("errno").getInt(cause) }.getOrNull()
                if(errno==11) return true
            }
            cause=cause.cause
        }
        return false
    }
}
