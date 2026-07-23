package kyo.internal.transport

import kyo.*
import kyo.net.NetConnectException
import kyo.net.NetException

/** Tests [[UdsServerWireTransport]]'s discrimination of the first-accept promise's two failure shapes: a genuine close before any client
  * connected ends `incoming` cleanly (empty stream), while a transport-side [[NetException]] reaching the same promise surfaces typed rather
  * than being silently absorbed as a `Closed`. Driven directly against a hand-built `first` promise (no real socket, no `UdsBackend.connect`),
  * matching this file's package to `UdsBackend.scala` for access to the `private[kyo]` wire class.
  */
class UdsBackendTest extends kyo.JsonRpcTest:

    import AllowUnsafe.embrace.danger

    "a first-connection transport failure surfaces typed, not a Panic" in {
        val first    = Promise.Unsafe.init[kyo.net.Connection, Abort[NetException | Closed]]()
        val wire     = UdsServerWireTransport(first)
        val injected = NetConnectException("h", 1)
        discard(first.complete(Result.fail(injected)))
        Abort.run[Closed](wire.incoming.run).map { result =>
            result.foldError(
                onSuccess = chunks => fail(s"expected a typed transport failure, got success: $chunks"),
                onError = {
                    case Result.Panic(e: NetConnectException) => assert(e eq injected, "must surface the exact injected leaf")
                    case other                                => fail(s"expected the injected NetConnectException, got $other")
                }
            )
        }
    }

    "a genuine close before any client connected still ends the stream cleanly" in {
        val first = Promise.Unsafe.init[kyo.net.Connection, Abort[NetException | Closed]]()
        val wire  = UdsServerWireTransport(first)
        discard(first.complete(Result.fail(Closed("UdsBackend", summon[Frame], "closed before a client connected"))))
        wire.incoming.run.map(chunks => assert(chunks.isEmpty, s"expected an empty stream on a genuine close, got $chunks"))
    }

end UdsBackendTest
