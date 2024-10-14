package kyo.scheduler.top

import javax.management.MBeanServerConnection
import javax.management.ObjectName
import javax.management.remote.JMXConnectorFactory
import javax.management.remote.JMXServiceURL
import scala.concurrent.duration.*

object Client {

    def run(
        args: List[String]
    )(
        consumeStatus: Status => Unit
    ): Unit = {
        val (host, port, interval) =
            args.toList match {
                case Nil =>
                    ("localhost", 1099, 1.second)
                case host :: Nil =>
                    (host, 1099, 1.second)
                case host :: port :: Nil =>
                    (host, port.toInt, 1.second)
                case host :: port :: interval :: Nil =>
                    (host, port.toInt, interval.toInt.seconds)
                case args =>
                    throw new IllegalArgumentException("Expected host and port but got: " + args)
            }
        run(host, port, interval)(consumeStatus)
    }

    def run(
        host: String,
        port: Int,
        interval: FiniteDuration
    )(
        consumeStatus: Status => Unit
    ): Unit = {
        val jmxServiceURL                                = new JMXServiceURL(s"service:jmx:rmi:///jndi/rmi://$host:$port/jmxrmi")
        val jmxConnector                                 = JMXConnectorFactory.connect(jmxServiceURL)
        val mbeanServerConnection: MBeanServerConnection = jmxConnector.getMBeanServerConnection

        val objectName         = new ObjectName("kyo.scheduler:type=Top")
        var lastStatus: Status = null

        while (true) {
            val status = mbeanServerConnection.getAttribute(objectName, "Status").asInstanceOf[Status]
            if (lastStatus ne null) {
                consumeStatus(status - lastStatus)
            }
            lastStatus = status
            Thread.sleep(interval.toMillis)
        }
    }

}
