package kyo.net

import kyo.*

/** Cross-backend Unix-domain-socket round-trip for the public [[Transport]] surface.
  *
  * `connectUnix` / `listenUnix` exist on every backend (NIO on JVM, posix on Native, Node on JS), so the UDS round-trip contract belongs in the
  * shared suite rather than in the per-backend posix and NIO suites. A listener binds a unique path under `/tmp` (short enough to stay well under
  * the 108-byte `sun_path` limit on every platform), a client connects to it, a known message round-trips through an echo handler, and the
  * listener reports the Unix address with port `-1`.
  *
  * The path uses `nanoTime` for a fresh name per run (no `java.util.UUID` / `SecureRandom`, which Native lacks; no `java.io.File`, which Scala.js
  * lacks). `/tmp` exists on Linux and macOS, the two OSes any of these backends runs on.
  */
class TransportUnixSocketTest extends Test:

    import AllowUnsafe.embrace.danger

    "Transport UDS round-trip (every backend via NetPlatform.transport)" - {
        "connectUnix + listenUnix round-trips a known message through an echo handler" in {
            val transport = NetPlatform.transport
            val path      = s"/tmp/kyo-uds-${java.lang.System.nanoTime()}.sock"
            for
                accepted <- Channel.init[Unit](1)
                listener <- transport.listenUnix(path, 16) { serverConn =>
                    discard(Sync.Unsafe.evalOrThrow {
                        Fiber.initUnscoped {
                            Abort.run[Closed] {
                                accepted.put(()).andThen {
                                    Loop.foreach {
                                        serverConn.inbound.safe.take.map { chunk =>
                                            serverConn.outbound.safe.put(chunk).andThen(Loop.continue)
                                        }
                                    }
                                }
                            }.unit
                        }
                    })
                }.safe.get
                client <- transport.connectUnix(path).safe.get
                message = "kyo-uds-roundtrip".getBytes("UTF-8")
                _ <- client.outbound.safe.put(Span.fromUnsafe(message))
                _ <- accepted.take
                echoed <- Loop(Array.emptyByteArray) { acc =>
                    if acc.length >= message.length then Loop.done(acc)
                    else client.inbound.safe.take.map(chunk => Loop.continue(acc ++ chunk.toArray))
                }
            yield
                client.close()
                listener.close()
                assert(listener.port == -1, s"Unix listener port must be -1, got ${listener.port}")
                assert(listener.address == NetAddress.Unix(path), s"unexpected Unix address ${listener.address}")
                assert(echoed.sameElements(message), s"UDS echo mismatch: got '${new String(echoed, "UTF-8")}'")
            end for
        }
    }

end TransportUnixSocketTest
