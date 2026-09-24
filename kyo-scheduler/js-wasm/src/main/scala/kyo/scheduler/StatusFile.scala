package kyo.scheduler

import scala.scalajs.js
import scala.util.control.NonFatal

/** Periodic writer for the scheduler's status line.
  *
  * Node's `fs` is reached through `process.getBuiltinModule` rather than a static `node:fs` import, because a static import is loaded
  * whenever the scheduler is linked, which would break a host without Node's modules. With no `process` or no `getBuiltinModule` (a
  * browser, Node before 20.16), nothing is armed. The interval is `unref`'d so it never keeps a finished Node process alive.
  */
private[scheduler] object StatusFile {

    def start(path: String, intervalMs: Int, line: () => String): js.UndefOr[js.timers.SetIntervalHandle] =
        // Must stay inline on the global selection: only then does Scala.js emit `typeof process`, safe on an undeclared identifier.
        if (js.typeOf(js.Dynamic.global.process) == "undefined") js.undefined
        else if (js.typeOf(js.Dynamic.global.process.getBuiltinModule) != "function") js.undefined
        else {
            val fs     = js.Dynamic.global.process.getBuiltinModule("fs")
            val handle = js.timers.setInterval(intervalMs.toDouble) {
                try { val _ = fs.writeFileSync(path, line()) }
                catch { case e if NonFatal(e) => () }
            }
            val _ = handle.asInstanceOf[js.Dynamic].unref()
            handle
        }
}
