package kyo

import kyo.net.NetPlatform

/** Unix-domain-socket transport tests, over kyo-net's cross-platform `connectUnix`/`listenUnix`. Runs identically on every platform kyo-net
  * targets (JVM, JS, Native, Wasm); the client side uses a kyo-net `Connection` rather than a raw JVM `SocketChannel`, mirroring kyo-net's own
  * `TransportUnixSocketTest`.
  *
  * Every leaf gates on `Transport.supportsUnixSockets` and cancels where the transport cannot bind AF_UNIX paths (Node on Windows maps the
  * local domain to named pipes, so a filesystem listen path fails EACCES there), mirroring `TransportUnixSocketTest`.
  */
class JsonRpcTransportUnixTest extends JsonRpcTest:

    import AllowUnsafe.embrace.danger

    private def assumeUnixSockets()(using Frame): Unit =
        if !NetPlatform.transport.supportsUnixSockets then cancel("AF_UNIX sockets unsupported on this platform")

    /** Connect a client to `sock`, send `payload`, and close. kyo-net flushes the queued outbound bytes before releasing the socket, so the
      * server receives the frame even though the client closes immediately after the put.
      */
    private def clientSend(sock: Path, payload: String)(using Frame): Unit < (Async & Abort[Throwable]) =
        NetPlatform.transport.connectUnix(sock.toString).safe.get.map { client =>
            client.outbound.safe.put(Span.fromUnsafe(payload.getBytes("UTF-8"))).andThen(Sync.defer(client.close()))
        }

    // `unixDomain` binds the listener, which creates the socket file, in the step that starts the listen fiber, and
    // registers their release only once the join returns. An interrupt landing at that join leaves both behind, and
    // the next bind on the same path fails. The join lasts a listen round trip, so the rounds interrupt at staggered
    // delays around it, and the check is the bind itself.
    "an interrupt landing as the listener binds leaves no listener or socket file behind" in {
        assumeUnixSockets()
        Path.run(Path.tempDir("kyo-jsonrpc-uds-").map { tempDir =>
            val sock   = Path(tempDir, "test.sock")
            val rounds = 40
            for
                _ <- Loop.indexed { i =>
                    if i >= rounds then Loop.done
                    else
                        Fiber.initUnscoped(Scope.run(JsonRpcTransport.unixDomain(sock).andThen(Async.never))).map { fiber =>
                            Async.delay((i % 3).millis)(fiber.interrupt).andThen(fiber.getResult).andThen(Loop.continue)
                        }
                }
                bound <- Abort.run[Throwable](Scope.run(JsonRpcTransport.unixDomain(sock).unit))
                left  <- sock.exists
            yield
                assert(bound.isSuccess, s"the path is still held by a listener a round left behind: $bound")
                assert(!left, "the socket file is still there after the last transport closed")
            end for
        })
    }

    "unixDomain binds and accepts a connection" in {
        assumeUnixSockets()
        Path.run(Path.tempDir("kyo-jsonrpc-uds-").map { tempDir =>
            val sock = Path(tempDir, "test.sock")
            Scope.run {
                JsonRpcTransport.unixDomain(sock).map { _ =>
                    sock.exists.map(exists => assert(exists)).andThen {
                        NetPlatform.transport.connectUnix(sock.toString).safe.get.map { client =>
                            Sync.defer {
                                val open = client.isOpen
                                client.close()
                                assert(open)
                            }
                        }
                    }
                }
            }
        })
    }

    "unixDomain round-trips one envelope" in {
        assumeUnixSockets()
        Path.run(Path.tempDir("kyo-jsonrpc-uds-").map { tempDir =>
            val sock = Path(tempDir, "test.sock")
            Scope.run {
                JsonRpcTransport.unixDomain(sock).map { t =>
                    clientSend(sock, """{"jsonrpc":"2.0","method":"ping"}""" + "\n").andThen {
                        t.incoming.take(1).run.map { frames =>
                            assert(frames.size == 1)
                            frames.head match
                                case JsonRpcNotification("ping", _, _) => succeed
                                case other                             => fail(s"unexpected $other")
                        }
                    }
                }
            }
        })
    }

    "unixDomain Scope cleanup deletes socket file" in {
        assumeUnixSockets()
        Path.run(Path.tempDir("kyo-jsonrpc-uds-").map { tempDir =>
            val sock = Path(tempDir, "test.sock")
            Scope.run {
                JsonRpcTransport.unixDomain(sock).map(_ => ())
            }.andThen {
                sock.exists.map(exists => assert(!exists))
            }
        })
    }

    "unixDomain framer override changes wire shape" in {
        assumeUnixSockets()
        Path.run(Path.tempDir("kyo-jsonrpc-uds-").map { tempDir =>
            val sock = Path(tempDir, "test.sock")
            Scope.run {
                JsonRpcTransport.unixDomain(sock, framer = JsonRpcFramer.contentLength).map { t =>
                    val body = """{"jsonrpc":"2.0","method":"p"}"""
                    clientSend(sock, s"Content-Length: ${body.length}\r\n\r\n$body").andThen {
                        t.incoming.take(1).run.map { frames =>
                            assert(frames.size == 1)
                            frames.head match
                                case JsonRpcNotification("p", _, _) => succeed
                                case other                          => fail(s"unexpected $other")
                        }
                    }
                }
            }
        })
    }

end JsonRpcTransportUnixTest
