package kyo

trait ParseTest(lazyTestLength: Int) extends ParseTestBase:

    "combinators" - {
        "firstOf" - {
            "takes first match" in {
                val parser = Parse.firstOf(
                    Parse.literal("test").andThen(1),
                    Parse.literal("test").andThen(2)
                )
                Parse.runOrAbort("test")(parser).map { result =>
                    assert(result == 1)
                }
            }

            "tries alternatives" in {
                val parser = Parse.firstOf(
                    Parse.literal("hello").andThen(1),
                    Parse.literal("world").andThen(2),
                    Parse.literal("test").andThen(3)
                )
                Parse.runOrAbort("test")(parser).map { result =>
                    assert(result == 3)
                }
            }

            "handles recursive parsers" in {
                def nested: String < Parse[Char] =
                    Parse.firstOf(
                        Parse.between(
                            Parse.literal('('),
                            nested,
                            Parse.literal(')')
                        ),
                        Parse.literal("()")
                    )

                for
                    r1   <- Parse.runOrAbort("()")(nested)
                    r2   <- Parse.runOrAbort("(())")(nested)
                    r3   <- Parse.runOrAbort("((()))")(nested)
                    fail <- Parse.runResult("(()")(nested)
                yield
                    assert(r1 == ("()"))
                    assert(r2 == ("()"))
                    assert(r3 == ("()"))
                    assert(fail.isFailure)
                end for
            }

            "handles mutually recursive parsers" in {
                def term: Int < Parse[Char] =
                    Parse.firstOf(
                        Parse.int,
                        Parse.between(
                            Parse.literal('('),
                            expr,
                            Parse.literal(')')
                        )
                    )

                def expr: Int < Parse[Char] =
                    Parse.firstOf(
                        for
                            left  <- term
                            _     <- Parse.literal('+')
                            right <- expr
                        yield left + right,
                        term
                    )

                for
                    r1   <- Parse.runOrAbort("42")(expr)
                    r2   <- Parse.runOrAbort("(42)")(expr)
                    r3   <- Parse.runOrAbort("1+2")(expr)
                    r4   <- Parse.runOrAbort("1+2+3")(expr)
                    r5   <- Parse.runOrAbort("(1+2)+3")(expr)
                    fail <- Parse.runResult("(1+)")(expr)
                yield
                    assert(r1 == 42)
                    assert(r2 == 42)
                    assert(r3 == 3)
                    assert(r4 == 6)
                    assert(r5 == 6)
                    assert(fail.isFailure)
                end for
            }

            "lazy evaluation prevents stack overflow" in {
                def nested: String < Parse[Char] =
                    Parse.firstOf(
                        Parse.between(
                            Parse.literal('['),
                            nested,
                            Parse.literal(']')
                        ),
                        Parse.literal("[]")
                    )

                val deeplyNested = "[" * lazyTestLength + "]" * lazyTestLength
                Parse.runOrAbort(deeplyNested)(nested).map(result => assert(result == "[]"))
            }

            "overloads" - {
                "2 parsers" in {
                    val parser = Parse.firstOf(
                        Parse.literal("one").andThen(1),
                        Parse.literal("two").andThen(2)
                    )
                    for
                        r1 <- Parse.runOrAbort("one")(parser)
                        r2 <- Parse.runOrAbort("two")(parser)
                    yield
                        assert(r1 == 1)
                        assert(r2 == 2)
                    end for
                }

                "3 parsers" in {
                    val parser = Parse.firstOf(
                        Parse.literal("one").andThen(1),
                        Parse.literal("two").andThen(2),
                        Parse.literal("three").andThen(3)
                    )
                    for
                        r1 <- Parse.runOrAbort("one")(parser)
                        r2 <- Parse.runOrAbort("two")(parser)
                        r3 <- Parse.runOrAbort("three")(parser)
                    yield
                        assert(r1 == 1)
                        assert(r2 == 2)
                        assert(r3 == 3)
                    end for
                }

                "4 parsers" in {
                    val parser = Parse.firstOf(
                        Parse.literal("one").andThen(1),
                        Parse.literal("two").andThen(2),
                        Parse.literal("three").andThen(3),
                        Parse.literal("four").andThen(4)
                    )
                    for
                        r1 <- Parse.runOrAbort("one")(parser)
                        r2 <- Parse.runOrAbort("two")(parser)
                        r3 <- Parse.runOrAbort("three")(parser)
                        r4 <- Parse.runOrAbort("four")(parser)
                    yield
                        assert(r1 == 1)
                        assert(r2 == 2)
                        assert(r3 == 3)
                        assert(r4 == 4)
                    end for
                }

                "5 parsers" in {
                    val parser = Parse.firstOf(
                        Parse.literal("one").andThen(1),
                        Parse.literal("two").andThen(2),
                        Parse.literal("three").andThen(3),
                        Parse.literal("four").andThen(4),
                        Parse.literal("five").andThen(5)
                    )
                    for
                        r1 <- Parse.runOrAbort("one")(parser)
                        r2 <- Parse.runOrAbort("two")(parser)
                        r3 <- Parse.runOrAbort("three")(parser)
                        r4 <- Parse.runOrAbort("four")(parser)
                        r5 <- Parse.runOrAbort("five")(parser)
                    yield
                        assert(r1 == 1)
                        assert(r2 == 2)
                        assert(r3 == 3)
                        assert(r4 == 4)
                        assert(r5 == 5)
                    end for
                }

                "6 parsers" in {
                    val parser = Parse.firstOf(
                        Parse.literal("one").andThen(1),
                        Parse.literal("two").andThen(2),
                        Parse.literal("three").andThen(3),
                        Parse.literal("four").andThen(4),
                        Parse.literal("five").andThen(5),
                        Parse.literal("six").andThen(6)
                    )
                    for
                        r1 <- Parse.runOrAbort("one")(parser)
                        r2 <- Parse.runOrAbort("two")(parser)
                        r3 <- Parse.runOrAbort("three")(parser)
                        r4 <- Parse.runOrAbort("four")(parser)
                        r5 <- Parse.runOrAbort("five")(parser)
                        r6 <- Parse.runOrAbort("six")(parser)
                    yield
                        assert(r1 == 1)
                        assert(r2 == 2)
                        assert(r3 == 3)
                        assert(r4 == 4)
                        assert(r5 == 5)
                        assert(r6 == 6)
                    end for
                }

                "7 parsers" in {
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
                        r1 <- Parse.runOrAbort("one")(parser)
                        r2 <- Parse.runOrAbort("two")(parser)
                        r3 <- Parse.runOrAbort("three")(parser)
                        r4 <- Parse.runOrAbort("four")(parser)
                        r5 <- Parse.runOrAbort("five")(parser)
                        r6 <- Parse.runOrAbort("six")(parser)
                        r7 <- Parse.runOrAbort("seven")(parser)
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

                "8 parsers" in {
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
                        r1 <- Parse.runOrAbort("one")(parser)
                        r2 <- Parse.runOrAbort("two")(parser)
                        r3 <- Parse.runOrAbort("three")(parser)
                        r4 <- Parse.runOrAbort("four")(parser)
                        r5 <- Parse.runOrAbort("five")(parser)
                        r6 <- Parse.runOrAbort("six")(parser)
                        r7 <- Parse.runOrAbort("seven")(parser)
                        r8 <- Parse.runOrAbort("eight")(parser)
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
            "skips until pattern" in {
                val parser = Parse.skipUntil(Parse.literal("end").andThen("found"))
                Parse.runOrAbort("abc123end")(parser).map { result =>
                    assert(result == "found")
                }
            }

            "empty input" in {
                Parse.runResult("")(Parse.skipUntil(Parse.literal("end"))).map { result =>
                    assert(result.isFailure)
                }
            }

            "large skip" in {
                val input = "a" * 10000 + "end"
                Parse.runOrAbort(input)(Parse.skipUntil(Parse.literal("end").andThen("found"))).map { result =>
                    assert(result == "found")
                }
            }

            "pattern never matches" in {
                Parse.runResult("abcdef")(Parse.skipUntil(Parse.literal("xyz"))).map { result =>
                    assert(result.isFailure)
                }
            }
        }

        "attempt" - {
            "backtracks on failure" in {
                val parser =
                    Parse.attempt(Parse.literal("hello")).map { r =>
                        assert(r.isEmpty)
                        Parse.literal("world")
                    }
                Parse.runOrAbort("world")(parser).map { result =>
                    assert(result == ("world"))
                }
            }

            "preserves success" in {
                val parser = Parse.attempt(Parse.literal("hello"))
                Parse.runOrAbort("hello")(parser).map { r =>
                    assert(r.isDefined)
                }
            }
        }

        "peek" - {
            "doesn't consume input on success" in {
                val parser =
                    for
                        _      <- Parse.peek(Parse.literal("hello"))
                        result <- Parse.literal("hello")
                    yield result
                Parse.runOrAbort("hello")(parser).map(result => assert(result == "hello"))
            }

            "doesn't consume input on failure" in {
                val parser =
                    for
                        r      <- Parse.peek(Parse.literal("hello"))
                        result <- Parse.literal("world")
                    yield (r, result)
                Parse.runOrAbort("world")(parser).map { case (r, _) =>
                    assert(r.isEmpty)
                }
            }
        }

        "repeat" - {
            "repeats until failure" in {
                val parser = Parse.repeat(Parse.literal("a")).andThen(Parse.literal("b"))
                Parse.runOrAbort("aaab")(parser).map { result =>
                    assert(result == ("b"))
                }
            }

            "fixed repetitions" in {
                val parser = Parse.repeat(3)(Parse.literal("a"))
                Parse.runOrAbort("aaa")(parser).map { result =>
                    assert(result.length == 3)
                }
            }

            "fails if not enough repetitions" in {
                val parser = Parse.repeat(3)(Parse.literal("a"))
                Parse.runResult("aa")(parser).map { result =>
                    assert(result.isFailure)
                }
            }

            "empty input" in {
                Parse.runOrAbort("")(Parse.repeat(Parse.literal("a"))).map { result =>
                    assert(result.isEmpty)
                }
            }

            "exceeds fixed count" in {
                Parse.runOrAbort("aaaa")(Parse.repeat(3)(Parse.literal("a")).map(r => Parse.literal("a").andThen(r))).map { result =>
                    assert(result.length == 3)
                }
            }

            "large repetition" in {
                val parser = Parse.repeat(1000)(Parse.literal("a"))
                Parse.runOrAbort("a" * 1000)(parser).map { result =>
                    assert(result.length == 1000)
                }
            }
        }

        "require" - {
            "succeeds with match" in {
                val parser = Parse.require(Parse.literal("test"))
                Parse.runOrAbort("test")(parser).map(result => assert(result == "test"))
            }

            "fails without backtracking" in {
                val parser =
                    Parse.firstOf(
                        Parse.require(Parse.literal("test")),
                        Parse.literal("world")
                    )
                Parse.runResult("world")(parser).map { result =>
                    assert(result.isFailure)
                }
            }
        }

        "separatedBy" - {
            "comma separated numbers" in {
                val parser = Parse.separatedBy(
                    Parse.int,
                    Parse.literal(',')
                )
                Parse.runOrAbort("1,2,3")(parser).map { result =>
                    assert(result == Chunk(1, 2, 3))
                }
            }

            "with whitespace" in {
                val parser = Parse.separatedBy(
                    Parse.int,
                    for
                        _ <- Parse.whitespaces
                        _ <- Parse.literal(',')
                        _ <- Parse.whitespaces
                    yield ()
                )
                Parse.runOrAbort("1 , 2 , 3")(parser).map { result =>
                    assert(result == Chunk(1, 2, 3))
                }
            }

            "single element" in {
                val parser = Parse.separatedBy(Parse.int, Parse.literal(','))
                Parse.runOrAbort("42")(parser).map { result =>
                    assert(result == Chunk(42))
                }
            }

            "empty input" in {
                val parser = Parse.separatedBy(Parse.int, Parse.literal(','))
                Parse.runOrAbort("")(parser).map { result =>
                    assert(result.isEmpty)
                }
            }

            "missing separator fails" in {
                val parser = Parse
                    .separatedBy(Parse.int.andThen(Parse.literal(' ')), Parse.literal(','))
                    .andThen(Parse.end[Char])

                Parse.runResult("1 2 ,3 ")(parser).map { result =>
                    assert(result.isFailure)
                }
            }

            "multiple missing separators fails" in {
                val parser = Parse
                    .separatedBy(Parse.int.andThen(Parse.literal(' ')), Parse.literal(','))
                    .andThen(Parse.end[Char])

                Parse.runResult("1 2 3")(parser).map { result =>
                    assert(result.isFailure)
                }
            }

            "allowTrailing" - {
                "accepts trailing separator when enabled" in {
                    val parser = Parse.separatedBy(
                        Parse.int,
                        Parse.literal(','),
                        allowTrailing = true
                    )
                    Parse.runOrAbort("1,2,3,")(parser).map { result =>
                        assert(result == Chunk(1, 2, 3))
                    }
                }

                "rejects trailing separator when disabled" in {
                    val parser = Parse.separatedBy(
                        Parse.int,
                        Parse.literal(','),
                        allowTrailing = false
                    )
                    Parse.runResult("1,2,3,")(parser).map { result =>
                        assert(result.isFailure)
                    }
                }

                "handles empty input with trailing enabled" in {
                    val parser = Parse.separatedBy(
                        Parse.int,
                        Parse.literal(','),
                        allowTrailing = true
                    )
                    Parse.runOrAbort("")(parser).map { result =>
                        assert(result.isEmpty)
                    }
                }

                "handles single element with trailing enabled" in {
                    val parser = Parse.separatedBy(
                        Parse.int,
                        Parse.literal(','),
                        allowTrailing = true
                    )
                    Parse.runOrAbort("42,")(parser).map { result =>
                        assert(result == Chunk(42))
                    }
                }

                "multiple trailing separators fail even when enabled" in {
                    val parser = Parse.separatedBy(
                        Parse.int,
                        Parse.literal(','),
                        allowTrailing = true
                    ).andThen(Parse.end[Char])

                    Parse.runResult("1,2,3,,")(parser).map { result =>
                        assert(result.isFailure)
                    }
                }
            }
        }

        "between" - {
            "parentheses" in {
                val parser = Parse.between(
                    Parse.literal('('),
                    Parse.int,
                    Parse.literal(')')
                )
                Parse.runOrAbort("(42)")(parser).map { result =>
                    assert(result == 42)
                }
            }

            "with whitespace" in {
                val parser = Parse.between(
                    Parse.literal('[').andThen(Parse.whitespaces),
                    Parse.int,
                    Parse.whitespaces.andThen(Parse.literal(']'))
                )
                Parse.runOrAbort("[ 42 ]")(parser).map { result =>
                    assert(result == 42)
                }
            }

            "nested" in {
                val parser = Parse.between(
                    Parse.literal('('),
                    Parse.between(
                        Parse.literal('['),
                        Parse.int,
                        Parse.literal(']')
                    ),
                    Parse.literal(')')
                )
                Parse.runOrAbort("([42])")(parser).map { result =>
                    assert(result == 42)
                }
            }

            "missing right delimiter" in {
                val parser = Parse.between(
                    Parse.literal('('),
                    Parse.int,
                    Parse.literal(')')
                )
                Parse.runResult("(42")(parser).map { result =>
                    assert(result.isFailure)
                }
            }

            "missing left delimiter" in {
                val parser = Parse.between(
                    Parse.literal('('),
                    Parse.int,
                    Parse.literal(')')
                )
                Parse.runResult("42)")(parser).map { result =>
                    assert(result.isFailure)
                }
            }
        }

        "inOrder" - {
            "two parsers" in {
                val parser = Parse.inOrder(
                    Parse.literal("hello").andThen(1),
                    Parse.literal("world").andThen(2)
                )
                Parse.runOrAbort("helloworld")(parser).map { case (r1, r2) =>
                    assert(r1 == 1)
                    assert(r2 == 2)
                }
            }

            "three parsers" in {
                val parser = Parse.inOrder(
                    Parse.literal("hello").andThen(1),
                    Parse.literal("world").andThen(2),
                    Parse.literal("!").andThen(3)
                )
                Parse.runOrAbort("helloworld!")(parser).map { case (r1, r2, r3) =>
                    assert(r1 == 1)
                    assert(r2 == 2)
                    assert(r3 == 3)
                }
            }

            "four parsers" in {
                val parser = Parse.inOrder(
                    Parse.literal("hello").andThen(1),
                    Parse.literal("world").andThen(2),
                    Parse.literal("!").andThen(3),
                    Parse.literal("?").andThen(4)
                )
                Parse.runOrAbort("helloworld!?")(parser).map { case (r1, r2, r3, r4) =>
                    assert(r1 == 1)
                    assert(r2 == 2)
                    assert(r3 == 3)
                    assert(r4 == 4)
                }
            }

            "sequence of parsers" in {
                val parser = Parse.inOrder(
                    Parse.literal("hello").andThen(1),
                    Parse.literal(" ").andThen(2),
                    Parse.literal("world").andThen(3)
                )
                Parse.runOrAbort("hello world")(parser).map { result =>
                    assert(result == (1, 2, 3))
                }
            }

            "fails if any parser fails" in {
                val parser = Parse.inOrder(
                    Parse.literal("hello").andThen(1),
                    Parse.literal("world").andThen(2),
                    Parse.literal("!").andThen(3)
                )
                Parse.runResult("hello test")(parser).map { result =>
                    assert(result.isFailure)
                }
            }

            "consumes input sequentially" in {
                val parser = Parse.inOrder(
                    Parse.literal("a").andThen(1),
                    Parse.literal("b").andThen(2),
                    Parse.literal("c").andThen(3)
                )
                Parse.runOrAbort("abc")(parser).map { result =>
                    assert(result == (1, 2, 3))
                }
            }

            "inOrder" - {
                "two parsers" in {
                    val parser = Parse.inOrder(
                        Parse.literal("hello").andThen(1),
                        Parse.literal("world").andThen(2)
                    )
                    Parse.runOrAbort("helloworld")(parser).map { case (r1, r2) =>
                        assert(r1 == 1)
                        assert(r2 == 2)
                    }
                }

                "three parsers" in {
                    val parser = Parse.inOrder(
                        Parse.literal("hello").andThen(1),
                        Parse.literal("world").andThen(2),
                        Parse.literal("!").andThen(3)
                    )
                    Parse.runOrAbort("helloworld!")(parser).map { case (r1, r2, r3) =>
                        assert(r1 == 1)
                        assert(r2 == 2)
                        assert(r3 == 3)
                    }
                }

                "four parsers" in {
                    val parser = Parse.inOrder(
                        Parse.literal("hello").andThen(1),
                        Parse.literal("world").andThen(2),
                        Parse.literal("!").andThen(3),
                        Parse.literal("?").andThen(4)
                    )
                    Parse.runOrAbort("helloworld!?")(parser).map { case (r1, r2, r3, r4) =>
                        assert(r1 == 1)
                        assert(r2 == 2)
                        assert(r3 == 3)
                        assert(r4 == 4)
                    }
                }

                "five parsers" in {
                    val parser = Parse.inOrder(
                        Parse.literal("a").andThen(1),
                        Parse.literal("b").andThen(2),
                        Parse.literal("c").andThen(3),
                        Parse.literal("d").andThen(4),
                        Parse.literal("e").andThen(5)
                    )
                    Parse.runOrAbort("abcde")(parser).map { case (r1, r2, r3, r4, r5) =>
                        assert(r1 == 1)
                        assert(r2 == 2)
                        assert(r3 == 3)
                        assert(r4 == 4)
                        assert(r5 == 5)
                    }
                }

                "six parsers" in {
                    val parser = Parse.inOrder(
                        Parse.literal("a").andThen(1),
                        Parse.literal("b").andThen(2),
                        Parse.literal("c").andThen(3),
                        Parse.literal("d").andThen(4),
                        Parse.literal("e").andThen(5),
                        Parse.literal("f").andThen(6)
                    )
                    Parse.runOrAbort("abcdef")(parser).map { case (r1, r2, r3, r4, r5, r6) =>
                        assert(r1 == 1)
                        assert(r2 == 2)
                        assert(r3 == 3)
                        assert(r4 == 4)
                        assert(r5 == 5)
                        assert(r6 == 6)
                    }
                }

                "seven parsers" in {
                    val parser = Parse.inOrder(
                        Parse.literal("a").andThen(1),
                        Parse.literal("b").andThen(2),
                        Parse.literal("c").andThen(3),
                        Parse.literal("d").andThen(4),
                        Parse.literal("e").andThen(5),
                        Parse.literal("f").andThen(6),
                        Parse.literal("g").andThen(7)
                    )
                    Parse.runOrAbort("abcdefg")(parser).map { case (r1, r2, r3, r4, r5, r6, r7) =>
                        assert(r1 == 1)
                        assert(r2 == 2)
                        assert(r3 == 3)
                        assert(r4 == 4)
                        assert(r5 == 5)
                        assert(r6 == 6)
                        assert(r7 == 7)
                    }
                }

                "eight parsers" in {
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
                    Parse.runOrAbort("abcdefgh")(parser).map { case (r1, r2, r3, r4, r5, r6, r7, r8) =>
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
            "reads digits" in {
                val parser =
                    for
                        digits <- Parse.readWhile[Char](_.isDigit)
                        rest   <- Parse.readWhile[Char](_ => true)
                    yield (digits, rest)

                Parse.runOrAbort("123abc")(parser).map { case (digits, rest) =>
                    assert(digits == Chunk('1', '2', '3'))
                    assert(rest == Chunk('a', 'b', 'c'))
                }
            }

            "empty input" in {
                val parser = Parse.readWhile[Char](_.isDigit)
                Parse.runOrAbort("")(parser).map { result =>
                    assert(result.isEmpty)
                }
            }

            "no matching characters" in {
                val parser =
                    for
                        digits <- Parse.readWhile[Char](_.isDigit)
                        rest   <- Parse.readWhile[Char](_ => true)
                    yield (digits, rest)

                Parse.runOrAbort("abc")(parser).map { case (digits, rest) =>
                    assert(digits.isEmpty)
                    assert(rest == Chunk('a', 'b', 'c'))
                }
            }

            "reads until predicate fails" in {
                val parser =
                    for
                        letters <- Parse.readWhile[Char](_.isLetter)
                        rest    <- Parse.readWhile[Char](_ => true)
                    yield (letters, rest)

                Parse.runOrAbort("aaa123")(parser).map { case (letters, rest) =>
                    assert(letters == Chunk('a', 'a', 'a'))
                    assert(rest == Chunk('1', '2', '3'))
                }
            }

        }

        "spaced" - {
            "handles surrounding whitespace" in {
                val parser = Parse.spaced(Parse.literal("test"))
                Parse.runOrAbort("  test  ")(parser).map(result => assert(result == "test"))
            }

            "preserves inner content" in {
                val parser = Parse.spaced(Parse.literal("hello world"))
                Parse.runOrAbort("  hello world  ")(parser).map(result => assert(result == "hello world"))
            }

            "handles no whitespace" in {
                val parser = Parse.spaced(Parse.int)
                Parse.runOrAbort("42")(parser).map { result =>
                    assert(result == 42)
                }
            }

            "handles different whitespace types" in {
                val parser = Parse.spaced(Parse.int)
                Parse.runOrAbort("\t\n\r 42 \t\n\r")(parser).map { result =>
                    assert(result == 42)
                }
            }

            "preserves failure" in {
                val parser = Parse.spaced(Parse.literal("test"))
                Parse.runResult("  fail  ")(parser).map { result =>
                    assert(result.isFailure)
                }
            }

            "composes with other parsers" in {
                val parser = Parse.inOrder(
                    Parse.spaced(Parse.int),
                    Parse.spaced(Parse.literal("+")),
                    Parse.spaced(Parse.int)
                )
                Parse.runOrAbort(" 1  +  2 ")(parser).map { case (n1, _, n2) =>
                    assert(n1 == 1)
                    assert(n2 == 2)
                }
            }

            "mathematical expression with optional whitespace" in {
                def eval(left: Double, op: Char, right: Double): Double = op match
                    case '+' => left + right
                    case '-' => left - right
                    case '*' => left * right
                    case '/' => left / right

                def parens: Double < Parse[Char] =
                    Parse.between(
                        Parse.literal('('),
                        add,
                        Parse.literal(')')
                    )

                def factor: Double < Parse[Char] =
                    Parse.firstOf(Parse.decimal, parens)

                def addOp: Char < Parse[Char] = Parse.anyIn("+-")

                def mult: Double < Parse[Char] =
                    for
                        init <- factor
                        rest <- Parse.repeat(
                            for
                                op    <- Parse.anyIn("*/")
                                right <- factor
                            yield (op, right)
                        )
                    yield rest.foldLeft(init)((acc, pair) => eval(acc, pair._1, pair._2))

                def add: Double < Parse[Char] =
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
                    r1 <- Parse.runOrAbort("2 * (3 + 4)")(expr)
                    r2 <- Parse.runOrAbort(" 2*(3+4) ")(expr)
                    r3 <- Parse.runOrAbort("2*( 3 + 4 )/( 1 + 2 )")(expr)
                    r4 <- Parse.runOrAbort("1 + 2 * 3 + 4")(expr)
                yield
                    assert(r1 == 14.0)
                    assert(r2 == 14.0)
                    assert(r3 == 14.0 / 3.0)
                    assert(r4 == 11.0)
                end for
            }

        }

        "custom whitespace predicate" - {
            "only spaces" in {
                val parser = Parse.spaced(Parse.int, _ == ' ')
                Parse.runOrAbort("  42  ")(parser).map { result =>
                    assert(result == 42)
                }
            }

            "custom multi-char predicate" in {
                val customWhitespace = Set('_', '-', '~')
                val parser           = Parse.spaced(Parse.int, customWhitespace.contains)
                Parse.runOrAbort("__42~~--")(parser).map { result =>
                    assert(result == 42)
                }
            }

            "complex parser with custom whitespace" in {
                val customWhitespace = Set('.', '*')
                val parser = Parse.spaced(
                    Parse.inOrder(
                        Parse.int,
                        Parse.literal("+"),
                        Parse.int
                    ),
                    customWhitespace.contains
                )
                Parse.runOrAbort("..1**+**2..")(parser).map { case (n1, _, n2) =>
                    assert(n1 == 1)
                    assert(n2 == 2)
                }
            }
        }
    }

    "standard parsers" - {

        "whitespaces" - {
            "empty string" in {
                Parse.runOrAbort("")(Parse.whitespaces).map { result =>
                    assert(result == (""))
                }
            }

            "mixed whitespace types" in {
                Parse.runOrAbort(" \t\n\r ")(Parse.whitespaces).map { result =>
                    assert(result == (" \t\n\r "))
                }
            }

            "large whitespace input" in {
                Parse.runOrAbort(" " * 10000)(Parse.whitespaces).map { result =>
                    assert(result == (" " * 10000))
                }
            }
        }

        "int" - {
            "positive" in {
                Parse.runOrAbort("123")(Parse.int).map { result =>
                    assert(result == 123)
                }
            }

            "negative" in {
                Parse.runOrAbort("-123")(Parse.int).map { result =>
                    assert(result == -123)
                }
            }

            "invalid" in {
                Parse.runResult("abc")(Parse.int).map { result =>
                    assert(result.isFailure)
                }
            }

            "max int" in {
                Parse.runOrAbort(s"${Int.MaxValue}")(Parse.int).map { result =>
                    assert(result == Int.MaxValue)
                }
            }

            "min int" in {
                Parse.runOrAbort(s"${Int.MinValue}")(Parse.int).map { result =>
                    assert(result == Int.MinValue)
                }
            }
        }

        "boolean" - {
            "true" in {
                Parse.runOrAbort("true")(Parse.boolean).map { result =>
                    assert(result == true)
                }
            }

            "false" in {
                Parse.runOrAbort("false")(Parse.boolean).map { result =>
                    assert(result == false)
                }
            }

            "invalid" in {
                Parse.runResult("xyz")(Parse.boolean).map { result =>
                    assert(result.isFailure)
                }
            }
        }

        "char" in {
            Parse.runOrAbort("a")(Parse.literal('a')).map(result => assert(result == 'a'))
        }

        "literal" - {
            "empty literal" in {
                Parse.runOrAbort("")(Parse.literal("")).map(result => assert(result == ""))
            }

            "partial match" in {
                Parse.runResult("hell")(Parse.literal("hello").andThen(Parse.end[Char])).map { result =>
                    assert(result.isFailure)
                }
            }

            "case sensitive" in {
                Parse.runResult("HELLO")(Parse.literal("hello")).map { result =>
                    assert(result.isFailure)
                }
            }
        }

        "decimal" - {
            "positive" in {
                Parse.runOrAbort("123.45")(Parse.decimal).map { result =>
                    assert(result == 123.45)
                }
            }

            "negative" in {
                Parse.runOrAbort("-123.45")(Parse.decimal).map { result =>
                    assert(result == -123.45)
                }
            }

            "integer" in {
                Parse.runOrAbort("123")(Parse.decimal).map { result =>
                    assert(result == 123.0)
                }
            }

            "invalid" in {
                Parse.runResult("abc")(Parse.decimal).map { result =>
                    assert(result.isFailure)
                }
            }

            "leading dot" in {
                Parse.runOrAbort(".123")(Parse.decimal).map { result =>
                    assert(result == 0.123)
                }
            }

            "trailing dot" in {
                Parse.runOrAbort("123.")(Parse.decimal).map { result =>
                    assert(result == 123.0)
                }
            }

            "multiple dots" in {
                Parse.runResult("1.2.3")(Parse.decimal).map { result =>
                    assert(result.isFailure)
                }
            }

            "multiple leading dots" in {
                Parse.runResult("..123")(Parse.decimal).map { result =>
                    assert(result.isFailure)
                }
            }

            "malformed negative" in {
                Parse.runResult(".-123")(Parse.decimal).map { result =>
                    assert(result.isFailure)
                }
            }

            "very large precision" in {
                Parse.runOrAbort("123.45678901234567890")(Parse.decimal).map { result =>
                    assert(result == 123.45678901234567890)
                }
            }
        }

        "identifier" - {
            "valid identifiers" in {
                Parse.runOrAbort("_hello123")(Parse.identifier).map { result =>
                    assert(result == ("_hello123"))
                }
            }

            "starting with number" in {
                Parse.runResult("123abc")(Parse.identifier).map { result =>
                    assert(result.isFailure)
                }
            }

            "special characters" in {
                Parse.runResult("hello@world")(Parse.identifier.andThen(Parse.end)).map { result =>
                    assert(result.isFailure)
                }
            }

            "very long identifier" in {
                val longId = "a" * 1000
                Parse.runOrAbort(longId)(Parse.identifier).map { result =>
                    assert(result == (longId))
                }
            }

            "unicode letters" in {
                Parse.runOrAbort("αβγ123")(Parse.identifier).map { result =>
                    assert(result == ("αβγ123"))
                }
            }

            "empty string" in {
                Parse.runResult("")(Parse.identifier).map { result =>
                    assert(result.isFailure)
                }
            }
        }

        "regex" - {
            "simple pattern" in {
                Parse.runOrAbort("abc123")(Parse.regex("[a-z]+[0-9]+")).map { result =>
                    assert(result == ("abc123"))
                }
            }

            "no match" in {
                Parse.runResult("123abc")(Parse.regex("[a-z]+[0-9]+")).map { result =>
                    assert(result.isFailure)
                }
            }

            "empty match pattern" in {
                Parse.runOrAbort("")(Parse.regex(".*")).map { result =>
                    assert(result == (""))
                }
            }
        }

        "oneOf" - {
            "single match" in {
                Parse.runOrAbort("a")(Parse.anyIn("abc")).map { result =>
                    assert(result == 'a')
                }
            }

            "no match" in {
                Parse.runResult("d")(Parse.anyIn("abc")).map { result =>
                    assert(result.isFailure)
                }
            }
        }

        "noneOf" - {
            "valid char" in {
                Parse.runOrAbort("d")(Parse.anyNotIn("abc")).map { result =>
                    assert(result == 'd')
                }
            }

            "invalid char" in {
                Parse.runResult("a")(Parse.anyNotIn("abc")).map { result =>
                    assert(result.isFailure)
                }
            }
        }

        "end" - {
            "empty string" in {
                Parse.runOrAbort("")(Parse.end).map(result => assert(result == ()))
            }

            "non-empty string" in {
                Parse.runResult("abc")(Parse.end).map { result =>
                    assert(result.isFailure)
                }
            }
        }
    }

    "complex parsers" - {
        "arithmetic expression" in {

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
                            _ <- Parse.literal('(')
                            _ <- Parse.whitespaces
                            r <- expr
                            _ <- Parse.whitespaces
                            _ <- Parse.literal(')')
                        yield r,
                        Parse.int
                    )
                    _ <- Parse.whitespaces
                yield n

            def expr: Int < Parse[Char] =
                for
                    left <- term
                    rest <- Parse.firstOf(
                        for
                            _     <- Parse.whitespaces
                            op    <- Parse.anyIn("+-*/")
                            right <- expr
                        yield eval(left, op, right),
                        left
                    )
                yield rest

            for
                r1 <- Parse.runOrAbort("(1 + (2 * 3))")(expr)
                r2 <- Parse.runOrAbort("((1 + 2) * 3)")(expr)
                r3 <- Parse.runOrAbort("((10 - 5) + 2)")(expr)
                r4 <- Parse.runOrAbort("(2 * (3 + 4))")(expr)
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
            "emit parsed values" in {
                def parseAndEmit: Unit < (Parse[Char] & Emit[Int]) =
                    for
                        n1 <- Parse.int
                        _  <- Emit.value(n1)
                        _  <- Parse.whitespaces
                        n2 <- Parse.int
                        _  <- Emit.value(n2)
                    yield ()

                val result = Abort.run(Emit.run(Parse.runOrAbort("42 123")(parseAndEmit))).eval
                assert(result.contains((Chunk(42, 123), ())))
            }
        }

        "Parse with Env" - {
            trait NumberParser:
                def parseNumber: Int < Parse[Char]

            "configurable number parsing" in {
                val hexParser = new NumberParser:
                    def parseNumber =
                        Parse.read: in =>
                            val num = in.remaining.takeWhile(c => c.isDigit || ('a' to 'f').contains(c.toLower))
                            Result((in.advance(num.length), Integer.parseInt(num.mkString, 16)))
                                .orElse(Result.fail(Chunk(ParseFailure("Invalid hex int", in.position))))

                val decimalParser = new NumberParser:
                    def parseNumber = Parse.int

                def parseWithConfig: Int < (Parse[Char] & Env[NumberParser]) =
                    Env.use[NumberParser](_.parseNumber)

                for
                    hex <- Env.run(hexParser)(Parse.runOrAbort("ff")(parseWithConfig))
                    dec <- Env.run(decimalParser)(Parse.runOrAbort("42")(parseWithConfig))
                yield
                    assert(hex == 255)
                    assert(dec == 42)
                end for
            }
        }

        "Two Parse of different input type" in {
            val intCharZipParser: Chunk[(Char, Int)] < (Parse[Int] & Parse[Char]) =
                for
                    chars <- Parse.repeat(Parse.any[Char])
                    ints  <- Parse.repeat(Parse.any[Int])
                yield chars.zip(ints)

            for
                result <- intCharZipParser.handle(Parse.runOrAbort("abc"), Parse.runOrAbort(Chunk(1, 2, 3)))
            yield assert(result == Chunk(('a', 1), ('b', 2), ('c', 3)))
        }
    }

    "streaming" - {

        "basic streaming" in {
            val parser = Parse.int
            val input  = Stream.init(Seq("1", "2", "3"))
            Parse.runStream(input)(parser).run.map { result =>
                assert(result == Chunk(123))
            }
        }

        "partial inputs" in {
            val parser =
                for
                    r <- Parse.int
                    _ <- Parse.attempt(Parse.literal(' '))
                yield r
            val input = Stream.init(Seq("1", "2 3", "4 5"))
            Parse.runStream(input)(parser).run.map { result =>
                assert(result == Chunk(12, 34, 5))
            }
        }

        "with whitespace" in {
            val parser =
                for
                    _ <- Parse.whitespaces
                    n <- Parse.int
                    _ <- Parse.whitespaces
                yield n

            val input = Stream.init(Seq(" 1 ", "  2  ", " 3 "))
            Parse.runStream(input)(parser).run.map { result =>
                assert(result == Chunk(1, 2, 3))
            }
        }

        "error handling" in {
            val parser = Parse.int
            val input  = Stream.init(Seq("1", "abc", "3"))

            Abort.run(Parse.runStream(input)(parser).run).map { result =>
                assert(result.isFailure)
            }
        }

        "incomplete parse" in {
            val parser = Parse.literal("abc")
            val input  = Stream.init(Seq("ab"))

            Abort.run(Parse.runStream(input)(parser).run).map { result =>
                assert(result.isFailure)
                assert(result.failure.get.getMessage().contains("Incomplete"))
            }
        }

        "large input stream" in {
            val size = 20
            val parser =
                for
                    r <- Parse.int
                    _ <- Parse.whitespaces
                yield r
            val numbers = (1 to size).map(_.toString)
            val chunks  = numbers.grouped(size / 10).map(seq => seq.map(n => s"$n ").mkString).toSeq
            val input   = Stream.init(chunks)

            Parse.runStream(input)(parser).run.map { result =>
                assert(result == Chunk.from(1 to size))
            }
        }

        "empty chunks handling" in {
            val parser = Parse.int
            val input  = Stream.init(Seq("", "1", "", "2", ""))
            Parse.runStream(input)(parser).run.map { result =>
                assert(result == Chunk(12))
            }
        }

        "partial token across chunks" in {
            val parser = Parse.int
            val input  = Stream.init(Seq("1", "2", "34", "5"))
            Parse.runStream(input)(parser).run.map { result =>
                assert(result == Chunk(12345))
            }
        }

        "complex token splitting" in {
            val parser = Parse.literal("hello world")
            val input  = Stream.init(Seq("he", "llo", " wo", "rld"))
            Parse.runStream(input)(parser).run.map { result =>
                assert(result.size == 1)
                assert(result(0) == ("hello world"))
            }
        }

        "accumulated state handling" in {
            val parser =
                for
                    _ <- Parse.whitespaces
                    n <- Parse.int
                    _ <- Parse.whitespaces
                    _ <- Parse.literal(",")
                yield n

            val input = Stream.init(Seq("1,", " 2 ,", "  3,"))
            Parse.runStream(input)(parser).run.map { result =>
                assert(result == Chunk(1, 2, 3))
            }
        }

        "nested parsers with streaming" in {
            val numberList =
                for
                    _ <- Parse.literal('[')
                    n <- Parse.int
                    _ <- Parse.literal(']')
                yield n

            val input = Stream.init(Seq("[1]", "[2", "]", "[3]"))
            Parse.runStream(input)(numberList).run.map { result =>
                assert(result == Chunk(1, 2, 3))
            }
        }

        "backtracking across chunks" in {
            val parser = Parse.firstOf(
                Parse.literal("foo bar").andThen(1),
                Parse.literal("foo baz").andThen(2)
            )
            val input = Stream.init(Seq("foo ", "ba", "z"))
            Parse.runStream(input)(parser).run.map { result =>
                assert(result == Chunk(2))
            }
        }

        "with Var effect" in {
            val parser =
                for
                    r <- Parse.int
                    _ <- Parse.attempt(Parse.literal(' '))
                    _ <- Var.update[Int](_ + 1)
                yield r
            val input = Stream.init(Seq("1", "2 3", "4 5"))
            Var.runTuple(0)(Parse.runStream(input)(parser).run).map { (count, result) =>
                assert(result == Chunk(12, 34, 5))
                assert(count == 3)
            }
        }

        "with Env effect" in {
            val parser =
                for
                    r         <- Parse.int
                    separator <- Env.get[Char]
                    _         <- Parse.attempt(Parse.literal(separator))
                yield r
            val input = Stream.init(Seq("1", "2 3", "4 5"))
            Env.run(' ')(Parse.runStream(input)(parser).run).map { result =>
                assert(result == Chunk(12, 34, 5))
            }
        }
    }
end ParseTest
