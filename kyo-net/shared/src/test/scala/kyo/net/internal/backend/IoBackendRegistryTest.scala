package kyo.net.internal.backend

import kyo.*
import kyo.net.NetBackendUnavailableException
import kyo.net.Test
import kyo.net.internal.transport.RecordingLog

/** Selection-logic tests for the single generic `IoBackend.select`/`selectAndBuild`/`buildFirst`. They drive selection over fixed stub lists
  * so the priority sort, capability gradient, forced-override behavior, typed failure, warning log, structured outcomes, selection report,
  * and build-fallback preservation are verified independent of any real driver. The same `select` backs both the I/O and TLS registries;
  * `TlsProviderRegistryTest` covers the TLS side.
  */
class IoBackendRegistryTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    /** A minimal capability descriptor: the identity selection reads plus a fixed probe outcome. It declares no libraries, because nothing
      * here loads one; the outcome it reports is supplied per stub, so a leaf can pin the exact degrade it is about.
      */
    final private class Stub(val name: String, val priority: Int, outcome: CapabilityOutcome) extends CapabilityDescriptor:
        def libraryIds: Chunk[String]                                  = Chunk.empty
        private[net] def doProbe(using AllowUnsafe): CapabilityOutcome = outcome
    end Stub

    private object Stub:
        /** The availability-only form the gradient, forced, and build-fallback leaves use: an unavailable stub reports the clean-false
          * degrade, which is the shape a real probe that ran and said no produces.
          */
        def apply(name: String, priority: Int, available: Boolean): Stub =
            val outcome = if available then CapabilityOutcome.Available else CapabilityOutcome.Unavailable("stub probe reported no")
            new Stub(name, priority, outcome)
        end apply

        /** The outcome form, for the leaves whose subject is WHICH degrade a candidate reported. */
        def apply(name: String, priority: Int, outcome: CapabilityOutcome): Stub = new Stub(name, priority, outcome)
    end Stub

    private def select(
        registered: Chunk[Stub],
        forced: Maybe[String],
        log: Log.Unsafe = Log.live.unsafe
    ): Result[NetBackendUnavailableException, Stub] =
        IoBackend.select[Stub, NetBackendUnavailableException](
            registered,
            forced = forced,
            onUnavailable = (name, report) => NetBackendUnavailableException(name, report.render),
            log = log
        )

    /** Drives `IoBackend.selectAndBuild` over stubs whose `build` returns the backend name, or throws `NetBackendUnavailableException` for
      * any name in `failing` (modeling a backend whose capability probe passed but whose driver construction fails on this host, e.g.
      * io_uring on a sandbox where the production-depth ring cannot init).
      */
    private def selectAndBuild(
        registered: Chunk[Stub],
        forced: Maybe[String],
        failing: Set[String],
        log: Log.Unsafe = Log.live.unsafe
    ): Result[NetBackendUnavailableException, String] =
        IoBackend.selectAndBuild[Stub, String](
            registered,
            stub =>
                if failing(stub.name) then throw NetBackendUnavailableException(Present(stub.name), s"${stub.name} build failed")
                else stub.name,
            forced = forced,
            onUnavailable = (name, report) => NetBackendUnavailableException(name, report.render),
            log = log
        )

    "select returns the highest-priority available entry" in {
        val list = Chunk(Stub("io_uring", 30, true), Stub("epoll", 20, true), Stub("nio", 10, true))
        select(list, Absent) match
            case Result.Success(stub) => assert(stub.name == "io_uring")
            case other                => fail(other.toString)
    }

    "select skips an unavailable higher-priority entry" in {
        val list = Chunk(Stub("io_uring", 30, false), Stub("epoll", 20, true), Stub("nio", 10, true))
        select(list, Absent) match
            case Result.Success(stub) => assert(stub.name == "epoll")
            case other                => fail(other.toString)
    }

    "select walks the full gradient down to the floor" in {
        val list = Chunk(Stub("io_uring", 30, false), Stub("epoll", 20, false), Stub("nio", 10, true))
        select(list, Absent) match
            case Result.Success(stub) => assert(stub.name == "nio")
            case other                => fail(other.toString)
    }

    "forced available name is honored over a higher-priority available entry" in {
        val list = Chunk(Stub("io_uring", 30, true), Stub("epoll", 20, true))
        select(list, Present("epoll")) match
            case Result.Success(stub) => assert(stub.name == "epoll")
            case other                => fail(other.toString)
    }

    "forced-but-unavailable name surfaces NetBackendUnavailableException with no fall-through" in {
        val list = Chunk(Stub("io_uring", 30, false), Stub("epoll", 20, true))
        select(list, Present("io_uring")) match
            case Result.Failure(e: NetBackendUnavailableException) => assert(e.getMessage.contains("io_uring"))
            case other                                             => fail(other.toString)
    }

    "forced unknown name falls through to the highest-priority available entry" in {
        // An unset name is not in the list, so resolution proceeds as if unforced (the name does not name a registered entry).
        val list = Chunk(Stub("io_uring", 30, true), Stub("epoll", 20, true))
        select(list, Present("does-not-exist")) match
            case Result.Success(stub) => assert(stub.name == "io_uring")
            case other                => fail(other.toString)
    }

    "no available entry surfaces NetBackendUnavailableException" in {
        val list = Chunk(Stub("io_uring", 30, false), Stub("epoll", 20, false))
        select(list, Absent) match
            case Result.Failure(e: NetBackendUnavailableException) => assert(e.getMessage.contains("no I/O backend is available"))
            case other                                             => fail(other.toString)
    }

    "adding a new entry uses the same select with no source edit" in {
        // A brand-new priority-99 backend wins purely by being a list entry; select itself is never touched.
        val list = Chunk(Stub("future-backend", 99, true), Stub("io_uring", 30, true))
        select(list, Absent) match
            case Result.Success(stub) => assert(stub.name == "future-backend")
            case other                => fail(other.toString)
    }

    "selectAndBuild builds the highest-priority available entry when it constructs" in {
        val list = Chunk(Stub("io_uring", 30, true), Stub("epoll", 20, true))
        selectAndBuild(list, Absent, failing = Set.empty) match
            case Result.Success(name) => assert(name == "io_uring")
            case other                => fail(other.toString)
    }

    "selectAndBuild falls back to the next available entry when the highest-priority one fails to build" in {
        // io_uring is available (its probe passed) but cannot construct at production scale on this host; selection must
        // degrade to epoll rather than surface the build failure.
        val list = Chunk(Stub("io_uring", 30, true), Stub("epoll", 20, true), Stub("nio", 10, true))
        selectAndBuild(list, Absent, failing = Set("io_uring")) match
            case Result.Success(name) => assert(name == "epoll")
            case other                => fail(other.toString)
    }

    "selectAndBuild walks the gradient across consecutive build failures" in {
        val list = Chunk(Stub("io_uring", 30, true), Stub("epoll", 20, true), Stub("nio", 10, true))
        selectAndBuild(list, Absent, failing = Set("io_uring", "epoll")) match
            case Result.Success(name) => assert(name == "nio")
            case other                => fail(other.toString)
    }

    "selectAndBuild fails with the last build failure when every available entry fails to build" in {
        val list = Chunk(Stub("io_uring", 30, true), Stub("epoll", 20, true))
        selectAndBuild(list, Absent, failing = Set("io_uring", "epoll")) match
            case Result.Failure(e: NetBackendUnavailableException) => assert(e.getMessage.contains("epoll build failed"))
            case other                                             => fail(other.toString)
    }

    "selectAndBuild does NOT fall back for a forced entry whose build fails (fail loud)" in {
        val list = Chunk(Stub("io_uring", 30, true), Stub("epoll", 20, true))
        selectAndBuild(list, Present("io_uring"), failing = Set("io_uring")) match
            case Result.Failure(e: NetBackendUnavailableException) => assert(e.getMessage.contains("io_uring build failed"))
            case other                                             => fail(other.toString)
    }

    "selectAndBuild surfaces NetBackendUnavailableException for a forced entry that is unavailable, without attempting a build (fail loud)" in {
        // Distinct from the forced-build-fails case above: here the forced backend's probe reports unavailable, so selectAndBuild fails at
        // the availability gate before it ever calls build. A forced name never silently falls through to another backend even when it is not usable.
        val list = Chunk(Stub("io_uring", 30, false), Stub("epoll", 20, true))
        selectAndBuild(list, Present("io_uring"), failing = Set.empty) match
            case Result.Failure(e: NetBackendUnavailableException) =>
                assert(e.getMessage.contains("io_uring"))
                assert(e.getMessage.contains("unavailable"))
            case other => fail(other.toString)
        end match
    }

    "forced-unavailable selection surfaces NetBackendUnavailableException and logs a warning" in {
        val list         = Chunk(Stub("io_uring", 30, false), Stub("epoll", 20, true))
        val recordingLog = new RecordingLog(Log.live.unsafe)
        select(list, Present("io_uring"), log = recordingLog) match
            case Result.Failure(e: NetBackendUnavailableException) => assert(e.getMessage.contains("io_uring"))
            case other                                             => fail(other.toString)
        assert(recordingLog.warnCount.get() > 0, "expected a warning logged for the forced-unavailable selection")
    }

    "no-available-backend selection surfaces NetBackendUnavailableException(Absent) and logs a warning" in {
        val list         = Chunk(Stub("io_uring", 30, false), Stub("epoll", 20, false))
        val recordingLog = new RecordingLog(Log.live.unsafe)
        select(list, Absent, log = recordingLog) match
            case Result.Failure(e: NetBackendUnavailableException) => assert(e.getMessage.contains("no I/O backend is available"))
            case other                                             => fail(other.toString)
        assert(recordingLog.warnCount.get() > 0, "expected a warning logged for the no-available-backend selection")
    }

    "the io_uring->epoll build fallback survives the retype, landing on the next backend and logging the failed build" in {
        val list         = Chunk(Stub("io_uring", 30, true), Stub("epoll", 20, true))
        val recordingLog = new RecordingLog(Log.live.unsafe)
        selectAndBuild(list, Absent, failing = Set("io_uring"), log = recordingLog) match
            case Result.Success(name) => assert(name == "epoll")
            case other                => fail(other.toString)
        assert(recordingLog.warnCount.get() > 0, "expected a warning logged for the failed io_uring build")
    }

    "select warns once when it demotes past an unavailable higher-priority backend" in {
        val recordingLog = new RecordingLog(Log.live.unsafe)
        val list         = Chunk(Stub("kqueue", 20, false), Stub("nio", 10, true))
        select(list, Absent, recordingLog) match
            case Result.Success(stub) => assert(stub.name == "nio")
            case other                => fail(other.toString)
        assert(recordingLog.warnCount.get() == 1, s"expected one demotion warning, got ${recordingLog.warnCount.get()}")
    }

    "select does not warn when the highest-priority backend is available (the fast path is silent)" in {
        val recordingLog = new RecordingLog(Log.live.unsafe)
        val list         = Chunk(Stub("kqueue", 20, true), Stub("nio", 10, true))
        select(list, Absent, recordingLog) match
            case Result.Success(stub) => assert(stub.name == "kqueue")
            case other                => fail(other.toString)
        assert(recordingLog.warnCount.get() == 0, s"expected no warning on the fast path, got ${recordingLog.warnCount.get()}")
    }

    "selectAndBuild warns once when it demotes past an unavailable higher-priority backend" in {
        val recordingLog = new RecordingLog(Log.live.unsafe)
        val list         = Chunk(Stub("kqueue", 20, false), Stub("nio", 10, true))
        selectAndBuild(list, Absent, failing = Set.empty, recordingLog) match
            case Result.Success(name) => assert(name == "nio")
            case other                => fail(other.toString)
        assert(recordingLog.warnCount.get() == 1, s"expected one demotion warning, got ${recordingLog.warnCount.get()}")
    }

    "selection skips every non-available outcome alike and lands on the floor" in {
        // The three degrades a real registry produces on one host: a kernel too old for io_uring, a native that was never staged for this
        // platform, and a backend belonging to another OS. They are three different operator situations, so the report keeps them apart,
        // but selection treats them identically: none of them can serve, so the floor wins.
        val list = Chunk(
            Stub("io_uring", 30, CapabilityOutcome.Unavailable("io_uring could not initialize a ring at the production depth 256")),
            Stub("epoll", 20, CapabilityOutcome.NotBundled("kyonet_posix_uring", "linux-x86_64")),
            Stub("kqueue", 20, CapabilityOutcome.UnsupportedOS),
            Stub("nio", 10, true)
        )
        select(list, Absent) match
            case Result.Success(stub) => assert(stub.name == "nio")
            case other                => fail(other.toString)
    }

    "the terminal failure carries a report naming every candidate and the reason each one was skipped" in {
        val list = Chunk(
            Stub("io_uring", 30, CapabilityOutcome.Unavailable("kernel does not provide io_uring")),
            Stub("epoll", 20, CapabilityOutcome.NotBundled("kyonet_posix_uring", "linux-x86_64")),
            Stub("kqueue", 20, CapabilityOutcome.UnsupportedOS)
        )
        select(list, Absent) match
            case Result.Failure(e: NetBackendUnavailableException) =>
                val message = e.getMessage
                assert(message.contains("io_uring[30]"), s"report must name io_uring and its priority, got $message")
                assert(message.contains("kernel does not provide io_uring"), s"report must carry the clean-false reason, got $message")
                assert(message.contains("kyonet_posix_uring"), s"report must name the missing library, got $message")
                assert(message.contains("linux-x86_64"), s"report must name the platform the library was missing for, got $message")
                assert(message.contains("kqueue[20]"), s"report must name every candidate, not only the failing ones, got $message")
                assert(message.contains("no candidate selected"), s"report must say nothing won, got $message")
            case other => fail(other.toString)
        end match
    }

    "a successful selection logs the report once at info, naming the winner" in {
        val recordingLog = new RecordingLog(Log.live.unsafe)
        val list         = Chunk(Stub("io_uring", 30, true), Stub("nio", 10, true))
        select(list, Absent, recordingLog) match
            case Result.Success(stub) => assert(stub.name == "io_uring")
            case other                => fail(other.toString)
        assert(recordingLog.infoCount.get() == 1, s"expected exactly one report line, got ${recordingLog.infoCount.get()}")
        assert(recordingLog.warnCount.get() == 0, s"a fast-path selection reports without warning, got ${recordingLog.warnCount.get()}")
    }

    "a probe is run once per candidate however many questions selection asks of it" in {
        // Selection asks three things of the same candidates (who wins, who was skipped and why, what the report says) and the demotion
        // path used to re-run the probe for every skipped candidate. The memo makes the answer one probe per candidate per process, which
        // is what removes a real second syscall on every degraded host.
        val probes = new java.util.concurrent.atomic.AtomicInteger(0)
        val counting = new CapabilityDescriptor:
            def name: String              = "counting"
            def priority: Int             = 30
            def libraryIds: Chunk[String] = Chunk.empty
            private[net] def doProbe(using AllowUnsafe): CapabilityOutcome =
                discard(probes.getAndIncrement())
                CapabilityOutcome.Unavailable("counted")
        val floor = new CapabilityDescriptor:
            def name: String                                               = "floor"
            def priority: Int                                              = 10
            def libraryIds: Chunk[String]                                  = Chunk.empty
            private[net] def doProbe(using AllowUnsafe): CapabilityOutcome = CapabilityOutcome.Available
        val list: Chunk[CapabilityDescriptor] = Chunk(counting, floor)
        IoBackend.select[CapabilityDescriptor, NetBackendUnavailableException](
            list,
            forced = Absent,
            onUnavailable = (name, report) => NetBackendUnavailableException(name, report.render)
        ) match
            case Result.Success(chosen) => assert(chosen.name == "floor")
            case other                  => fail(other.toString)
        end match
        assert(probes.get() == 1, s"expected one probe across the whole selection, got ${probes.get()}")
        // A second selection reads the memo rather than probing again.
        discard(IoBackend.select[CapabilityDescriptor, NetBackendUnavailableException](
            list,
            forced = Absent,
            onUnavailable = (name, report) => NetBackendUnavailableException(name, report.render)
        ))
        assert(probes.get() == 1, s"expected the memo to answer the second selection, got ${probes.get()} probes")
    }

end IoBackendRegistryTest
