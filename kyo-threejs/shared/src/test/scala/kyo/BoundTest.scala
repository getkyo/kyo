package kyo

class BoundTest extends kyo.test.Test[Any]:

    "Const and Ref are distinct CanEqual variants" in {
        val sig             = Signal.initConst(Color.red)
        val r: Bound[Color] = Bound.Ref(sig)
        val c: Bound[Color] = Bound.Const(Color.red)
        assert(c != r)
        assert(c == Bound.Const(Color.red))
    }

end BoundTest
