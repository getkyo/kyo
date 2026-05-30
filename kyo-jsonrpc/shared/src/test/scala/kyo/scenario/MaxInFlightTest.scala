package kyo.scenario

import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present

class MaxInFlightTest extends JsonRpcTest:

    case class PingReq(n: Int) derives Schema, CanEqual
    case class PingResp(n: Int) derives Schema, CanEqual
    case class LogMsg(text: String) derives Schema, CanEqual

    private class CapturingTransport(inner: JsonRpcTransport) extends JsonRpcTransport:
        // Unsafe: AtomicRef.Unsafe.init used for thread-safe envelope accumulation outside effect context
        val sent = AtomicRef.Unsafe.init(List.empty[JsonRpcEnvelope])(using AllowUnsafe.embrace.danger)

        def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.defer(discard(sent.getAndUpdate(env :: _)(using AllowUnsafe.embrace.danger))).andThen(inner.send(env))

        def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]] =
            inner.incoming

        def close(using Frame): Unit < Async =
            inner.close

        def sentList: List[JsonRpcEnvelope] = sent.get()(using AllowUnsafe.embrace.danger).reverse
    end CapturingTransport

    "maxInFlight = 2 parks the third concurrent call until a slot is freed" in run {
        // Unsafe: AtomicInt.Unsafe.init used for concurrent counter in synchronous handler scope
        val handlerEntered = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
        // Unsafe: AtomicRef.Unsafe.init used for concurrent promise accumulation outside effect context
        val handlerPromises = AtomicRef.Unsafe.init(List.empty[Fiber.Promise[Unit, Any]])(using AllowUnsafe.embrace.danger)

        val pingOnB = JsonRpcRoute[PingReq, PingResp, Async & Abort[JsonRpcError]]("ping") { (req, _) =>
            Fiber.Promise.init[Unit, Any].map { p =>
                Sync.defer(discard(handlerPromises.getAndUpdate(p :: _)(using AllowUnsafe.embrace.danger))).andThen {
                    Sync.defer(discard(handlerEntered.incrementAndGet()(using AllowUnsafe.embrace.danger))).andThen {
                        p.get.andThen(PingResp(req.n))
                    }
                }
            }
        }

        val cfg = JsonRpcHandler.Config(
            maxInFlight = Present(2),
            cancellation = Absent
        )

        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty, cfg).map { endpointA =>
                JsonRpcHandler.init(tb, Seq(pingOnB), cfg).map { _ =>
                    Fiber.initUnscoped(
                        Abort.run[JsonRpcError | Closed](endpointA.call[PingReq, PingResp]("ping", PingReq(1)))
                    ).map { fib1 =>
                        Fiber.initUnscoped(
                            Abort.run[JsonRpcError | Closed](endpointA.call[PingReq, PingResp]("ping", PingReq(2)))
                        ).map { fib2 =>
                            Fiber.initUnscoped(
                                Abort.run[JsonRpcError | Closed](endpointA.call[PingReq, PingResp]("ping", PingReq(3)))
                            ).map { fib3 =>
                                // Wait until exactly 2 handlers have entered (two slots acquired)
                                untilTrue(Sync.defer(handlerEntered.get()(using AllowUnsafe.embrace.danger) == 2)).andThen {
                                    Sync.defer(assert(
                                        handlerEntered.get()(using AllowUnsafe.embrace.danger) == 2,
                                        "third call should be parked"
                                    )).andThen {
                                        // Release one slot (whichever handler got it first) so the third call can proceed
                                        Sync.defer {
                                            val all = handlerPromises.get()(using AllowUnsafe.embrace.danger)
                                            all.headOption.foreach { first =>
                                                handlerPromises.set(all.tail)(using AllowUnsafe.embrace.danger)
                                                first.unsafe.completeUnitDiscard()(using AllowUnsafe.embrace.danger)
                                            }
                                        }.andThen {
                                            // Wait for all three handlers to have entered
                                            untilTrue(Sync.defer(handlerEntered.get()(using AllowUnsafe.embrace.danger) == 3)).andThen {
                                                // Release remaining slots
                                                Sync.defer {
                                                    handlerPromises.get()(using AllowUnsafe.embrace.danger).foreach { p =>
                                                        p.unsafe.completeUnitDiscard()(using AllowUnsafe.embrace.danger)
                                                    }
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
        // Unsafe: AtomicInt.Unsafe.init for concurrent counter in handler scope
        val notifyReceived = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
        // Unsafe: AtomicRef.Unsafe.init for handler promise capture across fibers
        val handlerBlocked = AtomicRef.Unsafe.init[Maybe[Fiber.Promise[Unit, Any]]](Absent)(using AllowUnsafe.embrace.danger)

        val pingOnB = JsonRpcRoute[PingReq, PingResp, Async & Abort[JsonRpcError]]("ping") { (_, _) =>
            Fiber.Promise.init[Unit, Any].map { p =>
                Sync.defer(handlerBlocked.set(Present(p))(using AllowUnsafe.embrace.danger)).andThen(p.get.andThen(PingResp(0)))
            }
        }
        val logOnB = JsonRpcRoute[LogMsg, Unit, Async & Abort[JsonRpcError]]("log") { (_, _) =>
            Sync.defer(discard(notifyReceived.incrementAndGet()(using AllowUnsafe.embrace.danger)))
        }

        val cfg = JsonRpcHandler.Config(
            maxInFlight = Present(1),
            cancellation = Absent
        )

        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty, cfg).map { endpointA =>
                JsonRpcHandler.init(tb, Seq(pingOnB, logOnB), cfg).map { _ =>
                    // Start one call to consume the single slot
                    Fiber.initUnscoped(
                        Abort.run[JsonRpcError | Closed](endpointA.call[PingReq, PingResp]("ping", PingReq(0)))
                    ).andThen {
                        // Wait for the handler to have acquired the slot and blocked
                        untilTrue(Sync.defer(handlerBlocked.get()(using AllowUnsafe.embrace.danger).isDefined)).andThen {
                            // Send a notification while the slot is full; notify should go through immediately
                            endpointA.notify[LogMsg]("log", LogMsg("hi")).andThen {
                                // Verify notification was received on the server
                                untilTrue(Sync.defer(notifyReceived.get()(using AllowUnsafe.embrace.danger) == 1)).andThen {
                                    // Release the blocked call
                                    Sync.defer(handlerBlocked.get()(using AllowUnsafe.embrace.danger)).map {
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
        // Unsafe: AtomicRef.Unsafe.init for handler promise capture across fibers
        val blocked = AtomicRef.Unsafe.init[Maybe[Fiber.Promise[Unit, Any]]](Absent)(using AllowUnsafe.embrace.danger)

        val pingOnB = JsonRpcRoute[PingReq, PingResp, Async & Abort[JsonRpcError]]("ping") { (_, _) =>
            Fiber.Promise.init[Unit, Any].map { p =>
                Sync.defer(blocked.set(Present(p))(using AllowUnsafe.embrace.danger)).andThen(p.get.andThen(PingResp(0)))
            }
        }

        val cfg = JsonRpcHandler.Config(
            requestTimeout = 100.millis,
            cancellation = Absent
        )

        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty, cfg).map { endpointA =>
                JsonRpcHandler.init(tb, Seq(pingOnB), cfg).map { _ =>
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
        val pingOnB = JsonRpcRoute[PingReq, PingResp, Async & Abort[JsonRpcError]]("ping") { (_, _) =>
            // Block forever; timeout is the only exit
            Fiber.Promise.init[Unit, Any].map { p => p.get.andThen(PingResp(0)) }
        }

        val cfg = JsonRpcHandler.Config(
            requestTimeout = 100.millis,
            cancellation = Present(JsonRpcHandler.CancellationPolicy.lsp)
        )

        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capA = new CapturingTransport(ta)
            JsonRpcHandler.init(capA, Seq.empty, cfg).map { endpointA =>
                JsonRpcHandler.init(tb, Seq(pingOnB), cfg).map { _ =>
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
                            capA.sentList.exists {
                                case JsonRpcNotification("$/cancelRequest", _, _) => true
                                case _                                            => false
                            }
                        }).andThen(succeed)
                    }
                }
            }
        }
    }

    "requestTimeout fires with cancellation = Absent: no cancel notification sent" in run {
        val pingOnB = JsonRpcRoute[PingReq, PingResp, Async & Abort[JsonRpcError]]("ping") { (_, _) =>
            // Block forever; timeout is the only exit
            Fiber.Promise.init[Unit, Any].map { p => p.get.andThen(PingResp(0)) }
        }

        val cfg = JsonRpcHandler.Config(
            requestTimeout = 100.millis,
            cancellation = Absent
        )

        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capA = new CapturingTransport(ta)
            JsonRpcHandler.init(capA, Seq.empty, cfg).map { endpointA =>
                JsonRpcHandler.init(tb, Seq(pingOnB), cfg).map { _ =>
                    Abort.run[JsonRpcError | Closed](
                        endpointA.call[PingReq, PingResp]("ping", PingReq(0))
                    ).map { result =>
                        val cancelNotifications =
                            capA.sentList.count {
                                case _: JsonRpcNotification => true
                                case _                      => false
                            }
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
        // Unsafe: AtomicInt.Unsafe.init for call counter in handler scope
        val callCount = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)

        // First call: handler always fails with MethodNotFound
        val failOnB = JsonRpcRoute[PingReq, PingResp, Async & Abort[JsonRpcError]]("ping") { (req, _) =>
            val n = callCount.incrementAndGet()(using AllowUnsafe.embrace.danger)
            if n == 1 then Abort.fail(JsonRpcMethodNotFoundError("ping", Chunk.empty))
            else PingResp(req.n)
        }

        val cfg = JsonRpcHandler.Config(
            maxInFlight = Present(1),
            cancellation = Absent
        )

        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty, cfg).map { endpointA =>
                JsonRpcHandler.init(tb, Seq(failOnB), cfg).map { _ =>
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
        val longTask = JsonRpcRoute[PingReq, PingResp, Async & Abort[JsonRpcError]]("longTask") { (req, ctx) =>
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

        val cfg = JsonRpcHandler.Config(
            requestTimeout = 1.second,
            progress = Present(JsonRpcHandler.ProgressPolicy.lsp),
            cancellation = Present(JsonRpcHandler.CancellationPolicy.lsp),
            progressResetsTimeout = true
        )

        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty, cfg).map { endpointA =>
                JsonRpcHandler.init(tb, Seq(longTask), cfg).map { _ =>
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
