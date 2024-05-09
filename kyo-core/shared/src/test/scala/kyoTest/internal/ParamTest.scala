package kyoTest.internal

import kyo.internal.Param
import kyoTest.KyoTest

class ParamTest extends KyoTest:

    "one param" in {
        def param[T](v: Param[T]) = v
        assert(param(1 + 1) == Param("1 + 1", 2))
        assert(param("s") == Param("\"s\"", "s"))
    }

    "vararg params" in {
        def params[T](v: Param[T]*) = v.toList
        assert(params("s") == Param("\"s\"", "s") :: Nil)
        assert(params(1 + 1, 2) == Param("1 + 1", 2) :: Param("2", 2) :: Nil)
    }

    "show" in {
        def params[T](v: Param[T]*) = Param.show(v*)
        assert(params(1 + 1, "s") == """Params("1 + 1" -> 2, "\"s\"" -> "s")""")
        assert(params(-4.abs, 2 * 2) == """Params("-4.abs" -> 4, "2 * 2" -> 4)""")
    }
end ParamTest
