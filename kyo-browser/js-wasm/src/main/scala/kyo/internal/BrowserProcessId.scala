package kyo.internal

import kyo.*
import scala.scalajs.js

/** The id of the running process, which names the Chrome user-data directories it owns (see `BrowserLauncher.userDataDirPrefix`).
  *
  * Reads `process.pid`, so it needs a Node-like host; anywhere else it panics naming the host, as launching Chrome would.
  */
private[kyo] object BrowserProcessId:

    def current(using Frame): Long < Sync =
        Sync.defer {
            // The `typeof` guard stays inline on the global selection: a host with no `process` global throws on binding it.
            if js.typeOf(js.Dynamic.global.process) == "undefined" then
                Abort.panic(IllegalStateException("Chrome launch needs a Node-like host: there is no `process` global"))
            else js.Dynamic.global.process.pid.asInstanceOf[Double].toLong
        }

end BrowserProcessId
