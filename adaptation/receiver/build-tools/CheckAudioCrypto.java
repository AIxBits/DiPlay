import java.util.Arrays;
import com.shilapi.xcertplay.airplay.AirPlayCrypto;
import local.airuize.receiver.LegacyAudioCrypto;

/** Synthetic data only. Host timing is diagnostic, not an i.MX6 performance claim. */
public final class CheckAudioCrypto {
    public static void main(String[] args) throws Exception {
        byte[] key = new byte[32], nonce = new byte[12], aad = new byte[8];
        byte[] plain = new byte[1416];
        for (int i = 0; i < plain.length; i++) plain[i] = (byte)i;
        nonce[4] = 1;
        byte[] sealed = AirPlayCrypto.INSTANCE.chachaSeal(key, nonce, plain, aad);
        byte[] wire = new byte[12 + sealed.length + 8];
        System.arraycopy(sealed, 0, wire, 12, sealed.length);
        System.arraycopy(nonce, 4, wire, wire.length - 8, 8);
        LegacyAudioCrypto crypto = new LegacyAudioCrypto(key);
        if (args.length > 0 && !crypto.getBackend().startsWith(args[0]))
            throw new AssertionError("Unexpected backend: " + crypto.getBackend());
        for (int index : new int[]{4, 12, wire.length - 24, wire.length - 1}) {
            byte[] bad = wire.clone(); bad[index] ^= 1;
            boolean rejected = false;
            try { crypto.open(bad); } catch (Exception expected) { rejected = true; }
            if (!rejected) throw new AssertionError("Accepted invalid authentication");
            if (!Arrays.equals(plain, Arrays.copyOfRange(crypto.open(wire), 12, 1428)))
                throw new AssertionError("Failed recovery after rejected packet");
        }
        for (int i = 0; i < 3000; i++) {
            crypto.open(wire);
            AirPlayCrypto.INSTANCE.chachaOpen(key, nonce, sealed, aad);
        }
        int count = 20000;
        long start = System.nanoTime();
        int checksum = 0;
        for (int i = 0; i < count; i++) checksum += crypto.open(wire)[13];
        long adapted = System.nanoTime() - start;
        start = System.nanoTime();
        for (int i = 0; i < count; i++) checksum += AirPlayCrypto.INSTANCE.chachaOpen(key, nonce, sealed, aad)[1];
        long original = System.nanoTime() - start;
        if (checksum != count * 2) throw new AssertionError("Wrong plaintext");
        System.out.printf("PASS %s authentication and recovery; host-only 1416-byte mean: adapted %.2f us, pinned upstream BC %.2f us%n",
            crypto.getBackend(), adapted / (count * 1000.0), original / (count * 1000.0));
    }
}
