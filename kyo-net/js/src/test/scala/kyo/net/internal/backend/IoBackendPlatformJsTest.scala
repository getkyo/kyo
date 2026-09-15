package kyo.net.internal.backend

import kyo.*
import kyo.net.Test
import scala.scalajs.js as sjs

/** The Node backend's probe on a host that is not Node-like, the state a browser is in.
  *
  * The Node backend drives Node's `net` and `tls` modules, which such a host does not have, so its probe reports it unavailable and
  * selection, with no other candidate, fails with `NetBackendUnavailableException` instead of handing out a transport whose first operation
  * cannot load its modules. The leaves call `doProbe`, not the memoized `probe`: a probe answer is kept for the life of the process, and an
  * answer taken with `process` removed would decide selection for every later suite in the run. Selection itself over unavailable
  * candidates is `IoBackendRegistryTest`'s, and the whole path runs in `linkCheck`'s NetEcho launch with no process global.
  */
class IoBackendPlatformJsTest extends Test:

    import AllowUnsafe.embrace.danger

    override def config = super.config.sequential

    /** Runs `f` with `process` deleted from the global object. Restored in a `finally` because the test runner talks over `process.stdout`. */
    private def withoutProcessGlobal[A](f: => A): A =
        val global = sjs.Dynamic.global.globalThis
        val saved  = sjs.Dynamic.global.process
        discard(sjs.special.delete(global, "process"))
        try f
        finally global.updateDynamic("process")(saved)
        end try
    end withoutProcessGlobal

    "on Node, the node backend probes available" in {
        assert(NodeBackend.doProbe == CapabilityOutcome.Available)
    }

    "with no process global" - {
        "the node backend probes unavailable, naming the host" in {
            val (outcome, host) = withoutProcessGlobal((NodeBackend.doProbe, kyo.internal.Platform.host))
            assert(outcome == CapabilityOutcome.Unavailable(
                s"Node's net and tls modules need a Node-like host (Node, Bun or Deno); this host is $host"
            ))
        }
    }

end IoBackendPlatformJsTest
