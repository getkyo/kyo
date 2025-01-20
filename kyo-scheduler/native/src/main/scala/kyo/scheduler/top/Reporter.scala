package kyo.scheduler.top

import kyo.scheduler.InternalTimer

class Reporter(
    status: () => Status,
    enableTopJMX: Boolean,
    enableTopConsoleMs: Int,
    timer: InternalTimer
) extends TopMBean {

    def getStatus() = status()

    def close(): Unit = ()
}

trait TopMBean {
    def getStatus(): Status
}
