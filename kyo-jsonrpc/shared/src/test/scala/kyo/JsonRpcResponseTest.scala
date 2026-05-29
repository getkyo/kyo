package kyo

import kyo.Maybe.Absent
import kyo.Maybe.Present

class JsonRpcResponseTest extends JsonRpcTestBase:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    "success factory enforces result-present and error-Absent" in run {
        val resp = JsonRpcResponse.success(JsonRpcId.Num(1L), Structure.Value.Str("ok"))
        assert(resp.id == Present(JsonRpcId.Num(1L)))
        assert(resp.result == Present(Structure.Value.Str("ok")))
        assert(resp.error == Absent)
    }

    "failure factory enforces error-present and result-Absent" in run {
        val resp = JsonRpcResponse.failure(JsonRpcId.Num(2L), JsonRpcError.MethodNotFound)
        assert(resp.id == Present(JsonRpcId.Num(2L)))
        assert(resp.result == Absent)
        assert(resp.error == Present(JsonRpcError.MethodNotFound))
    }

    "Schema[JsonRpcResponse] round-trips a success through Structure" in run {
        val resp    = JsonRpcResponse.success(JsonRpcId.Num(3L), Structure.Value.Str("payload"))
        val encoded = Structure.encode[JsonRpcResponse](resp)
        val decoded = Structure.decode[JsonRpcResponse](encoded).getOrElse(fail("decode failed"))
        assert(decoded == resp)
    }

    "Schema[JsonRpcResponse] round-trips a failure through Structure" in run {
        val resp    = JsonRpcResponse.failure(JsonRpcId.Str("k"), JsonRpcError.InvalidParams)
        val encoded = Structure.encode[JsonRpcResponse](resp)
        val decoded = Structure.decode[JsonRpcResponse](encoded).getOrElse(fail("decode failed"))
        assert(decoded == resp)
    }

    "copy preserves equality across both fields" in run {
        val base    = JsonRpcResponse.success(JsonRpcId.Num(4L), Structure.Value.Str("v"))
        val mutated = base.copy(error = Present(JsonRpcError.InternalError))
        assert(base != mutated)
        assert(mutated.error == Present(JsonRpcError.InternalError))
        assert(mutated.id == base.id)
    }

end JsonRpcResponseTest
