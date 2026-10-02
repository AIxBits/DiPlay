package local.airuize.receiver
import org.junit.Assert.*
import org.junit.Test
class ConnectionStartPolicyTest {
    @Test fun pairedPhoneAndStaleWantedDoNotBypassManualSelection() {
        assertFalse(ConnectionStartPolicy.onOpen(false,false,false))
        assertFalse(ConnectionStartPolicy.onServiceRestore(false,false,true))
    }
    @Test fun explicitAutomaticStartupDoesNotDuplicatePendingOrActiveSessions() {
        assertTrue(ConnectionStartPolicy.onOpen(true,false,false))
        assertFalse(ConnectionStartPolicy.onOpen(true,true,false))
        assertFalse(ConnectionStartPolicy.onOpen(true,false,true))
    }
    @Test fun stoppedSessionIsNotRestoredEvenWithAutomaticStartupEnabled() {
        assertFalse(ConnectionStartPolicy.onServiceRestore(true,true,false))
        assertTrue(ConnectionStartPolicy.onServiceRestore(true,false,true))
        assertTrue(ConnectionStartPolicy.onServiceRestore(false,true,true))
    }
}
