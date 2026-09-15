package kyo.internal

import kyo.*
import scala.scalajs.js

/** The id of the running process, which names the Chrome user-data directories it owns (see `BrowserLauncher.userDataDirPrefix`).
  *
  * Reads `process.pid`, so it needs a Node-like host; anywhere else it panics naming the host, as launching Chrome would.
  */
private[kyo] object BrowserProcessId:

    def current(using Frame): Long < Sync =
        Sync.defer(NodeProcess.require("Chrome launch").pid.asInstanceOf[Double].toLong)

end BrowserProcessId
