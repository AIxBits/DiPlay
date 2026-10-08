#include <stddef.h>
/* No libc dependency on API18; volatile prevents recursive builtin lowering. */
void *memcpy(void *target,const void *source,size_t length) {
    volatile unsigned char *to=target;const unsigned char *from=source;
    for(size_t i=0;i<length;i++)to[i]=from[i];return target;
}
void *memset(void *target,int value,size_t length) {
    volatile unsigned char *to=target;
    for(size_t i=0;i<length;i++)to[i]=(unsigned char)value;return target;
}
void __aeabi_memclr(void *target,size_t length) {memset(target,0,length);}
void __aeabi_memclr4(void *target,size_t length) {memset(target,0,length);}
void __aeabi_memclr8(void *target,size_t length) {memset(target,0,length);}
void __aeabi_memcpy(void *target,const void *source,size_t length) {memcpy(target,source,length);}
void __aeabi_memcpy4(void *target,const void *source,size_t length) {memcpy(target,source,length);}
void __aeabi_memcpy8(void *target,const void *source,size_t length) {memcpy(target,source,length);}
