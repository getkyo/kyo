package kyo

import kyo.Maybe.Absent
import kyo.Maybe.Present

class JsonRpcRouteContextTest extends JsonRpcTest:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    "progress with a Present sink invokes the captured callback" in {
        // Unsafe: AtomicRef.Unsafe.init for thread-safe capture outside effect context
        val captured = AtomicRef.Unsafe.init(List.empty[Structure.Value])(using AllowUnsafe.embrace.danger)
        val sink: Structure.Value => Unit < (Async & Abort[Closed]) =
            v =>
                Sync.defer {
                    captured.updateAndGet(_ :+ v)(using AllowUnsafe.embrace.danger)
                    ()
                }
        for
            promise <- Fiber.Promise.init[Unit, Sync]
            ctx = JsonRpcRoute.Context.forTest(promise, Present(JsonRpcId.Num(1L)), Absent, Present(sink))
            _ <- ctx.progress(Structure.Value.Str("p"))
            seen = captured.get()(using AllowUnsafe.embrace.danger)
        yield assert(seen == List(Structure.Value.Str("p")))
        end for
    }

    "progress with an Absent sink is a no-op" in {
        for
            promise <- Fiber.Promise.init[Unit, Sync]
            ctx = JsonRpcRoute.Context.forTest(promise, Absent, Absent, Absent)
            _ <- ctx.progress(Structure.Value.Str("p"))
        yield succeed
    }

    "extras and requestId are surfaced verbatim from forTest" in {
        val extras = Structure.Value.Str("opaque")
        for
            promise <- Fiber.Promise.init[Unit, Sync]
            ctx = JsonRpcRoute.Context.forTest(promise, Present(JsonRpcId.Str("rid")), Present(extras), Absent)
        yield
            assert(ctx.requestId == Present(JsonRpcId.Str("rid")))
            assert(ctx.extras == Present(extras))
        end for
    }

    "cancelled Promise is constructible and not yet completed at forTest exit" in {
        for
            promise <- Fiber.Promise.init[Unit, Sync]
            ctx = JsonRpcRoute.Context.forTest(promise, Absent, Absent, Absent)
            done <- ctx.cancelled.done
        yield assert(!done)
    }

    "notify with a Present sink sends the encoded notification stamped with the extras" in {
        case class Note(text: String) derives Schema, CanEqual
        val extras = Structure.Value.Str("opaque")
        for
            sent    <- AtomicRef.init(Chunk.empty[JsonRpcNotification])
            promise <- Fiber.Promise.init[Unit, Sync]
            sink: (JsonRpcNotification => Unit < (Async & Abort[Closed])) = n => sent.updateAndGet(_.append(n)).unit
            ctx = JsonRpcRoute.Context.forTest(promise, Present(JsonRpcId.Num(1L)), Present(extras), Absent, Absent, Present(sink))
            _    <- ctx.notify("log", Note("hello"))
            seen <- sent.get
        yield assert(seen == Chunk(JsonRpcNotification("log", Present(Structure.encode(Note("hello"))), Present(extras))))
        end for
    }

    "notify with an Absent sink is a no-op" in {
        for
            promise <- Fiber.Promise.init[Unit, Sync]
            ctx = JsonRpcRoute.Context.forTest(promise, Absent, Absent, Absent)
            _ <- ctx.notify("log", "ignored")
        yield assert(ctx.notificationSink.isEmpty)
    }

    "meta is surfaced verbatim from forTest, and the four-argument forTest leaves it Absent" in {
        val meta = Structure.Value.Record(Chunk("progressToken" -> Structure.Value.Integer(7L)))
        for
            promise <- Fiber.Promise.init[Unit, Sync]
            full  = JsonRpcRoute.Context.forTest(promise, Absent, Absent, Present(meta), Absent, Absent)
            short = JsonRpcRoute.Context.forTest(promise, Absent, Absent, Absent)
        yield
            assert(full.meta == Present(meta))
            assert(short.meta == Absent)
        end for
    }

    "metaOf reads the _meta member of a params object and is Absent otherwise" in {
        val meta        = Structure.Value.Record(Chunk("k" -> Structure.Value.Str("v")))
        val withMeta    = Present(Structure.Value.Record(Chunk("x" -> Structure.Value.Integer(1L), "_meta" -> meta)))
        val without     = Present(Structure.Value.Record(Chunk("x" -> Structure.Value.Integer(1L))))
        val notAnObject = Present(Structure.Value.Sequence(Chunk(Structure.Value.Integer(1L))))
        assert(JsonRpcRoute.Context.metaOf(withMeta) == Present(meta))
        assert(JsonRpcRoute.Context.metaOf(without) == Absent)
        assert(JsonRpcRoute.Context.metaOf(notAnObject) == Absent)
        assert(JsonRpcRoute.Context.metaOf(Absent) == Absent)
    }

end JsonRpcRouteContextTest
