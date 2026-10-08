import com.shilapi.xcertplay.transport.*;
import local.airuize.receiver.LegacyLockdownTls;
import javax.net.ssl.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.security.spec.*;
import java.net.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real JDK TLS peer against the post-shrink API18 client, synthetic credentials only. */
public final class CheckLockdownTls {
    public static void main(String[] args) throws Exception {
        byte[] keyPem=Files.readAllBytes(Paths.get(args[0])), certPem=Files.readAllBytes(Paths.get(args[1]));
        String encoded=new String(keyPem,"US-ASCII").replaceAll("-----[^\\n]+-----", "").replaceAll("\\s", "");
        PrivateKey key=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(java.util.Base64.getDecoder().decode(encoded)));
        X509Certificate cert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certPem));
        KeyStore store=KeyStore.getInstance("JKS");store.load(null,null);
        char[] password="synthetic-test".toCharArray();
        store.setKeyEntry("identity",key,password,new java.security.cert.Certificate[]{cert});
        KeyManagerFactory managers=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());managers.init(store,password);
        X509TrustManager trust=new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain,String auth) throws CertificateException {
                if(chain.length!=1 || !java.util.Arrays.equals(chain[0].getEncoded(),cert.getEncoded())) throw new CertificateException("Wrong client root identity");
            }
            public void checkServerTrusted(X509Certificate[] chain,String auth) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[]{cert}; }
        };
        SSLContext context=SSLContext.getInstance("TLSv1.2");context.init(managers.getKeyManagers(),new TrustManager[]{trust},null);
        AtomicReference<Throwable> failure=new AtomicReference<>();
        try(SSLServerSocket listener=(SSLServerSocket)context.getServerSocketFactory().createServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            listener.setEnabledProtocols(new String[]{"TLSv1.2"});listener.setNeedClientAuth(true);listener.setSoTimeout(8000);
            Thread server=new Thread(()-> {
                try(SSLSocket peer=(SSLSocket)listener.accept()) {
                    peer.setSoTimeout(8000);peer.startHandshake();
                    byte[] data=new byte[5];new DataInputStream(peer.getInputStream()).readFully(data);
                    peer.getOutputStream().write(data);peer.getOutputStream().flush();
                } catch(Throwable error) { failure.set(error); }
            });server.setDaemon(true);server.start();
            Socket socket=new Socket("127.0.0.1",listener.getLocalPort());
            BlockingDuplexByteStream transport=new BlockingDuplexByteStream() {
                public void send(byte[] bytes) { try { socket.getOutputStream().write(bytes);socket.getOutputStream().flush(); } catch(IOException e) { throw new RuntimeException(e); } }
                public byte[] recv(int maximum,long timeout) {
                    try { socket.setSoTimeout((int)Math.max(1,Math.min(8000,timeout)));byte[] bytes=new byte[maximum];int n=socket.getInputStream().read(bytes);return n<0?new byte[0]:java.util.Arrays.copyOf(bytes,n); }
                    catch(SocketTimeoutException e) { return null; } catch(IOException e) { throw new RuntimeException(e); }
                }
                public void close() { try { socket.close(); } catch(IOException e) { throw new RuntimeException(e); } }
            };
            LockdownPairRecord record=LockdownPairRecord.Companion.restore("synthetic-host","synthetic-buid","02:00:00:00:00:01",certPem,certPem,keyPem,certPem,keyPem,certPem);
            try(BlockingDuplexByteStream tls=LegacyLockdownTls.Companion.open(transport,record,5000)) {
                tls.send(new byte[]{1,2,3,4,5});ByteArrayOutputStream received=new ByteArrayOutputStream();
                while(received.size()<5) { byte[] data=tls.recv(5-received.size(),5000);if(data==null || data.length==0)throw new AssertionError("Missing TLS echo");received.write(data); }
                if(!java.util.Arrays.equals(new byte[]{1,2,3,4,5},received.toByteArray()))throw new AssertionError("TLS payload mismatch");
            }
            server.join(8000);if(server.isAlive())throw new AssertionError("TLS peer did not exit");if(failure.get()!=null)throw new AssertionError("TLS peer failed",failure.get());
        }
        System.out.println("PASS: post-shrink TLS1.2 mutual root authentication and payload roundtrip");
    }
}
