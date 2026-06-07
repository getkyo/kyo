package kyo.compat.internal

import java.util.concurrent.TimeUnit
import scala.scalajs.js.timers

/** JS scheduling backend for `CIO.sleep`, `CIO.timeout`, and `CIO.cede`. Scala.js is single-threaded; `setTimeout` is the only available
  * delay primitive. The requested delay is converted to milliseconds and scheduled via `scala.scalajs.js.timers.setTimeout`.
  *
  * Public on purpose: `private[kyo]` triggers a `NoClassDefFoundError` at runtime when `sleep`/`timeout` inline at user-package call sites
  * (Scala 3 synthesizes an inline accessor typed against a package-as-class symbol that does not exist at runtime).
  */
object CompatScheduler:

    /** Schedules `action` to run after `delay` (interpreted with `unit`) via `scala.scalajs.js.timers.setTimeout`; returns immediately. */
    def schedule(action: () => Unit, delay: Long, unit: TimeUnit): Unit =
        val delayMs = unit.toMillis(delay).toDouble
        val _       = timers.setTimeout(delayMs)(action())
        ()
    end schedule
end CompatScheduler
