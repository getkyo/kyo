package kyo

import kyo.Maybe.Absent
import kyo.Maybe.Present

class JsonRpcErrorTest extends JsonRpcTestBase:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    "RFC code constants match the spec catalog" in run {
        assert(JsonRpcError.ParseError.code == -32700)
        assert(JsonRpcError.InvalidRequest.code == -32600)
        assert(JsonRpcError.MethodNotFound.code == -32601)
        assert(JsonRpcError.InvalidParams.code == -32602)
        assert(JsonRpcError.InternalError.code == -32603)
        assert(JsonRpcError.ServerNotInitialized.code == -32002)
        assert(JsonRpcError.UnknownErrorCode.code == -32001)
        assert(JsonRpcError.RequestCancelled.code == -32800)
        assert(JsonRpcError.ContentModified.code == -32801)
        assert(JsonRpcError.ServerCancelled.code == -32802)
        assert(JsonRpcError.RequestFailed.code == -32803)
    }

    "methodNotFound stamps the method name into message" in run {
        val err = JsonRpcError.methodNotFound("subscribe")
        assert(err.code == -32601)
        assert(err.message == "Method not found: subscribe")
        assert(err.data == Absent)
    }

    "invalidRequest, invalidParams, internalError carry reason into data" in run {
        val ir = JsonRpcError.invalidRequest("bad-shape")
        val ip = JsonRpcError.invalidParams("missing-field")
        val ie = JsonRpcError.internalError("boom", Present(Structure.Value.Str("ctx")))
        assert(ir.code == -32600 && ir.data == Present(Structure.Value.Str("bad-shape")))
        assert(ip.code == -32602 && ip.data == Present(Structure.Value.Str("missing-field")))
        assert(ie.code == -32603 && ie.message == "boom" && ie.data == Present(Structure.Value.Str("ctx")))
    }

    "cancelled smart constructor reports RequestCancelled with reason" in run {
        val withReason    = JsonRpcError.cancelled(Present("client-cancel"))
        val withoutReason = JsonRpcError.cancelled(Absent)
        assert(withReason.code == -32800 && withReason.message == "Request cancelled")
        assert(withReason.data == Present(Structure.Value.Str("client-cancel")))
        assert(withoutReason.code == -32800 && withoutReason.data == Absent)
    }

    "Schema[JsonRpcError] round-trips through Structure" in run {
        val err     = JsonRpcError.invalidParams("bad")
        val encoded = Structure.encode[JsonRpcError](err)
        val decoded = Structure.decode[JsonRpcError](encoded).getOrElse(fail("decode failed"))
        assert(decoded == err)
    }

end JsonRpcErrorTest
