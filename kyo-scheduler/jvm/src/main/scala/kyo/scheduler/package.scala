package kyo

import java.util.logging.*
import kyo.stats.internal.StatsRegistry

package object scheduler {

    private[scheduler] val log = Logger.getLogger("kyo.scheduler")

    private[scheduler] def statsScope =
        StatsRegistry.scope("kyo", "scheduler")

    private[scheduler] def bug(msg: String, ex: Throwable) =
        log.log(Level.SEVERE, s"🙈 !!Kyo Scheduler Bug!!", new Exception(msg, ex))

}
