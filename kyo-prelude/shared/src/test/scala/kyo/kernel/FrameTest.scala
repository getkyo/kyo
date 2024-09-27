package kyo.kernel

import kyo.Test

class FrameTest extends Test:

    def test(v: Int)(using frame: Frame) = frame

    def test1 = test(1 + 2)

    def test2 = test {
        val x = 1
        x / x
    }

    "parse.toString" in {
        assert(test1.parse.toString == "Frame(kyo.kernel.FrameTest, test1, FrameTest.scala:9:28, def test1 = test(1 + 2))")
        assert(test2.parse.toString == "Frame(kyo.kernel.FrameTest, test2, FrameTest.scala:14:6, })")
    }

    "show" in {
        import kyo.Ansi.*
        assert(test1.show.stripAnsi ==
            """|  │ // FrameTest.scala:9:28 kyo.kernel.FrameTest test1
               |9 │ def test1 = test(1 + 2)📍""".stripMargin)
        assert(test2.show.stripAnsi ==
            """|   │ // FrameTest.scala:14:6 kyo.kernel.FrameTest test2
               |14 │     x / x
               |15 │ }📍""".stripMargin)
    }

    "parse" in {
        val parsed = test1.parse
        assert(parsed.declaringClass == "kyo.kernel.FrameTest")
        assert(parsed.methodName == "test1")
        assert(parsed.position.fileName == "FrameTest.scala")
        assert(parsed.position.lineNumber == 9)
        assert(parsed.position.columnNumber == 28)
        assert(parsed.snippetShort == "def test1 = test(1 + 2)")
        assert(parsed.snippetLong == "def test1 = test(1 + 2)📍")
    }

    "internal" in {
        val internal = Frame.internal
        val parsed   = internal.parse
        assert(parsed.declaringClass == "kyo.kernel.FrameTest")
        assert(parsed.methodName == "?")
        assert(parsed.position.fileName == "FrameTest.scala")
        assert(parsed.position.lineNumber == 44)
        assert(parsed.position.columnNumber == 24)
        assert(parsed.snippetShort == "<internal>")
        assert(parsed.snippetLong == "<internal>")
        assert(parsed.toString == "Frame(kyo.kernel.FrameTest, ?, FrameTest.scala:44:24, <internal>)")
    }

end FrameTest
