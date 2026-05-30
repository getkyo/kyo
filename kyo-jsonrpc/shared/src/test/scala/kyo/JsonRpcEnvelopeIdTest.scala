package kyo

class JsonRpcEnvelopeIdTest extends JsonRpcTest:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    "Num case round-trips through Structure" in run {
        val id      = JsonRpcId.Num(42L)
        val encoded = Structure.encode[JsonRpcId](id)
        val decoded = Structure.decode[JsonRpcId](encoded).getOrElse(fail("decode failed"))
        assert(decoded == id)
    }

    "Str case round-trips through Structure" in run {
        val id      = JsonRpcId.Str("abc")
        val encoded = Structure.encode[JsonRpcId](id)
        val decoded = Structure.decode[JsonRpcId](encoded).getOrElse(fail("decode failed"))
        assert(decoded == id)
    }

    "encoding Num produces a numeric Structure value" in run {
        val encoded = Structure.encode[JsonRpcId](JsonRpcId.Num(7L))
        encoded match
            case Structure.Value.Integer(n) => assert(n == 7L)
            case other                      => fail(s"expected Integer, got $other")
    }

    "encoding Str produces a string Structure value" in run {
        val encoded = Structure.encode[JsonRpcId](JsonRpcId.Str("x"))
        encoded match
            case Structure.Value.Str(s) => assert(s == "x")
            case other                  => fail(s"expected Str, got $other")
    }

    "decoding Structure.Value.Null fails" in run {
        val result = Structure.decode[JsonRpcId](Structure.Value.Null)
        assert(result.isFailure)
    }

end JsonRpcEnvelopeIdTest
