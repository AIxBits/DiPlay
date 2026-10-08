package local.airuize.receiver
import java.net.InetAddress
import java.net.Inet6Address
import java.io.IOException
object LegacyBindAddress {
    fun any(address:InetAddress?):InetAddress {
        if(address is Inet6Address) {
            require(!address.isAnyLocalAddress) { "API18 IPv6 listener requires a concrete scoped address" }
            return address
        }
        return InetAddress.getByName("0.0.0.0")
    }
    fun wired(literal:String,cancelled:()->Boolean,log:(String)->Unit):Inet6Address {
        val parsed=InetAddress.getByName(literal)
        require(parsed is Inet6Address && parsed.isLinkLocalAddress)
        val deadline=System.nanoTime()+3_000_000_000L
        do {
            if(cancelled()) throw InterruptedException("USB stopped before IPv6 bind")
            val iface=java.net.NetworkInterface.getByInetAddress(parsed)
            if(iface!=null) {
                val scoped=Inet6Address.getByAddress(null,parsed.address,iface)
                if(scoped.scopeId>0) {
                    log("USB IPv6 bind ready: interface=${iface.name} scope=${scoped.scopeId}; concrete VPN address")
                    return scoped
                }
            }
            Thread.sleep(50)
        } while(System.nanoTime()<deadline)
        error("VPN IPv6 address has no interface scope after 3 seconds")
    }
}
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
