package kyo.net.internal.backend

import kyo.*
import kyo.net.Test

/** Selection-logic tests for the single generic `IoBackend.select`. They drive `select` over fixed stub lists so the priority sort,
  * capability gradient, and forced-override behavior are verified independent of any real driver. The same `select` backs both the I/O and
  * TLS registries; `TlsProviderRegistryTest` covers the TLS side.
  */
class IoBackendRegistryTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    private case class Stub(name: String, priority: Int, available: Boolean)

    private def select(registered: Chunk[Stub], forcedProp: String): Stub =
        IoBackend.select[Stub](registered, _.name, _.priority, _.available, forcedProp)

    /** Drives `IoBackend.selectAndBuild` over stubs whose `build` returns the backend name, or throws `Closed` for any name in `failing`
      * (modeling a backend whose `isAvailable` probe passed but whose driver construction fails on this host, e.g. io_uring on a sandbox
      * where the production-depth ring cannot init).
      */
    private def selectAndBuild(registered: Chunk[Stub], forcedProp: String, failing: Set[String]): String =
        IoBackend.selectAndBuild[Stub, String](
            registered,
            _.name,
            _.priority,
            _.available,
            stub =>
                if failing(stub.name) then throw Closed("IoBackendRegistryTest", Frame.internal, s"${stub.name} build failed")
                else stub.name,
            forcedProp
        )

    /** Runs `body` with `prop` set to `value`, restoring the prior value afterward. The forced-selection leaves read a system property, so
      * each uses a distinct prop name to stay isolated when tests run concurrently.
      */
    private def withProp[A](prop: String, value: String)(body: => A): A =
        val previous = Maybe(java.lang.System.getProperty(prop))
        java.lang.System.setProperty(prop, value)
        try body
        finally previous match
                case Present(v) => discard(java.lang.System.setProperty(prop, v))
                case Absent     => discard(java.lang.System.clearProperty(prop))
        end try
    end withProp

    "select returns the highest-priority available entry" in {
        val list = Chunk(Stub("io_uring", 30, true), Stub("epoll", 20, true), Stub("nio", 10, true))
        assert(select(list, "kyo.net.test.backend.empty").name == "io_uring")
    }

    "select skips an unavailable higher-priority entry" in {
        val list = Chunk(Stub("io_uring", 30, false), Stub("epoll", 20, true), Stub("nio", 10, true))
        assert(select(list, "kyo.net.test.backend.empty").name == "epoll")
    }

    "select walks the full gradient down to the floor" in {
        val list = Chunk(Stub("io_uring", 30, false), Stub("epoll", 20, false), Stub("nio", 10, true))
        assert(select(list, "kyo.net.test.backend.empty").name == "nio")
    }

    "forced available name is honored over a higher-priority available entry" in {
        val list = Chunk(Stub("io_uring", 30, true), Stub("epoll", 20, true))
        withProp("kyo.net.test.backend.forced1", "epoll") {
            assert(select(list, "kyo.net.test.backend.forced1").name == "epoll")
        }
    }

    "forced-but-unavailable name aborts Closed with no fall-through" in {
        val list = Chunk(Stub("io_uring", 30, false), Stub("epoll", 20, true))
        withProp("kyo.net.test.backend.forced2", "io_uring") {
            val ex = intercept[Closed](select(list, "kyo.net.test.backend.forced2"))
            assert(ex.getMessage.contains("io_uring"))
        }
    }

    "forced unknown name falls through to the highest-priority available entry" in {
        // An unset name is not in the list, so resolution proceeds as if unforced (the property names a backend that does not exist).
        val list = Chunk(Stub("io_uring", 30, true), Stub("epoll", 20, true))
        withProp("kyo.net.test.backend.forced3", "does-not-exist") {
            assert(select(list, "kyo.net.test.backend.forced3").name == "io_uring")
        }
    }

    "no available entry aborts Closed" in {
        val list = Chunk(Stub("io_uring", 30, false), Stub("epoll", 20, false))
        val ex   = intercept[Closed](select(list, "kyo.net.test.backend.empty"))
        assert(ex.getMessage.contains("closed"))
    }

    "adding a new entry uses the same select with no source edit" in {
        // A brand-new priority-99 backend wins purely by being a list entry; select itself is never touched.
        val list = Chunk(Stub("future-backend", 99, true), Stub("io_uring", 30, true))
        assert(select(list, "kyo.net.test.backend.empty").name == "future-backend")
    }

    "selectAndBuild builds the highest-priority available entry when it constructs" in {
        val list = Chunk(Stub("io_uring", 30, true), Stub("epoll", 20, true))
        assert(selectAndBuild(list, "kyo.net.test.backend.empty", failing = Set.empty) == "io_uring")
    }

    "selectAndBuild falls back to the next available entry when the highest-priority one fails to build" in {
        // io_uring is available (its cheap probe passed) but cannot construct at production scale on this host; selection must
        // degrade to epoll rather than surface the build failure.
        val list = Chunk(Stub("io_uring", 30, true), Stub("epoll", 20, true), Stub("nio", 10, true))
        assert(selectAndBuild(list, "kyo.net.test.backend.empty", failing = Set("io_uring")) == "epoll")
    }

    "selectAndBuild walks the gradient across consecutive build failures" in {
        val list = Chunk(Stub("io_uring", 30, true), Stub("epoll", 20, true), Stub("nio", 10, true))
        assert(selectAndBuild(list, "kyo.net.test.backend.empty", failing = Set("io_uring", "epoll")) == "nio")
    }

    "selectAndBuild rethrows the last build failure when every available entry fails to build" in {
        val list = Chunk(Stub("io_uring", 30, true), Stub("epoll", 20, true))
        val ex   = intercept[Closed](selectAndBuild(list, "kyo.net.test.backend.empty", failing = Set("io_uring", "epoll")))
        assert(ex.getMessage.contains("epoll build failed"))
    }

    "selectAndBuild does NOT fall back for a forced entry whose build fails (fail loud)" in {
        val list = Chunk(Stub("io_uring", 30, true), Stub("epoll", 20, true))
        withProp("kyo.net.test.backend.forced4", "io_uring") {
            val ex = intercept[Closed](selectAndBuild(list, "kyo.net.test.backend.forced4", failing = Set("io_uring")))
            assert(ex.getMessage.contains("io_uring build failed"))
        }
    }

    "selectAndBuild aborts Closed for a forced entry that is unavailable, without attempting a build (fail loud)" in {
        // Distinct from the forced-build-fails case above: here the forced backend's cheap probe reports unavailable, so selectAndBuild aborts at
        // the availability gate before it ever calls build. A forced name never silently falls through to another backend even when it is not usable.
        val list = Chunk(Stub("io_uring", 30, false), Stub("epoll", 20, true))
        withProp("kyo.net.test.backend.forced5", "io_uring") {
            val ex = intercept[Closed](selectAndBuild(list, "kyo.net.test.backend.forced5", failing = Set.empty))
            assert(ex.getMessage.contains("io_uring"))
            assert(ex.getMessage.contains("unavailable"))
        }
    }

end IoBackendRegistryTest
