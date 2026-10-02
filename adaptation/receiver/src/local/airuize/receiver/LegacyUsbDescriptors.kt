package local.airuize.receiver

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.Parcelable
import java.util.Collections
import java.util.WeakHashMap

/** USB wire descriptors retain configuration IDs and alternate settings absent from SDK18. */
object UsbDescriptorParser {
    data class Endpoint(val address:Int,val attributes:Int,val packet:Int,val interval:Int)
    data class Interface(val id:Int,val alternate:Int,val klass:Int,val subclass:Int,val protocol:Int,val endpoints:MutableList<Endpoint> = ArrayList())
    data class Configuration(val id:Int,val interfaces:MutableList<Interface> = ArrayList())
    fun parse(raw:ByteArray): List<Configuration> {
        val configs=ArrayList<Configuration>(); var current:Configuration?=null; var iface:Interface?=null; var offset=0
        fun value(i:Int)=raw[offset+i].toInt() and 255
        while(offset+2<=raw.size) {
            val length=value(0); val type=value(1)
            require(length>=2 && offset+length<=raw.size) { "Malformed USB descriptor" }
            when(type) {
                2 -> { require(length>=9); current=Configuration(value(5)); configs.add(current); iface=null }
                4 -> { require(length>=9); iface=Interface(value(2),value(3),value(5),value(6),value(7)); current?.interfaces?.add(iface) }
                5 -> { require(length>=7); iface?.endpoints?.add(Endpoint(value(2),value(3),value(4) or (value(5) shl 8),value(6))) }
            }
            offset+=length
        }
        require(offset==raw.size)
        return configs
    }
}
class LegacyUsbConfiguration(val id:Int, private val interfaces:List<UsbInterface>) {
    val interfaceCount get()=interfaces.size
    fun getInterface(index:Int)=interfaces[index]
}
object LegacyUsbPlatform {
    private val alternates=Collections.synchronizedMap(WeakHashMap<UsbInterface,Int>())
    fun alternate(iface:UsbInterface):Int=alternates[iface] ?: 0
    fun configurations(connection:UsbDeviceConnection):List<LegacyUsbConfiguration> {
        // Both constructors verified in the provided Android4.3 framework DEX.
        val endpoint=UsbEndpoint::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).apply { isAccessible=true }
        val iface=UsbInterface::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Array<Parcelable>::class.java).apply { isAccessible=true }
        return UsbDescriptorParser.parse(connection.rawDescriptors).map { config ->
            LegacyUsbConfiguration(config.id,config.interfaces.map { descriptor ->
                val endpoints=descriptor.endpoints.map { ep -> endpoint.newInstance(ep.address,ep.attributes,ep.packet,ep.interval) as Parcelable }.toTypedArray()
                (iface.newInstance(descriptor.id,descriptor.klass,descriptor.subclass,descriptor.protocol,endpoints) as UsbInterface).also { alternates[it]=descriptor.alternate }
            })
        }
    }
    fun selectConfiguration(connection:UsbDeviceConnection,id:Int):Boolean {
        val active=ByteArray(1)
        if(connection.controlTransfer(0x80,8,0,0,active,1,1000)==1 && active[0].toInt() and 255==id) return true
        return connection.controlTransfer(0,9,id,0,null,0,1000)>=0
    }
    fun selectInterface(connection:UsbDeviceConnection,iface:UsbInterface):Boolean =
        connection.controlTransfer(1,11,alternate(iface),iface.id,null,0,1000)>=0
}
val UsbInterface.alternateSetting:Int get()=LegacyUsbPlatform.alternate(this)
