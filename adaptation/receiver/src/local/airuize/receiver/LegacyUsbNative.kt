package local.airuize.receiver

/** Small ARM EABI bridge, invoked only for wired USB interface activation. */
object LegacyUsbNative {
    private val loaded by lazy { System.loadLibrary("diplay_usb"); true }
    private external fun setInterface(fd:Int, interfaceId:Int, alternate:Int):Int
    fun select(fd:Int, interfaceId:Int, alternate:Int):Int {
        check(loaded)
        return setInterface(fd,interfaceId,alternate)
    }
}
