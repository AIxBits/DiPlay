import java.io.*;
import java.net.URL;
import javax.net.ssl.*;
import java.util.concurrent.atomic.AtomicBoolean;
import kotlin.Unit;
import local.airuize.receiver.UpdateDownload;
import local.airuize.receiver.UploadTlsSocketFactory;

/** Explicit manual integration check against a published public release. */
public final class CheckUpdateDownload {
    public static void main(String[] args) throws Exception {
        HttpsURLConnection request = (HttpsURLConnection)new URL(args[0]).openConnection();
        request.setConnectTimeout(10000); request.setReadTimeout(15000);
        request.setInstanceFollowRedirects(false);
        request.setSSLSocketFactory(new UploadTlsSocketFactory((SSLSocketFactory)SSLSocketFactory.getDefault()));
        try {
            if (request.getResponseCode() != 200) throw new IOException("Download HTTP failure");
            try (InputStream input=request.getInputStream(); FileOutputStream output=new FileOutputStream(args[3])) {
                long count=UpdateDownload.INSTANCE.copy(input,output,Long.valueOf(args[2]),args[1],
                    new AtomicBoolean(false), received -> Unit.INSTANCE);
                System.out.println("PASS public HTTPS download through application TLS/stream verification: " + count + " bytes");
            }
        } finally {request.disconnect();}
    }
}
