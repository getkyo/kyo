package kyo

class PointerTest extends kyo.test.Test[Any]:

    "Pointer carries the hit payload and button flags" in {
        val point   = Three.Vec3(1, 2, 3)
        val buttons = Three.Pointer.Buttons(left = true, right = false, middle = false)
        val p       = Three.Pointer(point, 6.29, (0.0, 0.0), buttons)
        assert(p.point == Three.Vec3(1, 2, 3))
        assert(p.distance == 6.29)
        assert(p.buttons.left == true)
        assert(p.buttons.right == false)
    }

end PointerTest
