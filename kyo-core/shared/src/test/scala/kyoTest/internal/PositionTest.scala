package kyoTest.internal

import kyo.internal.Position
import kyoTest.KyoTest

class PositionTest extends KyoTest:
    def makeErrorMessage(msg: => String)(using f: Position): String =
        s"$f - $msg"

    "implicitly" in {
        val f = implicitly[Position]
        assert(f.toString() == "PositionTest.scala:11 - val f")
    }

    "using" in {
        val e = makeErrorMessage("Hello World")
        assert(e == "PositionTest.scala:16 - val e - Hello World")
    }

    "given" in {
        given file: Position = Position.derive

        val e = makeErrorMessage("")
        assert(e == "PositionTest.scala:21 - val file - ")
    }

    "scope" in {
        def t1 = Position.derive
        assert(t1.toString() == "PositionTest.scala:28 - method t1")

        val t2 = Position.derive
        assert(t2.toString() == "PositionTest.scala:31 - val t2")

        class t4(using val p: Position)
        assert(t4().p.toString() == "PositionTest.scala:35 - class PositionTest")
    }
end PositionTest
