/* Audio-only RFC8439 backend. Protocol/key/nonce/AAD match upstream exactly. */
#include <jni.h>
#include "vendor/monocypher/monocypher.h"
JNIEXPORT jbyteArray JNICALL
Java_local_airuize_receiver_LegacyAudioCrypto_openNative(JNIEnv *env, jobject self,
                                                        jbyteArray key_array, jbyteArray wire_array) {
    (void)self;
    if (!key_array || !wire_array) return 0;
    jsize size=(*env)->GetArrayLength(env,wire_array);
    if ((*env)->GetArrayLength(env,key_array)!=32 || size<36 || size>4096) return 0;
    unsigned char key[32],wire[4096],output[4096],nonce[12]={0};
    crypto_aead_ctx context;
    (*env)->GetByteArrayRegion(env,key_array,0,32,(jbyte *)key);
    (*env)->GetByteArrayRegion(env,wire_array,0,size,(jbyte *)wire);
    if ((*env)->ExceptionCheck(env)) {crypto_wipe(key,sizeof(key));crypto_wipe(wire,sizeof(wire));return 0;}
    for(int i=0;i<8;i++)nonce[i+4]=wire[size-8+i];
    /* Fresh context per datagram: do not use Monocypher's multi-chunk ratchet. */
    crypto_aead_init_ietf(&context,key,nonce);
    int failed=crypto_aead_read(&context,output+12,wire+size-24,wire+4,8,wire+12,(size_t)size-36);
    jbyteArray result=0;
    if (!failed) {
        for(int i=0;i<12;i++)output[i]=wire[i];
        result=(*env)->NewByteArray(env,size-24);
        if(result)(*env)->SetByteArrayRegion(env,result,0,size-24,(jbyte *)output);
    }
    crypto_wipe(&context,sizeof(context));crypto_wipe(key,sizeof(key));
    crypto_wipe(wire,sizeof(wire));crypto_wipe(output,sizeof(output));crypto_wipe(nonce,sizeof(nonce));
    return result;
}
