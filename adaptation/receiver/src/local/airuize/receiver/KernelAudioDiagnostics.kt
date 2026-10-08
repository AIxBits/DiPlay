package local.airuize.receiver

import java.io.File

/** Read-only, background kernel counters. Never export addresses, raw tables or socket owners. */
class KernelAudioDiagnostics(private val uid:Int, private val report:(String)->Unit):java.io.Closeable {
    @Volatile private var running=true
    private val previous=HashMap<String,Long>()
    private val thread=Thread({
        while(running) {
            val values=collect(uid,LegacyAudioIngress.activePorts())
            val fields=values.entries.sortedBy {it.key}.joinToString(" ") { (key,value) ->
                val old=previous.put(key,value)
                "$key=$value ${key}Delta=${if(old==null || old<0 || value<0 || value<old)-1 else value-old}"
            }
            if(running)report("Kernel audio scope=system-and-port $fields")
            try {Thread.sleep(5000)} catch(_:InterruptedException) {break}
        }
    },"legacy-kernel-audio-diag").apply {isDaemon=true;start()}
    override fun close(){running=false;thread.interrupt()}
    companion object {
        fun snmp(text:String):Map<String,Long> {
            val wanted=setOf("Udp6RcvbufErrors","Udp6InErrors","Udp6InDatagrams")
            return text.lineSequence().mapNotNull {line -> val p=line.trim().split(Regex("\\s+"));
                if(p.size==2 && p[0] in wanted)p[1].toLongOrNull()?.let {p[0] to it} else null }.toMap()
        }
        fun socket(text:String,ports:Set<Int>,uid:Int):Map<String,Long> {
            val result=HashMap<String,Long>()
            text.lineSequence().forEach {line ->
                val p=line.trim().split(Regex("\\s+"))
                if(p.size>=13 && p[7].toIntOrNull()==uid) {
                    val port=p[1].substringAfterLast(':').toIntOrNull(16)
                    if(port!=null && port in ports) {
                        p.last().toLongOrNull()?.let {result["port${port}Drops"]=it}
                        p[4].substringAfter(':').toLongOrNull(16)?.let {result["port${port}RxQueueBytes"]=it}
                    }
                }
            }
            return result
        }
        fun softnet(text:String):Long? {
            var total=0L;var count=0
            for(line in text.lineSequence()) {
                if(line.isBlank())continue
                val p=line.trim().split(Regex("\\s+"))
                val value=p.getOrNull(1)?.toLongOrNull(16) ?: return null
                total+=value;count++
            }
            return if(count>0)total else null
        }
        private fun read(path:String):String?=try {File(path).bufferedReader().use {it.readText().takeIf {s->s.length<=262144}}} catch(_:Exception){null}
        fun collect(uid:Int,ports:Set<Int>):Map<String,Long> {
            val result=HashMap<String,Long>()
            val counters=read("/proc/net/snmp6")?.let(::snmp) ?: emptyMap()
            for(key in listOf("Udp6RcvbufErrors","Udp6InErrors","Udp6InDatagrams"))result[key]=counters[key] ?: -1
            result["softnetBacklogDrops"]=read("/proc/net/softnet_stat")?.let(::softnet) ?: -1
            val table=read("/proc/net/udp6")
            result["udp6TableAvailable"]=if(table==null)0 else 1
            val sockets=table?.let {socket(it,ports,uid)} ?: emptyMap()
            ports.forEach {port->result["port${port}Drops"]=sockets["port${port}Drops"] ?: -1;result["port${port}RxQueueBytes"]=sockets["port${port}RxQueueBytes"] ?: -1}
            for(key in listOf("rmem_max","rmem_default"))result[key]=read("/proc/sys/net/core/$key")?.trim()?.toLongOrNull() ?: -1
            val interfaces=File("/sys/class/net").listFiles()?.filter {File(it,"tun_flags").exists()} ?: emptyList()
            result["tunInterfaces"]=interfaces.size.toLong()
            var drops=0L;var errors=0L;var valid=interfaces.isNotEmpty()
            for(item in interfaces) {
                val d=read(File(item,"statistics/rx_dropped").path)?.trim()?.toLongOrNull()
                val e=read(File(item,"statistics/rx_errors").path)?.trim()?.toLongOrNull()
                if(d==null || e==null)valid=false else {drops+=d;errors+=e}
            }
            result["tunRxDropped"]=if(valid)drops else -1;result["tunRxErrors"]=if(valid)errors else -1
            return result
        }
    }
}
