package kyo.internal

import kyo.*

/** Schema round-trip codec test for every HostPayload/HostValue/StructuralOp/SceneDescriptor/PointerData leaf.
  *
  * All leaves are purely synchronous (encode then decode with no async), run on JVM, JS, and Wasm, and carry no
  * browser dependency. They pin INV-TJS-05: every wire type survives a Json.encode then Json.decode round-trip with
  * field-exact equality (CanEqual ==). The grep-half of INV-TJS-05 (zero js.Dynamic in HostPayload.scala) is a
  * verify-time static check, not a Scala test body.
  */
class HostPayloadTest extends kyo.test.Test[Any]:

    "INV-TJS-05: Prop(V3) Schema round-trip" in {
        val original: HostPayload = HostPayload.Prop("node-3", "position", HostValue.V3(1.0, 2.0, 3.0))
        val encoded               = Json.encode[HostPayload](original)
        val decoded               = Json.decode[HostPayload](encoded)
        assert(decoded == Result.Success(original))
    }

    "INV-TJS-05: Prop(Col) Schema round-trip" in {
        // 0x00ff00 == 65280 decimal; the round-trip must preserve the Int bit-pattern exactly.
        val original: HostPayload = HostPayload.Prop("node-0", "color", HostValue.Col(0x00ff00))
        val encoded               = Json.encode[HostPayload](original)
        val decoded               = Json.decode[HostPayload](encoded)
        assert(decoded == Result.Success(original))
        decoded match
            case Result.Success(HostPayload.Prop(_, _, HostValue.Col(rgb))) => assert(rgb == 65280)
            case other                                                      => fail(s"unexpected: $other")
        end match
    }

    "INV-TJS-05: Prop(Num) Schema round-trip" in {
        val original: HostPayload = HostPayload.Prop("node-1", "opacity", HostValue.Num(0.5))
        val encoded               = Json.encode[HostPayload](original)
        val decoded               = Json.decode[HostPayload](encoded)
        assert(decoded == Result.Success(original))
    }

    "INV-TJS-05: Structural(Insert) round-trip with descriptor" in {
        val descriptor            = SceneDescriptor("mesh", Seq("color" -> HostValue.Col(255)), Seq.empty)
        val original: HostPayload = HostPayload.Structural(StructuralOp.Insert("k7", 2, descriptor))
        val encoded               = Json.encode[HostPayload](original)
        val decoded               = Json.decode[HostPayload](encoded)
        assert(decoded == Result.Success(original))
        decoded match
            case Result.Success(HostPayload.Structural(StructuralOp.Insert(k, idx, d))) =>
                assert(k == "k7")
                assert(idx == 2)
                assert(d.kind == "mesh")
                assert(d.children == Seq.empty)
            case other => fail(s"unexpected: $other")
        end match
    }

    "INV-TJS-05: Structural(Remove) round-trip" in {
        val original: HostPayload = HostPayload.Structural(StructuralOp.Remove("k3"))
        val encoded               = Json.encode[HostPayload](original)
        val decoded               = Json.decode[HostPayload](encoded)
        assert(decoded == Result.Success(original))
    }

    "INV-TJS-05: Structural(Move) round-trip" in {
        val original: HostPayload = HostPayload.Structural(StructuralOp.Move("k2", 0))
        val encoded               = Json.encode[HostPayload](original)
        val decoded               = Json.decode[HostPayload](encoded)
        assert(decoded == Result.Success(original))
    }

    "INV-TJS-05: PointerData round-trip" in {
        val original = PointerData(1.5, -2.0, 0.25, 8.0, 0.3, -0.4)
        val encoded  = Json.encode[PointerData](original)
        val decoded  = Json.decode[PointerData](encoded)
        assert(decoded == Result.Success(original))
        decoded match
            case Result.Success(pd) =>
                assert(pd.pointX == 1.5)
                assert(pd.pointY == -2.0)
                assert(pd.pointZ == 0.25)
                assert(pd.distance == 8.0)
                assert(pd.ndcX == 0.3)
                assert(pd.ndcY == -0.4)
            case other => fail(s"unexpected: $other")
        end match
    }

end HostPayloadTest
