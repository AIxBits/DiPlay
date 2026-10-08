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
    fun selectConfiguration(connection:UsbDeviceConnection,id:Int,log:(String)->Unit = {}):Boolean {
        require(id in 1..255)
        fun active():Int? {
            val value=ByteArray(1)
            return if(connection.controlTransfer(0x80,8,0,0,value,1,1000)==1) value[0].toInt() and 255 else null
        }
        // A raw SET_CONFIGURATION control request changes the phone but bypasses
        // usbfs endpoint/interface bookkeeping. Use the same ioctl as Android's
        // newer UsbDeviceConnection.setConfiguration, through API18 libcore.
        val before=active()
        val switched=try {
            val libcore=Class.forName("libcore.io.Libcore")
            val os=libcore.getField("os").get(null)
            val mutable=Class.forName("libcore.util.MutableInt")
            val arg=mutable.getConstructor(Int::class.javaPrimitiveType).newInstance(id)
            val ioctl=Class.forName("libcore.io.Os").getMethod("ioctlInt",java.io.FileDescriptor::class.java,Int::class.javaPrimitiveType,mutable)
            android.os.ParcelFileDescriptor.fromFd(connection.fileDescriptor).use { duplicate ->
                (ioctl.invoke(os,duplicate.fileDescriptor,UsbConfigurationPolicy.SET_CONFIGURATION_IOCTL,arg) as Int)>=0
            }
        } catch(error:Exception) {
            val cause=(error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
            val errno=runCatching { cause.javaClass.getField("errno").getInt(cause) }.getOrNull()
            log("USB configuration ioctl failed: ${cause.javaClass.simpleName} errno=$errno reason=${cause.message?.take(160)}")
            false
        }
        val after=active()
        log("USB configuration ioctl: requested=$id before=$before after=$after success=$switched")
        return UsbConfigurationPolicy.confirmed(id,switched,after)
    }
    fun selectInterface(connection:UsbDeviceConnection,iface:UsbInterface,log:(String)->Unit = {}):Boolean {
        val setting=alternate(iface)
        val result=try { LegacyUsbNative.select(connection.fileDescriptor,iface.id,setting) }
        catch(error:LinkageError) { log("NCM interface native backend unavailable: ${error.javaClass.simpleName}"); return false }
        log("NCM SETINTERFACE ioctl: interface=${iface.id} alternate=$setting result=$result errno=${if(result<0) -result else 0}")
        if(result<0) return false
        val value=ByteArray(1)
        val read=connection.controlTransfer(0x81,10,0,iface.id,value,1,1000)
        val actual=if(read==1) value[0].toInt() and 255 else null
        log("NCM GET_INTERFACE: interface=${iface.id} expected=$setting actual=$actual readResult=$read")
        return actual==null || actual==setting
    }
}
val UsbInterface.alternateSetting:Int get()=LegacyUsbPlatform.alternate(this)
