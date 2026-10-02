package local.airuize.receiver
import android.content.Context
import com.shilapi.xcertplay.transport.LockdownPairRecord
import org.json.JSONObject
import java.io.File

/** Private app storage only; excluded from diagnostics, source archives and Android backup. */
class LegacyPairRecords(context:Context) {
    private val file=File(context.filesDir,"usb-pair-record.json")
    fun load():LockdownPairRecord?=runCatching {
        if(!file.isFile) return null
        val data=JSONObject(file.readText(Charsets.UTF_8))
        fun bytes(name:String)=android.util.Base64.decode(data.getString(name),2)
        LockdownPairRecord.restore(data.getString("hostId"),data.getString("systemBuid"),data.getString("wifiMacAddress"),
            bytes("devicePublicKeyPem"),bytes("deviceCertificatePem"),bytes("hostPrivateKeyPem"),bytes("hostCertificatePem"),bytes("rootPrivateKeyPem"),bytes("rootCertificatePem"))
    }.getOrNull()
    fun save(record:LockdownPairRecord) {
        val json=JSONObject().put("hostId",record.hostId).put("systemBuid",record.systemBuid).put("wifiMacAddress",record.wifiMacAddress)
        mapOf("devicePublicKeyPem" to record.devicePublicKeyPem,"deviceCertificatePem" to record.deviceCertificatePem,
            "hostPrivateKeyPem" to record.hostPrivateKeyPem,"hostCertificatePem" to record.hostCertificatePem,
            "rootPrivateKeyPem" to record.rootPrivateKeyPem,"rootCertificatePem" to record.rootCertificatePem).forEach { (key,value)-> json.put(key,android.util.Base64.encodeToString(value,2)) }
        val temporary=File(file.parentFile,file.name+".tmp"); temporary.writeText(json.toString(),Charsets.UTF_8)
        check(temporary.renameTo(file)) { "Unable to save private USB pairing" }
    }
    fun clear() { file.delete() }
}
