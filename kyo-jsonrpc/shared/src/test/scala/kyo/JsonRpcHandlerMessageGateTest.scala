package kyo

import kyo.Maybe.Absent

class JsonRpcHandlerMessageGateTest extends JsonRpcTest:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    "Decision values are CanEqual-distinguishable across Allow / Reject / Drop" in run {
        val a: JsonRpcHandler.MessageGate.Decision = JsonRpcHandler.MessageGate.Decision.Allow
        val d: JsonRpcHandler.MessageGate.Decision = JsonRpcHandler.MessageGate.Decision.Drop
        val r: JsonRpcHandler.MessageGate.Decision = JsonRpcHandler.MessageGate.Decision.Reject(JsonRpcError.InvalidRequest)
        assert(a != d)
        assert(a != r)
        assert(d != r)
    }

    "Reject decision carries the supplied JsonRpcError" in run {
        val err                                      = JsonRpcError.invalidRequest("nope")
        val dec: JsonRpcHandler.MessageGate.Decision = JsonRpcHandler.MessageGate.Decision.Reject(err)
        dec match
            case JsonRpcHandler.MessageGate.Decision.Reject(captured) => assert(captured == err)
            case other                                                => fail(s"expected Reject, got $other")
    }

    "a test-double gate returning Drop reports Drop for any envelope" in run {
        val gate = new JsonRpcHandler.MessageGate:
            def beforeDispatch(env: JsonRpcEnvelope)(using Frame): JsonRpcHandler.MessageGate.Decision < Sync =
                Sync.defer(JsonRpcHandler.MessageGate.Decision.Drop)
        val env = JsonRpcNotification("ping", Absent, Absent)
        gate.beforeDispatch(env).map: dec =>
            assert(dec == JsonRpcHandler.MessageGate.Decision.Drop)
    }

    "a test-double gate returning Allow reports Allow for any envelope" in run {
        val gate = new JsonRpcHandler.MessageGate:
            def beforeDispatch(env: JsonRpcEnvelope)(using Frame): JsonRpcHandler.MessageGate.Decision < Sync =
                Sync.defer(JsonRpcHandler.MessageGate.Decision.Allow)
        val env = JsonRpcRequest(JsonRpcId.Num(1L), "ping", Absent, Absent)
        gate.beforeDispatch(env).map: dec =>
            assert(dec == JsonRpcHandler.MessageGate.Decision.Allow)
    }

    "Reject and Drop are structurally distinct from Allow under pattern matching" in run {
        val cases: Seq[JsonRpcHandler.MessageGate.Decision] = Seq(
            JsonRpcHandler.MessageGate.Decision.Allow,
            JsonRpcHandler.MessageGate.Decision.Drop,
            JsonRpcHandler.MessageGate.Decision.Reject(JsonRpcError.InvalidParams)
        )
        val tags = cases.map:
            case JsonRpcHandler.MessageGate.Decision.Allow     => "A"
            case JsonRpcHandler.MessageGate.Decision.Drop      => "D"
            case JsonRpcHandler.MessageGate.Decision.Reject(_) => "R"
        assert(tags == Seq("A", "D", "R"))
    }

end JsonRpcHandlerMessageGateTest
