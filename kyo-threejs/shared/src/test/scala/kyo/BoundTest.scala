package kyo

class BoundTest extends kyo.test.Test[Any]:

    "Const and Ref are distinct CanEqual variants" in {
        val sig                   = Signal.initConst(Three.Color.red)
        val r: Bound[Three.Color] = Bound.Ref(sig)
        val c: Bound[Three.Color] = Bound.Const(Three.Color.red)
        assert(c != r)
        assert(c == Bound.Const(Three.Color.red))
    }

end BoundTest
