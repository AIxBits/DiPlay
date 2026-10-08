package local.airuize.receiver
import org.junit.Test
import org.junit.Assert.*
class UsbPipelineAndSpeedTest {
    private class Slot(var sequence:Int=-1)
    @Test fun eightAndSixteenRequestsRemainPrimedAndPreserveCompletedByteOrder() {
        for(depth in listOf(1,8,16)) {
            val slots=List(depth){Slot()};val pending=ArrayList<Slot>();var counter=0;var peak=0
            val pipeline=OrderedUsbPipeline(slots,{slot->slot.sequence=counter++;pending.add(slot);peak=maxOf(peak,pending.size);true},
                {pending.removeAt(0)},{byteArrayOf(it.sequence.toByte())})
            repeat(100){assertArrayEquals(byteArrayOf(it.toByte()),pipeline.next());assertEquals(depth,pending.size)}
            assertEquals(depth,peak)
        }
    }
    @Test fun notificationsOutOfOrderAreReassembledBySubmissionOrder() {
        val slots=List(8){Slot()};val pending=ArrayList<Slot>();var counter=0;var first=true
        val pipeline=OrderedUsbPipeline(slots,{slot->slot.sequence=counter++;pending.add(slot);true},
            {pending.removeAt(if(first){first=false;2}else 0)},{byteArrayOf(it.sequence.toByte())})
        repeat(40){assertArrayEquals(byteArrayOf(it.toByte()),pipeline.next())}
        assertEquals(1L,pipeline.outOfOrder)
    }
    @Test fun queueFailureAndUnknownCompletionFailExplicitly() {
        val slot=Slot()
        for(pipeline in listOf(OrderedUsbPipeline(listOf(slot),{false},{slot},{byteArrayOf(1)}),
            OrderedUsbPipeline(listOf(slot),{true},{Slot()},{byteArrayOf(1)}))) {
            try {pipeline.next();fail("failure hidden")}catch(_:java.io.IOException){}
        }
    }
    @Test fun readsActualSpeedOnlyForMatchingBusDeviceAndVendorProduct() {
        val root=java.nio.file.Files.createTempDirectory("usb-speed-test").toFile()
        try {
            val device=java.io.File(root,"synthetic").apply{mkdir()}
            for((name,value) in mapOf("busnum" to "2","devnum" to "3","idVendor" to "05ac","idProduct" to "1234","speed" to "480"))java.io.File(device,name).writeText(value)
            assertTrue(UsbLinkSpeed.read("/dev/bus/usb/002/003",0x05ac,0x1234,root).contains("high-speed"))
            java.io.File(device,"speed").writeText("12")
            assertTrue(UsbLinkSpeed.read("/dev/bus/usb/002/003",0x05ac,0x1234,root).contains("full-speed"))
            assertTrue(UsbLinkSpeed.read("/dev/bus/usb/002/004",0x05ac,0x1234,root).contains("unknown"))
            assertTrue(UsbLinkSpeed.read("/dev/bus/usb/002/003",0x05ac,0x9999,root).contains("unknown"))
        }finally{root.deleteRecursively()}
    }
    @Test fun diagnosticVideoPauseExpiresAndDepthIsBounded() {
        VideoDiagnosticGate.reset()
        assertTrue(VideoDiagnosticGate.toggle(100))
        assertTrue(VideoDiagnosticGate.paused(200))
        assertFalse(VideoDiagnosticGate.paused(30000000101))
        VideoDiagnosticGate.reset();assertFalse(VideoDiagnosticGate.paused())
        assertEquals(8,UsbReceiveDiagnostics.sanitize(7));assertEquals(16,UsbReceiveDiagnostics.sanitize(16))
    }
}
