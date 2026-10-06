package kyo.test.runner.internal

import kyo.Duration
import kyo.Maybe

/** A no-op on the JVM: a leaf that blocks its carrier still leaves the scheduler's other workers and timer thread running, so its timeout
  * and heartbeat fire on their own. The JS version exists because a single event loop has no other thread to fire them.
  */
private[runner] object EventLoopWatchdog:
    def leafStarted(label: String, timeout: Maybe[Duration], stuckAfter: Duration): Int = 0
    def leafFinished(id: Int): Unit                                                     = ()
end EventLoopWatchdog
