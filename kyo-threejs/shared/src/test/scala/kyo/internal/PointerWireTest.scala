package kyo.internal

import kyo.*

class PointerWireTest extends kyo.test.Test[Any]:

    "encode/decode round-trips a Pointer exactly" in {
        val pointer = Three.Pointer(
            point = Three.Vec3(1.5, -2.25, 3.0),
            distance = 4.5,
            ndc = (0.1, -0.2),
            buttons = Three.Pointer.Buttons(left = true, right = false, middle = true)
        )
        val encoded = PointerWire.encode(pointer)
        val decoded = PointerWire.decode(encoded)
        assert(decoded == Present(pointer))
    }

    "decode of a malformed string yields Absent" in {
        assert(PointerWire.decode("not valid json") == Absent)
    }

end PointerWireTest
