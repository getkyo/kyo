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

    /** Runs `f` with `process` deleted from the global object. Restored in a `finally` because the test runner talks over `process.stdout`.
      *
      * Both the read and the restore go through `globalThis`: a bare `process` is a ReferenceError where none is declared, and a page has
      * none to stash, so it starts in the state this helper fabricates on Node and the leaf below asserts the real thing there.
      */
    private def withoutProcessGlobal[A](f: => A): A =
        val global = sjs.Dynamic.global.globalThis
        val saved  = global.selectDynamic("process")
        val had    = !sjs.isUndefined(saved)
        discard(sjs.special.delete(global, "process"))
        try f
        finally if had then global.updateDynamic("process")(saved)
        end try
    end withoutProcessGlobal

    "on Node, the node backend probes available".notBrowser in {
        assert(NodeBackend.doProbe == CapabilityOutcome.Available)
    }

    // The build stages kyo-net's natives beside each linked test program and installs koffi under target/, as an application does, and
    // sets no KYO_FFI_<ID>_PATH. Without this leaf a staging that stopped working would only turn the readiness backend's leaves into
    // cancels and every transport suite would still pass on the node floor.
    "on macOS and Linux, the readiness backend probes available, with koffi and its native resolved from the linked program" in {
        val backend: Maybe[PosixIoBackend] =
            if kyo.internal.Platform.isMac then Present(KqueueBackend)
            else if kyo.internal.Platform.isLinux then Present(EpollBackend)
            else Absent
        backend match
            case Absent           => cancel(s"no koffi readiness backend runs on ${kyo.internal.Platform.os}")
            case Present(backend) => assert(backend.probe == CapabilityOutcome.Available)
        end match
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
