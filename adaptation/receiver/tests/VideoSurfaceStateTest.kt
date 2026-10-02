package local.airuize.receiver
import org.junit.Assert.*
import org.junit.Test
class VideoSurfaceStateTest {
    @Test fun destroyAndRecreateSameWrapperCannotReuseOldDecoder() {
        val state=VideoSurfaceState<Any>(); val wrapper=Any()
        state.attach(wrapper); val initial=state.snapshot
        state.attach(null); state.attach(wrapper)
        assertSame(initial.target,state.snapshot.target)
        assertTrue(state.snapshot.generation>initial.generation)
    }
    @Test fun resumeRebuildsEvenWhenAndroidRetainsSameSurfaceObject() {
        val state=VideoSurfaceState<Any>(); val wrapper=Any()
        state.attach(wrapper); val before=state.snapshot.generation
        assertFalse(state.attach(wrapper)); assertEquals(before,state.snapshot.generation)
        assertTrue(state.attach(wrapper,true)); assertEquals(before+1,state.snapshot.generation)
    }
    @Test fun oldWorkerSnapshotRemainsConsistentAfterDetach() {
        val state=VideoSurfaceState<Any>(); val wrapper=Any()
        state.attach(wrapper); val captured=state.snapshot; state.attach(null)
        assertSame(wrapper,captured.target); assertNull(state.snapshot.target)
        assertNotEquals(captured.generation,state.snapshot.generation)
    }
}
