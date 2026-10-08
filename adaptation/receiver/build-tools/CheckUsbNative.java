import local.airuize.receiver.LegacyUsbNative;

/** JVM JNI linkage test against a host library with a synthetic ioctl backend. */
public final class CheckUsbNative {
    public static void main(String[] args) {
        if (LegacyUsbNative.INSTANCE.select(17, 4, 1) != 0)
            throw new AssertionError("JNI did not reach the synthetic ioctl");
        if (LegacyUsbNative.INSTANCE.select(-1, 4, 1) != -22)
            throw new AssertionError("JNI argument rejection was lost");
        System.out.println("PASS: Kotlin/JNI symbol linkage and signed errno roundtrip (host synthetic ioctl)");
    }
}
