package kyo.internal

import kyo.*
import scala.scalajs.js as sjs

/** Signal handling on a Node-like host.
  *
  * The JVM leaf raises a signal through `sun.misc.Signal`; here the host raises it by signalling its own process,
  * which is the only way to deliver one to this program. A page has neither, and the leaf below says so instead of
  * pretending to test it.
  */
class OsSignalJsTest extends kyo.test.Test[Any]:

    // Both leaves register for the same signal, and a second registration on a Node process adds a listener rather
    // than replacing one, so a concurrent leaf could see the other's handler run. Sequential, as the JVM leaf is.
    override def config = super.config.sequential

    private def isNodeLike = Platform.isNodeLike

    "a signal the host delivers reaches the handler" in {
        if !isNodeLike then succeed("no process to signal on this host")
        else
            var handled = false
            OsSignal.handle("USR2", () => handled = true)
            val process = sjs.Dynamic.global.selectDynamic("process")
            discard(process.applyDynamic("kill")(process.selectDynamic("pid"), "SIGUSR2"))
            // Node delivers a signal as an event, so it arrives on a later turn of the loop rather than inside the
            // call above. Waiting on the condition gives the loop those turns without pinning a duration.
            assertEventually(Sync.defer(handled)).andThen {
                assert(handled, "the handler did not run for a signal the host delivered")
            }
        end if
    }

    "registering does not run the handler" in {
        var handled = false
        OsSignal.handle("USR2", () => handled = true)
        assert(!handled)
    }

    "a host with no process registers nothing rather than failing" in {
        // The registration is chosen by the host, so this is what a page gets: the no-op handler, reached without a
        // `process` read that would throw there.
        if isNodeLike then assert(OsSignal.handle.toString == "Signal.Handler.NodeProcess")
        else assert(OsSignal.handle.toString != "Signal.Handler.NodeProcess")
    }

end OsSignalJsTest
