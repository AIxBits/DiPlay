package local.airuize.receiver

import java.util.concurrent.ConcurrentHashMap

/** Structural counters only. Observe matching UDP flow before/after TUN and decryption. */
class LegacyAudioIngress {
    private var direct=0L;private var directGaps=0L;private var directSeq:Int?=null
    @Synchronized fun directReceived(packet:ByteArray) {
        direct++;if(packet.size<12 || (packet[0].toInt() and 192)!=128)return
        val seq=((packet[2].toInt() and 255) shl 8) or (packet[3].toInt() and 255)
        val prior=directSeq;val delta=if(prior==null)1 else (seq-prior) and 65535
        if(delta in 1..32767){directGaps+=delta-1;directSeq=seq}
    }
    private var wire=0L;private var delivered=0L;private var udp=0L;private var decoded=0L
    private var rejected=0L;private var ncmGaps=0L;private var udpGaps=0L
    private var wireSeq:Int?=null;private var udpSeq:Int?=null
    @Volatile private var port=0
    private var ncmDatagrams=0L;private var tunDatagrams=0L;private var ncmBytes=0L;private var udpBytes=0L
    private var ncmLate=0L;private var udpLate=0L
    private var malformedDatagrams=0L
    private var ncmFirst:Int?=null;private var udpFirst:Int?=null
    private var logMaxUs=0L;private var logSumUs=0L;private var logCalls=0L
    private var decryptMaxUs=0L;private var decryptSumUs=0L
    @Synchronized fun ncm(sequence:Int, written:Boolean) {
        if(written) {delivered++;return}
        wire++
        val previous=wireSeq
        val delta=if(previous==null) 1 else (sequence-previous) and 65535
        if(ncmFirst==null)ncmFirst=sequence
        if(delta in 1..32767) {ncmGaps+=delta-1;wireSeq=sequence}
        else ncmLate++
    }
    @Synchronized private fun datagram(bytes:Int,sequence:Int?,written:Boolean) {
        if(written)tunDatagrams++ else {ncmDatagrams++;ncmBytes+=bytes}
        if(sequence!=null)ncm(sequence,written)
    }
    @Synchronized private fun malformed(){malformedDatagrams++}
    @Synchronized fun received(packet:ByteArray) {
        udp++
        udpBytes+=packet.size
        if(packet.size<12 || (packet[0].toInt() and 192)!=128)return
        val seq=((packet[2].toInt() and 255) shl 8) or (packet[3].toInt() and 255)
        val previous=udpSeq
        val delta=if(previous==null) 1 else (seq-previous) and 65535
        if(udpFirst==null)udpFirst=seq
        if(delta in 1..32767) {udpGaps+=delta-1;udpSeq=seq}
        else udpLate++
    }
    @Synchronized fun decrypted(success:Boolean,durationNs:Long) {
        if(success)decoded++ else rejected++
        val micros=durationNs.coerceAtLeast(0)/1000
        decryptMaxUs=maxOf(decryptMaxUs,micros);decryptSumUs+=micros
    }
    @Synchronized fun reported(durationNs:Long) {val micros=durationNs.coerceAtLeast(0)/1000;logCalls++;logSumUs+=micros;logMaxUs=maxOf(logMaxUs,micros)}
    @Synchronized fun summary():String="ncm=$wire tunWritten=$delivered udp=$udp decoded=$decoded rejected=$rejected ncmGaps=$ncmGaps udpGaps=$udpGaps decryptMaxUs=$decryptMaxUs decryptMeanUs=${decryptSumUs/maxOf(1L,decoded+rejected)} logMaxUs=$logMaxUs logMeanUs=${logSumUs/maxOf(1L,logCalls)} port=$port ncmDatagrams=$ncmDatagrams tunDatagrams=$tunDatagrams ncmPayloadBytes=$ncmBytes udpPayloadBytes=$udpBytes ncmMalformedDatagrams=$malformedDatagrams ncmFirst=${ncmFirst ?: -1} ncmLast=${wireSeq ?: -1} udpFirst=${udpFirst ?: -1} udpLast=${udpSeq ?: -1} ncmLateOrDuplicate=$ncmLate udpLateOrDuplicate=$udpLate direct=$direct directGaps=$directGaps"
    companion object {
        private val flows=ConcurrentHashMap<Int,LegacyAudioIngress>()
        fun activePorts():Set<Int> = flows.keys.toSet()
        fun register(port:Int,probe:LegacyAudioIngress) {probe.port=port;flows[port]=probe}
        fun unregister(port:Int,probe:LegacyAudioIngress) { flows.remove(port,probe) }
        /** Complete datagrams, including bounded extension chains; no fragment reassembly. */
        fun observe(frame:ByteArray,offset:Int,length:Int,written:Boolean) {
            val parsed=Ipv6TransportView.parse(frame,offset,length)
            if(!written)VideoDiagnosticGate.observe(frame,parsed)
            val view=parsed ?: return
            if(view.protocol!=17 || view.offset+4>view.end)return
            fun u16(at:Int)=((frame[at].toInt() and 255) shl 8) or (frame[at+1].toInt() and 255)
            val at=view.offset;val probe=flows[u16(at+2)] ?: return
            if(at+8>view.end) {if(!written)probe.malformed();return}
            val udpLength=u16(at+4)
            if(udpLength<8 || at+udpLength>view.end) {if(!written)probe.malformed();return}
            val rtp=at+8
            val seq=if(udpLength>=20 && (frame[rtp].toInt() and 192)==128)u16(rtp+2) else null
            probe.datagram(udpLength-8,seq,written)
        }
    }
}
