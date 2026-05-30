package kyo

import kyo.Maybe.Absent
import kyo.Maybe.Present

class JsonRpcErrorTest extends JsonRpcTest:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    "leaf codes match the JSON-RPC 2.0 spec" in run {
        assert(JsonRpcParseError("[input]", 0, JsonRpcParseError.Reason.TrailingContent).code == -32700)
        assert(JsonRpcInvalidRequestError(Structure.Value.Null, Chunk.empty).code == -32600)
        assert(JsonRpcMethodNotFoundError("m", Chunk.empty).code == -32601)
        assert(JsonRpcInvalidParamsError("m", Absent, Chunk.empty).code == -32602)
        assert(JsonRpcConfigurationError("setting", "reason").code == -32603)
        assert(JsonRpcLifecycleError(JsonRpcLifecycleError.Stage.Close).code == -32603)
        assert(JsonRpcTransportError("detail", new RuntimeException("cause")).code == -32603)
        assert(JsonRpcHandlerPanicError("method", new RuntimeException("cause")).code == -32603)
        assert(JsonRpcInternalError(JsonRpcInternalError.Operation.DecodeResult, new RuntimeException("cause")).code == -32603)
        assert(JsonRpcImplementationError(-32050, "label").code == -32050)
        assert(JsonRpcCustomError(409, "Conflict").code == 409)
    }

    "JsonRpcParseError.Reason.describe" in run {
        import JsonRpcParseError.Reason.*
        assert(UnexpectedEof.describe == "unexpected end of input")
        assert(UnexpectedChar('x', "y").describe == "unexpected 'x', expected y")
        assert(InvalidEscape("n").describe == "invalid escape sequence \\n")
        assert(NumberOutOfRange("1e999").describe == "number 1e999 out of range")
        assert(TrailingContent.describe == "trailing content after JSON value")
    }

    "JsonRpcLifecycleError.Stage.describe" in run {
        assert(JsonRpcLifecycleError.Stage.Close.describe == "close")
        assert(JsonRpcLifecycleError.Stage.Init.describe == "init")
    }

    "JsonRpcInternalError.Operation.describe" in run {
        assert(JsonRpcInternalError.Operation.DecodeResult.describe == "result decode")
        assert(JsonRpcInternalError.Operation.EncodeResponse.describe == "response encode")
        assert(JsonRpcInternalError.Operation.Other.describe == "internal operation")
    }

    "JsonRpcInvalidParamsError.ParamError.describe" in run {
        import JsonRpcInvalidParamsError.*
        val e = ParamError("field", Problem.Missing)
        assert(e.describe == "'field': missing required field")
        val e2 = ParamError("x", Problem.TypeMismatch("Int", "String"))
        assert(e2.describe == "'x': expected Int, got String")
        val e3 = ParamError("y", Problem.ConstraintViolation("must be positive"))
        assert(e3.describe == "'y': constraint violated: must be positive")
    }

    "JsonRpcImplementationError rejects out-of-range codes" in run {
        val result = Result.catching[IllegalArgumentException](JsonRpcImplementationError(-32200, "bad"))
        assert(result.isFailure)
        val ok = JsonRpcImplementationError(-32050, "label")
        assert(ok.code == -32050)
    }

    "JsonRpcError.fromWire maps standard codes to typed leaves" in run {
        val p = JsonRpcError.fromWire(-32700, "Parse error", Absent)
        assert(p.isInstanceOf[JsonRpcParseError])
        assert(p.code == -32700)
        val ir = JsonRpcError.fromWire(-32600, "Invalid Request", Absent)
        assert(ir.isInstanceOf[JsonRpcInvalidRequestError])
        val mn = JsonRpcError.fromWire(-32601, "Method not found", Absent)
        assert(mn.isInstanceOf[JsonRpcMethodNotFoundError])
        val ip = JsonRpcError.fromWire(-32602, "Invalid params", Absent)
        assert(ip.isInstanceOf[JsonRpcInvalidParamsError])
        val ie = JsonRpcError.fromWire(-32603, "Internal error", Absent)
        assert(ie.isInstanceOf[JsonRpcInternalError])
        val impl = JsonRpcError.fromWire(-32050, "Server error", Absent)
        assert(impl.isInstanceOf[JsonRpcImplementationError])
        val custom = JsonRpcError.fromWire(409, "Conflict", Absent)
        assert(custom.isInstanceOf[JsonRpcCustomError])
        assert(custom.code == 409)
    }

    "subcategory traits are correctly assigned" in run {
        assert(JsonRpcParseError("[input]", 0, JsonRpcParseError.Reason.TrailingContent).isInstanceOf[JsonRpcParseFailure])
        assert(JsonRpcInvalidRequestError(Structure.Value.Null, Chunk.empty).isInstanceOf[JsonRpcParseFailure])
        assert(JsonRpcMethodNotFoundError("m", Chunk.empty).isInstanceOf[JsonRpcDispatchFailure])
        assert(JsonRpcInvalidParamsError("m", Absent, Chunk.empty).isInstanceOf[JsonRpcDispatchFailure])
        assert(JsonRpcConfigurationError("s", "r").isInstanceOf[JsonRpcExecutionFailure])
        assert(JsonRpcLifecycleError(JsonRpcLifecycleError.Stage.Close).isInstanceOf[JsonRpcExecutionFailure])
        assert(JsonRpcTransportError("d", new RuntimeException("c")).isInstanceOf[JsonRpcExecutionFailure])
        assert(JsonRpcHandlerPanicError("m", new RuntimeException("c")).isInstanceOf[JsonRpcExecutionFailure])
        assert(JsonRpcInternalError(
            JsonRpcInternalError.Operation.DecodeResult,
            new RuntimeException("c")
        ).isInstanceOf[JsonRpcExecutionFailure])
        assert(JsonRpcCustomError(409, "Conflict").isInstanceOf[JsonRpcApplicationFailure])
    }

    "Schema[JsonRpcError] wire round-trip via fromWire" in run {
        val err     = JsonRpcMethodNotFoundError("subscribe", Chunk.empty)
        val encoded = Structure.encode[JsonRpcError](err)
        val decoded = Structure.decode[JsonRpcError](encoded).getOrElse(fail("decode failed"))
        assert(decoded.code == -32601)
        assert(decoded.isInstanceOf[JsonRpcMethodNotFoundError])
    }

    "Schema[JsonRpcError] round-trips code/message/data triple" in run {
        val err     = JsonRpcCustomError(409, "Conflict", Present(Structure.Value.Str("details")))
        val encoded = Structure.encode[JsonRpcError](err)
        val decoded = Structure.decode[JsonRpcError](encoded).getOrElse(fail("decode failed"))
        assert(decoded.code == 409)
        assert(decoded.data == Present(Structure.Value.Str("details")))
    }

end JsonRpcErrorTest
