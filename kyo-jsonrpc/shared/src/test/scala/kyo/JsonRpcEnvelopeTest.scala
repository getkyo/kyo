package kyo

import kyo.Maybe.Absent
import kyo.Maybe.Present

class JsonRpcEnvelopeTest extends JsonRpcTestBase:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    "Request, Notification, Response, Malformed are CanEqual-distinguishable" in run {
        val req: JsonRpcEnvelope = JsonRpcEnvelope.Request(JsonRpcId.Num(1L), "m", Absent, Absent)
        val ntf: JsonRpcEnvelope = JsonRpcEnvelope.Notification("m", Absent, Absent)
        val rsp: JsonRpcEnvelope = JsonRpcEnvelope.Response(JsonRpcId.Num(1L), Absent, Absent, Absent)
        val mal: JsonRpcEnvelope = JsonRpcEnvelope.Malformed(Absent, "bad", Structure.Value.Null)
        assert(req != ntf)
        assert(req != rsp)
        assert(req != mal)
        assert(ntf != rsp)
        assert(ntf != mal)
        assert(rsp != mal)
    }

    "Request preserves the extras field on round-trip" in run {
        val extras                       = Structure.Value.Str("opaque")
        val req: JsonRpcEnvelope.Request = JsonRpcEnvelope.Request(JsonRpcId.Num(1L), "m", Absent, Present(extras))
        assert(req.extras == Present(extras))
    }

    "Notification preserves the extras field on round-trip" in run {
        val extras                            = Structure.Value.Str("opaque")
        val ntf: JsonRpcEnvelope.Notification = JsonRpcEnvelope.Notification("m", Absent, Present(extras))
        assert(ntf.extras == Present(extras))
    }

    "Response with Present id and Present result is constructible" in run {
        val rsp: JsonRpcEnvelope.Response = JsonRpcEnvelope.Response(
            JsonRpcId.Num(7L),
            Present(Structure.Value.Str("ok")),
            Absent,
            Absent
        )
        assert(rsp.id == JsonRpcId.Num(7L))
        assert(rsp.result == Present(Structure.Value.Str("ok")))
        assert(rsp.error == Absent)
    }

    "Malformed retains both reason and raw payload" in run {
        val raw                            = Structure.Value.Str("garbage")
        val mal: JsonRpcEnvelope.Malformed = JsonRpcEnvelope.Malformed(Absent, "bad-shape", raw)
        assert(mal.reason == "bad-shape")
        assert(mal.raw == raw)
    }

    "Malformed carries Maybe id slot" in run {
        val env = JsonRpcEnvelope.Malformed(Present(JsonRpcId.Num(7)), "stringy error", Structure.Value.Str("raw"))
        env match
            case JsonRpcEnvelope.Malformed(id, _, _) => assert(id == Present(JsonRpcId.Num(7)))
            case _                                   => fail("expected Malformed")
    }

end JsonRpcEnvelopeTest
