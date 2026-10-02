package local.airuize.receiver

/** System Bluetooth pairing does not authorize starting a CarPlay session. */
object ConnectionStartPolicy {
    fun onOpen(autoOpen:Boolean, hasSession:Boolean, requested:Boolean)=autoOpen && !hasSession && !requested
    fun onServiceRestore(autoOpen:Boolean, bootConnect:Boolean, wanted:Boolean)=wanted && (autoOpen || bootConnect)
}
