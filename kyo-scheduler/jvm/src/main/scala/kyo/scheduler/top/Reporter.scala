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

    private val mBeanServer: MBeanServer = ManagementFactory.getPlatformMBeanServer
    private val objectName               = new ObjectName("kyo.scheduler:type=Top")

    private var lastConsoleStatus: Status = null

    if (enableTopConsoleMs > 0) {
        val _ = timer.schedule(enableTopConsoleMs.millis) {
            val currentStatus = status()
            if (lastConsoleStatus ne null) {
                println(Printer(currentStatus - lastConsoleStatus))
            }
            lastConsoleStatus = currentStatus
        }
    }

    if (enableTopJMX) {
        close()
        val _ = mBeanServer.registerMBean(new StandardMBean(this, classOf[TopMBean]), objectName)
    }

    def getStatus() = status()

    def close(): Unit =
        if (mBeanServer.isRegistered(objectName))
            mBeanServer.unregisterMBean(objectName)
}

trait TopMBean {
    def getStatus(): Status
}
