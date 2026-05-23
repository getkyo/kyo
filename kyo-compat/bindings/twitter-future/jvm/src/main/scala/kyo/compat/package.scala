package kyo.compat

import com.twitter.util.FuturePool
import com.twitter.util.JavaTimer

/** Package-private Twitter Future runtime infrastructure shared across the binding's `CIO`, `CFiber`, and other carrier types. */

/** Daemon `JavaTimer` used by `CIO.sleep` / `CIO.delay` / `CIO.timeout` / `CIO.cede`. */
private[compat] val twitterTimer: JavaTimer =
    new JavaTimer(isDaemon = true)

/** Unbounded daemon `FuturePool` used by `CIO.blocking`. */
private[compat] val blockingFuturePool: FuturePool =
    FuturePool.unboundedPool
