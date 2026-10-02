package local.airuize.receiver

/** Codes verified in MCUKeyDefine; do not reuse BYD codes or infer a hold from one callback. */
object VoiceKeyPolicy {
    enum class Action { OPEN, SIRI }
    fun isMcuVoice(raw: Int): Boolean = raw in -128..255 && (raw and 255) in listOf(93, 221, 118)
    fun mcuAction(raw: Int): Action? = if(!isMcuVoice(raw)) null else if(raw and 255 == 221) Action.SIRI else Action.OPEN
    // KeyService adds 1000 to an unmapped signed byte; MCU221 is signed -35 -> key965.
    fun isWindowVoice(code: Int): Boolean = code in listOf(219, 231, 1093, 965, 1221, 1118)
    fun windowAction(code: Int, heldMillis: Long, thresholdMillis: Long): Action? = if(!isWindowVoice(code)) null
        else if(code in listOf(965,1221) || heldMillis >= thresholdMillis.coerceAtLeast(1)) Action.SIRI else Action.OPEN
}
class VoiceKeyGate {
    private var lastOpen = -1L
    private var lastSiri = -1L
    @Synchronized fun accept(now: Long, action: VoiceKeyPolicy.Action = VoiceKeyPolicy.Action.OPEN): Boolean {
        if(now<0) return false
        fun recent(value:Long)=value>=0 && now-value<600
        when(action) {
            VoiceKeyPolicy.Action.OPEN -> { if(recent(lastOpen) || recent(lastSiri)) return false; lastOpen=now }
            VoiceKeyPolicy.Action.SIRI -> { if(recent(lastSiri)) return false; lastSiri=now }
        }
        return true
    }
}
