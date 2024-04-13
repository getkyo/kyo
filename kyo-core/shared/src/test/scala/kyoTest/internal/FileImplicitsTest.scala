package kyoTest.internal

import kyo.internal.FileNameWithLine
import kyoTest.KyoTest

class FileImplicitsTest extends KyoTest:
    def makeErrorMessage(msg: => String)(using f: FileNameWithLine): String =
        s"$f - $msg"

    "implicitly" in {
        val f = implicitly[FileNameWithLine]
        assert(f == "FileImplicitsTest.scala:11")
    }

    "using" in {
        val e = makeErrorMessage("Hello World")
        assert(e == "FileImplicitsTest.scala:16 - Hello World")
    }

    "given" in {
        val file               = FileNameWithLine.derive
        given FileNameWithLine = file

        val e = makeErrorMessage("")
        assert(e == "FileImplicitsTest.scala:21 - ")
    }
end FileImplicitsTest
