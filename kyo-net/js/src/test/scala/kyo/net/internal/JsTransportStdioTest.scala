package kyo.net.internal

import kyo.*
import kyo.net.NetStdioUnsupportedException
import kyo.net.Test
import scala.scalajs.js as sjs

/** `JsTransport.stdio` on a host with no `process` global, the state a browser is in.
  *
  * stdio is `process.stdin` and `process.stdout`, so such a host has none, and the transport reports it on the declared failure channel
  * with [[NetStdioUnsupportedException]] instead of throwing `ReferenceError` from inside the call. The refusal must not claim the one stdio
  * slot a process has, or a later call on a host that does have stdio would be told it is already open. No leaf opens real stdio, which the
  * test runner itself uses.
  */
class JsTransportStdioTest extends Test:

    import AllowUnsafe.embrace.danger

    private def withoutProcessGlobal[A](f: => A): A =
        val global = sjs.Dynamic.global.globalThis
        val saved  = sjs.Dynamic.global.process
        sjs.special.delete(global, "process")
        try f
        finally global.updateDynamic("process")(saved)
        end try
    end withoutProcessGlobal

    "with no process global, stdio fails with NetStdioUnsupportedException and claims nothing" in {
        val transport = JsTransport.init(poolSize = 1)
        val first     = withoutProcessGlobal(transport.stdio(channelCapacity = 16, readChunkSize = 1024))
        val second    = withoutProcessGlobal(transport.stdio(channelCapacity = 16, readChunkSize = 1024))
        Abort.run[kyo.net.NetException](first.safe.get).map { firstResult =>
            Abort.run[kyo.net.NetException](second.safe.get).map { secondResult =>
                assert(firstResult.failure.exists(_.isInstanceOf[NetStdioUnsupportedException]))
                assert(secondResult.failure.exists(_.isInstanceOf[NetStdioUnsupportedException]))
            }
        }
    }

end JsTransportStdioTest
