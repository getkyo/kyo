import kyo.*

// KyoApp activates Stat before running this body, which starts the sampler; staying up past two of its one-second ticks
// makes it call into the host-metrics shim, so a symbol the link left unresolved or a faulting call ends the process.
object Main extends KyoApp:
    run {
        Async.sleep(2500.millis).andThen(Console.printLine("CONSUMER machine=sampled"))
    }
end Main
