package kyo

import kyo.stats.internal.StatsRegistry

package object scheduler {

    private[scheduler] def statsScope =
        StatsRegistry.scope("kyo", "scheduler")

    private[scheduler] def bug(msg: String, ex: Throwable) =
        new Exception(s"🙈 !!Kyo Scheduler Bug!! " + msg, ex).printStackTrace()

}
