package android.util;
/** JVM-only adapter for Android Base64; production uses the platform implementation. */
public final class Base64 {
    public static final int DEFAULT=0, NO_WRAP=2;
    public static byte[] decode(String value,int flags) { return java.util.Base64.getMimeDecoder().decode(value); }
    public static byte[] decode(byte[] value,int flags) { return java.util.Base64.getMimeDecoder().decode(value); }
    public static String encodeToString(byte[] value,int flags) { return java.util.Base64.getEncoder().encodeToString(value); }
}
