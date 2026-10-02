package local.airuize.receiver

import android.app.Service
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.*

/** Receive the firmware's existing key callback only; never send commands to MCU. */
class SteeringVoiceService : Service() {
    private val lock = Any()
    private var stopped = false
    private var remote: IBinder? = null
    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) { reply?.writeString(CALLBACK); return true }
            if (code != 1) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(CALLBACK)
            val raw = data.readInt()
            if (SystemClock.elapsedRealtime() < VoiceKeyJournal.captureUntil)
                VoiceKeyJournal.add("MCU按键检测：原始值=$raw；语音键=${VoiceKeyPolicy.isMcuVoice(raw)}")
            val action = VoiceKeyPolicy.mcuAction(raw)
            if (action != null) {
                synchronized(lock) {
                    if (!stopped && getSharedPreferences("receiver-ui", MODE_PRIVATE).getBoolean("steeringVoice", true)
                        && VoiceKeyJournal.gate.accept(SystemClock.elapsedRealtime(), action)) {
                        VoiceKeyJournal.add("方向盘语音键：MCU=${raw and 255}；动作=$action；会话未连接=打开首页，已连接=点击Siri；不依赖长按时长")
                        try {
                            startActivity(Intent(this@SteeringVoiceService, ReceiverActivity::class.java)
                                .setAction(if(action==VoiceKeyPolicy.Action.SIRI) SIRI_ACTION else VOICE_ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        } catch (error: Exception) { VoiceKeyJournal.add("语音键打开失败：${error.javaClass.simpleName}") }
                    }
                }
            }
            reply?.writeNoException()
            return true
        }
    }
    override fun onCreate() {
        super.onCreate()
        val launch = PendingIntent.getActivity(this, 0, Intent(this, ReceiverActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(13, Notification.Builder(this).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("DiPlay 方向盘语音键")
            .setContentText("点击语音键：打开 CarPlay / 调用 Siri").setContentIntent(launch).setOngoing(true).build())
        Thread({
            synchronized(lock) {
                if (stopped) return@Thread
                try {
                    val manager = Class.forName("android.os.ServiceManager")
                    val binder = manager.getMethod("getService", String::class.java).invoke(null, "skypine_keyservice") as? IBinder
                        ?: throw IllegalStateException()
                    check(binder.interfaceDescriptor == INTERFACE)
                    transact(binder, 2)
                    remote = binder
                    VoiceKeyJournal.add("方向盘服务：MCU回调注册成功（实际按钮响应待实测）")
                } catch (error: Exception) {
                    VoiceKeyJournal.add("方向盘服务注册失败：${error.javaClass.simpleName}；前台按键入口仍可用")
                    stopSelf()
                }
            }
        }, "legacy-steering-register").apply { isDaemon = true; start() }
    }
    private fun transact(binder: IBinder, code: Int) {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE); data.writeStrongBinder(callback)
            check(binder.transact(code, data, reply, 0))
            reply.readException()
        } finally { reply.recycle(); data.recycle() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!getSharedPreferences("receiver-ui", MODE_PRIVATE).getBoolean("steeringVoice", true)) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        synchronized(lock) {
            stopped = true
            remote?.let { try { transact(it, 3) } catch (error: Exception) { VoiceKeyJournal.add("方向盘服务注销异常：${error.javaClass.simpleName}") } }
            remote = null
        }
        stopForeground(true); super.onDestroy()
    }
    companion object {
        const val SIRI_ACTION = "local.airuize.diplaylegacy.STEERING_SIRI"
        const val VOICE_ACTION = "local.airuize.diplaylegacy.STEERING_VOICE"
        private const val INTERFACE = "android.app.skypine.mcukey.IKeyService"
        private const val CALLBACK = "android.app.skypine.mcukey.IKeyCallBack"
    }
}
