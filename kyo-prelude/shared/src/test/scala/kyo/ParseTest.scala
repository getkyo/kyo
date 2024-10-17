package kyo

import kyo.debug.Debug

class ParseTest extends Test:

    "basic parsers" - {
        "whitespaces" - {
            "simple" in run {
                Parse.run("   hello")(Parse.whitespaces).map { result =>
                    assert(result == Present(("hello", ())))
                }
            }
            "complex" in run {
                Parse.run(" \t\n\rrest")(Parse.whitespaces).map { result =>
                    assert(result == Present(("rest", ())))
                }
            }
        }

        "int" - {
            "positive" in run {
                Parse.run("123abc")(Parse.int).map { result =>
                    assert(result == Present(("abc", 123)))
                }
            }

            "negative" in run {
                Parse.run("-123abc")(Parse.int).map { result =>
                    assert(result == Present(("abc", -123)))
                }
            }

            "invalid" in run {
                Parse.run("abc")(Parse.int).map { result =>
                    assert(result == Maybe.empty)
                }
            }

            "max int" in run {
                Parse.run(s"${Int.MaxValue}rest")(Parse.int).map { result =>
                    assert(result == Present(("rest", Int.MaxValue)))
                }
            }

            "min int" in run {
                Parse.run(s"${Int.MinValue}rest")(Parse.int).map { result =>
                    assert(result == Present(("rest", Int.MinValue)))
                }
            }
        }

        "string" - {
            "simple" in run {
                Parse.run("\"hello\"world")(Parse.string).map { result =>
                    assert(result == Present(("world", "hello")))
                }
            }

            "with escapes" in run {
                Parse.run("\"hello\\nworld\"rest")(Parse.string).map { result =>
                    assert(result == Present(("rest", "hello\nworld")))
                }
            }

            "unterminated" in run {
                Parse.run("\"hello")(Parse.string).map { result =>
                    assert(result == Maybe.empty)
                }
            }
        }

        "boolean" - {
            "true" in run {
                Parse.run("truerest")(Parse.boolean).map { result =>
                    assert(result == Present(("rest", true)))
                }
            }

            "false" in run {
                Parse.run("falserest")(Parse.boolean).map { result =>
                    assert(result == Present(("rest", false)))
                }
            }

            "invalid" in run {
                Parse.run("xyz")(Parse.boolean).map { result =>
                    assert(result == Maybe.empty)
                }
            }
        }

        "char" in run {
            Parse.run("abc")(Parse.char('a')).map { result =>
                assert(result == Present(("bc", 'a')))
            }
        }

        "literal" in run {
            Parse.run("helloworldrest")(Parse.literal("hello")).map { result =>
                assert(result == Present(("worldrest", "hello")))
            }
        }

        "decimal" - {
            "positive" in run {
                Parse.run("123.45abc")(Parse.decimal).map { result =>
                    assert(result == Present(("abc", 123.45)))
                }
            }

            "negative" in run {
                Parse.run("-123.45abc")(Parse.decimal).map { result =>
                    assert(result == Present(("abc", -123.45)))
                }
            }

            "integer" in run {
                Parse.run("123abc")(Parse.decimal).map { result =>
                    assert(result == Present(("abc", 123.0)))
                }
            }

            "invalid" in run {
                Parse.run("abc")(Parse.decimal).map { result =>
                    assert(result == Maybe.empty)
                }
            }
        }
    }

    "combinators" - {
        "attempt" - {
            val parser = Parse.attempt(
                Parse.int.map { n =>
                    if n > 100 then Parse.drop
                    else n
                }
            )

            "ok" in run {
                Parse.run("50rest")(parser).map { result =>
                    assert(result == Present(("rest", Present(50))))
                }
            }

            "nok" in run {
                Parse.run("150rest")(parser).map { result =>
                    assert(result == Present(("150rest", Maybe.empty)))
                }
            }
        }

        "peek" in run {
            val parser = Parse.peek(Parse.int)

            Parse.run("123rest")(parser).map { result =>
                assert(result == Present(("123rest", Present(123))))
            }
        }

        "dropIf" - {
            val parser = Parse.int.map { n =>
                Parse.dropIf(n > 100).as(n)
            }

            "ok" in run {
                Parse.run("50rest")(parser).map { result =>
                    assert(result == Present(("rest", 50)))
                }
            }

            "nok" in run {
                Parse.run("150rest")(parser).map { result =>
                    assert(result == Maybe.empty)
                }
            }
        }

        "oneOf" - {
            given [A, B]: CanEqual[A, B] = CanEqual.derived
            val parser = Parse.oneOf(
                Parse.int,
                Parse.string,
                Parse.boolean
            )

            "int" in run {
                Parse.run("123rest")(parser).map { result =>
                    assert(result == Present(("rest", 123)))
                }
            }

            "string" in run {
                Parse.run("\"hello\"rest")(parser).map { result =>
                    assert(result == Present(("rest", "hello")))
                }
            }

            "boolean" in run {
                Parse.run("truerest")(parser).map { result =>
                    assert(result == Present(("rest", true)))
                }
            }

            "invalid" in run {
                Parse.run("invalid")(parser).map { result =>
                    assert(result == Maybe.empty)
                }
            }
        }
    }

    "error handling" - {
        "ambiguous parse" in run {
            val parser = Parse.oneOf(
                Parse.int,
                Parse.int
            )

            Abort.run(Parse.run("123rest")(parser)).map { result =>
                assert(result.isFail)
            }
        }

        "no match" in run {
            val parser = Parse.oneOf()

            Parse.run("input")(parser).map { result =>
                assert(result == Maybe.empty)
            }
        }
    }

    "complex parsers" - {
        "json number" in run {
            def jsonNumber: Double < Parse = Parse.decimal

            Parse.run("42.5rest")(jsonNumber).map { result =>
                assert(result == Present(("rest", 42.5)))
            }
        }

        "json string" in run {
            def jsonString: String < Parse = Parse.string

            Parse.run("\"hello\\nworld\"rest")(jsonString).map { result =>
                assert(result == Present(("rest", "hello\nworld")))
            }
        }

        "json boolean" in run {
            def jsonBoolean: Boolean < Parse = Parse.boolean

            Parse.run("truerest")(jsonBoolean).map { result =>
                assert(result == Present(("rest", true)))
            }
        }

        "json null" - {
            def jsonNull: Unit < Parse = Parse.literal("null").as(())

            "valid" in run {
                Parse.run("nullrest")(jsonNull).map { result =>
                    assert(result == Present(("rest", ())))
                }
            }

            "invalid" in run {
                Parse.run("notNull")(jsonNull).map { result =>
                    assert(result == Maybe.empty)
                }
            }
        }

        "config file parser" in run {
            def configParser: Map[String, Map[String, String]] < Parse =
                def key: String < Parse =
                    Parse.read(s =>
                        Maybe((s.dropWhile(c => c.isLetterOrDigit || c == '_'), s.takeWhile(c => c.isLetterOrDigit || c == '_'))).filter(
                            _._2.nonEmpty
                        )
                    )

                def value: String < Parse =
                    Parse.char('=').map(_ =>
                        Parse.whitespaces.map(_ =>
                            Parse.read(s => Maybe((s.dropWhile(_ != '\n'), s.takeWhile(_ != '\n').trim)))
                        )
                    )

                def keyValue: (String, String) < Parse =
                    for
                        k <- key
                        _ <- Parse.whitespaces
                        v <- value
                        _ <- Parse.whitespaces
                    yield (k, v)

                def section: (String, Map[String, String]) < Parse =
                    for
                        _     <- Parse.whitespaces
                        _     <- Parse.char('[')
                        name  <- key
                        _     <- Parse.char(']')
                        _     <- Parse.whitespaces
                        pairs <- Parse.repeatUntil(keyValue)(Parse.peek(Parse.char('[')).map(_.isDefined))
                    yield (name, pairs.collect { case (k, v) => (k, v) }.toMap)

                Parse.repeat(section).map(_.collect {
                    case (name, map) => (name, map)
                }.toMap)
            end configParser

            val input =
                """
                |[section1]
                |key1 = value1
                |key2 = value2
                |[section2]
                |key3 = value3
                |key4 = value4
                |""".stripMargin

            Parse.run(input)(configParser).map { result =>
                assert(result == Present((
                    "",
                    Map(
                        "section1" -> Map("key1" -> "value1", "key2" -> "value2"),
                        "section2" -> Map("key3" -> "value3", "key4" -> "value4")
                    )
                )))
            }
        }
    }

    "integration with other effects" - {
        "with Emit" - {
            "emit parsed values" in run {
                def numberParser: Int < (Parse & Emit[Int]) =
                    for
                        _   <- Parse.whitespaces
                        num <- Parse.int
                        _   <- Emit(num)
                    yield num

                val result = Emit.run {
                    Parse.run("1 2 3 4") {
                        for
                            a <- numberParser
                            b <- numberParser
                            c <- numberParser
                            d <- numberParser
                        yield (a, b, c, d)
                    }
                }

                result.map { case (emitted, parseResult) =>
                    assert(emitted == Chunk(1, 2, 3, 4))
                    assert(parseResult == Present(("", (1, 2, 3, 4))))
                }
            }

            "emit parsing events" in run {
                sealed trait ParsingEvent derives CanEqual
                case class NumberFound(value: Int)    extends ParsingEvent
                case class StringFound(value: String) extends ParsingEvent

                def mixedParser: (Int | String) < (Parse & Emit[ParsingEvent] & Parse) =
                    Parse.whitespaces.andThen(
                        Parse.oneOf(
                            Parse.int.map { n =>
                                Emit[ParsingEvent](NumberFound(n)).as(n)
                            },
                            Parse.string.map { s =>
                                Emit[ParsingEvent](StringFound(s)).as(s)
                            }
                        )
                    )

                val result = Emit.run {
                    Parse.run("""42 "hello" 123 "world"""") {
                        for
                            a <- mixedParser
                            b <- mixedParser
                            c <- mixedParser
                            d <- mixedParser
                        yield (a, b, c, d)
                    }
                }

                result.map { case (events, parseResult) =>
                    assert(events == Chunk(
                        NumberFound(42),
                        StringFound("hello"),
                        NumberFound(123),
                        StringFound("world")
                    ))
                }
            }
        }
    }

end ParseTest
