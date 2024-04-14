package kyoTest.internal

import kyo.internal.Position
import kyoTest.KyoTest

class FileImplicitsTest extends KyoTest:
    def makeErrorMessage(msg: => String)(using f: Position): String =
        s"$f - $msg"

    "implicitly" in {
        val f = implicitly[Position]
        assert(f == "FileImplicitsTest.scala:11")
    }

    "using" in {
        val e = makeErrorMessage("Hello World")
        assert(e == "FileImplicitsTest.scala:16 - Hello World")
    }

    "given" in {
        given file: Position = Position.derive

        val e = makeErrorMessage("")
        assert(e == "FileImplicitsTest.scala:21 - ")
    }
end FileImplicitsTest
