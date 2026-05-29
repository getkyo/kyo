package kyo

import kyo.Maybe.Absent

class MessageGateTest extends JsonRpcTestBase:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    "Decision values are CanEqual-distinguishable across Allow / Reject / Drop" in run {
        val a: MessageGate.Decision = MessageGate.Decision.Allow
        val d: MessageGate.Decision = MessageGate.Decision.Drop
        val r: MessageGate.Decision = MessageGate.Decision.Reject(JsonRpcError.InvalidRequest)
        assert(a != d)
        assert(a != r)
        assert(d != r)
    }

    "Reject decision carries the supplied JsonRpcError" in run {
        val err                       = JsonRpcError.invalidRequest("nope")
        val dec: MessageGate.Decision = MessageGate.Decision.Reject(err)
        dec match
            case MessageGate.Decision.Reject(captured) => assert(captured == err)
            case other                                 => fail(s"expected Reject, got $other")
    }

    "a test-double gate returning Drop reports Drop for any envelope" in run {
        val gate = new MessageGate:
            def beforeDispatch(env: JsonRpcEnvelope)(using Frame): MessageGate.Decision < Sync =
                Sync.defer(MessageGate.Decision.Drop)
        val env = JsonRpcEnvelope.Notification("ping", Absent, Absent)
        gate.beforeDispatch(env).map: dec =>
            assert(dec == MessageGate.Decision.Drop)
    }

    "a test-double gate returning Allow reports Allow for any envelope" in run {
        val gate = new MessageGate:
            def beforeDispatch(env: JsonRpcEnvelope)(using Frame): MessageGate.Decision < Sync =
                Sync.defer(MessageGate.Decision.Allow)
        val env = JsonRpcEnvelope.Request(JsonRpcId.Num(1L), "ping", Absent, Absent)
        gate.beforeDispatch(env).map: dec =>
            assert(dec == MessageGate.Decision.Allow)
    }

    "Reject and Drop are structurally distinct from Allow under pattern matching" in run {
        val cases: Seq[MessageGate.Decision] = Seq(
            MessageGate.Decision.Allow,
            MessageGate.Decision.Drop,
            MessageGate.Decision.Reject(JsonRpcError.InvalidParams)
        )
        val tags = cases.map:
            case MessageGate.Decision.Allow     => "A"
            case MessageGate.Decision.Drop      => "D"
            case MessageGate.Decision.Reject(_) => "R"
        assert(tags == Seq("A", "D", "R"))
    }

end MessageGateTest
