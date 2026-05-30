package kyo

import kyo.Maybe.Absent
import kyo.Maybe.Present

class JsonRpcEnvelopeTest extends JsonRpcTest:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    "Request, Notification, Response, Malformed are CanEqual-distinguishable" in run {
        val req: JsonRpcEnvelope = JsonRpcRequest(JsonRpcId.Num(1L), "m", Absent, Absent)
        val ntf: JsonRpcEnvelope = JsonRpcNotification("m", Absent, Absent)
        val rsp: JsonRpcEnvelope = JsonRpcResponse(JsonRpcId.Num(1L), Absent, Absent, Absent)
        val mal: JsonRpcEnvelope = JsonRpcMalformedMessage(Absent, "bad", Structure.Value.Null)
        assert(req != ntf)
        assert(req != rsp)
        assert(req != mal)
        assert(ntf != rsp)
        assert(ntf != mal)
        assert(rsp != mal)
    }

    "Request preserves the extras field on round-trip" in run {
        val extras              = Structure.Value.Str("opaque")
        val req: JsonRpcRequest = JsonRpcRequest(JsonRpcId.Num(1L), "m", Absent, Present(extras))
        assert(req.extras == Present(extras))
    }

    "Notification preserves the extras field on round-trip" in run {
        val extras                   = Structure.Value.Str("opaque")
        val ntf: JsonRpcNotification = JsonRpcNotification("m", Absent, Present(extras))
        assert(ntf.extras == Present(extras))
    }

    "Response with Present id and Present result is constructible" in run {
        val rsp: JsonRpcResponse = JsonRpcResponse(
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
        val raw                          = Structure.Value.Str("garbage")
        val mal: JsonRpcMalformedMessage = JsonRpcMalformedMessage(Absent, "bad-shape", raw)
        assert(mal.reason == "bad-shape")
        assert(mal.raw == raw)
    }

    "Malformed carries Maybe id slot" in run {
        val env = JsonRpcMalformedMessage(Present(JsonRpcId.Num(7)), "stringy error", Structure.Value.Str("raw"))
        env match
            case JsonRpcMalformedMessage(id, _, _) => assert(id == Present(JsonRpcId.Num(7)))
            case _                                 => fail("expected Malformed")
    }

    "Response.success factory sets result-present error-Absent extras-Absent" in run {
        val resp = JsonRpcResponse.success(JsonRpcId.Num(1L), Structure.Value.Str("ok"))
        assert(resp.id == JsonRpcId.Num(1L))
        assert(resp.result == Present(Structure.Value.Str("ok")))
        assert(resp.error == Absent)
        assert(resp.extras == Absent)
    }

    "Response.failure factory sets error-present result-Absent extras-Absent" in run {
        val resp = JsonRpcResponse.failure(JsonRpcId.Num(2L), JsonRpcError.MethodNotFound)
        assert(resp.id == JsonRpcId.Num(2L))
        assert(resp.result == Absent)
        assert(resp.error == Present(JsonRpcError.MethodNotFound))
        assert(resp.extras == Absent)
    }

    "Response copy preserves equality across fields" in run {
        val base    = JsonRpcResponse.success(JsonRpcId.Num(4L), Structure.Value.Str("v"))
        val mutated = base.copy(error = Present(JsonRpcError.InternalError))
        assert(base != mutated)
        assert(mutated.error == Present(JsonRpcError.InternalError))
        assert(mutated.id == base.id)
    }

end JsonRpcEnvelopeTest
