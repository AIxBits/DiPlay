package local.airuize.receiver

import com.shilapi.xcertplay.transport.*
import org.junit.Assert.*
import org.junit.Test

class LegacyVehicleSpeedTest {
    @Test fun successAndZeroHaveDistinctMeaningFromFailure() {
        assertEquals(10.0,VehicleSpeedPolicy.metersPerSecond(0,36.0)!!,0.000001)
        assertEquals(0.0,VehicleSpeedPolicy.metersPerSecond(0,0.0)!!,0.0)
        assertNull(VehicleSpeedPolicy.metersPerSecond(1,0.0))
        for(v in listOf(Double.NaN,Double.POSITIVE_INFINITY,-1.0,301.0)) assertNull(VehicleSpeedPolicy.metersPerSecond(0,v))
    }
    @Test fun staleAndFutureReadingsAreRejected() {
        assertTrue(VehicleSpeedPolicy.fresh(2000,500))
        assertFalse(VehicleSpeedPolicy.fresh(2001,500))
        assertFalse(VehicleSpeedPolicy.fresh(1000,1001))
        assertFalse(VehicleSpeedPolicy.fresh(0,-1))
    }
    @Test fun noVendorGearMappingIsAssumed() {
        for(v in listOf("","1,2,3","1,1,3,4","-1,2,3,4","1,2,3,256","P,R,N,D")) assertTrue(VehicleSpeedPolicy.gearMap(v).isEmpty())
        assertEquals(VehicleGear.REVERSE,VehicleSpeedPolicy.gearMap("4,3,2,1")[3])
    }
    @Test fun upstreamPascdCarriesSpeedWithoutPositionAndStops() {
        var active=false
        val gps=object:Iap2LocationProvider {
            override fun start()=false
            override fun stop(){}
            override fun latestNmea():String?=null
        }
        val speed=object:VehicleSpeedSource {
            override fun start(){ active=true }
            override fun stop(){ active=false }
            override fun drain()=if(active) VehicleSpeedReading(VehicleGear.DRIVE,listOf(VehicleSpeedSample(1000,10.0))) else null
        }
        val provider=VehicleSpeedLocationProvider(gps,speed)
        provider.onRequested(setOf(Iap2LocationMessages.VEHICLE_SPEED_DATA))
        assertTrue(provider.start())
        assertTrue(provider.latestNmea()!!.startsWith("\$PASCD,1.000,C,D,0,1,0.00,10.000*"))
        provider.stop(); assertNull(provider.latestNmea())
    }
}
