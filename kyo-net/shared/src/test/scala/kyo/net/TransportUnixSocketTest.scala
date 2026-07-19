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

    "the connect deadline applies to Unix sockets" - {

        // The deadline previously skipped Unix connects entirely: the arm was guarded on `port >= 0`, so connectUnix accepted a connectTimeout
        // and ignored it. Removing that guard is what this leaf protects, and it protects the dangerous direction: a timer that now runs for
        // every Unix connect must not fire on one that connects normally. A misfire here would break every Unix connection rather than only the
        // parked ones, so this runs on every platform.
        "a finite deadline does not disturb a Unix connect that completes" in {
            val transport = NetPlatform.transport
            val path      = s"/tmp/kyo-uds-deadline-${java.lang.System.nanoTime()}.sock"
            transport.listenUnix(path, 16)(_ => ()).safe.get.map { listener =>
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

        // The deadline-fires direction needs a Unix connect that actually parks, which is OS-dependent: on Linux a connect to a socket whose
        // accept queue is full blocks, so the deadline is reachable, while macOS fails it fast with ECONNREFUSED (measured: with backlog 1 and a
        // listener that never accepts, every subsequent connect returned ECONNREFUSED in well under a millisecond). So this asserts the typed
        // leaf where the OS can produce it and cancels where it cannot, rather than asserting something macOS would satisfy vacuously.
        "a Unix connect that parks past its deadline fails with the typed Unix timeout leaf" in {
            if !kyo.net.internal.posix.PosixConstants.isLinux then
                cancel("a Unix connect only parks on Linux; macOS fails a full-backlog connect fast with ECONNREFUSED")
            val transport = NetPlatform.transport
            val path      = s"/tmp/kyo-uds-park-${java.lang.System.nanoTime()}.sock"
            val timeout   = 200.millis
            // Backlog 1 and a handler that never returns leaves the accept queue full, so a later connect parks.
            transport.listenUnix(path, 1)(_ => ()).safe.get.map { listener =>
                Loop.indexed(0) { (i, _) =>
                    if i >= 8 then Loop.done(())
                    else Abort.run[NetException | Closed](transport.connectUnix(path, timeout).safe.get).map(_ => Loop.continue(0))
                }.andThen {
                    Abort.run[NetException | Closed | Timeout](
                        Async.timeout(5.seconds)(transport.connectUnix(path, timeout).safe.get)
                    ).map { outcome =>
                        listener.close()
                        outcome match
                            case Result.Failure(e: NetUnixConnectTimeoutException) =>
                                assert(e.timeout == timeout, s"expected the connect's own $timeout deadline, got ${e.timeout}")
                                assert(e.path == path, s"expected the Unix path in the leaf, got ${e.path}")
                            case other =>
                                assert(
                                    false,
                                    s"expected NetUnixConnectTimeoutException($timeout) once the accept queue is full, got $other"
                                )
                        end match
                    }
                }
            }
        }
    }

end TransportUnixSocketTest
