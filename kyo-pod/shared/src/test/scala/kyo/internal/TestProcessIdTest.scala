package kyo.internal

import kyo.*

/** Covers the ownership predicate [[TestContainers]] reaps on, for the platform-specific [[TestProcessId]] under each of `jvm/`,
  * `js-wasm/` and `native/`.
  *
  * This suite is the only check that runs the predicate on all four platforms: the container fixtures it serves are JVM-only in practice,
  * so without it the JS, Wasm and Native probes would be compile-verified and never executed. The three cases below are the three the
  * predicate can be driven through from a test. The unknown-failure branch of each probe cannot be, since that needs a broken Node or libc.
  */
class TestProcessIdTest extends BasePodTest:

    "this process's own pid reports running" in {
        TestProcessId.isAlive(TestProcessId.pid.toString).map(alive => assert(alive))
    }

    // The container's owner is judged dead only for a definite "no such process", so a pid no process can hold
    // must report not-running on every platform. This is the case the reaper acts on, and TestContainersItTest
    // drives the same pid value through a real sweep.
    "a pid no process holds reports not running" in {
        TestProcessId.isAlive("999999999").map(alive => assert(!alive))
    }

    // A label value that is not a Long reports not-running, which reaps. Asserted rather than assumed because the
    // opposite default would leak every container whose label was ever written by something other than
    // TestContainers.
    "a label value that is not a pid reports not running" in {
        Kyo.foreach(Chunk("", "not-a-pid", "12x", "9999999999999999999999")) { value =>
            TestProcessId.isAlive(value)
        }.map(results => assert(results == Chunk(false, false, false, false)))
    }

    // On Linux the pid namespace is the part that tells a build container from the host it shares a daemon
    // with, so a Linux process has to carry it; everywhere the host name has to be there.
    "the namespace names this host and, on Linux, this pid namespace" in {
        Abort.run[FileSystemException](Path.runReadOnly(Path("/proc/self/ns/pid").exists(followLinks = false))).map { probed =>
            val linux = probed.getOrElse(false)
            assert(TestProcessId.namespace.nonEmpty && !TestProcessId.namespace.startsWith("/"), TestProcessId.namespace)
            assert(!linux || TestProcessId.namespace.contains("/pid:["), TestProcessId.namespace)
        }
    }

end TestProcessIdTest
