#include <assert.h>
#include <stdio.h>
static int calls, outcome;
int test_ioctl(int fd, unsigned int request, void *argument) {
    unsigned int *values = argument;
    assert(fd == 17 && request == 0x80085504u);
    assert(values[0] == 4 && values[1] == 1);
    calls++;
    return outcome;
}
extern int Java_local_airuize_receiver_LegacyUsbNative_setInterface(void *, void *, int, int, int);
#define call(fd, iface, alt) Java_local_airuize_receiver_LegacyUsbNative_setInterface(0, 0, fd, iface, alt)
int main(void) {
    assert(call(17, 4, 1) == 0 && calls == 1);
    outcome = -16; /* EBUSY */
    assert(call(17, 4, 1) == -16 && calls == 2);
    assert(call(-1, 4, 1) == -22);
    assert(call(17, -1, 1) == -22);
    assert(call(17, 256, 1) == -22);
    assert(call(17, 4, -1) == -22);
    assert(call(17, 4, 256) == -22);
    assert(calls == 2);
    puts("PASS: USB ioctl code, two-field layout, errno preservation and invalid argument rejection");
}
