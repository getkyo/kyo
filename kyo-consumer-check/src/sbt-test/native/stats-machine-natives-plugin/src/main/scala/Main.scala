import kyo.*

// KyoApp activates Stat before running this body, which starts the sampler; staying up past two of its one-second ticks
// makes it call into the host-metrics shim, so a symbol the link left unresolved or a faulting call ends the process.
//
// This line prints whether or not the sampler started: the registry that would say is private[kyo], so a consumer
// cannot read it. Whether the provider was enlisted is asserted against nativeConfig in the test, not here.
object Main extends KyoApp:
    run {
        Async.sleep(2500.millis).andThen(Console.printLine("CONSUMER machine=sampled"))
    }
end Main
