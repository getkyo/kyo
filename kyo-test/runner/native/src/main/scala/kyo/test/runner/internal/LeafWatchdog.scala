package kyo.test.runner.internal

import kyo.Chunk
import kyo.Duration

/** No watchdog on Native: a leaf's timeout fires on a scheduler worker other than the one a spinning leaf holds, so it always reports. */
private[runner] object LeafWatchdog:
    def arm(suite: String, path: Chunk[String], timeout: Duration): Int = 0
    def disarm(token: Int): Unit                                        = ()
end LeafWatchdog
