package kyo

// SCRATCH PROBE (remove before commit): verifies BasePodTest.checkingContainerLeak passes a scoped container and
// fails an intentionally-leaked one.
class LeakCheckProbeTest extends BasePodTest:

    val alpine = Container.Config(ContainerImage("alpine", "latest"))
        .command("sh", "-c", "trap 'exit 0' TERM; sleep infinity & wait")
        .stopTimeout(0.seconds)

    "clean scoped container is freed (leak check must PASS)" - runBackend {
        Container.init(alpine).map(c => c.exec("echo", "ok").map(r => assert(r.isSuccess)))
    }

    "initUnscoped without cleanup leaks (leak check must FAIL this leaf)" - runBackend {
        Container.initUnscoped(alpine).map(c => c.exec("echo", "ok").map(r => assert(r.isSuccess)))
    }

end LeakCheckProbeTest
