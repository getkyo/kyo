package kyo.net

import kyo.*
import kyo.net.internal.backend.IoBackend

/** BUG-1 (reproduce-first): the OLD `buildFirst` caught only `Closed` around a build failure and ALWAYS fell back to the next backend
  * regardless of failure kind, so io_uring's eventfd `IllegalStateException` (a driver-init failure that is NOT a fallback-safe condition:
  * epoll's own liveness primitive is the SAME eventfd) both escaped the narrow catch AND, had it been caught, would have wrongly degraded.
  * The fix: every driver-init failure is a typed [[NetBackendInitException]] carrying a `recoverable` flag, and `buildFirst` consults it
  * BEFORE deciding to fall back: `recoverable=true` (io_uring `queue_init`, resource-pressure-specific) degrades; `recoverable=false`
  * (the wake eventfd / epoll `registerWake`, a shared liveness primitive no fallback can supply) propagates instead.
  *
  * Driven entirely through injected stub entries (no real io_uring): this suite tests `IoBackend.buildFirst`'s recoverable-predicate logic
  * in isolation, distinct from `IoBackendRegistryTest`'s general selection-logic coverage.
  */
class IoBackendBuildFirstTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    private case class Entry(name: String, priority: Int)

    private def selectAndBuild(
        registered: Chunk[Entry],
        build: Entry => String
    ): Result[NetException, String] =
        IoBackend.selectAndBuild[Entry, String](registered, _.name, _.priority, _ => true, build, "kyo.net.test.buildfirst.empty")

    "recoverable=true init failure DEGRADES to the next backend (injected init-failure driver, no real io_uring)" in {
        val registered = Chunk(Entry("io_uring", 30), Entry("epoll", 20))
        val result = selectAndBuild(
            registered,
            {
                case Entry("io_uring", _) => throw NetBackendInitException("io_uring", "queue_init failed: rc=-12", recoverable = true)
                case Entry("epoll", _)    => "epoll-transport"
                case other                => fail(s"unexpected build call for $other")
            }
        )
        assert(result == Result.succeed("epoll-transport"), s"a recoverable init failure must degrade to the next backend, got $result")
    }

    "recoverable=false init failure does NOT fall back; the typed leaf PROPAGATES (the Q-001 branch)" in {
        val registered = Chunk(Entry("io_uring", 30), Entry("epoll", 20))
        var epollBuilt = false
        val result = selectAndBuild(
            registered,
            {
                case Entry("io_uring", _) =>
                    throw NetBackendInitException(
                        "io_uring",
                        "eventfd creation failed; the wake eventfd is a liveness requirement for the indefinite park",
                        recoverable = false
                    )
                case Entry("epoll", _) =>
                    epollBuilt = true
                    "epoll-transport"
                case other => fail(s"unexpected build call for $other")
            }
        )
        result match
            case Result.Failure(e: NetBackendInitException) =>
                assert(!e.recoverable, "the propagated leaf must be the non-recoverable one")
                assert(e.getMessage.contains("io_uring"), s"the propagated leaf must name io_uring, got ${e.getMessage}")
            case other => fail(s"expected Result.Failure(NetBackendInitException(recoverable=false)), got $other")
        end match
        assert(!epollBuilt, "the second backend must NEVER be attempted after a non-recoverable init failure (no fallback)")
    }

    "buildFirst consults the recoverable predicate BEFORE recursing (sequence assertion)" in {
        val registered = Chunk(Entry("io_uring", 30), Entry("epoll", 20))
        val order      = scala.collection.mutable.ListBuffer[String]()
        val result = selectAndBuild(
            registered,
            {
                case Entry("io_uring", _) =>
                    order += "build(io_uring)"
                    throw NetBackendInitException("io_uring", "queue_init failed", recoverable = true)
                case Entry("epoll", _) =>
                    order += "build(epoll)"
                    "epoll-transport"
                case other => fail(s"unexpected build call for $other")
            }
        )
        assert(result == Result.succeed("epoll-transport"))
        assert(
            order.toList == List("build(io_uring)", "build(epoll)"),
            s"io_uring's failure must be classified (recoverable=true) BEFORE epoll is ever attempted, got $order"
        )
    }

end IoBackendBuildFirstTest
