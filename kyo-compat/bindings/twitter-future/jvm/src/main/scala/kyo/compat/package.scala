package kyo.compat

import com.twitter.util.FuturePool
import com.twitter.util.JavaTimer

/** twitterTimer: daemon JavaTimer for sleep/delay/timeout. blockingFuturePool: unbounded daemon FuturePool for blocking ops.
  */
private[compat] val twitterTimer: JavaTimer =
    new JavaTimer(isDaemon = true)

private[compat] val blockingFuturePool: FuturePool =
    FuturePool.unboundedPool
