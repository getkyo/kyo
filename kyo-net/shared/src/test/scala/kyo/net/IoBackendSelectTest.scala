package kyo.net

import kyo.*
import kyo.net.internal.backend.IoBackend

/** D-006: `IoBackend.select` RETURNS `Result[NetException, A]` and never throws. Pinned to INV-NET-04: a forced-but-unavailable name, and
  * a no-available-backend selection, both surface as `NetBackendUnavailableException` through the return value, never a raised exception.
  */
class IoBackendSelectTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    private case class Entry(name: String, priority: Int, available: Boolean)

    private def select(registered: Chunk[Entry], forcedProp: String): Result[NetException, Entry] =
        IoBackend.select[Entry](registered, _.name, _.priority, _.available, forcedProp)

    "select forced-but-unusable returns Result.fail(NetBackendUnavailableException(forced=Present(name))), never throws" in {
        val registered = Chunk(Entry("io_uring", 30, available = false), Entry("epoll", 20, available = true))
        withProp("kyo.net.test.select.forced", "io_uring") {
            select(registered, "kyo.net.test.select.forced") match
                case Result.Failure(e: NetBackendUnavailableException) => assert(e.forced == Present("io_uring"))
                case other => fail(s"expected Result.Failure(NetBackendUnavailableException), got $other")
        }
    }

    "select no-available-backend returns Result.fail(NetBackendUnavailableException(forced=Absent))" in {
        val registered = Chunk(Entry("io_uring", 30, available = false), Entry("epoll", 20, available = false))
        select(registered, "kyo.net.test.select.none") match
            case Result.Failure(e: NetBackendUnavailableException) => assert(e.forced == Absent)
            case other => fail(s"expected Result.Failure(NetBackendUnavailableException), got $other")
    }

end IoBackendSelectTest
