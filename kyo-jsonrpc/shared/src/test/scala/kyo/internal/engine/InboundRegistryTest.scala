package kyo.internal.engine

import kyo.*

/** Tests the in-flight table and each admitted request's state machine directly, with the handler result supplied by the test instead of a
  * running route. Placed in the engine package for access to the `private[engine]` transitions the pipeline drives.
  */
class InboundRegistryTest extends kyo.JsonRpcTest:

    // Unsafe: the registry and request API is the unsafe tier the pipeline itself calls; the tests drive it the same way.
    import AllowUnsafe.embrace.danger

    private val id1 = JsonRpcId.Num(1L)
    private val id2 = JsonRpcId.Num(2L)

    private val ok: Result[JsonRpcError | JsonRpcResponse.Halt, Structure.Value < Any] =
        Result.succeed(Structure.Value.Str("ok"))

    private def okResponse(id: JsonRpcId) = JsonRpcResponse(id, Present(Structure.Value.Str("ok")), Absent, Absent)

    private def register(
        registry: InboundRegistry,
        id: JsonRpcId,
        expectReply: Boolean = false,
        method: String = "m"
    ): InboundRequest =
        registry.registerRequest(id, method, Absent, expectReply) match
            case InboundRegistry.Registration.Registered(request) => request
            case other                                            => throw new AssertionError(s"expected a registration, got $other")

    "registration" - {

        "a registered request is found by id until its reply is committed" in {
            val registry = InboundRegistry.init()
            val request  = register(registry, id1)
            val found    = registry.get(id1)
            val settled  = request.settle(ok)
            val pending  = registry.get(id1)
            val written  = request.commit()
            assert(found.exists(_ eq request))
            assert(settled == InboundPipeline.Settlement.Reply(okResponse(id1)))
            assert(pending.exists(_ eq request), "a settled reply keeps its entry until it is committed")
            assert(written)
            assert(registry.get(id1).isEmpty && registry.size == 0)
        }

        "a second request with an id already in flight is refused and does not replace the first" in {
            val registry = InboundRegistry.init()
            val first    = register(registry, id1)
            val second   = registry.registerRequest(id1, "m", Absent, expectReplyWhenCancelled = false)
            assert(second == InboundRegistry.Registration.Duplicate)
            assert(registry.get(id1).exists(_ eq first) && registry.size == 1)
        }

        "an id is accepted again once the request that used it has left the table" in {
            val registry = InboundRegistry.init()
            val first    = register(registry, id1)
            discard(first.settle(ok))
            discard(first.commit())
            val again = registry.registerRequest(id1, "m", Absent, expectReplyWhenCancelled = false)
            again match
                case InboundRegistry.Registration.Registered(request) => assert(!(request eq first))
                case other                                            => fail(s"expected the id to be accepted again, got $other")
        }

        "nothing is registered once the registry is closing" in {
            val registry = InboundRegistry.init()
            registry.closeAll()
            val request      = registry.registerRequest(id1, "m", Absent, expectReplyWhenCancelled = false)
            val notification = registry.registerNotification("n", Absent)
            assert(registry.isClosing())
            assert(request == InboundRegistry.Registration.Closing)
            assert(notification == Absent)
            assert(registry.size == 0)
        }

        "a notification handler is registered without an id and leaves the table when it settles" in {
            val registry = InboundRegistry.init()
            registry.registerNotification("n", Absent) match
                case Absent                => fail("expected the notification to be registered")
                case Present(notification) =>
                    val sizeWhileRunning = registry.size
                    val settled          = notification.settle(ok)
                    assert(notification.id == Absent)
                    assert(sizeWhileRunning == 1)
                    assert(settled == InboundPipeline.Settlement.NoReply)
                    assert(registry.size == 0)
            end match
        }
    }

    "cancel" - {

        "cancelling a running request completes its cancelled promise and settles with no reply" in {
            val registry  = InboundRegistry.init()
            val request   = register(registry, id1)
            val cancelled = request.cancel()
            val running   = request.isRunning()
            val settled   = request.settle(ok)
            assert(cancelled)
            assert(request.cancelled.done())
            assert(!running)
            assert(settled == InboundPipeline.Settlement.NoReply)
            assert(registry.size == 0, "a request cancelled without a reply must leave the table")
        }

        "cancelling a running request under an expected reply still writes the handler's reply" in {
            val registry    = InboundRegistry.init()
            val request     = register(registry, id1, expectReply = true)
            val cancelled   = request.cancel()
            val settled     = request.settle(ok)
            val cancelAgain = request.cancel()
            val written     = request.commit()
            assert(cancelled && request.cancelled.done())
            assert(settled == InboundPipeline.Settlement.Reply(okResponse(id1)))
            assert(!cancelAgain, "a cancellation cannot suppress a reply the policy expects")
            assert(written)
            assert(registry.size == 0)
        }

        "cancelling a settled reply suppresses it at commit" in {
            val registry  = InboundRegistry.init()
            val request   = register(registry, id1)
            val settled   = request.settle(ok)
            val pending   = request.isReplyPending()
            val cancelled = request.cancel()
            val written   = request.commit()
            assert(settled == InboundPipeline.Settlement.Reply(okResponse(id1)))
            assert(pending)
            assert(cancelled)
            assert(!written, "a reply suppressed by a cancel must not be written")
            assert(registry.size == 0)
        }

        "cancelling a settled reply under an expected reply leaves it to be written" in {
            val registry = InboundRegistry.init()
            val request  = register(registry, id1, expectReply = true)
            discard(request.settle(ok))
            val cancelled = request.cancel()
            val written   = request.commit()
            assert(!cancelled)
            assert(written)
        }

        "cancel returns false for a request already cancelled and a request already written" in {
            val registry = InboundRegistry.init()
            val first    = register(registry, id1)
            val second   = register(registry, id2)
            val once     = first.cancel()
            val twice    = first.cancel()
            discard(second.settle(ok))
            discard(second.commit())
            val afterWrite = second.cancel()
            assert(once && !twice)
            assert(!afterWrite)
        }

        "a cancel that lands before the handler fiber is attached interrupts the fiber on attach" in {
            val registry = InboundRegistry.init()
            val request  = register(registry, id1)
            for
                cancelled <- Sync.defer(request.cancel())
                fiber     <- Fiber.initUnscoped(Async.never[Structure.Value])
                _         <- Sync.defer(request.attach(fiber.unsafe))
                result    <- fiber.getResult
            yield
                assert(cancelled)
                result match
                    case Result.Panic(_: Interrupted) => succeed
                    case other                        => fail(s"expected the handler fiber to be interrupted, got $other")
                end match
            end for
        }

        "a cancel after the handler fiber is attached interrupts it" in {
            val registry = InboundRegistry.init()
            val request  = register(registry, id1)
            for
                fiber     <- Fiber.initUnscoped(Async.never[Structure.Value])
                _         <- Sync.defer(request.attach(fiber.unsafe))
                cancelled <- Sync.defer(request.cancel())
                result    <- fiber.getResult
            yield
                assert(cancelled)
                result match
                    case Result.Panic(_: Interrupted) => succeed
                    case other                        => fail(s"expected the handler fiber to be interrupted, got $other")
                end match
            end for
        }

        "a cancel under an expected reply does not interrupt the handler fiber" in {
            val registry = InboundRegistry.init()
            val request  = register(registry, id1, expectReply = true)
            for
                gate      <- Latch.init(1)
                fiber     <- Fiber.initUnscoped(gate.await.andThen(Structure.Value.Str("finished"): Structure.Value))
                _         <- Sync.defer(request.attach(fiber.unsafe))
                cancelled <- Sync.defer(request.cancel())
                _         <- gate.release
                result    <- fiber.getResult
            yield
                assert(cancelled)
                assert(result == Result.succeed(Structure.Value.Str("finished")))
            end for
        }
    }

    "abort" - {

        "abort stops a running request with no reply even when the policy expects one" in {
            val registry = InboundRegistry.init()
            val request  = register(registry, id1, expectReply = true)
            for
                fiber   <- Fiber.initUnscoped(Async.never[Structure.Value])
                _       <- Sync.defer(request.attach(fiber.unsafe))
                aborted <- Sync.defer(request.abort())
                result  <- fiber.getResult
                settled <- Sync.defer(request.settle(ok))
            yield
                assert(aborted && request.cancelled.done())
                assert(result.isPanic, s"expected the handler fiber to be interrupted, got $result")
                assert(settled == InboundPipeline.Settlement.NoReply)
                assert(registry.size == 0)
            end for
        }

        "abort stops a request that was already cancelled under an expected reply" in {
            val registry  = InboundRegistry.init()
            val request   = register(registry, id1, expectReply = true)
            val cancelled = request.cancel()
            val aborted   = request.abort()
            val settled   = request.settle(ok)
            assert(cancelled && aborted)
            assert(settled == InboundPipeline.Settlement.NoReply)
        }

        "abort leaves a settled reply to be written" in {
            val registry = InboundRegistry.init()
            val request  = register(registry, id1)
            discard(request.settle(ok))
            val aborted = request.abort()
            val written = request.commit()
            assert(!aborted)
            assert(written)
        }

        "closeAll aborts every running request and notification handler and keeps settled replies" in {
            val registry = InboundRegistry.init()
            val running  = register(registry, id1)
            val replying = register(registry, id2)
            discard(replying.settle(ok))
            registry.registerNotification("n", Absent) match
                case Absent                => fail("expected the notification to be registered")
                case Present(notification) =>
                    registry.closeAll()
                    assert(running.cancelled.done() && notification.cancelled.done())
                    assert(!replying.cancelled.done())
                    assert(running.settle(ok) == InboundPipeline.Settlement.NoReply)
                    assert(notification.settle(ok) == InboundPipeline.Settlement.NoReply)
                    assert(replying.commit(), "a reply produced before the close is still written")
                    assert(registry.size == 0)
            end match
        }

        "abandon removes a settled reply that could not be delivered" in {
            val registry = InboundRegistry.init()
            val request  = register(registry, id1)
            discard(request.settle(ok))
            request.abandon()
            assert(registry.size == 0)
            assert(!request.commit())
        }

        "abandon leaves a running request alone" in {
            val registry = InboundRegistry.init()
            val request  = register(registry, id1)
            request.abandon()
            assert(request.isRunning())
            assert(registry.get(id1).exists(_ eq request))
        }
    }

    "pending replies" - {

        "closeAll counts each settled reply not yet with its writer, until it is handed off, written or abandoned" in {
            val registry        = InboundRegistry.init()
            val handedOffBefore = register(registry, id1)
            val suppressed      = register(registry, id2)
            val abandoned       = register(registry, JsonRpcId.Num(3L))
            val written         = register(registry, JsonRpcId.Num(4L))
            val handedOffAfter  = register(registry, JsonRpcId.Num(5L))
            val running         = register(registry, JsonRpcId.Num(6L))
            Seq(handedOffBefore, suppressed, abandoned, written, handedOffAfter).foreach(r => discard(r.settle(ok)))
            handedOffBefore.handOff()
            discard(suppressed.cancel())
            val beforeClose = registry.replies.count()
            registry.closeAll()
            val afterClose = registry.replies.count()
            handedOffAfter.handOff()
            val afterHandOff = registry.replies.count()
            discard(suppressed.commit())
            abandoned.abandon()
            discard(written.commit())
            assert(beforeClose == 0, "nothing is counted before a close")
            assert(
                afterClose == 4,
                "the suppressed, abandoned, written and later handed-off replies are counted; the one handed off before is not"
            )
            assert(afterHandOff == 3, "a counted reply handed to its writer is released")
            assert(handedOffAfter.isReplyPending(), "handing a reply to its writer does not write it")
            assert(running.settle(ok) == InboundPipeline.Settlement.NoReply, "the running request was aborted by the close")
            assert(registry.replies.count() == 0)
        }

        "handOff releases a counted reply exactly once" in {
            val registry = InboundRegistry.init()
            val replying = register(registry, id1)
            discard(replying.settle(ok))
            registry.closeAll()
            val counted = registry.replies.count()
            replying.handOff()
            replying.handOff()
            val afterRepeat = registry.replies.count()
            assert(counted == 1)
            assert(afterRepeat == 0, "a second handOff must not release the count again")
            assert(replying.commit(), "a reply handed off is still written at commit")
            assert(registry.replies.count() == 0, "commit after handOff must not release the count again")
        }

        "handOff before a request settles with a reply does nothing, so a later close still counts the reply" in {
            val registry = InboundRegistry.init()
            val request  = register(registry, id1)
            request.handOff()
            discard(request.settle(ok))
            registry.closeAll()
            assert(registry.replies.count() == 1)
        }

        "a request that ends without a reply is never counted" in {
            val registry  = InboundRegistry.init()
            val aborted   = register(registry, id1, expectReply = true)
            val cancelled = register(registry, id2)
            discard(aborted.abort())
            discard(cancelled.cancel())
            discard(aborted.settle(ok))
            discard(cancelled.settle(ok))
            registry.registerNotification("n", Absent).foreach(n => discard(n.settle(ok)))
            registry.closeAll()
            assert(registry.replies.count() == 0)
            assert(registry.size == 0)
        }

        "a settle after closeAll aborted its request produces no reply and nothing is counted" in {
            val registry = InboundRegistry.init()
            val request  = register(registry, id1, expectReply = true)
            registry.closeAll()
            val settled = request.settle(ok)
            assert(settled == InboundPipeline.Settlement.NoReply)
            assert(registry.replies.count() == 0)
            assert(registry.size == 0)
        }

        "waiting for pending replies after closeAll returns only once each reply produced before the close is handed to its writer" in {
            // closeAll leaves a settled reply to be written; a close waits on `replies` until the reply is in its writer's queue.
            val registry = InboundRegistry.init()
            val request  = register(registry, id1)
            discard(request.settle(ok))
            registry.closeAll()
            for
                waiter <- Fiber.initUnscoped(registry.replies.awaitIdle)
                _      <- assertEventually(Sync.defer(registry.replies.idleWaiters() == 1))
                early  <- waiter.done
                _      <- Sync.defer(request.handOff())
                _      <- waiter.get
            yield assert(!early, "the wait returned while a reply produced before the close was not yet with its writer")
            end for
        }
    }

    "responses" - {

        "a Halt is answered with its response verbatim" in {
            val registry = InboundRegistry.init()
            val request  = register(registry, id1)
            val halted   = JsonRpcResponse(id1, Present(Structure.Value.Str("halted")), Absent, Present(Structure.Value.Str("x")))
            val settled  = request.settle(Result.fail(JsonRpcResponse.Halt(halted)))
            assert(settled == InboundPipeline.Settlement.Reply(halted))
        }

        "a JsonRpcError is answered as an error response carrying the request's extras" in {
            val registry = InboundRegistry.init()
            val extras   = Structure.Value.Record(Chunk("session" -> Structure.Value.Str("s1")))
            val request  = registry.registerRequest(id1, "m", Present(extras), expectReplyWhenCancelled = false) match
                case InboundRegistry.Registration.Registered(r) => r
                case other                                      => throw new AssertionError(s"expected a registration, got $other")
            val error   = JsonRpcCustomError(-32001, "denied")
            val settled = request.settle(Result.fail(error))
            assert(settled == InboundPipeline.Settlement.Reply(JsonRpcResponse(id1, Absent, Present(error), Present(extras))))
        }

        "a panic is answered with a handler panic error naming the method" in {
            val registry = InboundRegistry.init()
            val request  = register(registry, id1, method = "explode")
            val boom     = new RuntimeException("boom")
            request.settle(Result.panic(boom)) match
                case InboundPipeline.Settlement.Reply(JsonRpcResponse(`id1`, Absent, Present(error: JsonRpcHandlerPanicError), Absent)) =>
                    assert(error.method == "explode")
                    assert(error.cause eq boom)
                case other => fail(s"expected a handler panic error response, got $other")
            end match
        }
    }

end InboundRegistryTest
