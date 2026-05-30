package kyo

class JsonRpcEnvelopeIdTest extends JsonRpcTest:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    "Num case round-trips through Structure" in run {
        val id      = JsonRpcEnvelope.Id.Num(42L)
        val encoded = Structure.encode[JsonRpcEnvelope.Id](id)
        val decoded = Structure.decode[JsonRpcEnvelope.Id](encoded).getOrElse(fail("decode failed"))
        assert(decoded == id)
    }

    "Str case round-trips through Structure" in run {
        val id      = JsonRpcEnvelope.Id.Str("abc")
        val encoded = Structure.encode[JsonRpcEnvelope.Id](id)
        val decoded = Structure.decode[JsonRpcEnvelope.Id](encoded).getOrElse(fail("decode failed"))
        assert(decoded == id)
    }

    "encoding Num produces a numeric Structure value" in run {
        val encoded = Structure.encode[JsonRpcEnvelope.Id](JsonRpcEnvelope.Id.Num(7L))
        encoded match
            case Structure.Value.Integer(n) => assert(n == 7L)
            case other                      => fail(s"expected Integer, got $other")
    }

    "encoding Str produces a string Structure value" in run {
        val encoded = Structure.encode[JsonRpcEnvelope.Id](JsonRpcEnvelope.Id.Str("x"))
        encoded match
            case Structure.Value.Str(s) => assert(s == "x")
            case other                  => fail(s"expected Str, got $other")
    }

    "decoding Structure.Value.Null fails" in run {
        val result = Structure.decode[JsonRpcEnvelope.Id](Structure.Value.Null)
        assert(result.isFailure)
    }

end JsonRpcEnvelopeIdTest
