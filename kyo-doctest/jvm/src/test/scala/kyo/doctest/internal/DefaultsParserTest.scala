package kyo.doctest.internal

import kyo.*
import kyo.doctest.*

/** Tests for DefaultsParser covering per-file default modifier parsing.
  *
  * These tests use fixture 09-per-readme-defaults.md.
  */
class DefaultsParserTest extends Test:

    private val dummyFile: kyo.Path = kyo.Path("test.md")

    // Helper: load a fixture file as a kyo.Path.
    // getResource returns a java.net.URL; we convert to a filesystem path via URI.getPath.
    private def fixturePath(name: String): kyo.Path =
        val url = getClass.getResource(s"/parser-fixtures/$name")
        assert(url != null, s"fixture file not found: $name")
        kyo.Path(url.toURI.getPath)
    end fixturePath

    "per-README defaults block applies expect=runs and scope=inherited to subsequent blocks" in run {
        val path = fixturePath("09-per-readme-defaults.md")
        Abort.run(MarkdownParser.parse(path)).map {
            case Result.Success(blocks) =>
                assert(blocks.size == 2, s"expected 2 blocks, got ${blocks.size}")
                // First block has no per-block modifiers: should use per-file defaults
                val first = blocks(0)
                assert(first.expect == Block.Expectation.Runs, s"expected Runs but got ${first.expect}")
                assert(first.visibility == Block.Visibility.Inherited, s"expected Inherited but got ${first.visibility}")
            case Result.Failure(err) =>
                fail(s"unexpected parse error: $err")
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getMessage}")
        }
    }

    "per-block scope=isolated overrides per-README default scope=inherited" in run {
        val path = fixturePath("09-per-readme-defaults.md")
        Abort.run(MarkdownParser.parse(path)).map {
            case Result.Success(blocks) =>
                assert(blocks.size == 2, s"expected 2 blocks, got ${blocks.size}")
                // Second block has doctest:scope=isolated which overrides the default scope=inherited
                val second = blocks(1)
                assert(second.visibility == Block.Visibility.Isolated, s"expected Isolated but got ${second.visibility}")
                // The per-file default for expect is Runs: should still apply here
                assert(second.expect == Block.Expectation.Runs, s"expected Runs but got ${second.expect}")
            case Result.Failure(err) =>
                fail(s"unexpected parse error: $err")
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getMessage}")
        }
    }

    // Additional: DefaultsParser.parse returns empty modifiers when no defaults block is present
    "DefaultsParser.parse returns empty modifiers when no defaults block is present" in run {
        val content = "# Just a heading\n\nSome prose.\n"
        Abort.run(DefaultsParser.parse(content, dummyFile)).map {
            case Result.Success(mods) =>
                assert(mods == ModifierParser.Parsed.empty)
            case Result.Failure(err) =>
                fail(s"unexpected parse error: $err")
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getMessage}")
        }
    }

    // Additional: DefaultsParser.parse handles a single-line defaults block
    "DefaultsParser.parse handles single-line defaults block" in run {
        val content = "<!-- doctest:default expect=runs -->\n\n# Content\n"
        Abort.run(DefaultsParser.parse(content, dummyFile)).map {
            case Result.Success(mods) =>
                assert(mods.expect == Present(Block.Expectation.Runs))
                assert(mods.scope == Absent)
            case Result.Failure(err) =>
                fail(s"unexpected parse error: $err")
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getMessage}")
        }
    }

end DefaultsParserTest
