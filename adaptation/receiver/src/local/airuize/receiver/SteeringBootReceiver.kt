package local.airuize.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restore enabled steering listener; upstream-style host launch when boot-connect is enabled. */
class SteeringBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if(ReceiverSettings.read(context).bootConnect) {
            try {
                context.startService(Intent(context,LegacySessionService::class.java).setAction(LegacySessionService.CONNECT))
                context.startActivity(Intent(context,ReceiverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            } catch(error:Exception) { VoiceKeyJournal.add("Boot CarPlay launch failed: ${error.javaClass.simpleName}") }
        }
        if (!context.getSharedPreferences("receiver-ui", Context.MODE_PRIVATE).getBoolean("steeringVoice", false)) return
        try { context.startService(Intent(context, SteeringVoiceService::class.java)) }
        catch (error: Exception) { VoiceKeyJournal.add("开机恢复方向盘监听失败：${error.javaClass.simpleName}") }
    }
}
