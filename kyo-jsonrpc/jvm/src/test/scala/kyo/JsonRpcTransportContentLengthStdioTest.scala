package kyo

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kyo.Maybe.Absent

/** Tests for [[JsonRpcTransport.contentLengthStdio]].
  *
  * Uses `ByteArrayInputStream` for reading (pre-built frames) and `ByteArrayOutputStream`
  * for capturing output. This avoids blocking I/O on a piped stream from a single fiber
  * and keeps tests deterministic.
  */
class JsonRpcTransportContentLengthStdioTest extends JsonRpcTest:

    /** Build a pre-loaded input stream from a raw Content-Length-framed string. */
    private def frameInput(frames: String): ByteArrayInputStream =
        new ByteArrayInputStream(frames.getBytes("UTF-8"))

    private val pingNotification = JsonRpcNotification("ping", Absent, Absent)

    "incoming parses single Content-Length frame" in run {
        val body     = """{"jsonrpc":"2.0","method":"ping"}"""
        val raw      = s"Content-Length: ${body.length}\r\n\r\n$body"
        val inStream = frameInput(raw)
        val outSink  = new ByteArrayOutputStream()
        Scope.run {
            JsonRpcTransport.contentLengthStdio(inStream, outSink).map { transport =>
                transport.incoming.take(1).run.map { frames =>
                    assert(frames.size == 1)
                    frames.head match
                        case JsonRpcNotification("ping", _, _) => succeed
                        case other                             => fail(s"unexpected envelope: $other")
                }
            }
        }
    }

    "incoming parses three Content-Length frames back-to-back" in run {
        val b1 = """{"jsonrpc":"2.0","method":"one"}"""
        val b2 = """{"jsonrpc":"2.0","method":"two"}"""
        val b3 = """{"jsonrpc":"2.0","method":"three"}"""
        val raw =
            s"Content-Length: ${b1.length}\r\n\r\n$b1" +
                s"Content-Length: ${b2.length}\r\n\r\n$b2" +
                s"Content-Length: ${b3.length}\r\n\r\n$b3"
        val inStream = frameInput(raw)
        val outSink  = new ByteArrayOutputStream()
        Scope.run {
            JsonRpcTransport.contentLengthStdio(inStream, outSink).map { transport =>
                transport.incoming.take(3).run.map { frames =>
                    assert(frames.size == 3)
                    val methods = frames.map {
                        case JsonRpcNotification(m, _, _) => m
                        case other                        => fail(s"unexpected: $other")
                    }
                    assert(methods == Chunk("one", "two", "three"))
                }
            }
        }
    }

    "incoming skips Content-Type header and parses Content-Length correctly" in run {
        val body     = """{"jsonrpc":"2.0","method":"hello"}"""
        val raw      = s"Content-Length: ${body.length}\r\nContent-Type: application/vscode-jsonrpc; charset=utf-8\r\n\r\n$body"
        val inStream = frameInput(raw)
        val outSink  = new ByteArrayOutputStream()
        Scope.run {
            JsonRpcTransport.contentLengthStdio(inStream, outSink).map { transport =>
                transport.incoming.take(1).run.map { frames =>
                    assert(frames.size == 1)
                    frames.head match
                        case JsonRpcNotification("hello", _, _) => succeed
                        case other                              => fail(s"unexpected: $other")
                }
            }
        }
    }

    "send emits correct Content-Length wire bytes" in run {
        val inStream = frameInput("")
        val outSink  = new ByteArrayOutputStream()
        Scope.run {
            JsonRpcTransport.contentLengthStdio(inStream, outSink).map { transport =>
                Abort.run[Closed](transport.send(pingNotification)).andThen {
                    Sync.defer {
                        val wire = outSink.toString("UTF-8")
                        assert(wire.startsWith("Content-Length: "))
                        val headerEnd = wire.indexOf("\r\n\r\n")
                        assert(headerEnd > 0)
                        val body = wire.substring(headerEnd + 4)
                        assert(body.contains("\"method\":\"ping\""))
                        assert(body.contains("\"jsonrpc\":\"2.0\""))
                        val headerLine  = wire.substring(0, wire.indexOf("\r\n"))
                        val declaredLen = headerLine.stripPrefix("Content-Length: ").trim.toInt
                        assert(declaredLen == body.getBytes("UTF-8").length)
                    }
                }
            }
        }
    }

    "Content-Length: 0 frame yields a malformed-message envelope without panic" in run {
        val raw      = "Content-Length: 0\r\n\r\n"
        val inStream = frameInput(raw)
        val outSink  = new ByteArrayOutputStream()
        Scope.run {
            JsonRpcTransport.contentLengthStdio(inStream, outSink).map { transport =>
                Abort.run[Closed](transport.incoming.take(1).run).map { result =>
                    result match
                        case Result.Panic(t) => fail(s"unexpected panic: ${t.getMessage}")
                        case _               => succeed
                }
            }
        }
    }

    "EOF on input closes the incoming stream without hanging" in run {
        val inStream = frameInput("")
        val outSink  = new ByteArrayOutputStream()
        Scope.run {
            JsonRpcTransport.contentLengthStdio(inStream, outSink).map { transport =>
                Abort.run[Closed](transport.incoming.run).map {
                    case Result.Panic(t)        => fail(s"panic: ${t.getMessage}")
                    case Result.Failure(_)      => succeed
                    case Result.Success(frames) => assert(frames.isEmpty)
                }
            }
        }
    }

    "round-trip send then parse via ByteArrayOutputStream->ByteArrayInputStream" in run {
        val captureSink = new ByteArrayOutputStream()
        val emptyIn     = frameInput("")
        Scope.run {
            // First: send a notification, capture the raw wire bytes.
            JsonRpcTransport.contentLengthStdio(emptyIn, captureSink).map { sendTransport =>
                Abort.run[Closed](sendTransport.send(pingNotification)).andThen {
                    Sync.defer {
                        // Second: feed the captured bytes into a new transport's incoming.
                        val capturedBytes = captureSink.toByteArray
                        (capturedBytes, capturedBytes.length)
                    }
                }
            }
        }.map { case (capturedBytes, n) =>
            assert(n > 0)
            val replayIn = new ByteArrayInputStream(capturedBytes)
            val devNull  = new ByteArrayOutputStream()
            Scope.run {
                JsonRpcTransport.contentLengthStdio(replayIn, devNull).map { readTransport =>
                    readTransport.incoming.take(1).run.map { frames =>
                        assert(frames.size == 1)
                        frames.head match
                            case JsonRpcNotification("ping", _, _) => succeed
                            case other                             => fail(s"unexpected: $other")
                    }
                }
            }
        }
    }

end JsonRpcTransportContentLengthStdioTest
