package kyo.internal

import kyo.*

class BrowserProcessIdTest extends BaseBrowserTest:

    "current is the process a spawned child reports as its parent" in {
        System.operatingSystem.map {
            case System.OS.Windows =>
                BrowserProcessId.current.map(pid => assert(pid > 0, s"expected a positive process id, got $pid"))
            case _ =>
                for
                    pid    <- BrowserProcessId.current
                    parent <- Command("sh", "-c", "echo $PPID").text
                yield assert(parent.trim == pid.toString, s"expected the child's parent ${parent.trim} to be $pid")
        }
    }

    "current is stable across calls" in {
        for
            first  <- BrowserProcessId.current
            second <- BrowserProcessId.current
        yield assert(first == second)
    }

end BrowserProcessIdTest
