package kyo

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kyo.Maybe.Absent
import kyo.Maybe.Present

class MaxInFlightTest extends Test:

    case class PingReq(n: Int) derives Schema, CanEqual
    case class PingResp(n: Int) derives Schema, CanEqual
    case class LogMsg(text: String) derives Schema, CanEqual

    private class CapturingTransport(inner: JsonRpcTransport) extends JsonRpcTransport:
        val sent = new ConcurrentLinkedQueue[JsonRpcEnvelope]()

        def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.defer(discard(sent.add(env))).andThen(inner.send(env))

        def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]] =
            inner.incoming

        def close(using Frame): Unit < Async =
            inner.close
    end CapturingTransport

    "maxInFlight = 2 parks the third concurrent call until a slot is freed" in run {
        val handlerEntered = new AtomicInteger(0)
        // doneSignals: handlers wait for their slot to be released
        val done1 = new AtomicReference[Maybe[Fiber.Promise[Unit, Any]]](Absent)
        val done2 = new AtomicReference[Maybe[Fiber.Promise[Unit, Any]]](Absent)
        val done3 = new AtomicReference[Maybe[Fiber.Promise[Unit, Any]]](Absent)

        val pingOnB = JsonRpcMethod[PingReq, PingResp, Async & Abort[JsonRpcError]]("ping") { (req, _) =>
            Fiber.Promise.init[Unit, Any].map { p =>
                val slot = req.n match
                    case 1 => done1
                    case 2 => done2
                    case _ => done3
                Sync.defer(slot.set(Present(p))).andThen {
                    discard(handlerEntered.incrementAndGet())
                    p.get.andThen(PingResp(req.n))
                }
            }
        }

        val cfg = JsonRpcEndpoint.Config(
            maxInFlight = Present(2),
            cancellation = Absent
        )

        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcEndpoint.init(ta, Seq.empty, cfg).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq(pingOnB), cfg).map { _ =>
                    // Spawn all three calls concurrently
                    Fiber.initUnscoped(
                        Abort.run[JsonRpcError | Closed](endpointA.call[PingReq, PingResp]("ping", PingReq(1)))
                    ).map { fib1 =>
                        Fiber.initUnscoped(
                            Abort.run[JsonRpcError | Closed](endpointA.call[PingReq, PingResp]("ping", PingReq(2)))
                        ).map { fib2 =>
                            Fiber.initUnscoped(
                                Abort.run[JsonRpcError | Closed](endpointA.call[PingReq, PingResp]("ping", PingReq(3)))
                            ).map { fib3 =>
                                // Wait until exactly 2 handlers have entered (slots 1 and 2 acquired)
                                untilTrue(Sync.defer(handlerEntered.get() == 2)).andThen {
                                    // The third call is parked; its handler has NOT entered yet
                                    Sync.defer(assert(handlerEntered.get() == 2, "third call should be parked")).andThen {
                                        // Release slot 1 so the third call can proceed
                                        Sync.defer(done1.get()).map {
                                            case Present(p) => p.completeUnitDiscard
                                            case Absent     => fail("done1 not set")
                                        }.andThen {
                                            // Wait for all three handlers to have entered
                                            untilTrue(Sync.defer(handlerEntered.get() == 3)).andThen {
                                                // Release remaining slots
                                                Sync.defer {
                                                    done2.get() match
                                                        case Present(p) => p.unsafe.completeUnitDiscard()(using AllowUnsafe.embrace.danger)
                                                        case Absent     => ()
                                                    done3.get() match
                                                        case Present(p) => p.unsafe.completeUnitDiscard()(using AllowUnsafe.embrace.danger)
                                                        case Absent     => ()
                                                }.andThen {
                                                    fib1.get.andThen(fib2.get).andThen(fib3.get).map { _ =>
                                                        succeed
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

    "notify is NOT rate-limited by maxInFlight" in run {
        val notifyReceived = new AtomicInteger(0)
        val handlerBlocked = new AtomicReference[Maybe[Fiber.Promise[Unit, Any]]](Absent)

        val pingOnB = JsonRpcMethod[PingReq, PingResp, Async & Abort[JsonRpcError]]("ping") { (_, _) =>
            Fiber.Promise.init[Unit, Any].map { p =>
                Sync.defer(handlerBlocked.set(Present(p))).andThen(p.get.andThen(PingResp(0)))
            }
        }
        val logOnB = JsonRpcMethod[LogMsg, Unit, Async & Abort[JsonRpcError]]("log") { (_, _) =>
            Sync.defer(discard(notifyReceived.incrementAndGet()))
        }

        val cfg = JsonRpcEndpoint.Config(
            maxInFlight = Present(1),
            cancellation = Absent
        )

        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcEndpoint.init(ta, Seq.empty, cfg).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq(pingOnB, logOnB), cfg).map { _ =>
                    // Start one call to consume the single slot
                    Fiber.initUnscoped(
                        Abort.run[JsonRpcError | Closed](endpointA.call[PingReq, PingResp]("ping", PingReq(0)))
                    ).andThen {
                        // Wait for the handler to have acquired the slot and blocked
                        untilTrue(Sync.defer(handlerBlocked.get().isDefined)).andThen {
                            // Send a notification while the slot is full; notify should go through immediately
                            endpointA.notify[LogMsg]("log", LogMsg("hi")).andThen {
                                // Verify notification was received on the server
                                untilTrue(Sync.defer(notifyReceived.get() == 1)).andThen {
                                    // Release the blocked call
                                    Sync.defer(handlerBlocked.get()).map {
                                        case Present(p) =>
                                            p.completeUnitDiscard.andThen(succeed)
                                        case Absent => fail("handler not blocked")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "requestTimeout fires and caller receives JsonRpcError.cancelled" in run {
        val blocked = new AtomicReference[Maybe[Fiber.Promise[Unit, Any]]](Absent)

        val pingOnB = JsonRpcMethod[PingReq, PingResp, Async & Abort[JsonRpcError]]("ping") { (_, _) =>
            Fiber.Promise.init[Unit, Any].map { p =>
                Sync.defer(blocked.set(Present(p))).andThen(p.get.andThen(PingResp(0)))
            }
        }

        val cfg = JsonRpcEndpoint.Config(
            requestTimeout = 100.millis,
            cancellation = Absent
        )

        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcEndpoint.init(ta, Seq.empty, cfg).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq(pingOnB), cfg).map { _ =>
                    Abort.run[JsonRpcError | Closed](
                        endpointA.call[PingReq, PingResp]("ping", PingReq(0))
                    ).map {
                        case Result.Failure(e: JsonRpcError) =>
                            assert(e.code == -32800, s"expected cancelled code -32800, got ${e.code}")
                        case other => fail(s"expected JsonRpcError.cancelled, got $other")
                    }
                }
            }
        }
    }

    "requestTimeout fires and $/cancelRequest appears on transport with LSP policy" in run {
        val pingOnB = JsonRpcMethod[PingReq, PingResp, Async & Abort[JsonRpcError]]("ping") { (_, _) =>
            // Block forever; timeout is the only exit
            Fiber.Promise.init[Unit, Any].map { p => p.get.andThen(PingResp(0)) }
        }

        val cfg = JsonRpcEndpoint.Config(
            requestTimeout = 100.millis,
            cancellation = Present(CancellationPolicy.lsp)
        )

        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capA        = new CapturingTransport(ta)
            val countBefore = capA.sent.size()
            JsonRpcEndpoint.init(capA, Seq.empty, cfg).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq(pingOnB), cfg).map { _ =>
                    Abort.run[JsonRpcError | Closed](
                        endpointA.call[PingReq, PingResp]("ping", PingReq(0))
                    ).map { result =>
                        result match
                            case Result.Failure(_: JsonRpcError) => ()
                            case other                           => fail(s"expected failure, got $other")
                        end match
                    }.andThen {
                        // The cancel notification is enqueued before the call fails, but the writer fiber
                        // delivers it asynchronously; wait until it appears in the capturing transport.
                        untilTrue(Sync.defer {
                            import scala.jdk.CollectionConverters.*
                            capA.sent.iterator().asScala.exists {
                                case JsonRpcEnvelope.Notification("$/cancelRequest", _, _) => true
                                case _                                                     => false
                            }
                        }).andThen(succeed)
                    }
                }
            }
        }
    }

    "requestTimeout fires with cancellation = Absent: no cancel notification sent" in run {
        val pingOnB = JsonRpcMethod[PingReq, PingResp, Async & Abort[JsonRpcError]]("ping") { (_, _) =>
            // Block forever; timeout is the only exit
            Fiber.Promise.init[Unit, Any].map { p => p.get.andThen(PingResp(0)) }
        }

        val cfg = JsonRpcEndpoint.Config(
            requestTimeout = 100.millis,
            cancellation = Absent
        )

        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capA = new CapturingTransport(ta)
            JsonRpcEndpoint.init(capA, Seq.empty, cfg).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq(pingOnB), cfg).map { _ =>
                    Abort.run[JsonRpcError | Closed](
                        endpointA.call[PingReq, PingResp]("ping", PingReq(0))
                    ).map { result =>
                        val cancelNotifications =
                            import scala.jdk.CollectionConverters.*
                            capA.sent.iterator().asScala.filter {
                                case _: JsonRpcEnvelope.Notification => true
                                case _                               => false
                            }.size
                        end cancelNotifications
                        result match
                            case Result.Failure(e: JsonRpcError) =>
                                assert(
                                    e.code == -32800 && cancelNotifications == 0,
                                    s"expected -32800 with 0 cancel notifications, got code=${e.code} notifications=$cancelNotifications"
                                )
                            case other => fail(s"expected failure, got $other")
                        end match
                    }
                }
            }
        }
    }

    "semaphore slot released after call failure: next call proceeds" in run {
        val callCount = new AtomicInteger(0)

        // First call: handler always fails with MethodNotFound
        val failOnB = JsonRpcMethod[PingReq, PingResp, Async & Abort[JsonRpcError]]("ping") { (req, _) =>
            val n = callCount.incrementAndGet()
            if n == 1 then Abort.fail(JsonRpcError.methodNotFound("ping"))
            else PingResp(req.n)
        }

        val cfg = JsonRpcEndpoint.Config(
            maxInFlight = Present(1),
            cancellation = Absent
        )

        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcEndpoint.init(ta, Seq.empty, cfg).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq(failOnB), cfg).map { _ =>
                    // First call: should fail with MethodNotFound
                    Abort.run[JsonRpcError | Closed](
                        endpointA.call[PingReq, PingResp]("ping", PingReq(1))
                    ).map {
                        case Result.Failure(_: JsonRpcError) =>
                            // Semaphore slot should be released; second call should proceed without parking
                            Abort.run[JsonRpcError | Closed](
                                endpointA.call[PingReq, PingResp]("ping", PingReq(2))
                            ).map {
                                case Result.Success(r) => assert(r == PingResp(2))
                                case other             => fail(s"second call failed: $other")
                            }
                        case other => fail(s"expected first call to fail, got $other")
                    }
                }
            }
        }
    }

    "progressResetsTimeout = true: progress notifications reset the deadline" in run {
        case class ProgressMsg(pct: Int) derives Schema, CanEqual

        // A 1-second timeout: without resets it would fire before the handler completes.
        // The handler sends 4 progress notifications at 300ms intervals (total ~1.2s).
        // With progressResetsTimeout = true, each notification resets the 1-second clock.
        val longTask = JsonRpcMethod[PingReq, PingResp, Async & Abort[JsonRpcError]]("longTask") { (req, ctx) =>
            val progressValue = Structure.Value.Record(Chunk("pct" -> Structure.Value.Integer(25L)))
            Async.sleep(300.millis).andThen {
                Abort.run[Closed](ctx.progress(progressValue)).andThen {
                    Async.sleep(300.millis).andThen {
                        Abort.run[Closed](ctx.progress(progressValue)).andThen {
                            Async.sleep(300.millis).andThen {
                                Abort.run[Closed](ctx.progress(progressValue)).andThen {
                                    Async.sleep(300.millis).andThen {
                                        Abort.run[Closed](ctx.progress(progressValue)).andThen {
                                            PingResp(req.n)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val cfg = JsonRpcEndpoint.Config(
            requestTimeout = 1.second,
            progress = Present(ProgressPolicy.lsp),
            cancellation = Present(CancellationPolicy.lsp),
            progressResetsTimeout = true
        )

        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcEndpoint.init(ta, Seq.empty, cfg).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq(longTask), cfg).map { _ =>
                    // callWithProgress so that progress notifications are received and fire the heartbeat
                    endpointA.callWithProgress[PingReq, PingResp]("longTask", PingReq(42)).map { pending =>
                        // Drain the progress stream so it doesn't back-pressure the handler
                        Fiber.initUnscoped(
                            Abort.run[Closed](pending.progress.discard)
                        ).andThen {
                            Abort.run[JsonRpcError | Closed](pending.result).map {
                                case Result.Success(resp) =>
                                    assert(resp == PingResp(42), s"expected PingResp(42), got $resp")
                                case Result.Failure(e: JsonRpcError) =>
                                    fail(s"call timed out or failed: ${e.message} (code ${e.code})")
                                case other =>
                                    fail(s"unexpected result: $other")
                            }
                        }
                    }
                }
            }
        }
    }

end MaxInFlightTest
