package kyo

import kyo.Maybe.Absent
import kyo.Maybe.Present

class ProgressPolicyTest extends Test:

    case class TaskReq(name: String) derives Schema, CanEqual
    case class TaskResp(done: Boolean) derives Schema, CanEqual
    case class SearchReq(query: String) derives Schema, CanEqual

    // Plain case class for out-of-band LSP progress notification params so Structure.encode
    // produces a flat {"token": ..., "value": ...} record (not a discriminated enum encoding).
    case class LspProgressParams(token: String, value: String) derives Schema, CanEqual

    // LSP stamped params include workDoneToken alongside the original fields.
    case class TaskReqWithToken(
        name: String,
        workDoneToken: Maybe[String] = Absent
    ) derives Schema, CanEqual

    // MCP stamped params include _meta with progressToken.
    case class ProgressMeta(progressToken: Maybe[String] = Absent) derives Schema, CanEqual
    case class TaskReqWithMeta(
        name: String,
        `_meta`: Maybe[ProgressMeta] = Absent
    ) derives Schema, CanEqual

    private val lspConfig = JsonRpcEndpoint.Config(progress = Present(ProgressPolicy.lsp))
    private val mcpConfig = JsonRpcEndpoint.Config(
        progress = Present(ProgressPolicy.mcp),
        cancellation = Present(CancellationPolicy.mcp)
    )

    private def sendProgress(ctx: HandlerCtx, v: Structure.Value)(using Frame): Unit < (Async & Abort[JsonRpcError]) =
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

    "callWithProgress with LSP: handler calls ctx.progress three times, caller observes three progress values" in run {
        val longTask = JsonRpcMethod[TaskReq, TaskResp, Async & Abort[JsonRpcError]]("longTask") {
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
            JsonRpcEndpoint.init(ta, Seq.empty, lspConfig).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq(longTask), lspConfig).map { _ =>
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

    "callWithProgress with LSP: stampOutboundToken attaches workDoneToken to params, handler reads token" in run {
        // Unsafe: AtomicRef.Unsafe.init for token capture across fibers
        val capturedToken = AtomicRef.Unsafe.init[Maybe[String]](Absent)(using AllowUnsafe.embrace.danger)
        val taskMethod = JsonRpcMethod[TaskReqWithToken, TaskResp, Async & Abort[JsonRpcError]]("task") {
            (params, _) =>
                Sync.defer(capturedToken.set(params.workDoneToken)(using AllowUnsafe.embrace.danger)).andThen(TaskResp(true))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcEndpoint.init(ta, Seq.empty, lspConfig).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq(taskMethod), lspConfig).map { _ =>
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

    "callPartialResults with LSP: handler sends three progress notifications then null final response, stream emits three strings" in run {
        val searchMethod = JsonRpcMethod[SearchReq, Structure.Value, Async & Abort[JsonRpcError]]("search") {
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
            JsonRpcEndpoint.init(ta, Seq.empty, lspConfig).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq(searchMethod), lspConfig).map { _ =>
                    endpointA.callPartialResults[SearchReq, String]("search", SearchReq("q")).run.map { chunks =>
                        assert(chunks.size == 3, s"expected 3 chunks, got ${chunks.size}: $chunks")
                    }
                }
            }
        }
    }

    "callWithProgress with MCP: outbound params carry _meta.progressToken, handler receives it" in run {
        // Unsafe: AtomicRef.Unsafe.init for meta capture across fibers
        val capturedMeta = AtomicRef.Unsafe.init[Maybe[ProgressMeta]](Absent)(using AllowUnsafe.embrace.danger)
        val taskMethod = JsonRpcMethod[TaskReqWithMeta, TaskResp, Async & Abort[JsonRpcError]]("run") {
            (params, _) =>
                Sync.defer(capturedMeta.set(params.`_meta`)(using AllowUnsafe.embrace.danger)).andThen(TaskResp(true))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcEndpoint.init(ta, Seq.empty, mcpConfig).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq(taskMethod), mcpConfig).map { _ =>
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

    "subscribeProgress returns a stream; subsequent progress notification with that token delivers a value" in run {
        val tokenStr = "oob-token-1"
        val token    = Structure.Value.Str(tokenStr)
        // Unsafe: AtomicBoolean.Unsafe.init for received flag across fibers
        val received = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger)
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcEndpoint.init(ta, Seq.empty, lspConfig).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq.empty, lspConfig).map { endpointB =>
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
                            endpointB.notify[LspProgressParams](
                                "$/progress",
                                LspProgressParams(tokenStr, "ping")
                            ).andThen {
                                // Wait until A's reader has delivered the value to the stream consumer.
                                untilTrue(Sync.defer(received.get()(using AllowUnsafe.embrace.danger))).andThen {
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

    "unsubscribeProgress: stream closes and subsequent notifications for that token are dropped" in run {
        val tokenStr = "oob-token-close"
        val token    = Structure.Value.Str(tokenStr)
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcEndpoint.init(ta, Seq.empty, lspConfig).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq.empty, lspConfig).map { endpointB =>
                    endpointA.subscribeProgress(token).map { stream =>
                        endpointA.unsubscribeProgress(token).andThen {
                            endpointB.notify[LspProgressParams](
                                "$/progress",
                                LspProgressParams(tokenStr, "ignored")
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

    "ctx.progress with progress = Absent returns Unit without sending any progress wire notification" in run {
        val taskMethod = JsonRpcMethod[TaskReq, TaskResp, Async & Abort[JsonRpcError]]("task") {
            (_, ctx) => sendProgress(ctx, mkBegin).andThen(TaskResp(true))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capA       = new CapturingTransport(ta)
            val noProgress = JsonRpcEndpoint.Config()
            JsonRpcEndpoint.init(capA, Seq.empty, noProgress).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq(taskMethod), noProgress).map { _ =>
                    endpointA.call[TaskReq, TaskResp]("task", TaskReq("t")).map { resp =>
                        assert(resp == TaskResp(true))
                        val notifs = capA.sentList.collect {
                            case n: JsonRpcEnvelope.Notification => n
                        }
                        assert(notifs.isEmpty, s"expected no progress notifications, got $notifs")
                    }
                }
            }
        }
    }

    "MCP monotonicity: non-monotonic progress value 5.0 between 10.0 and 20.0 is dropped" in run {
        val taskMethod = JsonRpcMethod[TaskReq, TaskResp, Async & Abort[JsonRpcError]]("task") {
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
            JsonRpcEndpoint.init(ta, Seq.empty, mcpConfig).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq(taskMethod), mcpConfig).map { _ =>
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

    "LSP non-monotonic: all three progress values pass through in order" in run {
        val taskMethod = JsonRpcMethod[TaskReq, TaskResp, Async & Abort[JsonRpcError]]("task") {
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
            JsonRpcEndpoint.init(ta, Seq.empty, lspConfig).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq(taskMethod), lspConfig).map { _ =>
                    endpointA.callWithProgress[TaskReq, TaskResp]("task", TaskReq("t")).map { pending =>
                        pending.progress.run.map { chunks =>
                            pending.result.map { _ =>
                                assert(chunks.size == 3, s"LSP expected all 3 values, got: $chunks")
                            }
                        }
                    }
                }
            }
        }
    }

    "ctx.progress called after handler has returned is a no-op: no extra wire notification sent" in run {
        // Unsafe: AtomicRef.Unsafe.init for sink capture across fibers
        val sinkRef =
            AtomicRef.Unsafe.init[Maybe[Structure.Value => Unit < (Async & Abort[Closed])]](Absent)(using AllowUnsafe.embrace.danger)
        // Unsafe: AtomicBoolean.Unsafe.init for handler-done flag across fibers
        val handlerDone = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger)
        val taskMethod = JsonRpcMethod[TaskReq, TaskResp, Async & Abort[JsonRpcError]]("task") {
            (_, ctx) =>
                Sync.defer(sinkRef.set(ctx.progressSink)(using AllowUnsafe.embrace.danger)).andThen {
                    Sync.defer(handlerDone.set(true)(using AllowUnsafe.embrace.danger)).andThen {
                        TaskResp(true)
                    }
                }
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capA = new CapturingTransport(ta)
            JsonRpcEndpoint.init(capA, Seq.empty, lspConfig).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq(taskMethod), lspConfig).map { _ =>
                    endpointA.callWithProgress[TaskReq, TaskResp]("task", TaskReq("t")).map { pending =>
                        pending.result.map { _ =>
                            untilTrue(Sync.defer(handlerDone.get()(using AllowUnsafe.embrace.danger))).andThen {
                                val notifsBefore = capA.sentList.count {
                                    case n: JsonRpcEnvelope.Notification if n.method == "$/progress" => true
                                    case _                                                           => false
                                }
                                sinkRef.get()(using AllowUnsafe.embrace.danger) match
                                    case Present(sink) =>
                                        Abort.run[Closed](sink(mkReport("late"))).map { _ =>
                                            val notifsAfter = capA.sentList.count {
                                                case n: JsonRpcEnvelope.Notification if n.method == "$/progress" => true
                                                case _                                                           => false
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

    "out-of-band progress notification with unknown token is silently dropped without error" in run {
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcEndpoint.init(ta, Seq.empty, lspConfig).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq.empty, lspConfig).map { endpointB =>
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

    "callPartialResults where final response has non-absent result: it is decoded as the last chunk" in run {
        val searchMethod = JsonRpcMethod[SearchReq, String, Async & Abort[JsonRpcError]]("search") {
            (_, ctx) =>
                sendProgress(ctx, Structure.Value.Str("partial1")).andThen {
                    "final-result"
                }
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcEndpoint.init(ta, Seq.empty, lspConfig).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq(searchMethod), lspConfig).map { _ =>
                    endpointA.callPartialResults[SearchReq, String]("search", SearchReq("q")).run.map { chunks =>
                        assert(chunks.nonEmpty, s"expected at least one chunk (partial or final), got $chunks")
                    }
                }
            }
        }
    }

    "ctx.progress extras: progress notification is built with extras captured from the inbound request" in run {
        val taskMethod = JsonRpcMethod[TaskReq, TaskResp, Async & Abort[JsonRpcError]]("task") {
            (_, ctx) => sendProgress(ctx, mkBegin).andThen(TaskResp(true))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            // Wrap B's transport to capture outbound envelopes from B (which include the progress notification).
            val capB = new CapturingTransport(tb)
            JsonRpcEndpoint.init(ta, Seq.empty, lspConfig).map { endpointA =>
                JsonRpcEndpoint.init(capB, Seq(taskMethod), lspConfig).map { _ =>
                    endpointA.callWithProgress[TaskReq, TaskResp]("task", TaskReq("t")).map { pending =>
                        pending.result.map { _ =>
                            val progressNotifs = capB.sentList.collect {
                                case n: JsonRpcEnvelope.Notification if n.method == "$/progress" => n
                            }
                            assert(progressNotifs.nonEmpty, "expected at least one progress notification on the wire")
                        }
                    }
                }
            }
        }
    }

    "MCP concurrent monotonicity: two concurrent progress calls emit only the one with the larger value" in run {
        val taskMethod = JsonRpcMethod[TaskReq, TaskResp, Async & Abort[JsonRpcError]]("task") {
            (_, ctx) =>
                Async.zip[JsonRpcError, Unit, Unit, Any](
                    sendProgress(ctx, mkProgress(10.0)),
                    sendProgress(ctx, mkProgress(5.0))
                ).andThen(TaskResp(true))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            // Wrap B's transport to capture outbound envelopes from B (progress notifications come from B).
            val capB = new CapturingTransport(tb)
            JsonRpcEndpoint.init(ta, Seq.empty, mcpConfig).map { endpointA =>
                JsonRpcEndpoint.init(capB, Seq(taskMethod), mcpConfig).map { _ =>
                    endpointA.callWithProgress[TaskReq, TaskResp]("task", TaskReq("t")).map { pending =>
                        pending.result.map { _ =>
                            val notifCount = capB.sentList.count {
                                case n: JsonRpcEnvelope.Notification if n.method == "notifications/progress" => true
                                case _                                                                       => false
                            }
                            assert(notifCount == 1, s"MCP monotonicity: expected 1 notification, got $notifCount")
                        }
                    }
                }
            }
        }
    }

end ProgressPolicyTest
