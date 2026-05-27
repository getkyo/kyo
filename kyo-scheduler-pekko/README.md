# kyo-scheduler-pekko

A bridge module that hands Pekko's dispatcher system over to Kyo's adaptive scheduler. Configure it as your default dispatcher's executor and every actor in your application starts running on Kyo's scheduler threads, so the scheduler gets full visibility of the workload and can make adaptive thread-utilization decisions across actors and ordinary Kyo computations alike.

You do not construct anything from this module in your code. The integration is a one-line entry in `application.conf` that names `kyo.scheduler.KyoExecutorServiceConfigurator` as the executor, and Pekko instantiates it for you when the actor system starts.

```hocon
pekko.actor.default-dispatcher {
  type     = "Dispatcher"
  executor = "kyo.scheduler.KyoExecutorServiceConfigurator"
}
```

That snippet is the entire usable surface of this module. The rest of this document explains what happens after Pekko reads it, and the one boundary-level call you still need to make yourself.

## Integration

When Pekko boots the actor system it reads the `executor` value, reflectively instantiates `kyo.scheduler.KyoExecutorServiceConfigurator`, and asks it for an `ExecutorService`. The configurator returns `Scheduler.get.asExecutorService`, so every actor mailbox dispatched by the default dispatcher runs on Kyo's scheduler threads from that point on.

The only observable effect at runtime is the thread the actor's `receive` block runs on. A trivial echo actor that reports `Thread.currentThread().getName` makes the wiring visible.

```scala
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{Actor, ActorSystem, Props}
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout
import scala.concurrent.Await
import scala.concurrent.duration.*

val system = ActorSystem(
  "KyoApp",
  ConfigFactory.parseString("""
    pekko.actor.default-dispatcher {
      type     = "Dispatcher"
      executor = "kyo.scheduler.KyoExecutorServiceConfigurator"
    }
  """)
)

val echo = system.actorOf(Props(new Actor {
  def receive = { case _ => sender() ! Thread.currentThread().getName }
}))

implicit val timeout: Timeout = Timeout(5.seconds)
val threadName = Await.result((echo ? "ping").mapTo[String], 5.seconds)
assert(threadName.contains("kyo"))
```

The thread-name assertion is the same check the module's own test uses to verify the integration is live.

> **Note:** `KyoExecutorServiceConfigurator` calls `Scheduler.get`, the JVM-wide scheduler singleton, so all actor systems in the process share one scheduler instance and one thread pool with any other Kyo code running in the same JVM.

> **Unlike** Pekko's built-in dispatcher types, this configurator ignores the `ThreadFactory` Pekko passes to `createExecutorServiceFactory`. Threads are owned by Kyo's scheduler and named accordingly, so per-dispatcher thread-name settings in `application.conf` have no effect.

### Dispatcher type

The intended dispatcher `type` is `"Dispatcher"`. The configurator hands Pekko a single shared `ExecutorService` backed by Kyo's scheduler, which is what a regular `Dispatcher` consumes. Other dispatcher types (`PinnedDispatcher`, custom dispatchers that demand a dedicated executor) are not the intended use of this module.

### Other dispatchers in the same config

The configuration above replaces only the default dispatcher. Routers, plugins, or actors that explicitly pin themselves to a different dispatcher continue to use whatever executor that dispatcher names. To route those actors through Kyo's scheduler as well, set `executor = "kyo.scheduler.KyoExecutorServiceConfigurator"` on each of their dispatcher blocks.

## Admission control at boundaries

Routing actor execution through Kyo's scheduler gives the scheduler visibility, not authority over inbound traffic. The configurator does not insert admission control: if 100k messages arrive at a queue in one second, all 100k are accepted and enqueued. Protecting the system from overload at the edges is your job, and the mechanism is `Scheduler.get.reject()`.

Call `reject()` (or the keyed variant `reject(key)`) at the application boundary where external work enters: the HTTP handler, the message-bus subscriber, the scheduled-job entry point. When the scheduler is under load it returns `true` and you drop or shed the request before constructing an actor message; when it has capacity it returns `false` and you proceed.

```scala
import kyo.scheduler.Scheduler
import org.apache.pekko.actor.ActorRef

def submit(work: ActorRef, payload: String): Boolean =
  if Scheduler.get.reject() then
    false
  else
    work ! payload
    true
```

The keyed form `Scheduler.get.reject(key)` lets the admission regulator shed traffic per-tenant or per-route when one key's traffic dominates. Pass a stable identifier (user id, route name) so the regulator can correlate decisions over time.

> **Caution:** `reject()` is advisory: it tells you the scheduler is loaded, it does not stop you from sending the message. If you ignore the return value, the message is enqueued and the load-shedding intent is lost.

For the full behavior model (how `reject` decides, how the admission regulator adapts over time), see the `kyo.scheduler.regulator.Admission` documentation in the `kyo-scheduler` module.

## Versions

The module pins `org.apache.pekko` `pekko-actor` to `1.4.0`. Downstream applications that depend on a different Pekko major version must align their Pekko dependency before adding this module, or Pekko's reflective load of `KyoExecutorServiceConfigurator` will fail at actor-system startup with a binary-incompatibility error.
