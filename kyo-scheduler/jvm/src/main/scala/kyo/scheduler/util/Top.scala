package kyo.scheduler.util

import java.lang.management.ManagementFactory
import javax.management.Attribute
import javax.management.MBeanServer
import javax.management.ObjectName
import javax.management.StandardMBean
import javax.management.remote.JMXConnectorFactory
import javax.management.remote.JMXServiceURL
import kyo.scheduler.InternalTimer
import kyo.scheduler.Scheduler
import scala.annotation.nowarn
import scala.concurrent.duration.*
import scala.io.StdIn

class Top(
    status: () => Scheduler.Status,
    enableTopJMX: Boolean,
    enableTopConsoleMs: Int,
    timer: InternalTimer
) extends TopMBean {

    private val mBeanServer: MBeanServer = ManagementFactory.getPlatformMBeanServer
    private val objectName               = new ObjectName("kyo.scheduler:type=Top")

    if (enableTopJMX) {
        close()
        mBeanServer.registerMBean(new StandardMBean(this, classOf[TopMBean]), objectName)
    }

    if (enableTopConsoleMs > 0)
        timer.schedule(enableTopConsoleMs.millis) {
            println(print(status()))
        }

    def getStatus() = status()

    def close(): Unit =
        if (mBeanServer.isRegistered(objectName))
            mBeanServer.unregisterMBean(objectName)
}

trait TopMBean {
    def getStatus(): Scheduler.Status
}

@nowarn
object Top extends App {

    lazy val (host, port) =
        args.toList match {
            case Nil =>
                ("localhost", 1099)
            case (host: String) :: Nil =>
                (host, 1099)
            case (host: String) :: (port: String) :: Nil =>
                (host, port.toInt)
            case args =>
                throw new IllegalArgumentException("Expected host and port but got: " + args)
        }

    private lazy val jmxServiceURL         = new JMXServiceURL(s"service:jmx:rmi:///jndi/rmi://$host:$port/jmxrmi")
    private lazy val jmxConnector          = JMXConnectorFactory.connect(jmxServiceURL)
    private lazy val mBeanServerConnection = jmxConnector.getMBeanServerConnection

    private lazy val objectName = new ObjectName("kyo.scheduler:type=Top")

    private var lastStatus: Scheduler.Status = null

    delayedInit {
        try {
            while (true) {

                val currentStatus = mBeanServerConnection.getAttribute(objectName, "Status").asInstanceOf[Scheduler.Status]

                if (lastStatus != null) {
                    val diffStatus = currentStatus - lastStatus
                    val clear      = "\u001b[2J\u001b[H"
                    println(clear + "\n" + print(diffStatus))
                }

                lastStatus = currentStatus
                Thread.sleep(1000) // Refresh every 1 second
            }
        } catch {
            case e: Exception =>
                e.printStackTrace()
        } finally {
            jmxConnector.close()
        }
    }

    private def print(status: Scheduler.Status): String = {
        val sb = new StringBuilder()

        sb.append(f"""
            |===============================================================================================
            |Kyo Scheduler Status      | LoadAvg: ${status.loadAvg}%1.4f   | Flushes: ${status.flushes}
            |===============================================================================================
            |Regulator   |   %% | Allow | Reject | Probes | Cmpl  | Adjmts | Updts |    Avg    |  Jitter
            |-----------------------------------------------------------------------------------------------
            |""".stripMargin)

        // Admission regulator row
        val admission       = status.admission
        val admissionAvg    = f"${admission.regulator.measurementsAvg}%7.1f"
        val admissionJitter = f"${admission.regulator.measurementsJitter}%7.2f"
        sb.append(
            f"Admission   | ${admission.admissionPercent}%3d | ${admission.allowed}%5d | ${admission.rejected}%6d | ${admission.regulator.probesSent}%6d | ${admission.regulator.probesCompleted}%5d | ${admission.regulator.adjustments}%6d | ${admission.regulator.updates}%5d | $admissionAvg%9s | $admissionJitter%8s\n"
        )

        // Concurrency regulator row
        val concurrency       = status.concurrency
        val concurrencyAvg    = f"${concurrency.regulator.measurementsAvg}%7.1f"
        val concurrencyJitter = f"${concurrency.regulator.measurementsJitter}%7.2f"
        sb.append(
            f"Concurrency |   - |     - |      - | ${concurrency.regulator.probesSent}%6d | ${concurrency.regulator.probesCompleted}%5d | ${concurrency.regulator.adjustments}%6d | ${concurrency.regulator.updates}%5d | $concurrencyAvg%9s | $concurrencyJitter%8s\n"
        )

        sb.append(f"""
            |===============================================================================================
            |Worker | Running | Blocked | Stalled | Load  | Exec  | Preempt | Done  | Stolen | Lost | Thread
            |-----------------------------------------------------------------------------------------------
            |""".stripMargin)

        // Worker table rows
        status.workers.foreach { w =>
            if (w ne null) {
                val running = if (w.running) "   🏃  " else "   ⚫  "
                val blocked = if (w.isBlocked) "   🚧  " else "   ⚫  "
                val stalled = if (w.isStalled) "   🐢  " else "   ⚫  "

                sb.append(
                    f"${w.id}%6d | $running | $blocked%-2s | $stalled%-2s | ${w.load}%5d | ${w.executions}%5d | ${w.preemptions}%7d | ${w.completions}%5d | ${w.stolenTasks}%6d | ${w.lostTasks}%4d | ${w.mount} ${w.frame}\n"
                )
            }
        }

        sb.append("\n")

        // Regulator table header
        sb.append("================================================================================================\n")

        sb.toString()
    }
}
