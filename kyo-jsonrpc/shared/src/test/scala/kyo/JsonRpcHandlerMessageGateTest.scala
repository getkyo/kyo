package kyo

import kyo.Maybe.Absent

class JsonRpcHandlerMessageGateTest extends JsonRpcTest:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    private val sentinelId = JsonRpcId.Num(1L)

    "Decision values are CanEqual-distinguishable across Allow / Reject / Drop" in run {
        val a: JsonRpcMessageGate.Decision = JsonRpcMessageGate.Decision.Allow
        val d: JsonRpcMessageGate.Decision = JsonRpcMessageGate.Decision.Drop
        val r: JsonRpcMessageGate.Decision =
            JsonRpcMessageGate.Decision.Reject(
                JsonRpcResponse.failure(sentinelId, JsonRpcInvalidRequestError(Structure.Value.Null, Chunk.empty))
            )
        assert(a != d)
        assert(a != r)
        assert(d != r)
    }

    "Reject decision carries the supplied JsonRpcResponse" in run {
        val err                              = JsonRpcInvalidRequestError(Structure.Value.Str("nope"), Chunk.empty)
        val resp                             = JsonRpcResponse.failure(sentinelId, err)
        val dec: JsonRpcMessageGate.Decision = JsonRpcMessageGate.Decision.Reject(resp)
        dec match
            case JsonRpcMessageGate.Decision.Reject(captured) => assert(captured == resp)
            case other                                        => fail(s"expected Reject, got $other")
    }

    "a test-double gate returning Drop reports Drop for any envelope" in run {
        val gate = new JsonRpcMessageGate:
            def beforeDispatch(env: JsonRpcEnvelope)(using Frame): JsonRpcMessageGate.Decision < Sync =
                Sync.defer(JsonRpcMessageGate.Decision.Drop)
        val env = JsonRpcNotification("ping", Absent, Absent)
        gate.beforeDispatch(env).map: dec =>
            assert(dec == JsonRpcMessageGate.Decision.Drop)
    }

    "a test-double gate returning Allow reports Allow for any envelope" in run {
        val gate = new JsonRpcMessageGate:
            def beforeDispatch(env: JsonRpcEnvelope)(using Frame): JsonRpcMessageGate.Decision < Sync =
                Sync.defer(JsonRpcMessageGate.Decision.Allow)
        val env = JsonRpcRequest(JsonRpcId.Num(1L), "ping", Absent, Absent)
        gate.beforeDispatch(env).map: dec =>
            assert(dec == JsonRpcMessageGate.Decision.Allow)
    }

    "Reject and Drop are structurally distinct from Allow under pattern matching" in run {
        val cases: Seq[JsonRpcMessageGate.Decision] = Seq(
            JsonRpcMessageGate.Decision.Allow,
            JsonRpcMessageGate.Decision.Drop,
            JsonRpcMessageGate.Decision.Reject(
                JsonRpcResponse.failure(sentinelId, JsonRpcInvalidParamsError("m", Maybe.Absent, Chunk.empty))
            )
        )
        val tags = cases.map:
            case JsonRpcMessageGate.Decision.Allow     => "A"
            case JsonRpcMessageGate.Decision.Drop      => "D"
            case JsonRpcMessageGate.Decision.Reject(_) => "R"
        assert(tags == Seq("A", "D", "R"))
    }

    "noop gate always returns Allow" in run {
        val env1 = JsonRpcNotification("ping", Absent, Absent)
        val env2 = JsonRpcRequest(JsonRpcId.Num(2L), "call", Absent, Absent)
        JsonRpcMessageGate.noop.beforeDispatch(env1).map { d1 =>
            JsonRpcMessageGate.noop.beforeDispatch(env2).map { d2 =>
                assert(d1 == JsonRpcMessageGate.Decision.Allow)
                assert(d2 == JsonRpcMessageGate.Decision.Allow)
            }
        }
    }

end JsonRpcHandlerMessageGateTest
