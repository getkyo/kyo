package kyo.net

import kyo.*

/** Proof that the cross-backend harness in [[Test]] fans a single scenario over every registered I/O backend.
  *
  * Each scenario is written once and registered by [[eachBackend]] / [[eachBackendTls]] as one leaf per backend (io_uring, epoll, kqueue, and
  * the NIO floor on the JVM; io_uring/epoll/kqueue on Native; node on JS). Backends unavailable on the running host appear as canceled leaves;
  * on macOS the kqueue and nio leaves run while io_uring and epoll cancel, on Linux the reverse. Every active leaf round-trips a real message
  * through a real echo handler over a real socket: no mocks, no in-memory shortcut.
  */
class BackendEchoPilotTest extends Test:

    import AllowUnsafe.embrace.danger

    /** A fire-and-forget echo handler: each accepted connection spawns a fiber that copies every inbound chunk straight back to its outbound,
      * so a connected client reads back exactly what it wrote. The handler must spawn an actual fiber to drive the read/write loop (the loop is
      * an `Async` computation, so it has to be run, not merely constructed); `Sync.Unsafe.evalOrThrow { Fiber.initUnscoped { ... } }` is the
      * spawn idiom the other shared transport suites use inside a `Connection => Unit` handler.
      */
    private def echo(conn: Connection): Unit =
        discard(Sync.Unsafe.evalOrThrow {
            Fiber.initUnscoped {
                Abort.run[Closed] {
                    Loop.foreach {
                        conn.inbound.safe.take.map { chunk =>
                            conn.outbound.safe.put(chunk).andThen(Loop.continue)
                        }
                    }
                }.unit
            }
        })

    /** Read exactly `target` bytes from a connection's inbound channel, concatenated in order. A single TLS record can arrive split across
      * chunks, so the read loops until it has accumulated the full message.
      */
    private def collect(conn: Connection, target: Int)(using Frame): Array[Byte] < (Async & Abort[Closed]) =
        Loop(Array.emptyByteArray) { acc =>
            if acc.length >= target then Loop.done(acc)
            else conn.inbound.safe.take.map(chunk => Loop.continue(acc ++ chunk.toArray))
        }

    "plaintext echo round-trips a message" - eachBackend { transport =>
        for
            listener <- transport.listen("127.0.0.1", 0, 128)(echo).safe.get
            conn     <- transport.connect("127.0.0.1", listener.port).safe.get
            message = "kyo-backend-echo-pilot".getBytes("UTF-8")
            _      <- conn.outbound.safe.put(Span.fromUnsafe(message))
            echoed <- collect(conn, message.length)
        yield
            conn.close()
            listener.close()
            assert(
                echoed.sameElements(message),
                s"plaintext echo mismatch on this backend: got '${new String(echoed, "UTF-8")}'"
            )
        end for
    }

    "concurrent full-duplex echo over many connections round-trips every byte in order (no dropped submission under overlap)" - eachBackend {
        transport =>
            // Plaintext + full-duplex on purpose: on io_uring a write's get_sqe runs on the engine-FIFO worker while a read re-arm's get_sqe runs
            // on the reap carrier (a plaintext read completes inline on the reap carrier, unlike TLS which re-arms on the FIFO worker), so the two
            // overlap across all connections at once. If the single submission ring is not single-producer-safe, an SQE is dropped and a side hangs
            // to the deadline; a correct driver round-trips every byte in order. This is the cross-backend concurrency guard: it must also pass on
            // epoll/kqueue/nio, where the selector loop is the sole submitter.
            val conns     = 8
            val chunkSize = 128
            val chunkN    = 128 // 16 KB per connection
            val total     = chunkSize * chunkN
            for
                listener <- transport.listen("127.0.0.1", 0, 128)(echo).safe.get
                _ <- Async.foreach(0 until conns, conns) { c =>
                    transport.connect("127.0.0.1", listener.port).safe.get.map { conn =>
                        val payload = Array.tabulate[Byte](total)(i => ((c * 131 + i) % 251).toByte)
                        val writer = Async.foreach(payload.grouped(chunkSize).toSeq, 1) { ch =>
                            conn.outbound.safe.put(Span.fromUnsafe(ch))
                        }.unit
                        val reader = collect(conn, total)
                        Async.zip(writer, reader).map { case (_, echoed) =>
                            conn.close()
                            assert(
                                echoed.sameElements(payload),
                                s"conn $c full-duplex echo mismatch: ${echoed.length}/$total bytes round-tripped"
                            )
                        }
                    }
                }
            yield
                listener.close()
                succeed
            end for
    }

    "TLS echo round-trips a message" - eachBackendTls { (transport, serverTls, clientTls) =>
        for
            listener <- transport.listen("127.0.0.1", 0, 16, serverTls)(echo).safe.get
            conn     <- transport.connect("127.0.0.1", listener.port, clientTls).safe.get
            message = "kyo-backend-tls-echo-pilot".getBytes("UTF-8")
            _      <- conn.outbound.safe.put(Span.fromUnsafe(message))
            echoed <- collect(conn, message.length)
        yield
            conn.close()
            listener.close()
            assert(
                echoed.sameElements(message),
                s"TLS echo mismatch on this backend: got '${new String(echoed, "UTF-8")}'"
            )
        end for
    }

    "bulk TLS transfer spanning many records round-trips intact (exercises the partial-record read re-arm on every backend)" - eachBackendTls {
        (transport, serverTls, clientTls) =>
            // A single TLS record carries at most ~16 KB of plaintext, so a 64 KB-per-connection payload spans multiple records and, with TCP
            // segmentation plus a finite read buffer, reliably delivers records split across recvs. On a split record the decrypt yields zero
            // plaintext and the driver must RE-ARM the read rather than complete it with an empty Span (which the ReadPump reads as EOF and tears
            // the connection down). If that re-arm were broken on any backend, this transfer would truncate, hang to the deadline, or close early;
            // a byte-exact full round-trip proves the re-arm holds end-to-end through the public API. This is the cross-backend proof that the
            // TLS read path is correct without lifting the decrypt out of the driver. Full-duplex (concurrent writer + reader) so the in-flight
            // transfer never deadlocks on channel/socket backpressure.
            val conns     = 4
            val perConn   = 64 * 1024
            val chunkSize = 4 * 1024
            for
                listener <- transport.listen("127.0.0.1", 0, 64, serverTls)(echo).safe.get
                _ <- Async.foreach(0 until conns, conns) { c =>
                    transport.connect("127.0.0.1", listener.port, clientTls).safe.get.map { conn =>
                        val payload = Array.tabulate[Byte](perConn)(i => ((c * 131 + i) % 251).toByte)
                        val writer = Async.foreach(payload.grouped(chunkSize).toSeq, 1) { ch =>
                            conn.outbound.safe.put(Span.fromUnsafe(ch))
                        }.unit
                        val reader = collect(conn, perConn)
                        Async.zip(writer, reader).map { case (_, echoed) =>
                            conn.close()
                            assert(
                                echoed.sameElements(payload),
                                s"conn $c TLS bulk mismatch: ${echoed.length}/$perConn bytes round-tripped"
                            )
                        }
                    }
                }
            yield
                listener.close()
                succeed
            end for
    }

end BackendEchoPilotTest
