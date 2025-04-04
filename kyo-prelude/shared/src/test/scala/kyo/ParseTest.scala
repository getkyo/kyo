package kyo

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
                    assert(result.isFailure)
                }
            }
        }

        "firstOf" - {
            "takes first match" in run {
                val parser = Parse.firstOf(
                    Parse.literal("test").andThen(1),
                    Parse.literal("test").andThen(2)
                )
                Parse.run("test")(parser).map { result =>
                    assert(result == 1)
                }
            }

            "tries alternatives" in run {
                val parser = Parse.firstOf(
                    Parse.literal("hello").andThen(1),
                    Parse.literal("world").andThen(2),
                    Parse.literal("test").andThen(3)
                )
                Parse.run("test")(parser).map { result =>
                    assert(result == 3)
                }
            }

            "handles recursive parsers" in run {
                def nested: Text < Parse =
                    Parse.firstOf(
                        Parse.between(
                            Parse.char('('),
                            nested,
                            Parse.char(')')
                        ),
                        Parse.literal("()")
                    )

                for
                    r1   <- Parse.run("()")(nested)
                    r2   <- Parse.run("(())")(nested)
                    r3   <- Parse.run("((()))")(nested)
                    fail <- Abort.run(Parse.run("(()")(nested))
                yield
                    assert(r1.is("()"))
                    assert(r2.is("()"))
                    assert(r3.is("()"))
                    assert(fail.isFailure)
                end for
            }

            "handles mutually recursive parsers" in run {
                def term: Int < Parse =
                    Parse.firstOf(
                        Parse.int,
                        Parse.between(
                            Parse.char('('),
                            expr,
                            Parse.char(')')
                        )
                    )

                def expr: Int < Parse =
                    Parse.firstOf(
                        for
                            left  <- term
                            _     <- Parse.char('+')
                            right <- expr
                        yield left + right,
                        term
                    )

                for
                    r1   <- Parse.run("42")(expr)
                    r2   <- Parse.run("(42)")(expr)
                    r3   <- Parse.run("1+2")(expr)
                    r4   <- Parse.run("1+2+3")(expr)
                    r5   <- Parse.run("(1+2)+3")(expr)
                    fail <- Abort.run(Parse.run("(1+)")(expr))
                yield
                    assert(r1 == 42)
                    assert(r2 == 42)
                    assert(r3 == 3)
                    assert(r4 == 6)
                    assert(r5 == 6)
                    assert(fail.isFailure)
                end for
            }

            "lazy evaluation prevents stack overflow" in run {
                def nested: Text < Parse =
                    Parse.firstOf(
                        Parse.between(
                            Parse.char('['),
                            nested,
                            Parse.char(']')
                        ),
                        Parse.literal("[]")
                    )

                val deeplyNested = "[" * 1000 + "[]" + "]" * 1000
                Parse.run(deeplyNested)(nested).map(_ => succeed)
            }

            "overloads" - {
                "2 parsers" in run {
                    val parser = Parse.firstOf(
                        Parse.literal("one").andThen(1),
                        Parse.literal("two").andThen(2)
                    )
                    for
                        r1 <- Parse.run("one")(parser)
                        r2 <- Parse.run("two")(parser)
                    yield
                        assert(r1 == 1)
                        assert(r2 == 2)
                    end for
                }

                "3 parsers" in run {
                    val parser = Parse.firstOf(
                        Parse.literal("one").andThen(1),
                        Parse.literal("two").andThen(2),
                        Parse.literal("three").andThen(3)
                    )
                    for
                        r1 <- Parse.run("one")(parser)
                        r2 <- Parse.run("two")(parser)
                        r3 <- Parse.run("three")(parser)
                    yield
                        assert(r1 == 1)
                        assert(r2 == 2)
                        assert(r3 == 3)
                    end for
                }

                "4 parsers" in run {
                    val parser = Parse.firstOf(
                        Parse.literal("one").andThen(1),
                        Parse.literal("two").andThen(2),
                        Parse.literal("three").andThen(3),
                        Parse.literal("four").andThen(4)
                    )
                    for
                        r1 <- Parse.run("one")(parser)
                        r2 <- Parse.run("two")(parser)
                        r3 <- Parse.run("three")(parser)
                        r4 <- Parse.run("four")(parser)
                    yield
                        assert(r1 == 1)
                        assert(r2 == 2)
                        assert(r3 == 3)
                        assert(r4 == 4)
                    end for
                }

                "5 parsers" in run {
                    val parser = Parse.firstOf(
                        Parse.literal("one").andThen(1),
                        Parse.literal("two").andThen(2),
                        Parse.literal("three").andThen(3),
                        Parse.literal("four").andThen(4),
                        Parse.literal("five").andThen(5)
                    )
                    for
                        r1 <- Parse.run("one")(parser)
                        r2 <- Parse.run("two")(parser)
                        r3 <- Parse.run("three")(parser)
                        r4 <- Parse.run("four")(parser)
                        r5 <- Parse.run("five")(parser)
                    yield
                        assert(r1 == 1)
                        assert(r2 == 2)
                        assert(r3 == 3)
                        assert(r4 == 4)
                        assert(r5 == 5)
                    end for
                }

                "6 parsers" in run {
                    val parser = Parse.firstOf(
                        Parse.literal("one").andThen(1),
                        Parse.literal("two").andThen(2),
                        Parse.literal("three").andThen(3),
                        Parse.literal("four").andThen(4),
                        Parse.literal("five").andThen(5),
                        Parse.literal("six").andThen(6)
                    )
                    for
                        r1 <- Parse.run("one")(parser)
                        r2 <- Parse.run("two")(parser)
                        r3 <- Parse.run("three")(parser)
                        r4 <- Parse.run("four")(parser)
                        r5 <- Parse.run("five")(parser)
                        r6 <- Parse.run("six")(parser)
                    yield
                        assert(r1 == 1)
                        assert(r2 == 2)
                        assert(r3 == 3)
                        assert(r4 == 4)
                        assert(r5 == 5)
                        assert(r6 == 6)
                    end for
                }

                "7 parsers" in run {
                    val parser = Parse.firstOf(
                        Parse.literal("one").andThen(1),
                        Parse.literal("two").andThen(2),
                        Parse.literal("three").andThen(3),
                        Parse.literal("four").andThen(4),
                        Parse.literal("five").andThen(5),
                        Parse.literal("six").andThen(6),
                        Parse.literal("seven").andThen(7)
                    )
                    for
                        r1 <- Parse.run("one")(parser)
                        r2 <- Parse.run("two")(parser)
                        r3 <- Parse.run("three")(parser)
                        r4 <- Parse.run("four")(parser)
                        r5 <- Parse.run("five")(parser)
                        r6 <- Parse.run("six")(parser)
                        r7 <- Parse.run("seven")(parser)
                    yield
                        assert(r1 == 1)
                        assert(r2 == 2)
                        assert(r3 == 3)
                        assert(r4 == 4)
                        assert(r5 == 5)
                        assert(r6 == 6)
                        assert(r7 == 7)
                    end for
                }

                "8 parsers" in run {
                    val parser = Parse.firstOf(
                        Parse.literal("one").andThen(1),
                        Parse.literal("two").andThen(2),
                        Parse.literal("three").andThen(3),
                        Parse.literal("four").andThen(4),
                        Parse.literal("five").andThen(5),
                        Parse.literal("six").andThen(6),
                        Parse.literal("seven").andThen(7),
                        Parse.literal("eight").andThen(8)
                    )
                    for
                        r1 <- Parse.run("one")(parser)
                        r2 <- Parse.run("two")(parser)
                        r3 <- Parse.run("three")(parser)
                        r4 <- Parse.run("four")(parser)
                        r5 <- Parse.run("five")(parser)
                        r6 <- Parse.run("six")(parser)
                        r7 <- Parse.run("seven")(parser)
                        r8 <- Parse.run("eight")(parser)
                    yield
                        assert(r1 == 1)
                        assert(r2 == 2)
                        assert(r3 == 3)
                        assert(r4 == 4)
                        assert(r5 == 5)
                        assert(r6 == 6)
                        assert(r7 == 7)
                        assert(r8 == 8)
                    end for
                }
            }
        }

        "skipUntil" - {
            "skips until pattern" in run {
                val parser = Parse.skipUntil(Parse.literal("end").andThen("found"))
                Parse.run("abc123end")(parser).map { result =>
                    assert(result == "found")
                }
            }

            "empty input" in run {
                Abort.run(Parse.run("")(Parse.skipUntil(Parse.literal("end")))).map { result =>
                    assert(result.isFailure)
                }
            }

            "large skip" in run {
                val input = "a" * 10000 + "end"
                Parse.run(input)(Parse.skipUntil(Parse.literal("end").andThen("found"))).map { result =>
                    assert(result == "found")
                }
            }

            "pattern never matches" in run {
                Abort.run(Parse.run("abcdef")(Parse.skipUntil(Parse.literal("xyz")))).map { result =>
                    assert(result.isFailure)
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
                    assert(result.is("world"))
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
                val parser = Parse.repeat(Parse.literal("a")).andThen(Parse.literal("b"))
                Parse.run("aaab")(parser).map { result =>
                    assert(result.is("b"))
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
                    assert(result.isFailure)
                }
            }

            "empty input" in run {
                Parse.run("")(Parse.repeat(Parse.literal("a"))).map { result =>
                    assert(result.isEmpty)
                }
            }

            "exceeds fixed count" in run {
                Parse.run("aaaa")(Parse.repeat(3)(Parse.literal("a")).map(r => Parse.literal("a").andThen(r))).map { result =>
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

        "require" - {
            "succeeds with match" in run {
                val parser = Parse.require(Parse.literal("test"))
                Parse.run("test")(parser).map(_ => succeed)
            }

            "fails without backtracking" in run {
                val parser =
                    Parse.firstOf(
                        Parse.require(Parse.literal("test")),
                        Parse.literal("world")
                    )
                Abort.run(Parse.run("world")(parser)).map { result =>
                    assert(result.isFailure)
                }
            }
        }

        "separatedBy" - {
            "comma separated numbers" in run {
                val parser = Parse.separatedBy(
                    Parse.int,
                    Parse.char(',')
                )
                Parse.run("1,2,3")(parser).map { result =>
                    assert(result == Chunk(1, 2, 3))
                }
            }

            "with whitespace" in run {
                val parser = Parse.separatedBy(
                    Parse.int,
                    for
                        _ <- Parse.whitespaces
                        _ <- Parse.char(',')
                        _ <- Parse.whitespaces
                    yield ()
                )
                Parse.run("1 , 2 , 3")(parser).map { result =>
                    assert(result == Chunk(1, 2, 3))
                }
            }

            "single element" in run {
                val parser = Parse.separatedBy(Parse.int, Parse.char(','))
                Parse.run("42")(parser).map { result =>
                    assert(result == Chunk(42))
                }
            }

            "empty input" in run {
                val parser = Parse.separatedBy(Parse.int, Parse.char(','))
                Parse.run("")(parser).map { result =>
                    assert(result.isEmpty)
                }
            }

            "missing separator fails" in run {
                val parser = Parse.separatedBy(Parse.int.andThen(Parse.char(' ')), Parse.char(','))
                Abort.run(Parse.run("1 2 ,3 ")(parser)).map { result =>
                    assert(result.isFailure)
                }
            }

            "multiple missing separators fails" in run {
                val parser = Parse.separatedBy(Parse.int.andThen(Parse.char(' ')), Parse.char(','))
                Abort.run(Parse.run("1 2 3")(parser)).map { result =>
                    assert(result.isFailure)
                }
            }

            "allowTrailing" - {
                "accepts trailing separator when enabled" in run {
                    val parser = Parse.separatedBy(
                        Parse.int,
                        Parse.char(','),
                        allowTrailing = true
                    )
                    Parse.run("1,2,3,")(parser).map { result =>
                        assert(result == Chunk(1, 2, 3))
                    }
                }

                "rejects trailing separator when disabled" in run {
                    val parser = Parse.separatedBy(
                        Parse.int,
                        Parse.char(','),
                        allowTrailing = false
                    )
                    Abort.run(Parse.run("1,2,3,")(parser)).map { result =>
                        assert(result.isFailure)
                    }
                }

                "handles empty input with trailing enabled" in run {
                    val parser = Parse.separatedBy(
                        Parse.int,
                        Parse.char(','),
                        allowTrailing = true
                    )
                    Parse.run("")(parser).map { result =>
                        assert(result.isEmpty)
                    }
                }

                "handles single element with trailing enabled" in run {
                    val parser = Parse.separatedBy(
                        Parse.int,
                        Parse.char(','),
                        allowTrailing = true
                    )
                    Parse.run("42,")(parser).map { result =>
                        assert(result == Chunk(42))
                    }
                }

                "multiple trailing separators fail even when enabled" in run {
                    val parser = Parse.separatedBy(
                        Parse.int,
                        Parse.char(','),
                        allowTrailing = true
                    )
                    Abort.run(Parse.run("1,2,3,,")(parser)).map { result =>
                        assert(result.isFailure)
                    }
                }
            }
        }

        "between" - {
            "parentheses" in run {
                val parser = Parse.between(
                    Parse.char('('),
                    Parse.int,
                    Parse.char(')')
                )
                Parse.run("(42)")(parser).map { result =>
                    assert(result == 42)
                }
            }

            "with whitespace" in run {
                val parser = Parse.between(
                    Parse.char('[').andThen(Parse.whitespaces),
                    Parse.int,
                    Parse.whitespaces.andThen(Parse.char(']'))
                )
                Parse.run("[ 42 ]")(parser).map { result =>
                    assert(result == 42)
                }
            }

            "nested" in run {
                val parser = Parse.between(
                    Parse.char('('),
                    Parse.between(
                        Parse.char('['),
                        Parse.int,
                        Parse.char(']')
                    ),
                    Parse.char(')')
                )
                Parse.run("([42])")(parser).map { result =>
                    assert(result == 42)
                }
            }

            "missing right delimiter" in run {
                val parser = Parse.between(
                    Parse.char('('),
                    Parse.int,
                    Parse.char(')')
                )
                Abort.run(Parse.run("(42")(parser)).map { result =>
                    assert(result.isFailure)
                }
            }

            "missing left delimiter" in run {
                val parser = Parse.between(
                    Parse.char('('),
                    Parse.int,
                    Parse.char(')')
                )
                Abort.run(Parse.run("42)")(parser)).map { result =>
                    assert(result.isFailure)
                }
            }
        }

        "inOrder" - {
            "two parsers" in run {
                val parser = Parse.inOrder(
                    Parse.literal("hello").andThen(1),
                    Parse.literal("world").andThen(2)
                )
                Parse.run("helloworld")(parser).map { case (r1, r2) =>
                    assert(r1 == 1)
                    assert(r2 == 2)
                }
            }

            "three parsers" in run {
                val parser = Parse.inOrder(
                    Parse.literal("hello").andThen(1),
                    Parse.literal("world").andThen(2),
                    Parse.literal("!").andThen(3)
                )
                Parse.run("helloworld!")(parser).map { case (r1, r2, r3) =>
                    assert(r1 == 1)
                    assert(r2 == 2)
                    assert(r3 == 3)
                }
            }

            "four parsers" in run {
                val parser = Parse.inOrder(
                    Parse.literal("hello").andThen(1),
                    Parse.literal("world").andThen(2),
                    Parse.literal("!").andThen(3),
                    Parse.literal("?").andThen(4)
                )
                Parse.run("helloworld!?")(parser).map { case (r1, r2, r3, r4) =>
                    assert(r1 == 1)
                    assert(r2 == 2)
                    assert(r3 == 3)
                    assert(r4 == 4)
                }
            }

            "sequence of parsers" in run {
                val parser = Parse.inOrder(
                    Parse.literal("hello").andThen(1),
                    Parse.literal(" ").andThen(2),
                    Parse.literal("world").andThen(3)
                )
                Parse.run("hello world")(parser).map { result =>
                    assert(result == (1, 2, 3))
                }
            }

            "fails if any parser fails" in run {
                val parser = Parse.inOrder(
                    Parse.literal("hello").andThen(1),
                    Parse.literal("world").andThen(2),
                    Parse.literal("!").andThen(3)
                )
                Abort.run(Parse.run("hello test")(parser)).map { result =>
                    assert(result.isFailure)
                }
            }

            "consumes input sequentially" in run {
                val parser = Parse.inOrder(
                    Parse.literal("a").andThen(1),
                    Parse.literal("b").andThen(2),
                    Parse.literal("c").andThen(3)
                )
                Parse.run("abc")(parser).map { result =>
                    assert(result == (1, 2, 3))
                }
            }

            "inOrder" - {
                "two parsers" in run {
                    val parser = Parse.inOrder(
                        Parse.literal("hello").andThen(1),
                        Parse.literal("world").andThen(2)
                    )
                    Parse.run("helloworld")(parser).map { case (r1, r2) =>
                        assert(r1 == 1)
                        assert(r2 == 2)
                    }
                }

                "three parsers" in run {
                    val parser = Parse.inOrder(
                        Parse.literal("hello").andThen(1),
                        Parse.literal("world").andThen(2),
                        Parse.literal("!").andThen(3)
                    )
                    Parse.run("helloworld!")(parser).map { case (r1, r2, r3) =>
                        assert(r1 == 1)
                        assert(r2 == 2)
                        assert(r3 == 3)
                    }
                }

                "four parsers" in run {
                    val parser = Parse.inOrder(
                        Parse.literal("hello").andThen(1),
                        Parse.literal("world").andThen(2),
                        Parse.literal("!").andThen(3),
                        Parse.literal("?").andThen(4)
                    )
                    Parse.run("helloworld!?")(parser).map { case (r1, r2, r3, r4) =>
                        assert(r1 == 1)
                        assert(r2 == 2)
                        assert(r3 == 3)
                        assert(r4 == 4)
                    }
                }

                "five parsers" in run {
                    val parser = Parse.inOrder(
                        Parse.literal("a").andThen(1),
                        Parse.literal("b").andThen(2),
                        Parse.literal("c").andThen(3),
                        Parse.literal("d").andThen(4),
                        Parse.literal("e").andThen(5)
                    )
                    Parse.run("abcde")(parser).map { case (r1, r2, r3, r4, r5) =>
                        assert(r1 == 1)
                        assert(r2 == 2)
                        assert(r3 == 3)
                        assert(r4 == 4)
                        assert(r5 == 5)
                    }
                }

                "six parsers" in run {
                    val parser = Parse.inOrder(
                        Parse.literal("a").andThen(1),
                        Parse.literal("b").andThen(2),
                        Parse.literal("c").andThen(3),
                        Parse.literal("d").andThen(4),
                        Parse.literal("e").andThen(5),
                        Parse.literal("f").andThen(6)
                    )
                    Parse.run("abcdef")(parser).map { case (r1, r2, r3, r4, r5, r6) =>
                        assert(r1 == 1)
                        assert(r2 == 2)
                        assert(r3 == 3)
                        assert(r4 == 4)
                        assert(r5 == 5)
                        assert(r6 == 6)
                    }
                }

                "seven parsers" in run {
                    val parser = Parse.inOrder(
                        Parse.literal("a").andThen(1),
                        Parse.literal("b").andThen(2),
                        Parse.literal("c").andThen(3),
                        Parse.literal("d").andThen(4),
                        Parse.literal("e").andThen(5),
                        Parse.literal("f").andThen(6),
                        Parse.literal("g").andThen(7)
                    )
                    Parse.run("abcdefg")(parser).map { case (r1, r2, r3, r4, r5, r6, r7) =>
                        assert(r1 == 1)
                        assert(r2 == 2)
                        assert(r3 == 3)
                        assert(r4 == 4)
                        assert(r5 == 5)
                        assert(r6 == 6)
                        assert(r7 == 7)
                    }
                }

                "eight parsers" in run {
                    val parser = Parse.inOrder(
                        Parse.literal("a").andThen(1),
                        Parse.literal("b").andThen(2),
                        Parse.literal("c").andThen(3),
                        Parse.literal("d").andThen(4),
                        Parse.literal("e").andThen(5),
                        Parse.literal("f").andThen(6),
                        Parse.literal("g").andThen(7),
                        Parse.literal("h").andThen(8)
                    )
                    Parse.run("abcdefgh")(parser).map { case (r1, r2, r3, r4, r5, r6, r7, r8) =>
                        assert(r1 == 1)
                        assert(r2 == 2)
                        assert(r3 == 3)
                        assert(r4 == 4)
                        assert(r5 == 5)
                        assert(r6 == 6)
                        assert(r7 == 7)
                        assert(r8 == 8)
                    }
                }

            }
        }

        "readWhile" - {
            "reads digits" in run {
                val parser =
                    for
                        digits <- Parse.readWhile(_.isDigit)
                        rest   <- Parse.readWhile(_ => true)
                    yield (digits, rest)

                Parse.run("123abc")(parser).map { case (digits, rest) =>
                    assert(digits.is("123"))
                    assert(rest.is("abc"))
                }
            }

            "empty input" in run {
                val parser = Parse.readWhile(_.isDigit)
                Parse.run("")(parser).map { result =>
                    assert(result.is(""))
                }
            }

            "no matching characters" in run {
                val parser =
                    for
                        digits <- Parse.readWhile(_.isDigit)
                        rest   <- Parse.readWhile(_ => true)
                    yield (digits, rest)

                Parse.run("abc")(parser).map { case (digits, rest) =>
                    assert(digits.is(""))
                    assert(rest.is("abc"))
                }
            }

            "reads until predicate fails" in run {
                val parser =
                    for
                        letters <- Parse.readWhile(_.isLetter)
                        rest    <- Parse.readWhile(_ => true)
                    yield (letters, rest)

                Parse.run("aaa123")(parser).map { case (letters, rest) =>
                    assert(letters.is("aaa"))
                    assert(rest.is("123"))
                }
            }

        }

        "spaced" - {
            "handles surrounding whitespace" in run {
                val parser = Parse.spaced(Parse.literal("test"))
                Parse.run("  test  ")(parser).map(_ => succeed)
            }

            "preserves inner content" in run {
                val parser = Parse.spaced(Parse.literal("hello world"))
                Parse.run("  hello world  ")(parser).map(_ => succeed)
            }

            "handles no whitespace" in run {
                val parser = Parse.spaced(Parse.int)
                Parse.run("42")(parser).map { result =>
                    assert(result == 42)
                }
            }

            "handles different whitespace types" in run {
                val parser = Parse.spaced(Parse.int)
                Parse.run("\t\n\r 42 \t\n\r")(parser).map { result =>
                    assert(result == 42)
                }
            }

            "preserves failure" in run {
                val parser = Parse.spaced(Parse.literal("test"))
                Abort.run(Parse.run("  fail  ")(parser)).map { result =>
                    assert(result.isFailure)
                }
            }

            "composes with other parsers" in run {
                val parser = Parse.inOrder(
                    Parse.spaced(Parse.int),
                    Parse.spaced(Parse.literal("+")),
                    Parse.spaced(Parse.int)
                )
                Parse.run(" 1  +  2 ")(parser).map { case (n1, _, n2) =>
                    assert(n1 == 1)
                    assert(n2 == 2)
                }
            }

            "mathematical expression with optional whitespace" in run {
                def eval(left: Double, op: Char, right: Double): Double = op match
                    case '+' => left + right
                    case '-' => left - right
                    case '*' => left * right
                    case '/' => left / right

                def parens: Double < Parse =
                    Parse.between(
                        Parse.char('('),
                        add,
                        Parse.char(')')
                    )

                def factor: Double < Parse =
                    Parse.firstOf(Parse.decimal, parens)

                def addOp: Char < Parse = Parse.charIn("+-")

                def mult: Double < Parse =
                    for
                        init <- factor
                        rest <- Parse.repeat(
                            for
                                op    <- Parse.charIn("*/")
                                right <- factor
                            yield (op, right)
                        )
                    yield rest.foldLeft(init)((acc, pair) => eval(acc, pair._1, pair._2))

                def add: Double < Parse =
                    for
                        init <- mult
                        rest <- Parse.repeat(
                            for
                                op    <- addOp
                                right <- mult
                            yield (op, right)
                        )
                    yield rest.foldLeft(init)((acc, pair) => eval(acc, pair._1, pair._2))

                val expr = Parse.spaced(add)

                for
                    r1 <- Parse.run("2 * (3 + 4)")(expr)
                    r2 <- Parse.run(" 2*(3+4) ")(expr)
                    r3 <- Parse.run("2*( 3 + 4 )/( 1 + 2 )")(expr)
                    r4 <- Parse.run("1 + 2 * 3 + 4")(expr)
                yield
                    assert(r1 == 14.0)
                    assert(r2 == 14.0)
                    assert(r3 == 14.0 / 3.0)
                    assert(r4 == 11.0)
                end for
            }

        }

        "custom whitespace predicate" - {
            "only spaces" in run {
                val parser = Parse.spaced(Parse.int, _ == ' ')
                Parse.run("  42  ")(parser).map { result =>
                    assert(result == 42)
                }
            }

            "custom multi-char predicate" in run {
                val customWhitespace = Set('_', '-', '~')
                val parser           = Parse.spaced(Parse.int, customWhitespace.contains)
                Parse.run("__42~~--")(parser).map { result =>
                    assert(result == 42)
                }
            }

            "complex parser with custom whitespace" in run {
                val customWhitespace = Set('.', '*')
                val parser = Parse.spaced(
                    Parse.inOrder(
                        Parse.int,
                        Parse.literal("+"),
                        Parse.int
                    ),
                    customWhitespace.contains
                )
                Parse.run("..1**+**2..")(parser).map { case (n1, _, n2) =>
                    assert(n1 == 1)
                    assert(n2 == 2)
                }
            }
        }
    }

    "standard parsers" - {

        "whitespaces" - {
            "empty string" in run {
                Parse.run("")(Parse.whitespaces).map { result =>
                    assert(result.is(""))
                }
            }

            "mixed whitespace types" in run {
                Parse.run(" \t\n\r ")(Parse.whitespaces).map { result =>
                    assert(result.is(" \t\n\r "))
                }
            }

            "large whitespace input" in run {
                Parse.run(" " * 10000)(Parse.whitespaces).map { result =>
                    assert(result.is(" " * 10000))
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
                    assert(result.isFailure)
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
                    assert(result.isFailure)
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
                    assert(result.isFailure)
                }
            }

            "case sensitive" in run {
                Abort.run(Parse.run("HELLO")(Parse.literal("hello"))).map { result =>
                    assert(result.isFailure)
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
                    assert(result.isFailure)
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
                    assert(result.isFailure)
                }
            }

            "multiple leading dots" in run {
                Abort.run(Parse.run("..123")(Parse.decimal)).map { result =>
                    assert(result.isFailure)
                }
            }

            "malformed negative" in run {
                Abort.run(Parse.run(".-123")(Parse.decimal)).map { result =>
                    assert(result.isFailure)
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
                    assert(result.isFailure)
                }
            }

            "special characters" in run {
                Abort.run(Parse.run("hello@world")(Parse.identifier)).map { result =>
                    assert(result.isFailure)
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
                    assert(result.isFailure)
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
                    assert(result.isFailure)
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
                Parse.run("a")(Parse.charIn("abc")).map { result =>
                    assert(result == 'a')
                }
            }

            "no match" in run {
                Abort.run(Parse.run("d")(Parse.charIn("abc"))).map { result =>
                    assert(result.isFailure)
                }
            }
        }

        "noneOf" - {
            "valid char" in run {
                Parse.run("d")(Parse.charNotIn("abc")).map { result =>
                    assert(result == 'd')
                }
            }

            "invalid char" in run {
                Abort.run(Parse.run("a")(Parse.charNotIn("abc"))).map { result =>
                    assert(result.isFailure)
                }
            }
        }

        "end" - {
            "empty string" in run {
                Parse.run("")(Parse.end).map(_ => succeed)
            }

            "non-empty string" in run {
                Abort.run(Parse.run("abc")(Parse.end)).map { result =>
                    assert(result.isFailure)
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
                            op    <- Parse.charIn("+-*/")
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
                        _  <- Emit.value(n1)
                        _  <- Parse.whitespaces
                        n2 <- Parse.int
                        _  <- Emit.value(n2)
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
                assert(result.isFailure)
            }
        }

        "ambiguous parse" in run {
            val parser = Parse.anyOf(
                Parse.literal("ab").andThen(1),
                Parse.literal("abc").andThen(2)
            )
            val input = Stream.init(Seq("abc").map(Text(_)))

            Abort.run(Parse.run(input)(parser).run).map { result =>
                assert(result.isFailure)
                assert(result.failure.get.getMessage().contains("Ambiguous"))
            }
        }

        "incomplete parse" in run {
            val parser = Parse.literal("abc")
            val input  = Stream.init(Seq("ab").map(Text(_)))

            Abort.run(Parse.run(input)(parser).run).map { result =>
                assert(result.isFailure)
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
                assert(result.size == 1)
                assert(result(0).is("hello world"))
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
                Parse.literal("foo bar").andThen(1),
                Parse.literal("foo baz").andThen(2)
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
end ParseTest
