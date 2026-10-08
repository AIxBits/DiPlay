package local.airuize.receiver
import java.net.InetAddress
import java.net.Inet6Address
object LegacyMicrophoneBind {
    fun local(peer:InetAddress,local:InetAddress?):InetAddress {
        if(peer !is Inet6Address) return InetAddress.getByName("0.0.0.0")
        require(local is Inet6Address && !local.isAnyLocalAddress && local!=peer) { "Wired microphone requires receiver IPv6 address" }
        return local
    }
}
