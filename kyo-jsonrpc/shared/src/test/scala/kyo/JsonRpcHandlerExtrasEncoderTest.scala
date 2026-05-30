package kyo

import kyo.Maybe.Absent
import kyo.Maybe.Present

class JsonRpcHandlerExtrasEncoderTest extends JsonRpcTest:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    "empty.resolve always yields Absent regardless of id" in run {
        for
            a <- JsonRpcHandler.ExtrasEncoder.empty.resolve(JsonRpcEnvelope.Id.Num(1L))
            b <- JsonRpcHandler.ExtrasEncoder.empty.resolve(JsonRpcEnvelope.Id.Str("x"))
        yield
            assert(a == Absent)
            assert(b == Absent)
    }

    "const(v).resolve always yields Present(v) regardless of id" in run {
        val v   = Structure.Value.Str("payload")
        val enc = JsonRpcHandler.ExtrasEncoder.const(v)
        for
            a <- enc.resolve(JsonRpcEnvelope.Id.Num(1L))
            b <- enc.resolve(JsonRpcEnvelope.Id.Str("x"))
        yield
            assert(a == Present(v))
            assert(b == Present(v))
        end for
    }

    "apply(f).resolve forwards id to f" in run {
        val enc = JsonRpcHandler.ExtrasEncoder { id =>
            Sync.defer(Present(Structure.Value.Str(id.toString)))
        }
        for
            a <- enc.resolve(JsonRpcEnvelope.Id.Num(7L))
        yield assert(a == Present(Structure.Value.Str(JsonRpcEnvelope.Id.Num(7L).toString)))
    }

    "apply(f) lifts a Sync-effectful body through .resolve" in run {
        // Unsafe: AtomicLong.Unsafe.init for in-test counter outside effect context
        val counter = AtomicLong.Unsafe.init(0L)(using AllowUnsafe.embrace.danger)
        val enc = JsonRpcHandler.ExtrasEncoder { _ =>
            Sync.Unsafe.defer(Present(Structure.Value.Integer(counter.incrementAndGet())))
        }
        for
            a <- enc.resolve(JsonRpcEnvelope.Id.Num(1L))
            b <- enc.resolve(JsonRpcEnvelope.Id.Num(2L))
        yield
            assert(a == Present(Structure.Value.Integer(1L)))
            assert(b == Present(Structure.Value.Integer(2L)))
        end for
    }

end JsonRpcHandlerExtrasEncoderTest
