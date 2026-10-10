import kyo.*
import kyo.stats.internal.StatsRegistry

// KyoApp activates Stat before running this body, which starts the sampler; staying up past two of its one-second
// ticks makes it call into the host-metrics shim, so a symbol the link left unresolved or a faulting call ends the
// process.
//
// The outcome names what the registry holds rather than the fact of having waited. A line printed after a sleep says
// nothing: without the enlistment this build declares, the factory is dropped at link time and the sampler never
// starts, with no error to show for it. `StatsRegistry.snapshot` is the read side of the registry, public for
// exactly this.
object Main extends KyoApp:
    run {
        Async.sleep(2500.millis).andThen(Sync.defer {
            val registered = StatsRegistry.snapshot("machine")
            val outcome    = if registered.nonEmpty then "sampled" else "absent"
            println(s"CONSUMER machine=$outcome metrics=${registered.size}")
        })
    }
end Main
