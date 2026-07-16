package kyo

import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

/** JVM-only transport test: exercises Unix domain socket bind/accept mechanics unavailable on JS/Native. */
class JsonRpcTransportUnixTest extends JsonRpcTest:

    "unixDomain binds and accepts a connection" in {
        Scope.run {
            Path.run {
                Path.tempDir("kyo-jsonrpc-uds-").map { tempDir =>
                    val sock = Path(tempDir, "test.sock")
                    Scope.run {
                        JsonRpcTransport.unixDomain(sock).map { _ =>
                            sock.exists.map(exists => assert(exists)).andThen {
                                Sync.defer {
                                    val client = SocketChannel.open(UnixDomainSocketAddress.of(sock.toString))
                                    try assert(client.isConnected)
                                    finally client.close()
                                    end try
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "unixDomain round-trips one envelope" in {
        Scope.run {
            Path.run {
                Path.tempDir("kyo-jsonrpc-uds-").map { tempDir =>
                    val sock = Path(tempDir, "test.sock")
                    Scope.run {
                        JsonRpcTransport.unixDomain(sock).map { t =>
                            Sync.defer {
                                val client = SocketChannel.open(UnixDomainSocketAddress.of(sock.toString))
                                try
                                    val payload = """{"jsonrpc":"2.0","method":"ping"}""" + "\n"
                                    discard(client.write(ByteBuffer.wrap(payload.getBytes("UTF-8"))))
                                    client.shutdownOutput()
                                finally client.close()
                                end try
                            }.andThen {
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
        }
    }

    "unixDomain Scope cleanup deletes socket file" in {
        Scope.run {
            Path.run {
                Path.tempDir("kyo-jsonrpc-uds-").map { tempDir =>
                    val sock = Path(tempDir, "test.sock")
                    Scope.run {
                        JsonRpcTransport.unixDomain(sock).map(_ => ())
                    }.andThen {
                        sock.exists.map(exists => assert(!exists))
                    }
                }
            }
        }
    }

    "unixDomain framer override changes wire shape" in {
        Scope.run {
            Path.run {
                Path.tempDir("kyo-jsonrpc-uds-").map { tempDir =>
                    val sock = Path(tempDir, "test.sock")
                    Scope.run {
                        JsonRpcTransport.unixDomain(sock, framer = JsonRpcFramer.contentLength).map { t =>
                            Sync.defer {
                                val client = SocketChannel.open(UnixDomainSocketAddress.of(sock.toString))
                                try
                                    val body    = """{"jsonrpc":"2.0","method":"p"}"""
                                    val payload = s"Content-Length: ${body.length}\r\n\r\n$body"
                                    discard(client.write(ByteBuffer.wrap(payload.getBytes("UTF-8"))))
                                    client.shutdownOutput()
                                finally client.close()
                                end try
                            }.andThen {
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
        }
    }

end JsonRpcTransportUnixTest
