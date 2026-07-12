package kyo

import kyo.net.NetException
import kyo.net.NetPlatform

/** Unix-domain-socket transport tests, over kyo-net's cross-platform `connectUnix`/`listenUnix`. Runs identically on every platform kyo-net
  * targets (JVM, JS, Native, Wasm); the client side uses a kyo-net `Connection` rather than a raw JVM `SocketChannel`, mirroring kyo-net's own
  * `TransportUnixSocketTest`.
  */
class JsonRpcTransportUnixTest extends JsonRpcTest:

    import AllowUnsafe.embrace.danger

    /** Connect a client to `sock`, send `payload`, and close. kyo-net flushes the queued outbound bytes before releasing the socket, so the
      * server receives the frame even though the client closes immediately after the put.
      */
    private def clientSend(sock: Path, payload: String)(using Frame): Unit < (Async & Abort[NetException | Closed]) =
        NetPlatform.transport.connectUnix(sock.toString).safe.get.map { client =>
            client.outbound.safe.put(Span.fromUnsafe(payload.getBytes("UTF-8"))).andThen(Sync.defer(client.close()))
        }

    "unixDomain binds and accepts a connection" in {
        Path.tempDir("kyo-jsonrpc-uds-").map { tempDir =>
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
        }
    }

    "unixDomain round-trips one envelope" in {
        Path.tempDir("kyo-jsonrpc-uds-").map { tempDir =>
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
        }
    }

    "unixDomain Scope cleanup deletes socket file" in {
        Path.tempDir("kyo-jsonrpc-uds-").map { tempDir =>
            val sock = Path(tempDir, "test.sock")
            Scope.run {
                JsonRpcTransport.unixDomain(sock).map(_ => ())
            }.andThen {
                sock.exists.map(exists => assert(!exists))
            }
        }
    }

    "unixDomain framer override changes wire shape" in {
        Path.tempDir("kyo-jsonrpc-uds-").map { tempDir =>
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
        }
    }

end JsonRpcTransportUnixTest
