package local.airuize.receiver

/** Immutable target/generation pair handles Android reusing the same Surface wrapper. */
class VideoSurfaceState<T> {
    data class Snapshot<T>(val target:T?,val generation:Long)
    @Volatile var snapshot=Snapshot<T>(null,0); private set
    @Synchronized fun attach(target:T?,force:Boolean=false):Boolean {
        val old=snapshot
        if(old.target===target && !force) return false
        snapshot=Snapshot(target,old.generation+1); return true
    }
}
