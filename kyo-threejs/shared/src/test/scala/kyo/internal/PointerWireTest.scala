package kyo.internal

import kyo.*

class PointerWireTest extends kyo.test.Test[Any]:

    "encode/decode round-trips a Pointer and its kind exactly" in {
        val pointer = Three.Pointer(
            point = Three.Vec3(1.5, -2.25, 3.0),
            distance = 4.5,
            ndc = (0.1, -0.2),
            buttons = Three.Pointer.Buttons(left = true, right = false, middle = true)
        )
        // Every kind survives the wire: the kind is what tells the receiving node WHICH of its three
        // handlers the event addresses, so a kind that decoded to the wrong value would silently run the
        // wrong closure.
        Chunk(PointerKind.Click, PointerKind.Over, PointerKind.Out).foreach { kind =>
            val decoded = PointerWire.decode(PointerWire.encode(kind, pointer))
            assert(decoded == Present((kind, pointer)))
        }
    }

    "decode of a malformed string yields Absent" in {
        assert(PointerWire.decode("not valid json") == Absent)
    }

end PointerWireTest
