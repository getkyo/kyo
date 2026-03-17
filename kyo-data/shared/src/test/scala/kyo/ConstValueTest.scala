package kyo

class ConstValueTest extends Test:

    "summon string literal" in {
        val v = summon[ConstValue["hello"]]
        assert(v == "hello")
    }

    "summon int literal" in {
        val v = summon[ConstValue[42]]
        assert(v == 42)
    }

    "summon boolean literal" in {
        val v = summon[ConstValue[true]]
        assert(v == true)
    }

    "subtype of literal type" in {
        val v: "test" = summon[ConstValue["test"]]
        assert(v == "test")
    }

    "used as function parameter" in {
        def getName[N <: String](using n: ConstValue[N]): String = n
        assert(getName["foo"] == "foo")
    }

end ConstValueTest
