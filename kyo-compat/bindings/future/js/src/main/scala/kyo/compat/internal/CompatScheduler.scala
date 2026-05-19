package kyo.compat.internal

import java.util.concurrent.TimeUnit
import scala.scalajs.js.timers

/** Public on purpose: `private[kyo]` triggers a `NoClassDefFoundError` at runtime when `sleep`/`timeout` inline at user-package call sites
  * (Scala 3 synthesizes an inline accessor typed against a package-as-class symbol that does not exist at runtime).
  *
  * JS impl: Scala.js is single-threaded; `setTimeout` is the only available delay primitive. We convert the requested delay to milliseconds
  * and schedule via `scala.scalajs.js.timers.setTimeout`.
  */
object CompatScheduler:

    def schedule(action: () => Unit, delay: Long, unit: TimeUnit): Unit =
        val delayMs = unit.toMillis(delay).toDouble
        val _       = timers.setTimeout(delayMs)(action())
        ()
    end schedule
end CompatScheduler
