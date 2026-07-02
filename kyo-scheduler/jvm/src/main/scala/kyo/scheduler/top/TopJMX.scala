package kyo.scheduler.top

import java.lang.management.ManagementFactory
import javax.management.ObjectName
import javax.management.StandardMBean

/** JMX management interface for the scheduler top. The JMX console client reads its `Status` attribute. */
trait TopMBean {
    def getStatus(): Status
}

/** JVM-only JMX registration for the scheduler top. Scala Native provides a no-op equivalent. */
private[top] object TopJMX {
    def register(enable: Boolean, status: () => Status): () => Unit =
        if (!enable) () => ()
        else {
            val server = ManagementFactory.getPlatformMBeanServer
            val name   = new ObjectName("kyo.scheduler:type=Top")
            val mbean  = new StandardMBean(new TopMBean { def getStatus(): Status = status() }, classOf[TopMBean])
            if (server.isRegistered(name)) server.unregisterMBean(name)
            val _ = server.registerMBean(mbean, name)
            () => if (server.isRegistered(name)) server.unregisterMBean(name)
        }
}
