package local.airuize.receiver

/** Allow only protocol stages and numeric metadata into the exported report. */
object AirPlayDiagnostics {
    fun command(type: String, params: Map<String, Any?> = emptyMap()): String? {
        val known = listOf("disableBluetooth", "disable-bluetooth", "requestUI", "modesChanged", "duckAudio", "unduckAudio")
        val label = known.firstOrNull { it.equals(type, true) }
            ?: return "AirPlay 命令：其他类型（不记录名称或参数）"
        if (label != "modesChanged") return "AirPlay 命令：$label（不记录参数）"
        val fields = ArrayList<String>()
        fun scalar(value: Any?): String? = when (value) {
            is Boolean -> value.toString()
            is Byte, is Short, is Int, is Long -> (value as Number).toLong().takeIf { it in -1L..65535L }?.toString()
            else -> null
        }
        fun record(map: Map<*, *>, prefix: String, keys: List<String>) {
            for (key in keys) scalar(map[key])?.let { fields.add("$prefix$key=$it") }
        }
        val flat = listOf("screen", "mainAudio", "speech", "phoneCall", "turnByTurn")
        val nested = listOf("appStateID", "resourceID", "entity", "state", "speechMode", "transferType", "transferPriority", "takeConstraint", "borrowConstraint", "unborrowConstraint")
        fun modes(map: Map<*, *>, prefix: String) {
            record(map, prefix, flat)
            for (container in listOf("appStates", "resources")) {
                val entries = map[container] as? List<*> ?: continue
                entries.take(8).forEachIndexed { index, item ->
                    if (item is Map<*, *>) record(item, "$prefix$container[$index].", nested)
                }
            }
        }
        modes(params, "")
        (params["modes"] as? Map<*, *>)?.let { modes(it, "modes.") }
        return "AirPlay 命令：modesChanged；" + if (fields.isEmpty()) "没有可记录的已知状态字段" else fields.distinct().joinToString("；")
    }
    private val endpoints = listOf("/pair-setup", "/pair-verify", "/auth-setup", "/info", "/command", "/feedback")
    fun summary(message: String): String? {
        if (message.length<=1600 && (message.startsWith("Receive: audio type=") || message.startsWith("Audio UDP receive buffer type=") || message.startsWith("Audio ingress type=") || message.startsWith("Audio queue ") || message.startsWith("Audio backend type=") || message.startsWith("Audio control type=") || message.startsWith("Audio retransmission type=")) &&
            Regex("[A-Za-z0-9= .:\\[\\]_-]+").matches(message)) return message
        if (Regex("^receiver siri request down=(true|false) up=(true|false)$").matches(message)) return "Siri上游请求写入：" + message.removePrefix("receiver siri request ")
        if (Regex("^receiver audio requested type=[0-9]+ purpose=(media|telephony|speechrecognition|alert|default|compatibility|其他) microphoneEnabled=(true|false)$").matches(message)) return "手机音频SETUP请求：" + message.removePrefix("receiver audio requested ")
        if (message == "receiver microphone negotiated waitingForAudio=true") return "麦克风已协商：按原仓库等待音频首包后启动录音"
        val microphoneReasons = mapOf("disabled" to "本会话未启用", "stream-type" to "非主音频流", "audio-purpose" to "非语音/电话用途", "missing-port" to "手机未提供上行端口", "invalid-port" to "上行端口无效", "missing-peer" to "缺少会话对端", "missing-input-key" to "缺少输入加密材料", "missing-output-key" to "缺少输出加密材料")
        microphoneReasons[message.removePrefix("receiver microphone unavailable reason=")].takeIf { message.startsWith("receiver microphone unavailable reason=") }?.let { return "麦克风未建立：$it" }
        if (Regex("^receiver audio setup type=[0-9]+ codec=(LPCM|AAC_LC|OPUS) rate=[0-9]+ channels=[0-9]+ purpose=(media|telephony|speechrecognition|alert|default|compatibility|其他) microphone=(true|false)$").matches(message)) return "音频SETUP协商：" + message.removePrefix("receiver audio setup ")
        if (Regex("^receiver audio rejected type=[0-9]+ count=[0-9]+$").matches(message)) return "音频包拒绝（认证/解析失败）：" + message.removePrefix("receiver audio rejected ")
        if (message.startsWith("AirPlay iAP SETUP uuid=")) return "Wi-Fi iAP隧道SETUP已请求（不记录UUID/密钥种子）"
        if (message.startsWith("AirPlay iAP tunnel listening address=")) return "Wi-Fi iAP隧道监听已建立"
        if (message.startsWith("airplay event connection accepted from ")) return "AirPlay 事件通道 TCP 已接入"
        if (message == "airplay event encryption ready") return "AirPlay 事件通道加密已就绪"
        if (message == "airplay event connection closed") return "AirPlay 事件通道已关闭"
        if (message.startsWith("airplay event rx ")) return summary(message.replaceFirst("airplay event rx ", "airplay rx "))?.replace("AirPlay 请求", "AirPlay 事件请求")
        Regex("^airplay rx ([A-Z]{1,16}) (\\S+) cseq=([0-9-]+) body=([0-9]+)$").matchEntire(message)?.let {
            val fields = it.groupValues
            val path = fields[2].substringBefore('?')
            val endpoint = endpoints.firstOrNull { known -> path.endsWith(known) } ?: "other"
            return "AirPlay 请求：${fields[1]} $endpoint；CSeq=${fields[3]}；body=${fields[4]}"
        }
        Regex("^airplay tx status=([0-9]{3}) cseq=([0-9-]+) body=([0-9]+)$").matchEntire(message)?.let {
            return "AirPlay 响应：status=${it.groupValues[1]}；CSeq=${it.groupValues[2]}；body=${it.groupValues[3]}"
        }
        if (Regex("^airplay pairing phase=pair-(setup|verify) requestState=([0-9]+|none|invalid) responseState=([0-9]+|none|invalid) error=([0-9]+|none|invalid)$").matches(message)) return message
        if (message == "airplay control encryption enabled") return "AirPlay 控制通道加密已启用"
        if (message.startsWith("airplay control closing reason=")) {
            val reason = message.substringAfter("reason=").substringBefore(" activeStreams=")
            val label = when {
                reason == "peer EOF" -> "对端关闭连接"
                reason == "session closed" -> "会话关闭"
                reason.startsWith("control decrypt failed") -> "控制通道解密失败"
                reason.startsWith("control I/O failed") -> "控制通道读写失败"
                reason == "control runtime failed" -> "运行时兼容错误"
                else -> "其他原因"
            }
            return "AirPlay 控制连接结束：$label"
        }
        if (message.startsWith("airplay screen stream type=")) {
            Regex("^airplay screen stream type=([0-9]+) dataPort=([0-9]+|rejected)$").matchEntire(message)?.let { return message }
        }
        return null
    }
}
