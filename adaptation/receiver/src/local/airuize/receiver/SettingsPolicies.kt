package local.airuize.receiver
/** API18 equivalent of upstream PHONE / USAGE_VOICE_COMMUNICATION attributes.
 * Keep Siri and media on their existing routes; zero is a valid phone stream.
 */
object LegacyAudioRoutePolicy {
    const val VOICE_CALL = 0
    const val MUSIC = 3
    fun automatic(kind:String,mappedStream:Int)=if(kind=="PHONE") VOICE_CALL else mappedStream
    fun focusStream(kind:String)=if(kind=="PHONE") VOICE_CALL else MUSIC
}
object AudioOverridePolicy {
    fun sanitize(value:Int)=if(value in 0..20) value else 0
    fun resolve(kind:String,automatic:Int,media:Int,navigation:Int):Int {
        val selected=when(kind) { "MEDIA"->sanitize(media); "NAVIGATION"->sanitize(navigation); else->0 }
        return if(selected==0) automatic else selected
    }
}
object ReportFolderPolicy {
    fun valid(value:String)=value.isNotBlank() && value.length<=120 && !value.startsWith("/") && '\\' !in value && value.split('/').all { it.isNotBlank() && it!="." && it!=".." && it.none { c->c<' ' } }
}

object UsbConfigurationPolicy {
    // API18 ARM Linux: _IOR('U', 5, unsigned int), unsigned int size=4.
    const val SET_CONFIGURATION_IOCTL = -2147199739 // 0x80045505
    fun confirmed(requested:Int,ioctlSucceeded:Boolean,reported:Int?) =
        ioctlSucceeded && (reported==null || reported==requested)
}
