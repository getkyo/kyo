package kyo.internal

import kyo.*
import scala.scalanative.posix.unistd

/** The id of the running process, which names the Chrome user-data directories it owns (see `BrowserLauncher.userDataDirPrefix`). */
private[kyo] object BrowserProcessId:

    // Scala Native's javalib has no `ProcessHandle.current()`, so the id comes from POSIX `getpid`.
    def current(using Frame): Long < Sync =
        Sync.defer(unistd.getpid().toLong)

end BrowserProcessId
