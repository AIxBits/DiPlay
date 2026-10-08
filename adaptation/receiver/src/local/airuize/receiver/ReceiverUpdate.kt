package local.airuize.receiver

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.EditText
import android.widget.Toast
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

data class ReceiverUpdateRelease(val packageName:String,val versionCode:Int,val versionName:String,
    val minSdk:Int?,val downloadUrl:String,val notes:String,val mandatory:Boolean,
    val sha256:String?=null,val bytes:Long?=null)
object ReceiverUpdatePolicy {
    const val DEFAULT_ENDPOINT="https://updates.aixbits.com/api/v1/android/latest"
    fun release(input:Map<String,Any?>,endpoint:String):ReceiverUpdateRelease {
        require(input["ok"] != false) { "Update service reported no release" }
        val fields=(input["release"] ?: input["data"] ?: input["latest"]) as? Map<*,*> ?: input
        fun value(vararg names:String):Any?=names.mapNotNull {fields[it]}.firstOrNull()
        fun string(vararg names:String):String=value(*names)?.toString() ?: ""
        fun integer(vararg names:String):Int?=value(*names)?.toString()?.toIntOrNull()
        val pkg=string("packageName","package_name")
        val code=integer("versionCode","version_code") ?: error("Missing version code")
        require(pkg=="local.airuize.diplaylegacy" && code>0) { "Mismatched update package or version" }
        val min=integer("minSdk","min_sdk")
        require(min==null || min in 1..18) { "Update requires a newer Android version" }
        val location=string("downloadUrl","download_url","apkUrl","apk_url","url")
        require(location.isNotEmpty()) { "Missing download URL" }
        val download=url(URL(url(endpoint),location).toString()).toString()
        val hash=string("sha256").takeIf { it.isNotEmpty() }
        require(hash==null || hash.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid APK checksum" }
        val size=value("bytes")?.toString()?.toLongOrNull()
        require(size==null || size in 1..33554432L) { "Invalid APK size" }
        return ReceiverUpdateRelease(pkg,code,string("versionName","version_name").ifEmpty {code.toString()}.take(100),
            min,download,string("releaseNotes","release_notes","notes").take(2000),value("mandatory")==true,hash,size)
    }
    fun url(value:String):URL {
        val url=URL(value)
        require(url.protocol=="https" && url.host.isNotEmpty() && url.userInfo==null && url.ref==null) { "Use an HTTPS address without credentials or fragment" }
        return url
    }
    fun compatible(packageName:String,minSdk:Int,versionCode:Int,current:Int):Boolean =
        packageName=="local.airuize.diplaylegacy" && minSdk in 1..18 && versionCode>current
}

/** Manual, public HTTPS metadata check; never reuse the log upload credential. */
class ReceiverUpdate(private val activity:Activity,private val log:(String)->Unit) {
    private val prefs=activity.getSharedPreferences("receiver-ui",0)
    fun configure() {
        val input=EditText(activity).apply { hint="https://…/updates/android43.json";setText(prefs.getString("updateEndpoint",ReceiverUpdatePolicy.DEFAULT_ENDPOINT));setSingleLine(true) }
        AlertDialog.Builder(activity).setTitle("更新服务地址 / Update service").setView(input)
            .setPositiveButton("保存 / Save") { _,_->
                val value=input.text.toString().trim()
                try { if(value.isNotEmpty())ReceiverUpdatePolicy.url(value);prefs.edit().putString("updateEndpoint",value).commit();log("更新服务配置已保存（不记录地址）") }
                catch(_:Exception) {Toast.makeText(activity,"请输入有效的 HTTPS 地址",Toast.LENGTH_LONG).show()}
            }.setNegativeButton("取消 / Cancel",null).show()
    }
    fun check() {
        val endpoint=(prefs.getString("updateEndpoint",ReceiverUpdatePolicy.DEFAULT_ENDPOINT) ?: "").ifEmpty {ReceiverUpdatePolicy.DEFAULT_ENDPOINT}
        if(endpoint.isEmpty()) {AlertDialog.Builder(activity).setTitle("检查更新 / Check updates").setMessage("更新服务尚未配置。收到网站接口地址后，在“更新服务地址”中填写。")
            .setPositiveButton("配置 / Configure") { _,_->configure() }.setNegativeButton("关闭 / Close",null).show();return}
        if(!running.compareAndSet(false,true))return
        Toast.makeText(activity,"正在检查更新…",Toast.LENGTH_SHORT).show()
        Thread({
            var connection:HttpsURLConnection?=null
            try {
                val request=ReceiverUpdatePolicy.url(endpoint).openConnection() as HttpsURLConnection
                connection=request;request.connectTimeout=10000;request.readTimeout=10000;request.instanceFollowRedirects=false
                request.sslSocketFactory=UploadTlsSocketFactory(SSLSocketFactory.getDefault() as SSLSocketFactory)
                request.setRequestProperty("Accept","application/json")
                if(request.responseCode==404) {
                    log("更新服务返回404：尚无发布版本或接口未启用")
                    activity.runOnUiThread {if(!activity.isFinishing)AlertDialog.Builder(activity).setTitle("检查更新 / Check updates").setMessage("更新服务返回 404：可能尚未发布安装包，或接口未启用。请稍后重试。").setPositiveButton("关闭 / Close",null).show()}
                    return@Thread
                }
                require(request.responseCode==200) { "Update service HTTP ${request.responseCode}" }
                val bytes=java.io.ByteArrayOutputStream()
                request.inputStream.use { input -> val buffer=ByteArray(4096);while(true) { val n=input.read(buffer);if(n<0)break;require(bytes.size()+n<=65536) { "Update response exceeds limit" };bytes.write(buffer,0,n) } }
                val metadata=org.json.JSONObject(String(bytes.toByteArray(),Charsets.UTF_8))
                fun map(json:org.json.JSONObject):Map<String,Any?> {
                    val result=HashMap<String,Any?>();val keys=json.keys()
                    while(keys.hasNext()) {val key=keys.next() as String;val value=json.opt(key);result[key]=if(value is org.json.JSONObject)map(value) else value}
                    return result
                }
                val release=ReceiverUpdatePolicy.release(map(metadata),endpoint)
                val code=release.versionCode
                val current=activity.packageManager.getPackageInfo(activity.packageName,0).versionCode
                val newer=code>current
                val download=release.downloadUrl
                val name=release.versionName
                val notes=release.notes
                log("更新检查完成：current=$current remote=$code minSdk=${release.minSdk ?: "unknown"}")
                activity.runOnUiThread {if(!activity.isFinishing) {
                    val dialog=AlertDialog.Builder(activity).setTitle(if(newer) "发现新版本 / Update available" else "已是最新版本 / Up to date")
                        .setMessage(if(newer) "$name\n$notes\n"+(if(release.minSdk==null) "服务未声明最低 Android 版本，请确认下载的是 Android 4.3 版本。\n" else "")+"\n由 DiPlay 下载并校验，随后由 Android 确认安装。" else "当前服务未提供更高版本。")
                        .setNegativeButton("关闭 / Close",null)
                    if(newer)dialog.setPositiveButton("下载 / Download") { _,_->
                        download(release)
                    }
                    dialog.show()
                }}
            } catch(error:Exception) {
                log("更新检查失败：${error.javaClass.simpleName}（网络、TLS 或接口格式；不记录服务地址）")
                activity.runOnUiThread {if(!activity.isFinishing)Toast.makeText(activity,"更新检查失败，请检查网络及更新服务配置；不影响 CarPlay",Toast.LENGTH_LONG).show()}
            } finally {connection?.disconnect();running.set(false)}
        },"legacy-update-check").apply {isDaemon=true;start()}
    }
    private fun download(release:ReceiverUpdateRelease) {
        if(!downloading.compareAndSet(false,true))return
        val cancelled=java.util.concurrent.atomic.AtomicBoolean(false)
        val progress=android.app.ProgressDialog(activity).apply {
            setTitle("下载更新 / Download update");setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            max=100;isIndeterminate=release.bytes==null;setCancelable(true)
            setOnCancelListener {cancelled.set(true)};show()
        }
        Thread({
            var connection:HttpsURLConnection?=null
            var temporary:java.io.File?=null
            try {
                require(release.sha256!=null) {"Update checksum missing"}
                val directory=java.io.File(activity.getExternalFilesDir(null) ?: error("Download storage unavailable"),"updates")
                check(directory.isDirectory || directory.mkdirs())
                val part=java.io.File(directory,"receiver-update.part");temporary=part
                val request=ReceiverUpdatePolicy.url(release.downloadUrl).openConnection() as HttpsURLConnection
                connection=request;request.connectTimeout=10000;request.readTimeout=15000;request.instanceFollowRedirects=false
                request.sslSocketFactory=UploadTlsSocketFactory(SSLSocketFactory.getDefault() as SSLSocketFactory)
                require(request.responseCode==200) {"APK download HTTP ${request.responseCode}"}
                var lastProgress=0L
                request.inputStream.use {input -> java.io.FileOutputStream(part).use {output ->
                    UpdateDownload.copy(input,output,release.bytes,release.sha256,cancelled) {received ->
                        val now=System.nanoTime()
                        if(now-lastProgress>=1000000000L) {lastProgress=now
                            activity.runOnUiThread {if(!activity.isFinishing && !cancelled.get()) {
                                release.bytes?.let {progress.progress=(received*100/it).toInt()}
                                progress.setMessage("${received/1024} KB")
                            }}
                        }
                    }
                }}
                if(cancelled.get())throw java.io.InterruptedIOException("Download cancelled")
                val pm=activity.packageManager
                val archive=pm.getPackageArchiveInfo(part.absolutePath,android.content.pm.PackageManager.GET_SIGNATURES) ?: error("Invalid APK")
                val installed=pm.getPackageInfo(activity.packageName,android.content.pm.PackageManager.GET_SIGNATURES)
                require(archive.packageName==activity.packageName && archive.versionCode==release.versionCode && archive.versionCode>installed.versionCode) {"APK identity/version mismatch"}
                require(!archive.signatures.isNullOrEmpty() && archive.signatures.toSet()==installed.signatures?.toSet()) {"APK signing certificate mismatch"}
                val apk=java.io.File(directory,"DiPlay-update-${release.versionCode}.apk")
                check(part.renameTo(apk)) {"Cannot save verified APK"};temporary=null
                log("更新安装包校验通过：version=${release.versionCode}；SHA256与签名一致")
                activity.runOnUiThread {if(!activity.isFinishing && !cancelled.get()) {
                    progress.dismiss()
                    AlertDialog.Builder(activity).setTitle("下载完成 / Download complete").setMessage("安装包已校验。安装更新将结束当前应用，请先结束 CarPlay 会话。")
                        .setPositiveButton("安装 / Install") {_,_->
                            try {activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(apk),"application/vnd.android.package-archive"))}
                            catch(_:Exception) {Toast.makeText(activity,"无法启动系统安装程序",Toast.LENGTH_LONG).show()}
                        }.setNegativeButton("稍后 / Later",null).show()
                }}
            } catch(error:Exception) {
                log("更新下载未完成：${error.javaClass.simpleName}；可重新检查更新后重试")
                activity.runOnUiThread {if(!activity.isFinishing) {progress.dismiss();if(!cancelled.get())Toast.makeText(activity,"下载或校验失败，请联网后重试；未启动安装",Toast.LENGTH_LONG).show()}}
            } finally {temporary?.delete();connection?.disconnect();downloading.set(false)}
        },"legacy-update-download").apply {isDaemon=true;start()}
    }
    companion object {
        private val running=java.util.concurrent.atomic.AtomicBoolean(false)
        private val downloading=java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
