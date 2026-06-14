package kyo.ffi.internal

import java.lang.foreign.Arena
import kyo.Frame
import kyo.ffi.Ffi

/** JVM factory for [[Ffi.Guard]]. Wraps a shared Arena; registers with [[GuardRegistry]] and [[JvmLeakDetector]] for leak warnings. */
private[ffi] object GuardFactory:

    // Read configurable drain timeout from system property (JVM-only).
    sys.props.get("kyo.ffi.guard.drainTimeoutMs")
        .flatMap(s => scala.util.Try(s.toLong).toOption)
        .foreach(ms => GuardCore.drainTimeoutNanos = ms * 1000L * 1000L)

    /** Open a new guard. The [[Frame]] is captured for leak diagnostics. */
    def open(frame: Frame): Ffi.Guard =
        val arena = Arena.ofShared().nn
        val guard = GuardFactoryShared.register(new JvmGuard(arena, frame))
        // Register with the leak detector AFTER the guard is published to the shared registry. The Cleaner's runnable
        // holds only the frame and the guard's AtomicInteger state, not the guard itself, so the guard can be collected
        // while the detector still observes the leak. The returned Cleanable is stashed on the guard so a legitimate
        // close() cancels the pending warning (otherwise a post-close GC would fire a false-positive).
        val cleanable = JvmLeakDetector.register(guard, frame)
        guard.attachLeakCleanable(cleanable)
        guard
    end open
end GuardFactory
