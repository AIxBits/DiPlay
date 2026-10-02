package local.airuize.receiver

import android.app.ActivityManager
import android.content.Context

/** Read a current error snapshot; never start, stop or change an OEM service. */
object SystemServiceDiagnostics {
    fun snapshot(context:Context):String = try {
        val manager=context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val errors=manager.processesInErrorState.orEmpty()
        val packages=listOf("com.iflytek.autofly.reverseassist","com.iflytek.autofly.platformservice")
        val names=packages.toMutableSet()
        if(errors.isNotEmpty()) packages.forEach { pkg ->
            try {
                context.packageManager.getPackageInfo(pkg,android.content.pm.PackageManager.GET_SERVICES).services?.forEach {
                    it.processName?.let { name -> names.add(name) }
                }
            } catch(_:android.content.pm.PackageManager.NameNotFoundException) {}
        }
        val targets=errors.filter {
            val name=it.processName ?: ""
            name in names || packages.any { pkg -> name.startsWith(pkg+":") }
        }
        if(targets.isEmpty()) "系统服务错误快照：未返回Reverse Assist/PlatformService当前错误；记录可能已清除或对本应用不可见，不能证明从未崩溃"
        else targets.joinToString("\n") {
            "系统服务错误快照：进程=${it.processName}；条件=${it.condition}；简述=${it.shortMsg?.take(1024)}\n"+
                "详情=${it.longMsg?.take(2048)}\n异常栈=${it.stackTrace?.take(8192) ?: "系统未提供"}"
        }
    } catch(error:Exception) {
        "系统服务错误快照：读取不可用=${error.javaClass.simpleName}；需要系统崩溃日志才能确认原因"
    }
}
