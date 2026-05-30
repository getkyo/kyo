package kyo

import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files

class JsonRpcTransportUnixTest extends JsonRpcTest:

    "unixDomain binds and accepts a connection" in run {
        val tempDir = Files.createTempDirectory("kyo-jsonrpc-uds-")
        val sock    = tempDir.resolve("test.sock")
        Scope.run {
            JsonRpcTransport.unixDomain(sock).map { _ =>
                Sync.defer {
                    val client = SocketChannel.open(UnixDomainSocketAddress.of(sock))
                    try
                        assert(Files.exists(sock))
                        assert(client.isConnected)
                    finally client.close()
                    end try
                }
            }
        }
    }

    "unixDomain round-trips one envelope" in run {
        val tempDir = Files.createTempDirectory("kyo-jsonrpc-uds-")
        val sock    = tempDir.resolve("test.sock")
        Scope.run {
            JsonRpcTransport.unixDomain(sock).map { t =>
                Sync.defer {
                    val client = SocketChannel.open(UnixDomainSocketAddress.of(sock))
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
                            case JsonRpcEnvelope.Notification("ping", _, _) => succeed
                            case other                                      => fail(s"unexpected $other")
                    }
                }
            }
        }
    }

    "unixDomain Scope cleanup deletes socket file" in run {
        val tempDir = Files.createTempDirectory("kyo-jsonrpc-uds-")
        val sock    = tempDir.resolve("test.sock")
        Scope.run {
            JsonRpcTransport.unixDomain(sock).map(_ => ())
        }.andThen {
            Sync.defer(assert(!Files.exists(sock)))
        }
    }

    "unixDomain framer override changes wire shape" in run {
        val tempDir = Files.createTempDirectory("kyo-jsonrpc-uds-")
        val sock    = tempDir.resolve("test.sock")
        Scope.run {
            JsonRpcTransport.unixDomain(sock, framer = JsonRpcTransport.Framer.contentLength).map { t =>
                Sync.defer {
                    val client = SocketChannel.open(UnixDomainSocketAddress.of(sock))
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
                            case JsonRpcEnvelope.Notification("p", _, _) => succeed
                            case other                                   => fail(s"unexpected $other")
                    }
                }
            }
        }
    }

end JsonRpcTransportUnixTest
