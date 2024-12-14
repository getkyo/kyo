package kyo.scheduler.top

import java.lang.management.ManagementFactory
import javax.management.MBeanServer
import javax.management.ObjectName
import javax.management.StandardMBean
import kyo.scheduler.InternalTimer
import scala.concurrent.duration.*

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
