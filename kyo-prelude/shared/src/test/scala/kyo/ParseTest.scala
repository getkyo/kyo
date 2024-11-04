package kyo

import kyo.debug.Debug

class ParseTest extends Test:

    "combinators" - {
        "anyOf" - {
            "matches any parser" in run {
                val parser = Parse.anyOf(
                    Parse.literal("hello"),
                    Parse.literal("world"),
                    Parse.literal("test")
                )
                Parse.run("test")(parser).map(_ => succeed)
            }

            "fails when no parser matches" in run {
                val parser = Parse.anyOf(
                    Parse.literal("hello"),
                    Parse.literal("world")
                )
                Abort.run(Parse.run("test")(parser)).map { result =>
                    assert(result.isFail)
                }
            }
        }

        "firstOf" - {
            "takes first match" in run {
                val parser = Parse.firstOf(
                    Parse.literal("test").as(1),
                    Parse.literal("test").as(2)
                )
                Parse.run("test")(parser).map { result =>
                    assert(result == 1)
                }
            }

            "tries alternatives" in run {
                val parser = Parse.firstOf(
                    Parse.literal("hello").as(1),
                    Parse.literal("world").as(2),
                    Parse.literal("test").as(3)
                )
                Parse.run("test")(parser).map { result =>
                    assert(result == 3)
                }
            }
        }

        "skipUntil" - {
            "skips until pattern" in run {
                val parser = Parse.skipUntil(Parse.literal("end").as("found"))
                Parse.run("abc123end")(parser).map { result =>
                    assert(result == "found")
                }
            }

            "empty input" in run {
                Abort.run(Parse.run("")(Parse.skipUntil(Parse.literal("end")))).map { result =>
                    assert(result.isFail)
                }
            }

            "large skip" in run {
                val input = "a" * 10000 + "end"
                Parse.run(input)(Parse.skipUntil(Parse.literal("end").as("found"))).map { result =>
                    assert(result == "found")
                }
            }

            "pattern never matches" in run {
                Abort.run(Parse.run("abcdef")(Parse.skipUntil(Parse.literal("xyz")))).map { result =>
                    assert(result.isFail)
                }
            }
        }

        "attempt" - {
            "backtracks on failure" in run {
                val parser =
                    Parse.attempt(Parse.literal("hello")).map { r =>
                        assert(r.isEmpty)
                        Parse.literal("world")
                    }
                Parse.run("world")(parser).map { result =>
                    assert(result == ())
                }
            }

            "preserves success" in run {
                val parser = Parse.attempt(Parse.literal("hello"))
                Parse.run("hello")(parser).map { r =>
                    assert(r.isDefined)
                }
            }
        }

        "peek" - {
            "doesn't consume input on success" in run {
                val parser =
                    for
                        _      <- Parse.peek(Parse.literal("hello"))
                        result <- Parse.literal("hello")
                    yield result
                Parse.run("hello")(parser).map(_ => succeed)
            }

            "doesn't consume input on failure" in run {
                val parser =
                    for
                        r      <- Parse.peek(Parse.literal("hello"))
                        result <- Parse.literal("world")
                    yield (r, result)
                Parse.run("world")(parser).map { case (r, _) =>
                    assert(r.isEmpty)
                }
            }
        }

        "repeat" - {
            "repeats until failure" in run {
                val parser = Parse.repeat(Parse.literal("a")).as(Parse.literal("b"))
                Parse.run("aaab")(parser).map { result =>
                    assert(result == ())
                }
            }

            "fixed repetitions" in run {
                val parser = Parse.repeat(3)(Parse.literal("a"))
                Parse.run("aaa")(parser).map { result =>
                    assert(result.length == 3)
                }
            }

            "fails if not enough repetitions" in run {
                val parser = Parse.repeat(3)(Parse.literal("a"))
                Abort.run(Parse.run("aa")(parser)).map { result =>
                    assert(result.isFail)
                }
            }

            "empty input" in run {
                Parse.run("")(Parse.repeat(Parse.literal("a"))).map { result =>
                    assert(result.isEmpty)
                }
            }

            "exceeds fixed count" in run {
                Parse.run("aaaa")(Parse.repeat(3)(Parse.literal("a")).map(r => Parse.literal("a").as(r))).map { result =>
                    assert(result.length == 3)
                }
            }

            "large repetition" in run {
                val parser = Parse.repeat(1000)(Parse.literal("a"))
                Parse.run("a" * 1000)(parser).map { result =>
                    assert(result.length == 1000)
                }
            }
        }

        "cut" - {
            "succeeds with match" in run {
                val parser = Parse.cut(Parse.literal("test"))
                Parse.run("test")(parser).map(_ => succeed)
            }

            "fails without backtracking" in run {
                val parser =
                    Parse.firstOf(
                        Parse.cut(Parse.literal("test")),
                        Parse.literal("world")
                    )
                Abort.run(Parse.run("world")(parser)).map { result =>
                    assert(result.isFail)
                }
            }
        }
    }

    "standard parsers" - {

        "shortest/longest" - {
            "shortest selects minimum length match" in run {
                val parser = Parse.shortest(
                    Parse.literal("test").as(1),
                    Parse.literal("testing").as(2)
                ).map { r =>
                    Parse.literal("ing").as(r)
                }
                Parse.run("testing")(parser).map { result =>
                    assert(result == 1)
                }
            }

            "longest selects maximum length match" in run {
                val parser = Parse.longest(
                    Parse.literal("test").as(1),
                    Parse.literal("testing").as(2)
                )
                Parse.run("testing")(parser).map { result =>
                    assert(result == 2)
                }
            }
        }

        "whitespaces" - {
            "empty string" in run {
                Parse.run("")(Parse.whitespaces).map { result =>
                    assert(result == ())
                }
            }

            "mixed whitespace types" in run {
                Parse.run(" \t\n\r ")(Parse.whitespaces).map { result =>
                    assert(result == ())
                }
            }

            "large whitespace input" in run {
                Parse.run(" " * 10000)(Parse.whitespaces).map { result =>
                    assert(result == ())
                }
            }
        }

        "int" - {
            "positive" in run {
                Parse.run("123")(Parse.int).map { result =>
                    assert(result == 123)
                }
            }

            "negative" in run {
                Parse.run("-123")(Parse.int).map { result =>
                    assert(result == -123)
                }
            }

            "invalid" in run {
                Abort.run(Parse.run("abc")(Parse.int)).map { result =>
                    assert(result.isFail)
                }
            }

            "max int" in run {
                Parse.run(s"${Int.MaxValue}")(Parse.int).map { result =>
                    assert(result == Int.MaxValue)
                }
            }

            "min int" in run {
                Parse.run(s"${Int.MinValue}")(Parse.int).map { result =>
                    assert(result == Int.MinValue)
                }
            }
        }

        "boolean" - {
            "true" in run {
                Parse.run("true")(Parse.boolean).map { result =>
                    assert(result == true)
                }
            }

            "false" in run {
                Parse.run("false")(Parse.boolean).map { result =>
                    assert(result == false)
                }
            }

            "invalid" in run {
                Abort.run(Parse.run("xyz")(Parse.boolean)).map { result =>
                    assert(result.isFail)
                }
            }
        }

        "char" in run {
            Parse.run("a")(Parse.char('a')).map(_ => succeed)
        }

        "literal" - {
            "empty literal" in run {
                Parse.run("")(Parse.literal("")).map(_ => succeed)
            }

            "partial match" in run {
                Abort.run(Parse.run("hell")(Parse.literal("hello"))).map { result =>
                    assert(result.isFail)
                }
            }

            "case sensitive" in run {
                Abort.run(Parse.run("HELLO")(Parse.literal("hello"))).map { result =>
                    assert(result.isFail)
                }
            }
        }

        "decimal" - {
            "positive" in run {
                Parse.run("123.45")(Parse.decimal).map { result =>
                    assert(result == 123.45)
                }
            }

            "negative" in run {
                Parse.run("-123.45")(Parse.decimal).map { result =>
                    assert(result == -123.45)
                }
            }

            "integer" in run {
                Parse.run("123")(Parse.decimal).map { result =>
                    assert(result == 123.0)
                }
            }

            "invalid" in run {
                Abort.run(Parse.run("abc")(Parse.decimal)).map { result =>
                    assert(result.isFail)
                }
            }

            "leading dot" in run {
                Parse.run(".123")(Parse.decimal).map { result =>
                    assert(result == 0.123)
                }
            }

            "trailing dot" in run {
                Parse.run("123.")(Parse.decimal).map { result =>
                    assert(result == 123.0)
                }
            }

            "multiple dots" in run {
                Abort.run(Parse.run("1.2.3")(Parse.decimal)).map { result =>
                    assert(result.isFail)
                }
            }

            "multiple leading dots" in run {
                Abort.run(Parse.run("..123")(Parse.decimal)).map { result =>
                    assert(result.isFail)
                }
            }

            "malformed negative" in run {
                Abort.run(Parse.run(".-123")(Parse.decimal)).map { result =>
                    assert(result.isFail)
                }
            }

            "very large precision" in run {
                Parse.run("123.45678901234567890")(Parse.decimal).map { result =>
                    assert(result == 123.45678901234567890)
                }
            }
        }

        "identifier" - {
            "valid identifiers" in run {
                Parse.run("_hello123")(Parse.identifier).map { result =>
                    assert(result.is("_hello123"))
                }
            }

            "starting with number" in run {
                Abort.run(Parse.run("123abc")(Parse.identifier)).map { result =>
                    assert(result.isFail)
                }
            }

            "special characters" in run {
                Abort.run(Parse.run("hello@world")(Parse.identifier)).map { result =>
                    assert(result.isFail)
                }
            }

            "very long identifier" in run {
                val longId = "a" * 1000
                Parse.run(longId)(Parse.identifier).map { result =>
                    assert(result.is(longId))
                }
            }

            "unicode letters" in run {
                Parse.run("αβγ123")(Parse.identifier).map { result =>
                    assert(result.is("αβγ123"))
                }
            }

            "empty string" in run {
                Abort.run(Parse.run("")(Parse.identifier)).map { result =>
                    assert(result.isFail)
                }
            }
        }

        "regex" - {
            "simple pattern" in run {
                Parse.run("abc123")(Parse.regex("[a-z]+[0-9]+")).map { result =>
                    assert(result.is("abc123"))
                }
            }

            "no match" in run {
                Abort.run(Parse.run("123abc")(Parse.regex("[a-z]+[0-9]+"))).map { result =>
                    assert(result.isFail)
                }
            }

            "empty match pattern" in run {
                Parse.run("")(Parse.regex(".*")).map { result =>
                    assert(result.is(""))
                }
            }
        }

        "oneOf" - {
            "single match" in run {
                Parse.run("a")(Parse.oneOf("abc")).map { result =>
                    assert(result == 'a')
                }
            }

            "no match" in run {
                Abort.run(Parse.run("d")(Parse.oneOf("abc"))).map { result =>
                    assert(result.isFail)
                }
            }
        }

        "noneOf" - {
            "valid char" in run {
                Parse.run("d")(Parse.noneOf("abc")).map { result =>
                    assert(result == 'd')
                }
            }

            "invalid char" in run {
                Abort.run(Parse.run("a")(Parse.noneOf("abc"))).map { result =>
                    assert(result.isFail)
                }
            }
        }

        "end" - {
            "empty string" in run {
                Parse.run("")(Parse.end).map(_ => succeed)
            }

            "non-empty string" in run {
                Abort.run(Parse.run("abc")(Parse.end)).map { result =>
                    assert(result.isFail)
                }
            }
        }
    }

    "complex parsers" - {
        "arithmetic expression" in run {

            def eval(left: Int, op: Char, right: Int): Int = op match
                case '+' => left + right
                case '-' => left - right
                case '*' => left * right
                case '/' => left / right

            def term =
                for
                    _ <- Parse.whitespaces
                    n <- Parse.firstOf(
                        for
                            _ <- Parse.char('(')
                            _ <- Parse.whitespaces
                            r <- expr
                            _ <- Parse.whitespaces
                            _ <- Parse.char(')')
                        yield r,
                        Parse.int
                    )
                    _ <- Parse.whitespaces
                yield n

            def expr: Int < Parse =
                for
                    left <- term
                    rest <- Parse.firstOf(
                        for
                            _     <- Parse.whitespaces
                            op    <- Parse.oneOf("+-*/")
                            right <- expr
                        yield eval(left, op, right),
                        left
                    )
                yield rest

            for
                r1 <- Parse.run("(1 + (2 * 3))")(expr)
                r2 <- Parse.run("((1 + 2) * 3)")(expr)
                r3 <- Parse.run("((10 - 5) + 2)")(expr)
                r4 <- Parse.run("(2 * (3 + 4))")(expr)
            yield
                assert(r1 == 7)
                assert(r2 == 9)
                assert(r3 == 7)
                assert(r4 == 14)
            end for
        }
    }

    "integration with other effects" - {
        "Parse with Emit" - {
            "emit parsed values" in run {
                def parseAndEmit: Unit < (Parse & Emit[Int]) =
                    for
                        n1 <- Parse.int
                        _  <- Emit(n1)
                        _  <- Parse.whitespaces
                        n2 <- Parse.int
                        _  <- Emit(n2)
                    yield ()

                val result = Abort.run(Emit.run(Parse.run("42 123")(parseAndEmit))).eval
                assert(result.contains((Chunk(42, 123), ())))
            }
        }

        "Parse with Env" - {
            trait NumberParser:
                def parseNumber: Int < Parse

            "configurable number parsing" in run {
                val hexParser = new NumberParser:
                    def parseNumber =
                        Parse.read { s =>
                            val (num, rest) = s.span(c => c.isDigit || ('a' to 'f').contains(c.toLower))
                            Result(Integer.parseInt(num.show, 16)).toMaybe.map((rest, _))
                        }

                val decimalParser = new NumberParser:
                    def parseNumber = Parse.int

                def parseWithConfig: Int < (Parse & Env[NumberParser]) =
                    Env.use[NumberParser](_.parseNumber)

                for
                    hex <- Env.run(hexParser)(Parse.run("ff")(parseWithConfig))
                    dec <- Env.run(decimalParser)(Parse.run("42")(parseWithConfig))
                yield
                    assert(hex == 255)
                    assert(dec == 42)
                end for
            }
        }
    }

    "streaming" - {

        "basic streaming" in run {
            val parser = Parse.int
            val input  = Stream.init(Seq[Text]("1", "2", "3"))
            Parse.run(input)(parser).run.map { result =>
                assert(result == Chunk(123))
            }
        }

        "partial inputs" in run {
            val parser =
                for
                    r <- Parse.int
                    _ <- Parse.attempt(Parse.char(' '))
                yield r
            val input = Stream.init(Seq[Text]("1", "2 3", "4 5"))
            Parse.run(input)(parser).run.map { result =>
                assert(result == Chunk(12, 34, 5))
            }
        }

        "with whitespace" in run {
            val parser =
                for
                    _ <- Parse.whitespaces
                    n <- Parse.int
                    _ <- Parse.whitespaces
                yield n

            val input = Stream.init(Seq[Text](" 1 ", "  2  ", " 3 "))
            Parse.run(input)(parser).run.map { result =>
                assert(result == Chunk(1, 2, 3))
            }
        }

        "error handling" in run {
            val parser = Parse.int
            val input  = Stream.init(Seq[Text]("1", "abc", "3"))

            Abort.run(Parse.run(input)(parser).run).map { result =>
                assert(result.isFail)
            }
        }

        "ambiguous parse" in run {
            val parser = Parse.anyOf(
                Parse.literal("ab").as(1),
                Parse.literal("abc").as(2)
            )
            val input = Stream.init(Seq("abc").map(Text(_)))

            Abort.run(Parse.run(input)(parser).run).map { result =>
                assert(result.isFail)
                assert(result.failure.get.getMessage().contains("Ambiguous"))
            }
        }

        "incomplete parse" in run {
            val parser = Parse.literal("abc")
            val input  = Stream.init(Seq("ab").map(Text(_)))

            Abort.run(Parse.run(input)(parser).run).map { result =>
                assert(result.isFail)
                assert(result.failure.get.getMessage().contains("Incomplete"))
            }
        }

        "large input stream" in run {
            val size = 20
            val parser =
                for
                    r <- Parse.int
                    _ <- Parse.whitespaces
                yield r
            val numbers = (1 to size).map(_.toString)
            val chunks  = numbers.grouped(size / 10).map(seq => Text(seq.map(n => s"$n ").mkString)).toSeq
            val input   = Stream.init(chunks)

            Parse.run(input)(parser).run.map { result =>
                assert(result == Chunk.from(1 to size))
            }
        }

        "empty chunks handling" in run {
            val parser = Parse.int
            val input  = Stream.init(Seq[Text]("", "1", "", "2", ""))
            Parse.run(input)(parser).run.map { result =>
                assert(result == Chunk(12))
            }
        }

        "partial token across chunks" in run {
            val parser = Parse.int
            val input  = Stream.init(Seq[Text]("1", "2", "34", "5"))
            Parse.run(input)(parser).run.map { result =>
                assert(result == Chunk(12345))
            }
        }

        "complex token splitting" in run {
            val parser = Parse.literal("hello world")
            val input  = Stream.init(Seq[Text]("he", "llo", " wo", "rld"))
            Parse.run(input)(parser).run.map { result =>
                assert(result == Chunk(()))
            }
        }

        "accumulated state handling" in run {
            val parser =
                for
                    _ <- Parse.whitespaces
                    n <- Parse.int
                    _ <- Parse.whitespaces
                    _ <- Parse.literal(",")
                yield n

            val input = Stream.init(Seq[Text]("1,", " 2 ,", "  3,"))
            Parse.run(input)(parser).run.map { result =>
                assert(result == Chunk(1, 2, 3))
            }
        }

        "nested parsers with streaming" in run {
            val numberList =
                for
                    _ <- Parse.char('[')
                    n <- Parse.int
                    _ <- Parse.char(']')
                yield n

            val input = Stream.init(Seq[Text]("[1]", "[2", "]", "[3]"))
            Parse.run(input)(numberList).run.map { result =>
                assert(result == Chunk(1, 2, 3))
            }
        }

        "backtracking across chunks" in run {
            val parser = Parse.firstOf(
                Parse.literal("foo bar").as(1),
                Parse.literal("foo baz").as(2)
            )
            val input = Stream.init(Seq[Text]("foo ", "ba", "z"))
            Parse.run(input)(parser).run.map { result =>
                assert(result == Chunk(2))
            }
        }

        "with Var effect" in run {
            val parser =
                for
                    r <- Parse.int
                    _ <- Parse.attempt(Parse.char(' '))
                    _ <- Var.update[Int](_ + 1)
                yield r
            val input = Stream.init(Seq[Text]("1", "2 3", "4 5"))
            Var.runTuple(0)(Parse.run(input)(parser).run).map { (count, result) =>
                assert(result == Chunk(12, 34, 5))
                assert(count == 3)
            }
        }

        "with Env effect" in run {
            val parser =
                for
                    r         <- Parse.int
                    separator <- Env.get[Char]
                    _         <- Parse.attempt(Parse.char(separator))
                yield r
            val input = Stream.init(Seq[Text]("1", "2 3", "4 5"))
            Env.run(' ')(Parse.run(input)(parser).run).map { result =>
                assert(result == Chunk(12, 34, 5))
            }
        }
    }

    "ParseFailed" - {
        "is serializable" in {
            import java.io.*
            val frame = Frame.internal
            val state = Parse.State("test input", 5)
            val error = ParseFailed(frame, Seq(state), "test error")

            // Serialize
            val baos = new ByteArrayOutputStream()
            val oos  = new ObjectOutputStream(baos)
            oos.writeObject(error)
            oos.close()

            // Deserialize
            val bais         = new ByteArrayInputStream(baos.toByteArray)
            val ois          = new ObjectInputStream(bais)
            val deserialized = ois.readObject().asInstanceOf[ParseFailed]
            ois.close()

            assert(deserialized.message == "test error")
            assert(deserialized.states.size == 1)
            assert(deserialized.states.head.input.is("test input"))
            assert(deserialized.states.head.position == 5)
            assert(deserialized.frame == frame)
        }
    }
end ParseTest
