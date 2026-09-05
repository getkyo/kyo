package kyo

import kyo.Maybe.Absent
import kyo.Maybe.Present

class JsonRpcHandlerProgressPolicyTest extends JsonRpcTest:

    case class TaskReq(name: String) derives Schema, CanEqual
    case class TaskResp(done: Boolean) derives Schema, CanEqual
    case class SearchReq(query: String) derives Schema, CanEqual

    // Plain case class for out-of-band progress notification params so Structure.encode
    // produces a flat {"token": ..., "value": ...} record (not a discriminated enum encoding).
    case class ProgressNotifParams(token: String, value: String) derives Schema, CanEqual

    // Stamped params include workDoneToken alongside the original fields ($/progress style).
    case class TaskReqWithToken(
        name: String,
        workDoneToken: Maybe[String] = Absent
    ) derives Schema, CanEqual

    // Stamped params include _meta with progressToken (notifications/progress style).
    case class ProgressMeta(progressToken: Maybe[String] = Absent) derives Schema, CanEqual
    case class TaskReqWithMeta(
        name: String,
        `_meta`: Maybe[ProgressMeta] = Absent
    ) derives Schema, CanEqual

    // Inline reconstruction of progress policies using the generic API.
    // progressMethod="$/progress", workDoneToken in request params, non-monotonic.
    private val progressWithWorkDoneToken = JsonRpcProgressPolicy(
        progressMethod = "$/progress",
        extractInboundToken = p => JsonRpcProgressPolicy.field(p, "token"),
        extractRequestToken = p => JsonRpcProgressPolicy.field(p, "workDoneToken"),
        stampOutboundToken = (p, t) => JsonRpcProgressPolicy.merge(p, Structure.Value.Record(Chunk("workDoneToken" -> t))),
        encodeProgressParams = (t, v) => Structure.Value.Record(Chunk("token" -> t, "value" -> v)),
        extractProgressValue = p => JsonRpcProgressPolicy.field(p, "value"),
        enforceMonotonic = false
    )

    // progressMethod="notifications/progress", _meta.progressToken in request params, monotonic.
    private case class CancelWithReasonParams(requestId: JsonRpcId, reason: Maybe[String]) derives Schema, CanEqual

    private val cancelWithReasonEncoder: JsonRpcCancellationPolicy.ParamsEncoder =
        (id, reason) =>
            f ?=>
                Sync.defer(Structure.encode(CancelWithReasonParams(id, reason)))(using f)

    private val cancelWithReasonDecoder: JsonRpcCancellationPolicy.ParamsDecoder =
        sv =>
            f ?=>
                Sync.defer {
                    Structure.decode[CancelWithReasonParams](sv)(using summon[Schema[CancelWithReasonParams]], f) match
                        case Result.Success(p) => Present(p.requestId)
                        case _                 => Absent
                }(using f)

    private val cancellationWithoutReply = JsonRpcCancellationPolicy(
        cancelMethod = "notifications/cancelled",
        encodeParams = cancelWithReasonEncoder,
        decodeParams = cancelWithReasonDecoder,
        expectReplyForCancelledRequest = false,
        cancelledError = Absent,
        protectedMethods = Set("initialize")
    )

    private val progressWithMetaToken = JsonRpcProgressPolicy(
        progressMethod = "notifications/progress",
        extractInboundToken = p => JsonRpcProgressPolicy.field(p, "progressToken"),
        extractRequestToken = p =>
            JsonRpcProgressPolicy.field(p, "_meta").map(meta => JsonRpcProgressPolicy.field(meta, "progressToken")).getOrElse(Absent),
        stampOutboundToken = (p, t) =>
            val existingMeta = JsonRpcProgressPolicy.field(p, "_meta").getOrElse(Structure.Value.Record(Chunk.empty))
            val newMeta      = JsonRpcProgressPolicy.merge(existingMeta, Structure.Value.Record(Chunk("progressToken" -> t)))
            JsonRpcProgressPolicy.merge(p, Structure.Value.Record(Chunk("_meta" -> newMeta)))
        ,
        encodeProgressParams = (t, v) =>
            JsonRpcProgressPolicy.merge(Structure.Value.Record(Chunk("progressToken" -> t)), v),
        extractProgressValue = p => Present(p),
        enforceMonotonic = true
    )

    private val workDoneTokenConfig = JsonRpcHandler.Config(progress = Present(progressWithWorkDoneToken))
    private val metaTokenConfig = JsonRpcHandler.Config(
        progress = Present(progressWithMetaToken),
        cancellation = Present(cancellationWithoutReply)
    )

    private def sendProgress(ctx: JsonRpcRoute.Context, v: Structure.Value)(using Frame): Unit < (Async & Abort[JsonRpcError]) =
        Abort.run[Closed](ctx.progress(v)).unit

    private def mkProgress(pct: Double): Structure.Value =
        Structure.Value.Record(Chunk("progress" -> Structure.Value.Decimal(pct)))

    private def mkBegin: Structure.Value =
        Structure.Value.Record(Chunk("kind" -> Structure.Value.Str("begin")))

    private def mkReport(msg: String): Structure.Value =
        Structure.Value.Record(Chunk("kind" -> Structure.Value.Str("report"), "message" -> Structure.Value.Str(msg)))

    private def mkEnd: Structure.Value =
        Structure.Value.Record(Chunk("kind" -> Structure.Value.Str("end")))

    private class CapturingTransport(inner: JsonRpcTransport) extends JsonRpcTransport:
        // Unsafe: AtomicRef.Unsafe.init for thread-safe envelope accumulation outside effect context
        val sent = AtomicRef.Unsafe.init(List.empty[JsonRpcEnvelope])(using AllowUnsafe.embrace.danger)

        def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.defer(discard(sent.getAndUpdate(env :: _)(using AllowUnsafe.embrace.danger))).andThen(inner.send(env))

        def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]] =
            inner.incoming

        def close(using Frame): Unit < Async =
            inner.close

        def sentList: List[JsonRpcEnvelope] = sent.get()(using AllowUnsafe.embrace.danger).reverse
    end CapturingTransport

    "callWithProgress with workDoneToken policy: handler calls ctx.progress three times, caller observes three progress values" in {
        val longTask = JsonRpcRoute.request[TaskReq, TaskResp]("longTask") {
            (_, ctx) =>
                sendProgress(ctx, mkBegin).andThen {
                    sendProgress(ctx, mkReport("working")).andThen {
                        sendProgress(ctx, mkEnd).andThen {
                            TaskResp(true)
                        }
                    }
                }
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty, workDoneTokenConfig).map { endpointA =>
                JsonRpcHandler.init(tb, Seq(longTask), workDoneTokenConfig).map { _ =>
                    endpointA.callWithProgress[TaskReq, TaskResp]("longTask", TaskReq("t")).map { pending =>
                        pending.progress.run.map { progressChunks =>
                            pending.result.map { _ =>
                                assert(progressChunks.size == 3, s"expected 3 progress values, got ${progressChunks.size}")
                            }
                        }
                    }
                }
            }
        }
    }

    "callWithProgress with workDoneToken policy: stampOutboundToken attaches workDoneToken to params, handler reads token" in {
        // Unsafe: AtomicRef.Unsafe.init for token capture across fibers
        val capturedToken = AtomicRef.Unsafe.init[Maybe[String]](Absent)(using AllowUnsafe.embrace.danger)
        val taskMethod = JsonRpcRoute.request[TaskReqWithToken, TaskResp]("task") {
            (params, _) =>
                Sync.defer(capturedToken.set(params.workDoneToken)(using AllowUnsafe.embrace.danger)).andThen(TaskResp(true))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty, workDoneTokenConfig).map { endpointA =>
                JsonRpcHandler.init(tb, Seq(taskMethod), workDoneTokenConfig).map { _ =>
                    endpointA.callWithProgress[TaskReq, TaskResp]("task", TaskReq("t")).map { pending =>
                        pending.result.map { _ =>
                            Sync.defer(capturedToken.get()(using AllowUnsafe.embrace.danger)).map {
                                case Present(_) => succeed
                                case Absent     => fail("workDoneToken not found in inbound params")
                            }
                        }
                    }
                }
            }
        }
    }

    "callPartialResults with workDoneToken policy: handler sends three progress notifications then null final response, stream emits three strings" in {
        val searchMethod = JsonRpcRoute.request[SearchReq, Structure.Value]("search") {
            (_, ctx) =>
                sendProgress(ctx, Structure.Value.Str("result1")).andThen {
                    sendProgress(ctx, Structure.Value.Str("result2")).andThen {
                        sendProgress(ctx, Structure.Value.Str("result3")).andThen {
                            Structure.Value.Null
                        }
                    }
                }
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty, workDoneTokenConfig).map { endpointA =>
                JsonRpcHandler.init(tb, Seq(searchMethod), workDoneTokenConfig).map { _ =>
                    endpointA.callPartialResults[SearchReq, String]("search", SearchReq("q")).run.map { chunks =>
                        assert(chunks.size == 3, s"expected 3 chunks, got ${chunks.size}: $chunks")
                    }
                }
            }
        }
    }

    "callWithProgress with metaToken policy: outbound params carry _meta.progressToken, handler receives it" in {
        // Unsafe: AtomicRef.Unsafe.init for meta capture across fibers
        val capturedMeta = AtomicRef.Unsafe.init[Maybe[ProgressMeta]](Absent)(using AllowUnsafe.embrace.danger)
        val taskMethod = JsonRpcRoute.request[TaskReqWithMeta, TaskResp]("run") {
            (params, _) =>
                Sync.defer(capturedMeta.set(params.`_meta`)(using AllowUnsafe.embrace.danger)).andThen(TaskResp(true))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty, metaTokenConfig).map { endpointA =>
                JsonRpcHandler.init(tb, Seq(taskMethod), metaTokenConfig).map { _ =>
                    endpointA.callWithProgress[TaskReq, TaskResp]("run", TaskReq("t")).map { pending =>
                        pending.result.map { _ =>
                            Sync.defer(capturedMeta.get()(using AllowUnsafe.embrace.danger)).map {
                                case Present(ProgressMeta(Present(_))) => succeed
                                case other                             => fail(s"_meta.progressToken not found: $other")
                            }
                        }
                    }
                }
            }
        }
    }

    "subscribeProgress returns a stream; subsequent progress notification with that token delivers a value" in {
        val tokenStr = "oob-token-1"
        val token    = Structure.Value.Str(tokenStr)
        // Unsafe: AtomicBoolean.Unsafe.init for received flag across fibers
        val received = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger)
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty, workDoneTokenConfig).map { endpointA =>
                JsonRpcHandler.init(tb, Seq.empty, workDoneTokenConfig).map { endpointB =>
                    endpointA.subscribeProgress(token).map { progressStream =>
                        // Wrap the stream to set the flag when at least one value is delivered.
                        val watched = progressStream.map { v =>
                            discard(received.set(true)(using AllowUnsafe.embrace.danger))
                            v
                        }
                        // Fork the stream consumer so it runs concurrently while we send the notification.
                        Fiber.initUnscoped(watched.run).map { streamFiber =>
                            // Use a typed case class so Structure.encode produces a flat JSON object
                            // {"token": "oob-token-1", "value": "ping"} on the wire. Passing
                            // Structure.Value directly would produce a discriminated enum encoding.
                            endpointB.notify[ProgressNotifParams](
                                "$/progress",
                                ProgressNotifParams(tokenStr, "ping")
                            ).andThen {
                                // Wait until A's reader has delivered the value to the stream consumer.
                                assertEventually(Sync.defer(received.get()(using AllowUnsafe.embrace.danger))).andThen {
                                    endpointA.unsubscribeProgress(token).andThen {
                                        streamFiber.get.map { chunks =>
                                            assert(chunks.nonEmpty, "expected at least one progress value on stream")
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

    "unsubscribeProgress: stream closes and subsequent notifications for that token are dropped" in {
        val tokenStr = "oob-token-close"
        val token    = Structure.Value.Str(tokenStr)
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty, workDoneTokenConfig).map { endpointA =>
                JsonRpcHandler.init(tb, Seq.empty, workDoneTokenConfig).map { endpointB =>
                    endpointA.subscribeProgress(token).map { stream =>
                        endpointA.unsubscribeProgress(token).andThen {
                            endpointB.notify[ProgressNotifParams](
                                "$/progress",
                                ProgressNotifParams(tokenStr, "ignored")
                            ).andThen {
                                stream.run.map { chunks =>
                                    assert(chunks.isEmpty, s"expected empty stream after unsubscribe, got $chunks")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "ctx.progress with progress = Absent returns Unit without sending any progress wire notification" in {
        val taskMethod = JsonRpcRoute.request[TaskReq, TaskResp]("task") {
            (_, ctx) => sendProgress(ctx, mkBegin).andThen(TaskResp(true))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capA       = new CapturingTransport(ta)
            val noProgress = JsonRpcHandler.Config()
            JsonRpcHandler.init(capA, Seq.empty, noProgress).map { endpointA =>
                JsonRpcHandler.init(tb, Seq(taskMethod), noProgress).map { _ =>
                    endpointA.call[TaskReq, TaskResp]("task", TaskReq("t")).map { resp =>
                        assert(resp == TaskResp(true))
                        val notifs = capA.sentList.collect {
                            case n: JsonRpcNotification => n
                        }
                        assert(notifs.isEmpty, s"expected no progress notifications, got $notifs")
                    }
                }
            }
        }
    }

    "enforceMonotonic=true: non-monotonic progress value 5.0 between 10.0 and 20.0 is dropped" in {
        val taskMethod = JsonRpcRoute.request[TaskReq, TaskResp]("task") {
            (_, ctx) =>
                sendProgress(ctx, mkProgress(10.0)).andThen {
                    sendProgress(ctx, mkProgress(5.0)).andThen {
                        sendProgress(ctx, mkProgress(20.0)).andThen {
                            TaskResp(true)
                        }
                    }
                }
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty, metaTokenConfig).map { endpointA =>
                JsonRpcHandler.init(tb, Seq(taskMethod), metaTokenConfig).map { _ =>
                    endpointA.callWithProgress[TaskReq, TaskResp]("task", TaskReq("t")).map { pending =>
                        pending.progress.run.map { chunks =>
                            pending.result.map { _ =>
                                val values = chunks.map {
                                    case Structure.Value.Record(fields) =>
                                        fields.iterator.collectFirst {
                                            case ("progress", Structure.Value.Decimal(n)) => n
                                        }.getOrElse(-1.0)
                                    case _ => -1.0
                                }
                                assert(values.size == 2, s"expected 2 values (10.0 and 20.0), got: $values")
                                assert(values.contains(10.0), s"expected 10.0 in $values")
                                assert(values.contains(20.0), s"expected 20.0 in $values")
                            }
                        }
                    }
                }
            }
        }
    }

    "enforceMonotonic=false: all three progress values pass through in order" in {
        val taskMethod = JsonRpcRoute.request[TaskReq, TaskResp]("task") {
            (_, ctx) =>
                sendProgress(ctx, mkProgress(10.0)).andThen {
                    sendProgress(ctx, mkProgress(5.0)).andThen {
                        sendProgress(ctx, mkProgress(20.0)).andThen {
                            TaskResp(true)
                        }
                    }
                }
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty, workDoneTokenConfig).map { endpointA =>
                JsonRpcHandler.init(tb, Seq(taskMethod), workDoneTokenConfig).map { _ =>
                    endpointA.callWithProgress[TaskReq, TaskResp]("task", TaskReq("t")).map { pending =>
                        pending.progress.run.map { chunks =>
                            pending.result.map { _ =>
                                assert(chunks.size == 3, s"enforceMonotonic=false: expected all 3 values, got: $chunks")
                            }
                        }
                    }
                }
            }
        }
    }

    "ctx.progress called after handler has returned is a no-op: no extra wire notification sent" in {
        // Unsafe: AtomicRef.Unsafe.init for sink capture across fibers
        val sinkRef =
            AtomicRef.Unsafe.init[Maybe[Structure.Value => Unit < (Async & Abort[Closed])]](Absent)(using AllowUnsafe.embrace.danger)
        // Unsafe: AtomicBoolean.Unsafe.init for handler-done flag across fibers
        val handlerDone = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger)
        val taskMethod = JsonRpcRoute.request[TaskReq, TaskResp]("task") {
            (_, ctx) =>
                Sync.defer(sinkRef.set(ctx.progressSink)(using AllowUnsafe.embrace.danger)).andThen {
                    Sync.defer(handlerDone.set(true)(using AllowUnsafe.embrace.danger)).andThen {
                        TaskResp(true)
                    }
                }
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capA = new CapturingTransport(ta)
            JsonRpcHandler.init(capA, Seq.empty, workDoneTokenConfig).map { endpointA =>
                JsonRpcHandler.init(tb, Seq(taskMethod), workDoneTokenConfig).map { _ =>
                    endpointA.callWithProgress[TaskReq, TaskResp]("task", TaskReq("t")).map { pending =>
                        pending.result.map { _ =>
                            assertEventually(Sync.defer(handlerDone.get()(using AllowUnsafe.embrace.danger))).andThen {
                                val notifsBefore = capA.sentList.count {
                                    case n: JsonRpcNotification if n.method == "$/progress" => true
                                    case _                                                  => false
                                }
                                sinkRef.get()(using AllowUnsafe.embrace.danger) match
                                    case Present(sink) =>
                                        Abort.run[Closed](sink(mkReport("late"))).map { _ =>
                                            val notifsAfter = capA.sentList.count {
                                                case n: JsonRpcNotification if n.method == "$/progress" => true
                                                case _                                                  => false
                                            }
                                            assert(
                                                notifsAfter == notifsBefore,
                                                s"expected no new notifications after handler returned; before=$notifsBefore after=$notifsAfter"
                                            )
                                        }
                                    case Absent => succeed
                                end match
                            }
                        }
                    }
                }
            }
        }
    }

    "out-of-band progress notification with unknown token is silently dropped without error" in {
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty, workDoneTokenConfig).map { endpointA =>
                JsonRpcHandler.init(tb, Seq.empty, workDoneTokenConfig).map { endpointB =>
                    endpointB.notify[Structure.Value](
                        "$/progress",
                        Structure.Value.Record(Chunk(
                            "token" -> Structure.Value.Str("no-such-token"),
                            "value" -> Structure.Value.Str("ignored")
                        ))
                    ).andThen(succeed)
                }
            }
        }
    }

    "callPartialResults where final response has non-absent result: it is decoded as the last chunk" in {
        val searchMethod = JsonRpcRoute.request[SearchReq, String]("search") {
            (_, ctx) =>
                sendProgress(ctx, Structure.Value.Str("partial1")).andThen {
                    "final-result"
                }
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty, workDoneTokenConfig).map { endpointA =>
                JsonRpcHandler.init(tb, Seq(searchMethod), workDoneTokenConfig).map { _ =>
                    endpointA.callPartialResults[SearchReq, String]("search", SearchReq("q")).run.map { chunks =>
                        assert(chunks.nonEmpty, s"expected at least one chunk (partial or final), got $chunks")
                    }
                }
            }
        }
    }

    "ctx.progress extras: progress notification is built with extras captured from the inbound request" in {
        val taskMethod = JsonRpcRoute.request[TaskReq, TaskResp]("task") {
            (_, ctx) => sendProgress(ctx, mkBegin).andThen(TaskResp(true))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            // Wrap B's transport to capture outbound envelopes from B (which include the progress notification).
            val capB = new CapturingTransport(tb)
            JsonRpcHandler.init(ta, Seq.empty, workDoneTokenConfig).map { endpointA =>
                JsonRpcHandler.init(capB, Seq(taskMethod), workDoneTokenConfig).map { _ =>
                    endpointA.callWithProgress[TaskReq, TaskResp]("task", TaskReq("t")).map { pending =>
                        pending.result.map { _ =>
                            val progressNotifs = capB.sentList.collect {
                                case n: JsonRpcNotification if n.method == "$/progress" => n
                            }
                            assert(progressNotifs.nonEmpty, "expected at least one progress notification on the wire")
                        }
                    }
                }
            }
        }
    }

    "enforceMonotonic=true concurrent: the larger value is always emitted; a smaller value never follows it".times(100) in {
        // Contract: monotonically-increasing emissions pass; a value <= the highest already-emitted is
        // dropped. With concurrent calls, the LARGER value always reaches the wire; the smaller may or
        // may not, depending on which fiber wins the gate. What the contract forbids is the smaller value
        // appearing AFTER the larger one (that would break monotonicity). The violation is scheduling-
        // dependent, so .times(100) reruns the scenario; every run must hold the contract.
        // Fan out many out-of-order values concurrently so each run has several chances to hit the gate
        // race (a single small/large pair is a nanosecond window that almost never fires on its own).
        val taskMethod = JsonRpcRoute.request[TaskReq, TaskResp]("task") {
            (_, ctx) =>
                Async.zip[JsonRpcError, Unit, Unit, Unit, Unit, Unit, Unit, Unit, Unit, Unit, Unit, Any](
                    sendProgress(ctx, mkProgress(10.0)),
                    sendProgress(ctx, mkProgress(5.0)),
                    sendProgress(ctx, mkProgress(8.0)),
                    sendProgress(ctx, mkProgress(3.0)),
                    sendProgress(ctx, mkProgress(9.0)),
                    sendProgress(ctx, mkProgress(2.0)),
                    sendProgress(ctx, mkProgress(7.0)),
                    sendProgress(ctx, mkProgress(4.0)),
                    sendProgress(ctx, mkProgress(6.0)),
                    sendProgress(ctx, mkProgress(1.0))
                ).andThen(TaskResp(true))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            // Wrap B's transport to capture outbound envelopes from B (progress notifications come from B).
            val capB = new CapturingTransport(tb)
            JsonRpcHandler.init(ta, Seq.empty, metaTokenConfig).map { endpointA =>
                JsonRpcHandler.init(capB, Seq(taskMethod), metaTokenConfig).map { _ =>
                    endpointA.callWithProgress[TaskReq, TaskResp]("task", TaskReq("t")).map { pending =>
                        pending.result.map { _ =>
                            // encodeProgressParams in metaTokenConfig merges {"progressToken": t} with the
                            // raw progress value {"progress": n}, so the top-level params Record carries
                            // a "progress" field directly.
                            val progressValues: List[BigDecimal] = capB.sentList.collect {
                                case JsonRpcNotification(m, Present(params), _) if m == "notifications/progress" =>
                                    params match
                                        case Structure.Value.Record(fields) =>
                                            fields.iterator.collectFirst {
                                                case ("progress", Structure.Value.Decimal(n)) => BigDecimal(n)
                                                case ("progress", Structure.Value.BigNum(n))  => n
                                                case ("progress", Structure.Value.Integer(n)) => BigDecimal(n)
                                            }
                                        case _ => None
                            }.flatten.toList
                            // The largest emission must appear (10.0 must be on the wire).
                            assert(progressValues.contains(BigDecimal(10.0)), s"larger value 10.0 missing; saw $progressValues")
                            // Monotonicity: each emission is strictly greater than its predecessor.
                            val pairs = progressValues.zip(progressValues.drop(1))
                            assert(
                                pairs.forall((prev, next) => next > prev),
                                s"emissions are not strictly monotonic: $progressValues"
                            )
                        }
                    }
                }
            }
        }
    }

    "progress-token allocator regenerates on collision" in {
        val map  = new java.util.concurrent.ConcurrentHashMap[Structure.Value, Channel[Structure.Value]]()
        val seed = Structure.Value.Str("seed-token")
        Channel.initUnscoped[Structure.Value](1).map { ch0 =>
            discard(map.put(seed, ch0))
            Channel.initUnscoped[Structure.Value](1).map { ch1 =>
                Channel.initUnscoped[Structure.Value](1).map { ch2 =>
                    internal.engine.ProgressEngine.allocateProgressToken(map, ch1, 32).map { t1 =>
                        internal.engine.ProgressEngine.allocateProgressToken(map, ch2, 32).map { t2 =>
                            assert(t1 != t2)
                            assert(t1 != seed)
                            assert(t2 != seed)
                            assert(map.containsKey(t1))
                            assert(map.containsKey(t2))
                        }
                    }
                }
            }
        }
    }

end JsonRpcHandlerProgressPolicyTest
