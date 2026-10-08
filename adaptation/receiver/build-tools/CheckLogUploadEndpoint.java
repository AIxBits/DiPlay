import local.airuize.receiver.*;
import java.io.*;
import java.nio.file.*;
import javax.net.ssl.HttpsURLConnection;

/** Explicit opt-in endpoint smoke test; reads private config, sends synthetic data only. */
public final class CheckLogUploadEndpoint {
    public static void main(String[] args) throws Exception {
        LogUploadConfig config;
        try(InputStream input=new FileInputStream(args[0])) { config=LogUploadConfig.Companion.load(input); }
        File folder=Files.createTempDirectory("diplay-synthetic-upload").toFile();
        try {
            LogUploadQueue queue=new LogUploadQueue(folder);
            PendingLog log=queue.enqueue("Synthetic DiPlay endpoint test. No actual vehicle or personal data.\nUTF8: 测试日志\n", "本地接口测试，非车机日志");
            LogUploadTransport transport=new LogUploadTransport(config,url -> {
                try { return (HttpsURLConnection)((java.net.URL)url).openConnection(); } catch(IOException error) { throw new RuntimeException(error); }
            });
            String receipt=transport.submit(log,"0.25-local-test","Local integration test");
            queue.accepted(log);
            System.out.println("PASS: packaged upload implementation accepted; receipt="+receipt);
        } finally {
            for(File file:folder.listFiles())file.delete();folder.delete();
        }
    }
}
