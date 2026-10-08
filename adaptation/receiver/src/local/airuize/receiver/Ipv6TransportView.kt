package local.airuize.receiver

/** Locate complete UDP/TCP datagrams through bounded IPv6 extension chains. No reassembly. */
data class Ipv6TransportView(val protocol:Int,val offset:Int,val end:Int) {
    companion object {
        fun parse(frame:ByteArray,start:Int,length:Int):Ipv6TransportView? {
            if(start<0 || length<40 || start>frame.size-length || (frame[start].toInt() and 240)!=96)return null
            fun byte(at:Int)=frame[at].toInt() and 255
            val end=start+40+((byte(start+4) shl 8) or byte(start+5))
            if(end>start+length)return null
            var next=byte(start+6);var at=start+40
            repeat(8) {
                when(next) {
                    6,17 -> return Ipv6TransportView(next,at,end)
                    0,43,60 -> {
                        if(at+2>end)return null
                        val size=(byte(at+1)+1)*8
                        if(at+size>end)return null
                        next=byte(at);at+=size
                    }
                    44 -> {
                        if(at+8>end || byte(at+2)!=0 || byte(at+3)!=0)return null // Only atomic fragments.
                        next=byte(at);at+=8
                    }
                    else -> return Ipv6TransportView(next,at,end)
                }
            }
            return null
        }
    }
}
