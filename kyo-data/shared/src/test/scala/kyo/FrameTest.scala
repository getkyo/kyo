package kyo

class FrameTest extends Test:

    def test(v: Int)(using frame: Frame) = frame

    def test1 = test(1 + 2)

    def test2 = test {
        val x = 1
        x / x
    }

    val internal = Frame.internal

    "show" in {
        assert(test1.show == "Frame(FrameTest.scala:7:28, kyo.FrameTest, test1, def test1 = test(1 + 2))")
        assert(test2.show == "Frame(FrameTest.scala:12:6, kyo.FrameTest, test2, })")
    }

    "render" - {
        "no details" in {
            import kyo.Ansi.*
            assert(test1.render.stripAnsi ==
                """|  │ ──────────────────────────────
                   |  │ // FrameTest.scala:7:28 kyo.FrameTest test1
                   |  │ ──────────────────────────────
                   |8 │ def test1 = test(1 + 2)📍
                   |  │ ──────────────────────────────""".stripMargin)
            assert(test2.render.stripAnsi ==
                """|   │ ──────────────────────────────
                   |   │ // FrameTest.scala:12:6 kyo.FrameTest test2
                   |   │ ──────────────────────────────
                   |13 │     x / x
                   |14 │ }📍
                   |   │ ──────────────────────────────""".stripMargin)
        }

        "with details" in {
            import kyo.Ansi.*
            assert(test1.render(3).stripAnsi ==
                """|  │ ──────────────────────────────
                   |  │ // FrameTest.scala:7:28 kyo.FrameTest test1
                   |  │ ──────────────────────────────
                   |8 │ def test1 = test(1 + 2)📍
                   |  │ ──────────────────────────────
                   |  │ 3
                   |  │ ──────────────────────────────""".stripMargin)

            assert(test1.render(1, "hello", true).stripAnsi ==
                """|  │ ──────────────────────────────
                   |  │ // FrameTest.scala:7:28 kyo.FrameTest test1
                   |  │ ──────────────────────────────
                   |8 │ def test1 = test(1 + 2)📍
                   |  │ ──────────────────────────────
                   |  │ 1
                   |  │ 
                   |  │ hello
                   |  │ 
                   |  │ true
                   |  │ ──────────────────────────────""".stripMargin)

            case class Person(name: String, age: Int)
            assert(test1.render(Person("Alice", 30)).stripAnsi ==
                """|  │ ──────────────────────────────
                   |  │ // FrameTest.scala:7:28 kyo.FrameTest test1
                   |  │ ──────────────────────────────
                   |8 │ def test1 = test(1 + 2)📍
                   |  │ ──────────────────────────────
                   |  │ Person(name = "Alice", age = 30)
                   |  │ ──────────────────────────────""".stripMargin)
        }
    }

    "parse" in {
        assert(test1.className == "kyo.FrameTest")
        assert(test1.methodName == "test1")
        assert(test1.position.fileName == "FrameTest.scala")
        assert(test1.position.lineNumber == 7)
        assert(test1.position.columnNumber == 28)
        assert(test1.snippet == "def test1 = test(1 + 2)📍")
        assert(test1.snippetShort == "def test1 = test(1 + 2)")
    }

    "internal" in {
        assert(internal.className == "kyo.FrameTest")
        assert(internal.methodName == "?")
        assert(internal.position.fileName == "FrameTest.scala")
        assert(internal.position.lineNumber == 14)
        assert(internal.position.columnNumber == 20)
        assert(internal.snippet == "<internal>")
        assert(internal.snippetShort == "<internal>")
    }

end FrameTest
