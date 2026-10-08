package local.airuize.receiver
import java.io.File

object UsbLinkSpeed {
    fun read(deviceName:String,vendor:Int,product:Int,root:File=File("/sys/bus/usb/devices")):String {
        return try {
            val path=deviceName.split('/');val bus=path.getOrNull(path.size-2)?.toIntOrNull();val device=path.lastOrNull()?.toIntOrNull()
            if(bus==null || device==null)return "USB negotiated speed unknown; sysfs mapping unavailable"
            val matches=(root.listFiles() ?: emptyArray()).take(128).filter {entry ->
                !entry.name.contains(':') && runCatching {
                    File(entry,"busnum").readText().trim().toInt()==bus && File(entry,"devnum").readText().trim().toInt()==device &&
                    File(entry,"idVendor").readText().trim().toInt(16)==vendor && File(entry,"idProduct").readText().trim().toInt(16)==product
                }.getOrDefault(false)
            }
            if(matches.size!=1)return "USB negotiated speed unknown; unique sysfs device unavailable"
            val speed=File(matches.single(),"speed").readText().trim().toDoubleOrNull()
            val label=when(speed){1.5->"low-speed";12.0->"full-speed";480.0->"high-speed";5000.0->"super-speed";10000.0->"super-speed-plus";else->null}
            if(label==null) "USB negotiated speed unknown; sysfs value unavailable" else "USB negotiated speed Mbps=$speed class=$label source=sysfs"
        }catch(_:Exception){"USB negotiated speed unknown; sysfs inaccessible"}
    }
}
