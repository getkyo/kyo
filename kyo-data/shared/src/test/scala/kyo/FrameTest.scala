package kyo

class FrameTest extends kyo.test.Test[Any]:

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
                   |6 │ def test1 = test(1 + 2)📍
                   |  │ ──────────────────────────────""".stripMargin)
            assert(test2.render.stripAnsi ==
                """|   │ ──────────────────────────────
                   |   │ // FrameTest.scala:12:6 kyo.FrameTest test2
                   |   │ ──────────────────────────────
                   |11 │     x / x
                   |12 │ }📍
                   |   │ ──────────────────────────────""".stripMargin)
        }

        "with details" in {
            import kyo.Ansi.*
            assert(test1.render(3).stripAnsi ==
                """|  │ ──────────────────────────────
                   |  │ // FrameTest.scala:7:28 kyo.FrameTest test1
                   |  │ ──────────────────────────────
                   |6 │ def test1 = test(1 + 2)📍
                   |  │ ──────────────────────────────
                   |  │ 3
                   |  │ ──────────────────────────────""".stripMargin)

            assert(test1.render(1, "hello", true).stripAnsi ==
                """|  │ ──────────────────────────────
                   |  │ // FrameTest.scala:7:28 kyo.FrameTest test1
                   |  │ ──────────────────────────────
                   |6 │ def test1 = test(1 + 2)📍
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
                   |6 │ def test1 = test(1 + 2)📍
                   |  │ ──────────────────────────────
                   |  │ Person(name = "Alice", age = 30)
                   |  │ ──────────────────────────────""".stripMargin)
        }
    }

    "parse" in {
        assert(test1.className == "kyo.FrameTest")
        assert(test1.callerName == "test1")
        assert(test1.position.fileName == "FrameTest.scala")
        assert(test1.position.lineNumber == 7)
        assert(test1.position.columnNumber == 28)
        assert(test1.snippet == "def test1 = test(1 + 2)📍")
        assert(test1.snippetShort == "def test1 = test(1 + 2)")
    }

    "internal" in {
        // Frame.internal is a single shared placeholder, not a per-call-site macro, so every
        // accessor returns the uniform "<internal>" sentinel rather than this call site's class
        // or position. Two references to Frame.internal are the same instance.
        assert(internal.className == "<internal>")
        assert(internal.callerName == "<internal>")
        assert(internal.calleeName == "<internal>")
        assert(internal.position.fileName == "<internal>")
        assert(internal.position.lineNumber == 0)
        assert(internal.position.columnNumber == 0)
        assert(internal.position.show == "<internal>:0:0")
        assert(internal.snippet == "<internal>")
        assert(internal.snippetShort == "<internal>")
        assert(internal eq Frame.internal)
    }

    "calleeName" - {

        // ── End-to-end via the macro ────────────────────────────────────────

        def captureFrame(using f: Frame): Frame                           = f
        def takeArg(arg: String)(using f: Frame): Frame                   = f
        def takeTwo(a: Int, b: Int)(using f: Frame): Frame                = f
        def takeNamed(arg: String, count: Int = 1)(using f: Frame): Frame = f
        def takeTypeArg[A](arg: A)(using f: Frame): Frame                 = f
        def takeCurried(a: Int)(b: Int)(using f: Frame): Frame            = f
        def under_score(using f: Frame): Frame                            = f
        def with$Dollar(using f: Frame): Frame                            = f

        "bare call (no explicit args, only implicit Frame)" in {
            assert(captureFrame.calleeName == "captureFrame")
        }

        "call with one explicit arg" in {
            assert(takeArg("hello").calleeName == "takeArg")
        }

        "call with multiple explicit args" in {
            assert(takeTwo(1, 2).calleeName == "takeTwo")
        }

        "call with expression argument" in {
            assert(takeArg("hello " + "world").calleeName == "takeArg")
        }

        "call with a named argument" in {
            assert(takeNamed("x", count = 3).calleeName == "takeNamed")
        }

        "call with an explicit type argument" in {
            assert(takeTypeArg[Int](7).calleeName == "takeTypeArg")
        }

        "call with curried argument lists" in {
            assert(takeCurried(1)(2).calleeName == "takeCurried")
        }

        "underscore in the identifier" in {
            assert(under_score.calleeName == "under_score")
        }

        "dollar sign in the identifier" in {
            assert(with$Dollar.calleeName == "with$Dollar")
        }

        "called from inside a lambda" in {
            val callFromLambda: () => Frame = () => captureFrame
            assert(callFromLambda().calleeName == "captureFrame")
        }

        "called from inside a for-comprehension" in {
            val frames =
                for i <- List(1)
                yield captureFrame
            assert(frames.head.calleeName == "captureFrame")
        }

        // ── Pure helper exercises (hand-built source strings) ───────────────

        "extractCalleeName on a bare identifier" in {
            assert(Frame.extractCalleeName("val a = foo", 11) == "foo")
        }

        "extractCalleeName on a one-arg call" in {
            assert(Frame.extractCalleeName("val a = foo(1)", 14) == "foo")
        }

        "extractCalleeName on a chained method call" in {
            assert(Frame.extractCalleeName("val a = obj.method(x)", 21) == "method")
        }

        "extractCalleeName on curried application" in {
            assert(Frame.extractCalleeName("val a = foo(1)(2)", 17) == "foo")
        }

        "extractCalleeName peels type args" in {
            assert(Frame.extractCalleeName("val a = foo[Int](1)", 19) == "foo")
        }

        "extractCalleeName peels multiple type args + explicit args mixed" in {
            assert(Frame.extractCalleeName("val a = foo[A][B](x)(y)", 23) == "foo")
        }

        "extractCalleeName handles nested parens" in {
            assert(Frame.extractCalleeName("val a = foo(bar(baz(1)))", 24) == "foo")
        }

        "extractCalleeName handles whitespace between identifier and args" in {
            assert(Frame.extractCalleeName("val a = foo  (1)", 16) == "foo")
        }

        "extractCalleeName handles trailing whitespace" in {
            assert(Frame.extractCalleeName("foo  ", 5) == "foo")
        }

        "extractCalleeName preserves underscores in identifier" in {
            assert(Frame.extractCalleeName("snake_case_name", 15) == "snake_case_name")
        }

        "extractCalleeName preserves dollar signs in identifier" in {
            assert(Frame.extractCalleeName("withDollar$Suffix", 17) == "withDollar$Suffix")
        }

        "extractCalleeName returns the operator string for operator-named methods" in {
            assert(Frame.extractCalleeName("val a = 1 +", 11) == "+")
            assert(Frame.extractCalleeName("val a = 1 ==", 12) == "==")
            assert(Frame.extractCalleeName("val a = 1 <=", 12) == "<=")
            assert(Frame.extractCalleeName("val a = 1 ::", 12) == "::")
            assert(Frame.extractCalleeName("val a = xs ++", 13) == "++")
        }

        "extractCalleeName handles backticked identifiers" in {
            assert(Frame.extractCalleeName("val a = `name`", 14) == "name")
            assert(Frame.extractCalleeName("val a = `my method`", 19) == "my method")
            assert(Frame.extractCalleeName("val a = `a.b.c`", 15) == "a.b.c")
        }

        // The macro passes the compiler's raw content, so CRLF sources reach the
        // walker with \r chars in place; each \r must be skipped as whitespace.
        "extractCalleeName skips CRLF terminators inside a multi-line arg list" in {
            val src = "val a = foo(\r\n    1,\r\n    2\r\n)"
            assert(Frame.extractCalleeName(src, src.length) == "foo")
        }

        "extractCalleeName skips a CRLF break before a chained call" in {
            val src = "val a = obj\r\n    .method(x)"
            assert(Frame.extractCalleeName(src, src.length) == "method")
        }

        "extractCalleeName returns '?' for an empty prefix" in {
            assert(Frame.extractCalleeName("", 0) == "?")
        }

        "extractCalleeName returns '?' when the trailing token starts with a digit" in {
            assert(Frame.extractCalleeName("val a = 123abc", 14) == "?")
        }

        "extractCalleeName returns '?' on unbalanced parens (still safe, no exception)" in {
            // A bare `)` with no matching `(`: peel walks the whole prefix, leaving no identifier.
            assert(Frame.extractCalleeName(")", 1) == "?")
        }

        "extractCalleeName returns the identifier at end-of-string" in {
            assert(Frame.extractCalleeName("identifier", 10) == "identifier")
        }

        "calleeName via macro reports the called method, not the enclosing one" in {
            assert(test1.callerName == "test1")
            assert(test1.calleeName == "test")
        }

        // Backward compatibility with legacy v'0' frames.

        "calleeName returns \"\" for a legacy version-'0' Frame" in {
            // Legacy format: '0' + position + |cls|method|snippet (no callee segment).
            // Frame is an opaque alias for String; cast is the test-only construction path.
            val legacy = "0FrameTest.scala:7:28|kyo.FrameTest|test1|def test1 = test(1 + 2)📍".asInstanceOf[Frame]
            assert(legacy.calleeName == "")
        }

        "snippet on a legacy version-'0' Frame skips three separators (not four)" in {
            val legacy = "0FrameTest.scala:7:28|kyo.FrameTest|test1|def test1 = test(1 + 2)📍".asInstanceOf[Frame]
            assert(legacy.snippet == "def test1 = test(1 + 2)📍")
        }

        "snippet on a current version-'1' Frame skips four separators" in {
            val current = "1FrameTest.scala:7:28|kyo.FrameTest|test1|test|def test1 = test(1 + 2)📍".asInstanceOf[Frame]
            assert(current.snippet == "def test1 = test(1 + 2)📍")
            assert(current.calleeName == "test")
        }

        "legacy v'0' parses className/callerName/position the same as v'1'" in {
            val legacy = "0FrameTest.scala:7:28|kyo.FrameTest|test1|def test1 = test(1 + 2)📍".asInstanceOf[Frame]
            assert(legacy.className == "kyo.FrameTest")
            assert(legacy.callerName == "test1")
            assert(legacy.position.fileName == "FrameTest.scala")
            assert(legacy.position.lineNumber == 7)
            assert(legacy.position.columnNumber == 28)
        }
    }

end FrameTest
