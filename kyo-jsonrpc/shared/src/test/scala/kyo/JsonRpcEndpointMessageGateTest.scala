package kyo

import kyo.Maybe.Absent

class JsonRpcEndpointMessageGateTest extends JsonRpcTestBase:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    "Decision values are CanEqual-distinguishable across Allow / Reject / Drop" in run {
        val a: JsonRpcEndpoint.MessageGate.Decision = JsonRpcEndpoint.MessageGate.Decision.Allow
        val d: JsonRpcEndpoint.MessageGate.Decision = JsonRpcEndpoint.MessageGate.Decision.Drop
        val r: JsonRpcEndpoint.MessageGate.Decision = JsonRpcEndpoint.MessageGate.Decision.Reject(JsonRpcError.InvalidRequest)
        assert(a != d)
        assert(a != r)
        assert(d != r)
    }

    "Reject decision carries the supplied JsonRpcError" in run {
        val err                                       = JsonRpcError.invalidRequest("nope")
        val dec: JsonRpcEndpoint.MessageGate.Decision = JsonRpcEndpoint.MessageGate.Decision.Reject(err)
        dec match
            case JsonRpcEndpoint.MessageGate.Decision.Reject(captured) => assert(captured == err)
            case other                                                 => fail(s"expected Reject, got $other")
    }

    "a test-double gate returning Drop reports Drop for any envelope" in run {
        val gate = new JsonRpcEndpoint.MessageGate:
            def beforeDispatch(env: JsonRpcEnvelope)(using Frame): JsonRpcEndpoint.MessageGate.Decision < Sync =
                Sync.defer(JsonRpcEndpoint.MessageGate.Decision.Drop)
        val env = JsonRpcEnvelope.Notification("ping", Absent, Absent)
        gate.beforeDispatch(env).map: dec =>
            assert(dec == JsonRpcEndpoint.MessageGate.Decision.Drop)
    }

    "a test-double gate returning Allow reports Allow for any envelope" in run {
        val gate = new JsonRpcEndpoint.MessageGate:
            def beforeDispatch(env: JsonRpcEnvelope)(using Frame): JsonRpcEndpoint.MessageGate.Decision < Sync =
                Sync.defer(JsonRpcEndpoint.MessageGate.Decision.Allow)
        val env = JsonRpcEnvelope.Request(JsonRpcEnvelope.Id.Num(1L), "ping", Absent, Absent)
        gate.beforeDispatch(env).map: dec =>
            assert(dec == JsonRpcEndpoint.MessageGate.Decision.Allow)
    }

    "Reject and Drop are structurally distinct from Allow under pattern matching" in run {
        val cases: Seq[JsonRpcEndpoint.MessageGate.Decision] = Seq(
            JsonRpcEndpoint.MessageGate.Decision.Allow,
            JsonRpcEndpoint.MessageGate.Decision.Drop,
            JsonRpcEndpoint.MessageGate.Decision.Reject(JsonRpcError.InvalidParams)
        )
        val tags = cases.map:
            case JsonRpcEndpoint.MessageGate.Decision.Allow     => "A"
            case JsonRpcEndpoint.MessageGate.Decision.Drop      => "D"
            case JsonRpcEndpoint.MessageGate.Decision.Reject(_) => "R"
        assert(tags == Seq("A", "D", "R"))
    }

end JsonRpcEndpointMessageGateTest
