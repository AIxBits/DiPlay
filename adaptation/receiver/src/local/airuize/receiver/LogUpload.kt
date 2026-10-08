package local.airuize.receiver

import java.io.File
import java.net.URL
import java.net.URLEncoder
import java.util.Properties
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import java.net.Socket
import java.net.InetAddress

object LogUploadJob { val running=java.util.concurrent.atomic.AtomicBoolean(false) }
object UploadSanitizer {
    // Compile once; never repeatedly compile patterns for each transport record.
    private val rules=listOf(
        Regex("-----BEGIN [^-]+-----.*?-----END [^-]+-----",setOf(RegexOption.DOT_MATCHES_ALL)) to "[credential container removed]",
        Regex("^.*(?:authorization\\s*:|bearer\\s+|identity\\.pk8|certificate\\.p7b|password\\s*[:=]|privatekey\\s*[:=]|pairrecord\\s*[:=]|upload.token|x-upload-token).*$",setOf(RegexOption.IGNORE_CASE,RegexOption.MULTILINE)) to "[credential line removed]",
        Regex("(?:[0-9a-f]{2}:){5}[0-9a-f]{2}",RegexOption.IGNORE_CASE) to "[MAC]",
        Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b") to "[IP]",
        Regex("(?<![\\w:])(?:[0-9a-f]{0,4}:){2,}[0-9a-f:%\\w]*",RegexOption.IGNORE_CASE) to "[IPv6]",
        Regex("\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b",RegexOption.IGNORE_CASE) to "[UUID]",
        Regex("/(?:Users|home)/[^\\s;；]+") to "[personal path]",
        Regex("wxid_[A-Za-z0-9_]+") to "[messaging ID]",
        Regex("(?i)(\\b(?:ssid|serial|udid|deviceid|usbTransport|hostid|systembuid|buid|pairingid|token|secret|latitude|longitude|lat|lon)\\s*[:=]\\s*)[^\\n;；]+") to "$1[redacted]"
    )
    fun clean(value:String):String {
        var text=value
        for((pattern,replacement) in rules) text=pattern.replace(text,replacement)
        return text
    }
}

class LogUploadConfig(val endpoint:URL,val appId:String,val token:String) {
    init {
        require(endpoint.protocol=="https" && endpoint.userInfo==null && endpoint.ref==null && endpoint.query==null)
        require(appId.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        require(token.length in 32..256 && token.all { it in '!'..'~' })
    }
    override fun toString()="LogUploadConfig(redacted)"
    companion object {
        fun load(input:java.io.InputStream):LogUploadConfig {
            val values=Properties().apply { load(input) }
            return LogUploadConfig(URL(values.getProperty("endpoint")),values.getProperty("appId"),values.getProperty("token"))
        }
    }
}
data class PendingLog(val file:File,val description:String) // Contains only sanitized text.
class LogUploadQueue(private val directory:File) {
    init { check(directory.isDirectory || directory.mkdirs()) }
    @Synchronized fun pending():List<PendingLog> = (directory.listFiles() ?: emptyArray()).filter { it.name.endsWith(".txt") }.sortedBy { it.name }.map {
        PendingLog(it,runCatching { File(directory,it.name+".description").readText(Charsets.UTF_8) }.getOrDefault(""))
    }
    @Synchronized fun enqueue(text:String,description:String):PendingLog {
        require(pending().size<5) { "待上传日志已满（5份），请先上传或清空" }
        val safeDescription=UploadSanitizer.clean(description).replace('\r',' ').replace('\n',' ').take(200)
        val bytes=("User description: $safeDescription\n\n"+UploadSanitizer.clean(text)).toByteArray(Charsets.UTF_8)
        require(bytes.size<=2*1024*1024) { "日志超过2MB，未加入待上传" }
        val name=System.currentTimeMillis().toString()+"-"+UUID.randomUUID().toString()+".txt"
        val file=File(directory,name);val meta=File(directory,name+".description")
        val temporary=File(directory,name+".part")
        try {
            meta.writeText(safeDescription,Charsets.UTF_8)
            java.io.FileOutputStream(temporary).use { it.write(bytes);it.fd.sync() }
            check(temporary.renameTo(file)) { "待上传日志保存失败" }
        } catch(error:Exception) { temporary.delete();file.delete();meta.delete();throw error }
        return PendingLog(file,safeDescription)
    }
    @Synchronized fun accepted(log:PendingLog) { check(log.file.parentFile==directory); log.file.delete();File(directory,log.file.name+".description").delete() }
    @Synchronized fun clear() {
        pending().forEach(::accepted)
        (directory.listFiles() ?: emptyArray()).filter { it.name.endsWith(".part") || it.name.endsWith(".description") }.forEach { it.delete() }
    }
}
/** Enable modern protocols without changing trust managers or hostname verification. */
class UploadTlsSocketFactory(private val delegate:SSLSocketFactory):SSLSocketFactory() {
    private fun secure(socket:Socket):Socket {
        if(socket is SSLSocket) {
            val modern=socket.supportedProtocols.filter { it=="TLSv1.2" || it=="TLSv1.3" }
            check(modern.isNotEmpty()) { "HTTPS socket has no TLS1.2 support" }
            socket.enabledProtocols=modern.toTypedArray()
        }
        return socket
    }
    override fun getDefaultCipherSuites()=delegate.defaultCipherSuites
    override fun getSupportedCipherSuites()=delegate.supportedCipherSuites
    override fun createSocket()=secure(delegate.createSocket())
    override fun createSocket(s:Socket,h:String,p:Int,close:Boolean)=secure(delegate.createSocket(s,h,p,close))
    override fun createSocket(h:String,p:Int)=secure(delegate.createSocket(h,p))
    override fun createSocket(h:String,p:Int,l:InetAddress,lp:Int)=secure(delegate.createSocket(h,p,l,lp))
    override fun createSocket(h:InetAddress,p:Int)=secure(delegate.createSocket(h,p))
    override fun createSocket(h:InetAddress,p:Int,l:InetAddress,lp:Int)=secure(delegate.createSocket(h,p,l,lp))
}
class LogUploadTransport(private val config:LogUploadConfig,
    private val connect:(URL)->HttpsURLConnection = { it.openConnection() as HttpsURLConnection }) {
    fun submit(log:PendingLog,version:String,os:String):String {
        val connection=connect(config.endpoint)
        try {
            connection.sslSocketFactory=UploadTlsSocketFactory(connection.sslSocketFactory)
            connection.requestMethod="POST";connection.doOutput=true;connection.instanceFollowRedirects=false
            connection.connectTimeout=15000;connection.readTimeout=20000
            connection.setRequestProperty("Content-Type","text/plain; charset=utf-8")
            connection.setRequestProperty("Accept","application/json")
            connection.setRequestProperty("User-Agent","DiPlay/$version (Android; API18)")
            connection.setRequestProperty("X-App-Version",version)
            connection.setRequestProperty("X-OS",os.replace('\r',' ').replace('\n',' '))
            connection.setRequestProperty("X-App-Id",config.appId)
            connection.setRequestProperty("X-Upload-Token",config.token)
            // HTTP headers must be ASCII. Preserve readable UTF-8 description in body too.
            connection.setRequestProperty("X-Description",URLEncoder.encode(UploadSanitizer.clean(log.description).replace('\r',' ').replace('\n',' ').take(200),"UTF-8"))
            connection.setRequestProperty("X-Description-Encoding","percent-encoded-utf8")
            val data=UploadSanitizer.clean(log.file.readText(Charsets.UTF_8)).replace(config.token,"[upload credential]").toByteArray(Charsets.UTF_8)
            require(data.size<=2*1024*1024)
            connection.setFixedLengthStreamingMode(data.size)
            connection.outputStream.use { it.write(data) }
            val status=connection.responseCode
            if(status!=201) throw java.io.IOException("上传返回 HTTP $status；待上传日志已保留")
            val input=connection.inputStream
            val body=input.use { val bytes=ByteArray(4096);val out=java.io.ByteArrayOutputStream();var n=it.read(bytes);while(n>0 && out.size()<8192) { out.write(bytes,0,n);n=it.read(bytes) };out.toString("UTF-8") }
            check(Regex("\"ok\"\\s*:\\s*true").containsMatchIn(body)) { "上传响应未确认成功；日志保留" }
            return Regex("\"logId\"\\s*:\\s*\"([A-Za-z0-9_-]{1,100})\"").find(body)?.groupValues?.get(1) ?: error("上传响应缺少回执；日志保留")
        } finally { connection.disconnect() }
    }
}
