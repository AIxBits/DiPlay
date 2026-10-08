package local.airuize.receiver

import com.shilapi.xcertplay.transport.Ntb16Codec
import java.io.IOException

/** NTB lengths delimit blocks; a USB short transfer or ZLP need not supply a pad byte. */
class LegacyNtb16Assembler(private val report:(String)->Unit = {}) {
    private var buffered=ByteArray(0)
    private var optionalPad=false
    private var padded=0L
    private var unpadded=0L
    private val context=java.util.ArrayDeque<String>()
    private var chunks=0L
    private var blocks=0L
    private var framesTotal=0L
    private var emptyBlocks=0L
    private var bytes=0L
    private var lastSummary=0L
    private fun remember(message:String) {
        if(context.size==32) context.removeFirst()
        context.addLast(message)
    }
    private fun failure(reason:String):Nothing {
        report("NCM evidence failure: $reason; chunks=$chunks bytes=$bytes blocks=$blocks frames=$framesTotal buffered=${buffered.size} optionalPad=$optionalPad zeroPads=$padded noPad=$unpadded\n"+
            context.joinToString("\n") { "NCM context: $it" })
        throw IOException(reason)
    }
    fun accept(source:ByteArray,length:Int=source.size):List<ByteArray> {
        require(length in 0..source.size)
        chunks++;bytes+=length
        remember("chunk=$chunks length=$length retained=${buffered.size} optionalPad=$optionalPad")
        if(buffered.size+length>131072) failure("NCM NTB assembly exceeded bound")
        buffered+=source.copyOf(length)
        val result=ArrayList<ByteArray>()
        var offset=0
        while(offset<buffered.size) {
            if(optionalPad) {
                if(buffered[offset].toInt()==0) { offset++;padded++;optionalPad=false }
                else {
                    // Validate the next NTH before accepting a missing optional pad.
                    if(buffered.size-offset<4) break
                    if(u32(offset)!=Ntb16Codec.NTH16_SIG) failure("NCM invalid NTB header after optional USB pad")
                    unpadded++;optionalPad=false
                }
                remember("boundary offset=$offset zeroPads=$padded noPad=$unpadded")
                if(padded+unpadded==1L || (padded+unpadded)%100L==0L)
                    report("NCM NTB boundary: zeroPads=$padded noPad=$unpadded; NTB block length authoritative")
            }
            if(buffered.size-offset<12) break
            if(u32(offset)!=Ntb16Codec.NTH16_SIG) failure("NCM read buffer does not begin with an NTB16 header")
            val lengthHere=u16(offset+8)
            val headerLength=u16(offset+4)
            if(headerLength!=12 || lengthHere<28) failure("NCM invalid NTB16 lengths header=$headerLength block=$lengthHere")
            val sequence=u16(offset+6)
            val ndp=u16(offset+10)
            remember("NTH chunk=$chunks offset=$offset sequence=$sequence header=$headerLength block=$lengthHere ndp=$ndp available=${buffered.size-offset}")
            if(buffered.size-offset<lengthHere) { remember("wait block missing=${lengthHere-(buffered.size-offset)}");break }
            val frames=Ntb16Codec.parse(buffered,offset,lengthHere)
            blocks++;framesTotal+=frames.size
            // Only interpret the first NDP after validating its signature and bounds.
            val table=offset+ndp
            if(ndp>=12 && ndp<=lengthHere-12 && (u32(table) and 0x00ffffff)==(Ntb16Codec.NDP16_SIG and 0x00ffffff)) {
                remember("NDP block=$blocks length=${u16(table+4)} next=${u16(table+6)} firstIndex=${u16(table+8)} firstLength=${u16(table+10)} parsedFrames=${frames.size}")
            } else remember("NDP block=$blocks invalidOrUnavailable parsedFrames=${frames.size}")
            if(frames.isEmpty()) emptyBlocks++
            if(frames.isEmpty() && (emptyBlocks==1L || emptyBlocks%1000L==0L)) report("NCM evidence empty block: sequence=$sequence length=$lengthHere ndp=$ndp; upstream parser returned no frames")
            result.addAll(frames)
            offset+=lengthHere
            optionalPad=lengthHere%512==0
        }
        if(offset>0) buffered=buffered.copyOfRange(offset,buffered.size)
        val now=System.nanoTime()
        if(lastSummary==0L || now-lastSummary>=10_000_000_000L) {
            lastSummary=now
            report("NCM evidence summary: chunks=$chunks bytes=$bytes blocks=$blocks frames=$framesTotal empty=$emptyBlocks retained=${buffered.size} optionalPad=$optionalPad zeroPads=$padded noPad=$unpadded\n"+
                context.toList().takeLast(4).joinToString("\n") { "NCM context: $it" })
        }
        return result
    }
    private fun u16(offset:Int)=(buffered[offset].toInt() and 255) or ((buffered[offset+1].toInt() and 255) shl 8)
    private fun u32(offset:Int)=u16(offset) or (u16(offset+2) shl 16)
}
