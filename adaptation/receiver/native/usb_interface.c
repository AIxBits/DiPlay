/* ARM EABI Linux USBDEVFS_SETINTERFACE adapter. No libc or Android API imports.
 * JNI jint is a signed 32-bit integer; env/class are opaque, unused pointers.
 * Borrow the permission-granted UsbDeviceConnection fd only during this call.
 */
struct usbdevfs_setinterface { unsigned int interface; unsigned int altsetting; };
_Static_assert(sizeof(unsigned int) == 4, "USB ioctl requires 32-bit fields");
_Static_assert(sizeof(struct usbdevfs_setinterface) == 8, "USB ioctl argument size");
#define SETINTERFACE_IOCTL 0x80085504u
#ifdef DIPLAY_NATIVE_TEST
extern int test_ioctl(int, unsigned int, void *);
#else
static int test_ioctl(int fd, unsigned int request, void *argument) {
    register int r0 __asm__("r0") = fd;
    register unsigned int r1 __asm__("r1") = request;
    register void *r2 __asm__("r2") = argument;
    register int r7 __asm__("r7") = 54; /* __NR_ioctl, ARM EABI */
    __asm__ volatile("svc 0" : "+r"(r0) : "r"(r1), "r"(r2), "r"(r7) : "memory", "cc");
    return r0; /* Kernel returns -errno directly; preserve it for diagnostics. */
}
#endif
__attribute__((visibility("default"))) int
Java_local_airuize_receiver_LegacyUsbNative_setInterface(void *env, void *clazz,
                                                       int fd, int interface, int alternate) {
    (void)env; (void)clazz;
    if (fd < 0 || interface < 0 || interface > 255 || alternate < 0 || alternate > 255)
        return -22; /* EINVAL */
    struct usbdevfs_setinterface value = {(unsigned int)interface, (unsigned int)alternate};
    return test_ioctl(fd, SETINTERFACE_IOCTL, &value);
}
