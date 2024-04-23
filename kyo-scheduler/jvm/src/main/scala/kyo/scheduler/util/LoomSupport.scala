package kyo.scheduler.util

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import scala.util.control.NonFatal

object LoomSupport:

    def tryVirtualize(enabled: Boolean, exec: Executor): Executor =
        if !enabled then exec
        else
            try
                val ofVirtual = classOf[java.lang.Thread].getMethod("ofVirtual").invoke(null)

                val schedulerField = ofVirtual.getClass.getDeclaredField("scheduler")
                schedulerField.setAccessible(true)
                schedulerField.set(ofVirtual, exec)

                val factoryMethod = ofVirtual.getClass.getDeclaredMethod("factory")
                factoryMethod.setAccessible(true)
                val factory = factoryMethod.invoke(ofVirtual)

                classOf[Executors]
                    .getMethod("newThreadPerTaskExecutor", classOf[ThreadFactory])
                    .invoke(null, factory).asInstanceOf[Executor]
            catch
                case ex if (NonFatal(ex)) =>
                    println(
                        s"WARNING: Kyo's Loom integration is unavailable: ${ex.getMessage()} " +
                            "For better performance, add '--add-opens=java.base/java.lang=ALL-UNNAMED' to " +
                            "your JVM arguments to use a dedicated thread pool. This step is needed due to " +
                            "limitations in Loom with customizing thread executors."
                    )
                    exec

end LoomSupport
