package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.client.*
import kyo.internal.http1.*
import kyo.net.TestChannelTransport
import kyo.net.internal.transport.Connection as TransportConnection
import scala.language.implicitConversions

/** Streaming-response connection-lifecycle tests for HttpClientBackend.
  *
  * `TransportConnection.inMemoryPair()` cross-wires two channel-backed `kyo.net.Connection`s so the test plays the server at exact byte
  * boundaries, no sockets. Parser callbacks run inside each offer; the body decode is a background task, so cross-task assertions await, never sleep.
  */
class HttpClientBackendStreamingTest extends kyo.BaseHttpTest:

    import AllowUnsafe.embrace.danger

    private def spanOf(s: String): Span[Byte] =
        Span.fromUnsafe(s.getBytes(StandardCharsets.US_ASCII))

    private def spanToString(s: Span[Byte]): String =
        new String(s.toArray, StandardCharsets.US_ASCII)

    private val dripRoute  = HttpRoute.getRaw("drip").response(_.bodyStream)
    private val plainRoute = HttpRoute.getRaw("plain").response(_.bodyText)
    private def dripReq    = HttpRequest.getRaw(HttpUrl.fromUri("/drip"))
    private def plainReq   = HttpRequest.getRaw(HttpUrl.fromUri("/plain"))

    private val streamHeaders = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
    private val chunk1        = "6\r\nchunk1\r\n"
    private val chunk2AndEnd  = "6\r\nchunk2\r\n0\r\n\r\n"
    private val plainHeaders  = "HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n"
    private val plainBody     = "fresh-body"

    /** Serve one request on `server`: await the request bytes, then offer the given response fragments. */
    private def serveOnce(server: kyo.net.Connection, fragments: Seq[String])(using Frame): Unit < (Async & Abort[Closed]) =
        server.inbound.safe.take.map { _ =>
            Sync.Unsafe.defer(fragments.foreach(f => discard(server.outbound.offer(spanOf(f)))))
        }

    "bodyOutcome seam" - {

        "completes true only after the terminal chunk, leaving inbound clean for the next request" in {
            val (clientConn, serverConn) = TransportConnection.inMemoryPair()
            val http1                    = Http1ClientConnection.init(clientConn.inbound, clientConn.outbound)
            val conn                     = new HttpConnection(clientConn, http1, "test", 80, false, "test")
            val backend                  = HttpClientBackend.init(new TestChannelTransport(Seq.empty), 2, 60.seconds)
            val bodyOutcome              = Promise.Unsafe.init[Boolean, Any]()
            val respFiber                = backend.sendStreaming(conn, dripRoute, dripReq, 1024 * 1024, Absent, Present(bodyOutcome))
            serverConn.inbound.safe.take.map { requestSpan =>
                assert(spanToString(requestSpan).startsWith("GET /drip HTTP/1.1\r\n"))
                discard(serverConn.outbound.offer(spanOf(streamHeaders)))
                discard(serverConn.outbound.offer(spanOf(chunk1)))
                respFiber.safe.get.map { resp =>
                    // The terminal chunk is the only writer of `true`, unsent here, so this negative assertion is race-free.
                    assert(bodyOutcome.poll().isEmpty, "bodyOutcome must not complete before the terminal chunk")
                    discard(serverConn.outbound.offer(spanOf(chunk2AndEnd)))
                    bodyOutcome.safe.get.map { reusable =>
                        assert(reusable, "a fully-delivered chunked body must mark the connection reusable")
                        assert(clientConn.inbound.empty().getOrThrow, "inbound must be empty at release time")
                        assert(clientConn.inbound.pendingTakes().getOrThrow == 0, "no taker may remain registered at release time")
                        // The handed-out stream still delivers the full decoded body.
                        resp.fields.body.run.map { chunks =>
                            assert(chunks.foldLeft("")(_ + spanToString(_)) == "chunk1chunk2")
                            // A clean connection serves a subsequent buffered request correctly.
                            val respFiber2 = backend.sendBuffered(conn, plainRoute, plainReq, 1024 * 1024, Absent)
                            serverConn.inbound.safe.take.map { _ =>
                                discard(serverConn.outbound.offer(spanOf(plainHeaders)))
                                discard(serverConn.outbound.offer(spanOf(plainBody)))
                                respFiber2.safe.get.map { resp2 =>
                                    assert(resp2.fields.body == plainBody)
                                }
                            }
                        }
                    }
                }
            }
        }

        "completes false when the consumer is interrupted before the terminal chunk" in {
            val (clientConn, serverConn) = TransportConnection.inMemoryPair()
            val http1                    = Http1ClientConnection.init(clientConn.inbound, clientConn.outbound)
            val conn                     = new HttpConnection(clientConn, http1, "test", 80, false, "test")
            val backend                  = HttpClientBackend.init(new TestChannelTransport(Seq.empty), 2, 60.seconds)
            val bodyOutcome              = Promise.Unsafe.init[Boolean, Any]()
            val respFiber                = backend.sendStreaming(conn, dripRoute, dripReq, 1024 * 1024, Absent, Present(bodyOutcome))
            serverConn.inbound.safe.take.map { _ =>
                discard(serverConn.outbound.offer(spanOf(streamHeaders)))
                discard(serverConn.outbound.offer(spanOf(chunk1)))
                respFiber.safe.get.map { resp =>
                    Latch.init(1).map { firstChunk =>
                        Fiber.init(resp.fields.body.foreachChunk(_ => firstChunk.release)).map { consumer =>
                            firstChunk.await.andThen {
                                // Consumer got chunk1 and is parked on chunk2. Interrupting it unwinds the stream run, whose
                                // finalizer closes the decoded channel, so the decoder's next delivery taints the connection.
                                consumer.interrupt.unit.andThen {
                                    consumer.getResult.map { _ =>
                                        discard(serverConn.outbound.offer(spanOf(chunk2AndEnd)))
                                        bodyOutcome.safe.get.map { reusable =>
                                            assert(!reusable, "an interrupted streaming body must mark the connection non-reusable")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Stream.take discards the continuation without unwinding, so no finalizer fires and the decode drains to Done, leaving the
        // connection reusable (unlike the interrupted leaf above). If take ever finalizes eagerly, this flips and needs re-review.
        "completes true when the consumer stops early but the body drains in the background" in {
            val (clientConn, serverConn) = TransportConnection.inMemoryPair()
            val http1                    = Http1ClientConnection.init(clientConn.inbound, clientConn.outbound)
            val conn                     = new HttpConnection(clientConn, http1, "test", 80, false, "test")
            val backend                  = HttpClientBackend.init(new TestChannelTransport(Seq.empty), 2, 60.seconds)
            val bodyOutcome              = Promise.Unsafe.init[Boolean, Any]()
            val respFiber                = backend.sendStreaming(conn, dripRoute, dripReq, 1024 * 1024, Absent, Present(bodyOutcome))
            serverConn.inbound.safe.take.map { _ =>
                discard(serverConn.outbound.offer(spanOf(streamHeaders)))
                discard(serverConn.outbound.offer(spanOf(chunk1)))
                respFiber.safe.get.map { resp =>
                    resp.fields.body.take(1).run.map { chunks =>
                        assert(chunks.size == 1 && spanToString(chunks(0)) == "chunk1")
                        discard(serverConn.outbound.offer(spanOf(chunk2AndEnd)))
                        bodyOutcome.safe.get.map { reusable =>
                            assert(reusable, "a fully-drained body after an early consumer stop leaves the connection reusable")
                            assert(clientConn.inbound.empty().getOrThrow, "inbound must be empty after the drain")
                            assert(clientConn.inbound.pendingTakes().getOrThrow == 0, "no taker may remain after the drain")
                        }
                    }
                }
            }
        }
    }

    "pooled connection with a streaming body in flight" - {

        // Regression guard: returning a pooled connection at headers time lets a second request check it out while the
        // streaming body is still arriving, so its bytes feed the stale decoder and it never gets its own body.
        "a second request must not observe another response's bytes" in {
            val (clientConn1, serverConn1) = TransportConnection.inMemoryPair()
            val (clientConn2, serverConn2) = TransportConnection.inMemoryPair()
            val transport                  = new TestChannelTransport(Seq(clientConn1, clientConn2))
            val backend                    = HttpClientBackend.init(transport, 2, 60.seconds)
            val config                     = HttpClientConfig(timeout = 2.seconds)
            Fiber.init(backend.sendWithConfig(dripRoute, dripReq, config)(r => r)).map { f1 =>
                serveOnce(serverConn1, Seq(streamHeaders, chunk1)).andThen {
                    f1.get.map { resp1 =>
                        // resp1's body is still in flight (no terminal sent). Keep a live partial chunk on the wire so a
                        // stale decoder would be engaged.
                        Sync.Unsafe.defer(discard(serverConn1.outbound.offer(spanOf("6\r\nch")))).andThen {
                            // Serve the second request on whichever connection carries it: a bug reuses poisoned conn1,
                            // correct opens conn2.
                            Fiber.init(serveOnce(serverConn1, Seq(plainHeaders, plainBody))).map { _ =>
                                Fiber.init(serveOnce(serverConn2, Seq(plainHeaders, plainBody))).map { _ =>
                                    Abort.run[HttpException](backend.sendWithConfig(plainRoute, plainReq, config)(r => r)).map {
                                        case Result.Success(resp2) =>
                                            assert(
                                                resp2.fields.body == plainBody,
                                                s"second request received another response's bytes: '${resp2.fields.body}'"
                                            )
                                        case other =>
                                            fail(
                                                s"second request must succeed with its own body while a streaming body is in flight, got: $other"
                                            )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Guard against overcorrection: once the streaming body drains, the SAME connection is released and reused
        // (connectCount stays 1). Fails a fix that discards or strands drained streaming connections.
        "after the body drains, the connection is released and reused" in {
            val (clientConn1, serverConn1) = TransportConnection.inMemoryPair()
            val transport                  = new TestChannelTransport(Seq(clientConn1))
            val backend                    = HttpClientBackend.init(transport, 2, 60.seconds)
            val config                     = HttpClientConfig(timeout = 2.seconds)
            Fiber.init(backend.sendWithConfig(dripRoute, dripReq, config)(r => r)).map { f1 =>
                serveOnce(serverConn1, Seq(streamHeaders, chunk1, chunk2AndEnd)).andThen {
                    f1.get.map { resp1 =>
                        resp1.fields.body.run.map { chunks =>
                            assert(chunks.foldLeft("")(_ + spanToString(_)) == "chunk1chunk2")
                            Fiber.init(serveOnce(serverConn1, Seq(plainHeaders, plainBody))).map { _ =>
                                backend.sendWithConfig(plainRoute, plainReq, config)(r => r).map { resp2 =>
                                    assert(resp2.fields.body == plainBody)
                                    assert(
                                        transport.connectCount == 1,
                                        "the drained connection must be pooled and reused, not replaced"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Interruption variant: a mid-stream interrupt closes the decoded channel via the finalizer, so the decoder's next
        // delivery taints the connection, the pool discards it, and the next request opens a fresh one.
        "an interrupted consumer discards the connection instead of pooling it" in {
            val (clientConn1, serverConn1) = TransportConnection.inMemoryPair()
            val (clientConn2, serverConn2) = TransportConnection.inMemoryPair()
            val transport                  = new TestChannelTransport(Seq(clientConn1, clientConn2))
            val backend                    = HttpClientBackend.init(transport, 2, 60.seconds)
            val config                     = HttpClientConfig(timeout = 2.seconds)
            Fiber.init(backend.sendWithConfig(dripRoute, dripReq, config)(r => r)).map { f1 =>
                serveOnce(serverConn1, Seq(streamHeaders, chunk1)).andThen {
                    f1.get.map { resp1 =>
                        Latch.init(1).map { firstChunk =>
                            Fiber.init(resp1.fields.body.foreachChunk(_ => firstChunk.release)).map { consumer =>
                                firstChunk.await.andThen {
                                    // chunk1 reached the consumer, which is now parked awaiting chunk2.
                                    consumer.interrupt.unit.andThen {
                                        consumer.getResult.map { _ =>
                                            // Finalizers ran; the next body fragment makes the decoder observe the closed channel -> discard.
                                            Sync.Unsafe.defer(discard(serverConn1.outbound.offer(spanOf(chunk2AndEnd)))).andThen {
                                                clientConn1.onClosing.safe.get.andThen {
                                                    Sync.Unsafe.defer(clientConn1.isOpen).map { open =>
                                                        assert(!open, "a tainted streaming connection must be closed, not pooled")
                                                        Fiber.init(serveOnce(serverConn2, Seq(plainHeaders, plainBody))).map { _ =>
                                                            backend.sendWithConfig(plainRoute, plainReq, config)(r => r).map { resp2 =>
                                                                assert(resp2.fields.body == plainBody)
                                                                assert(
                                                                    transport.connectCount == 2,
                                                                    "the request after a discard must open a fresh connection"
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end HttpClientBackendStreamingTest
