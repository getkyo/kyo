package kyo

import kyo.Maybe.Absent
import kyo.Maybe.Present

class JsonRpcHandlerExtrasEncoderTest extends JsonRpcTest:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    "empty.resolve always yields Absent regardless of id" in {
        for
            a <- JsonRpcExtrasEncoder.empty.resolve(JsonRpcId.Num(1L))
            b <- JsonRpcExtrasEncoder.empty.resolve(JsonRpcId.Str("x"))
        yield
            assert(a == Absent)
            assert(b == Absent)
    }

    "const(v).resolve always yields Present(v) regardless of id" in {
        val v   = Structure.Value.Str("payload")
        val enc = JsonRpcExtrasEncoder.const(v)
        for
            a <- enc.resolve(JsonRpcId.Num(1L))
            b <- enc.resolve(JsonRpcId.Str("x"))
        yield
            assert(a == Present(v))
            assert(b == Present(v))
        end for
    }

    "apply(f).resolve forwards id to f" in {
        val enc = JsonRpcExtrasEncoder { id =>
            Sync.defer(Present(Structure.Value.Str(id.toString)))
        }
        for
            a <- enc.resolve(JsonRpcId.Num(7L))
        yield assert(a == Present(Structure.Value.Str(JsonRpcId.Num(7L).toString)))
    }

    "apply(f) lifts a Sync-effectful body through .resolve" in {
        // Unsafe: AtomicLong.Unsafe.init for in-test counter outside effect context
        val counter = AtomicLong.Unsafe.init(0L)(using AllowUnsafe.embrace.danger)
        val enc = JsonRpcExtrasEncoder { _ =>
            Sync.Unsafe.defer(Present(Structure.Value.Integer(counter.incrementAndGet())))
        }
        for
            a <- enc.resolve(JsonRpcId.Num(1L))
            b <- enc.resolve(JsonRpcId.Num(2L))
        yield
            assert(a == Present(Structure.Value.Integer(1L)))
            assert(b == Present(Structure.Value.Integer(2L)))
        end for
    }

end JsonRpcHandlerExtrasEncoderTest
