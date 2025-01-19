package kyo.scheduler

import com.typesafe.config.Config
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadFactory
import org.apache.pekko.dispatch.DispatcherPrerequisites
import org.apache.pekko.dispatch.ExecutorServiceConfigurator
import org.apache.pekko.dispatch.ExecutorServiceFactory

/** A Pekko ExecutorServiceConfigurator that integrates Kyo's adaptive scheduling capabilities with Pekko's dispatcher system. The
  * configurator enables Kyo's scheduler to handle all actor executions within your Pekko application, allowing it to make optimal thread
  * utilization decisions by having full visibility of the workload.
  *
  * To use Kyo's scheduler in your Pekko application, configure it as the default dispatcher:
  *
  * {{{
  * pekko.actor.default-dispatcher {
  *   type = "Dispatcher"
  *   executor = "kyo.scheduler.KyoExecutorServiceConfigurator"
  * }
  * }}}
  *
  * The configurator uses Kyo's scheduler singleton instance, allowing it to share resources and optimization decisions across the entire
  * application. By handling all actor executions, it can efficiently adapt to varying workloads and system conditions, optimizing thread
  * utilization across your entire application.
  *
  * For effective load management, use Kyo's admission control through Scheduler.get.reject() methods at the boundaries of your application
  * where external work enters the system. See the Admission class documentation for details on admission control behavior and
  * configuration.
  *
  * @param config
  *   The dispatcher configuration from Pekko
  * @param prerequisites
  *   Core Pekko prerequisites for dispatcher creation
  *
  * @see
  *   [[kyo.scheduler.Scheduler]] for details on the underlying scheduling capabilities and `reject` methods
  * @see
  *   [[kyo.scheduler.regulator.Admission]] for details on admission control behavior
  * @see
  *   [[org.apache.pekko.dispatch.ExecutorServiceConfigurator]] for the Pekko dispatcher interface
  */
class KyoExecutorServiceConfigurator(config: Config, prerequisites: DispatcherPrerequisites)
    extends ExecutorServiceConfigurator(config, prerequisites) {

    override def createExecutorServiceFactory(id: String, threadFactory: ThreadFactory) = {
        val exec = Scheduler.get.asExecutorService
        new ExecutorServiceFactory {
            def createExecutorService = exec
        }
    }
}
