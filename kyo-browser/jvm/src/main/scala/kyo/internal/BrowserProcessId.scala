package kyo.internal

import kyo.*

/** The id of the running process, which names the Chrome user-data directories it owns (see `BrowserLauncher.userDataDirPrefix`). */
private[kyo] object BrowserProcessId:

    def current(using Frame): Long < Sync =
        Sync.defer(ProcessHandle.current().pid())

end BrowserProcessId
