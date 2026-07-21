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
                _      <- Scope.ensure(Sync.defer(listener.close()))
                client <- transport.connectUnix(path).safe.get
                _      <- Scope.ensure(Sync.defer(client.close()))
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

    "the connect deadline applies to Unix sockets" - {

        // The deadline previously skipped Unix connects entirely: the arm was guarded on `port >= 0`, so connectUnix accepted a connectTimeout
        // and ignored it. Removing that guard is what this leaf protects, and it protects the dangerous direction: a timer that now runs for
        // every Unix connect must not fire on one that connects normally. A misfire here would break every Unix connection rather than only the
        // parked ones, so this runs on every platform.
        "a finite deadline does not disturb a Unix connect that completes" in {
            val transport = NetPlatform.transport
            val path      = s"/tmp/kyo-uds-deadline-${java.lang.System.nanoTime()}.sock"
            transport.listenUnix(path, 16)(_ => ()).safe.get.map { listener =>
                Scope.ensure(Sync.defer(listener.close())).andThen {
                    Abort.run[NetException | Closed](transport.connectUnix(path, 5.seconds).safe.get).map { outcome =>
                        listener.close()
                        outcome match
                            case Result.Success(conn) =>
                                conn.close()
                                succeed
                            case other =>
                                assert(false, s"a Unix connect with a generous finite deadline must succeed, got $other")
                        end match
                    }
                }
            }
        }
    }

end TransportUnixSocketTest
