package kyo.net.internal

import kyo.*
import kyo.net.NetBackendUnavailableException
import kyo.net.NetPlatform
import kyo.net.Test

/** What an operation through [[NetPlatform.transport]] does in a page, where no backend can be selected.
  *
  * `NetPlatform`'s own scaladoc states the contract: "in a page, none, and every operation fails with
  * `NetBackendUnavailableException`". On Scala.js the backend is chosen when the first operation runs, not when the transport is read, so
  * the claim is about an operation's failure channel rather than about reading the transport, and a page is the only host where it can be
  * observed without taking something away first. The per-backend fan-out in `kyo.net.Test` cancels its cells in a page, which says the
  * backends are unavailable but nothing about what an operation returns; this is that half.
  */
class DeferredTransportTest extends Test:

    import AllowUnsafe.embrace.danger

    "in a page every operation fails NetBackendUnavailableException on its own channel".onlyBrowser in {
        // Port 1 is never reached: selection fails before a socket is attempted, so nothing here depends on what is listening.
        Abort.run[kyo.net.NetException](NetPlatform.transport.connect("127.0.0.1", 1).safe.get).map { result =>
            assert(
                result.failure.exists(_.isInstanceOf[NetBackendUnavailableException]),
                s"a page selects no backend, so a connect must fail NetBackendUnavailableException; got $result"
            )
            val reason = result.failure.map(_.getMessage).getOrElse("")
            // The message names the host, which is what tells a reader this is the page's answer and not a socket that refused.
            assert(reason.contains("Node-like host"), s"the failure must say why no backend is available; got $reason")
        }
    }

end DeferredTransportTest
