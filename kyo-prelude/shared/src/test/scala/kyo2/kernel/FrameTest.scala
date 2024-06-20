package kyo2.kernel

import kyo2.Test

class FrameTest extends Test:

    def test(v: Int)(using frame: Frame) = frame

    def test1 = test(1 + 2)

    def test2 = test {
        val x = 1
        x / x
    }

    "show" in {
        assert(test1.show == "Frame(kyo2.kernel.FrameTest, test1, FrameTest.scala:9:28, test(1 + 2))")
        assert(test2.show == "Frame(kyo2.kernel.FrameTest, test2, FrameTest.scala:14:6, })")
    }

    "parse" in {
        val parsed = test1.parse
        assert(parsed.declaringClass == "kyo2.kernel.FrameTest")
        assert(parsed.methodName == "test1")
        assert(parsed.position.fileName == "FrameTest.scala")
        assert(parsed.position.lineNumber == 9)
        assert(parsed.position.columnNumber == 28)
        assert(parsed.snippetShort == "              test(1 + 2)")
        assert(parsed.snippetLong == "def test1 = test(1 + 2)📍")
    }

end FrameTest
