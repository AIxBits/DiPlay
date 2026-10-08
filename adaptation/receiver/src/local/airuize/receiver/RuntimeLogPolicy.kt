package local.airuize.receiver
object RuntimeLogPolicy {
    fun updateUi(text:String):Boolean {
        if(text.contains("failure",true) || text.contains("failed",true) || text.contains("失败") || text.contains("ended",true) || text.contains("结束"))return true
        return listOf("Kernel audio ","NCM ","Receive:","Audio ingress","Audio queue","Audio health","媒体统计","音频通道统计","iap2 rx=0x5001","iAP2 rx=0x5001").none {text.startsWith(it)}
    }
}
