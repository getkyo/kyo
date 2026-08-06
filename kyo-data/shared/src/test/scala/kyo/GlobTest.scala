package kyo

import scala.compiletime.testing.typeCheckErrors

class GlobTest extends kyo.test.Test[Any]:

    private def parse(value: String): Glob =
        Glob.parse(value) match
            case Result.Success(glob) => glob
            case Result.Failure(error) =>
                throw new AssertionError(s"unexpected parse error at ${error.offset}: ${error.reason}")
            case Result.Panic(error) => throw error

    private def parseError(value: String): Glob.ParseError =
        Glob.parse(value) match
            case Result.Failure(error) => error
            case Result.Success(_)     => throw new AssertionError(s"expected '$value' to fail parsing")
            case Result.Panic(error)   => throw error

    private def matches(pattern: String, value: String): Boolean =
        parse(pattern).matches(value, Glob.CaseSensitivity.Sensitive)

    "public contract" in {
        val scalaSources: Glob = glob"**/*.{scala,java}"
        assert(scalaSources.matches("src/main/App.scala", Glob.CaseSensitivity.Sensitive))
        assert(!scalaSources.matches("src/main/App.SCALA", Glob.CaseSensitivity.Sensitive))
        assert(scalaSources.matches("src/main/App.SCALA", Glob.CaseSensitivity.Insensitive))
        assert(Glob.parse("[").failure.exists(_.offset == 0))
        assert(summon[CanEqual[Glob.CaseSensitivity, Glob.CaseSensitivity]] != null)
        assert(summon[CanEqual[Glob.ParseError, Glob.ParseError]] != null)
    }

    "wildcards" - {
        "star matches zero or more characters within one segment" in {
            assert(matches("src/*.scala", "src/App.scala"))
            assert(matches("src/*.scala", "src/.scala"))
            assert(!matches("src/*.scala", "src/main/App.scala"))
        }

        "question mark matches exactly one character within one segment" in {
            assert(matches("file?.txt", "file1.txt"))
            assert(!matches("file?.txt", "file.txt"))
            assert(!matches("file?.txt", "file12.txt"))
        }

        "complete segment double star matches zero or more complete segments" in {
            val glob = parse("src/**/App.scala")
            assert(glob.matches("src/App.scala", Glob.CaseSensitivity.Sensitive))
            assert(glob.matches("src/main/scala/App.scala", Glob.CaseSensitivity.Sensitive))
            assert(!glob.matches("other/App.scala", Glob.CaseSensitivity.Sensitive))
            assert(Glob.all.matches("", Glob.CaseSensitivity.Sensitive))
            assert(Glob.all.matches("any/depth/file", Glob.CaseSensitivity.Sensitive))
        }

        "double star outside a complete segment is rejected" in {
            val suffix = parseError("ab**cd")
            val prefix = parseError("**.scala")
            val triple = parseError("***")
            assert(suffix.offset == 2)
            assert(prefix.offset == 0)
            assert(triple.offset == 0)
            assert(suffix.reason.contains("complete segment"))
        }
    }

    "character classes" - {
        "match listed characters and ranges" in {
            assert(matches("file[abc].txt", "fileb.txt"))
            assert(!matches("file[abc].txt", "filed.txt"))
            assert(matches("file[a-c0-2].txt", "file1.txt"))
            assert(matches("file[a-c0-2].txt", "filec.txt"))
            assert(!matches("file[a-c0-2].txt", "file9.txt"))
        }

        "support class negation" in {
            assert(matches("file[!0-9].txt", "filex.txt"))
            assert(matches("file[^0-9].txt", "filex.txt"))
            assert(!matches("file[!0-9].txt", "file7.txt"))
        }

        "escaping works inside character classes" in {
            assert(matches("value[\\]a].txt", "value].txt"))
            assert(matches("value[\\-].txt", "value-.txt"))
        }
    }

    "alternatives" - {
        "match each branch" in {
            val glob = parse("**/*.{scala,java}")
            assert(glob.matches("Main.scala", Glob.CaseSensitivity.Sensitive))
            assert(glob.matches("src/Main.java", Glob.CaseSensitivity.Sensitive))
            assert(!glob.matches("src/Main.class", Glob.CaseSensitivity.Sensitive))
        }

        "allow wildcard and escaped comma branches" in {
            assert(matches("file.{t?t,md}", "file.txt"))
            assert(matches("file.{a\\,b,c}", "file.a,b"))
            assert(!matches("file.{a\\,b,c}", "file.a"))
        }

        "reject nested alternatives" in {
            val error = parseError("{a,{b,c}}")
            assert(error.offset == 3)
            assert(error.reason.contains("nested"))
        }
    }

    "escaping and separators" - {
        "backslash makes metacharacters literal" in {
            assert(matches("file\\*.txt", "file*.txt"))
            assert(matches("file\\?.txt", "file?.txt"))
            assert(matches("\\[literal\\]", "[literal]"))
            assert(matches("\\{a,b\\}", "{a,b}"))
            assert(!matches("file\\*.txt", "file1.txt"))
        }

        "slash is the separator on every platform" in {
            assert(matches("src/*", "src/Main.scala"))
            assert(!matches("src/*", "src/nested/Main.scala"))
            assert(!matches("src/*", "src\\Main.scala"))
            assert(matches("/tmp/", "/tmp/"))
            assert(!matches("/tmp/", "tmp"))
        }

        "parts are matched as complete path segments" in {
            val glob = parse("src/**/App.scala")
            assert(glob.matches(Chunk("src", "main", "App.scala"), Glob.CaseSensitivity.Sensitive))
            assert(!glob.matches(Chunk("src/main", "App.scala"), Glob.CaseSensitivity.Sensitive))
            assert(!parse("a\\/b").matches(Chunk("a/b"), Glob.CaseSensitivity.Sensitive))
            assert(parse("").matches(Chunk.empty[String], Glob.CaseSensitivity.Sensitive))
        }
    }

    "case sensitivity" in {
        val literal = parse("Src/[A-Z].SCALA")
        assert(literal.matches("Src/A.SCALA", Glob.CaseSensitivity.Sensitive))
        assert(!literal.matches("src/a.scala", Glob.CaseSensitivity.Sensitive))
        assert(literal.matches("src/a.scala", Glob.CaseSensitivity.Insensitive))
    }

    "dot-prefixed names have no hidden special case" in {
        assert(matches("*", ".gitignore"))
        assert(matches("**/*", ".git/config"))
    }

    "empty input and empty segments are matched explicitly" in {
        assert(matches("", ""))
        assert(matches("*", ""))
        assert(!matches("?", ""))
        assert(matches("a//b", "a//b"))
        assert(!matches("a/*/b", "a/b"))
    }

    "malformed syntax" - {
        "reports precise offsets and reasons" in {
            val cases = Chunk(
                ("[", 0, "unterminated character class"),
                ("[]", 0, "empty character class"),
                ("[z-a]", 2, "descending character range"),
                ("{", 0, "unterminated alternative"),
                ("{a}", 0, "at least two branches"),
                ("{a,}", 3, "empty alternative branch"),
                ("abc\\", 3, "dangling escape"),
                ("abc]", 3, "unmatched closing bracket"),
                ("abc}", 3, "unmatched closing brace")
            )
            cases.foreach { case (pattern, offset, reason) =>
                val error = parseError(pattern)
                assert(error.offset == offset)
                assert(error.reason.contains(reason))
            }
        }
    }

    "bounded complexity" - {
        "accepts patterns at the length limit and rejects patterns over it" in {
            val atLimit   = "a" * 4096
            val overLimit = atLimit + "a"
            assert(Glob.parse(atLimit).isSuccess)
            val error = parseError(overLimit)
            assert(error.offset == 4096)
            assert(error.reason.contains("maximum length of 4096"))
        }

        "accepts alternatives at the branch limit and rejects branches over it" in {
            val atLimit   = (1 to 256).map(_ => "a").mkString("{", ",", "}")
            val overLimit = (1 to 257).map(_ => "a").mkString("{", ",", "}")
            assert(Glob.parse(atLimit).isSuccess)
            val error = parseError(overLimit)
            assert(error.offset == overLimit.lastIndexOf(','))
            assert(error.reason.contains("maximum of 256 alternative branches"))
        }

        "matches adversarial wildcard input without backtracking" in {
            val pattern = (1 to 128).map(_ => "*a").mkString + "b"
            val input   = "a" * 2048
            assert(!matches(pattern, input))
        }
    }

    "literal interpolation" - {
        "valid literals compile" in {
            val errors = typeCheckErrors("""
                import kyo.*
                val value: Glob = glob"**/*.{scala,java}"
                """)
            assert(errors.isEmpty, s"valid glob literal failed to compile: $errors")
        }

        "invalid literals fail with the parse offset and reason" in {
            val errors = typeCheckErrors("""
                import kyo.*
                val value: Glob = glob"["
                """)
            assert(errors.nonEmpty)
            assert(errors.exists(_.message.contains("offset 0")))
            assert(errors.exists(_.message.contains("unterminated character class")))
        }
    }
end GlobTest
