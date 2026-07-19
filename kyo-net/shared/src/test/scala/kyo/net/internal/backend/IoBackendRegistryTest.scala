package kyo.net.internal.backend

import kyo.*
import kyo.net.NetBackendUnavailableException
import kyo.net.Test
import kyo.net.internal.transport.RecordingLog

/** Selection-logic tests for the single generic `IoBackend.select`/`selectAndBuild`/`buildFirst`. They drive selection over fixed stub lists
  * so the priority sort, capability gradient, forced-override behavior, typed failure, warning log, and build-fallback preservation are
  * verified independent of any real driver. The same `select` backs both the I/O and TLS registries; `TlsProviderRegistryTest` covers the
  * TLS side.
  */
class IoBackendRegistryTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    private case class Stub(name: String, priority: Int, available: Boolean)

    private def select(
        registered: Chunk[Stub],
        forced: Maybe[String],
        log: Log.Unsafe = Log.live.unsafe
    ): Result[NetBackendUnavailableException, Stub] =
        IoBackend.select[Stub, NetBackendUnavailableException](
            registered,
            _.name,
            _.priority,
            _.available,
            forced = forced,
            onUnavailable = NetBackendUnavailableException(_),
            log = log
        )

    /** Drives `IoBackend.selectAndBuild` over stubs whose `build` returns the backend name, or throws `NetBackendUnavailableException` for
      * any name in `failing` (modeling a backend whose `isAvailable` probe passed but whose driver construction fails on this host, e.g.
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
            _.name,
            _.priority,
            _.available,
            stub =>
                if failing(stub.name) then throw NetBackendUnavailableException(Present(stub.name), s"${stub.name} build failed")
                else stub.name,
            forced = forced,
            onUnavailable = NetBackendUnavailableException(_),
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
        // io_uring is available (its cheap probe passed) but cannot construct at production scale on this host; selection must
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
        // Distinct from the forced-build-fails case above: here the forced backend's cheap probe reports unavailable, so selectAndBuild fails at
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

end IoBackendRegistryTest
