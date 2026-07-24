package kyo.doctest.internal

import kyo.*

/** Tests for MarkdownParser covering block extraction, HTML wrapper transparency, HTML comment blocks, and line position tracking.
  *
  * Fixture files are used for most tests; inline strings are used where the content is simpler to express directly.
  */
class MarkdownParserTest extends kyo.doctest.DoctestTest:

    private val dummyFile: kyo.Path = kyo.Path("test.md")

    // Helper: load a fixture file as a kyo.Path.
    // getResource returns a java.net.URL; we convert to a filesystem path via URI.getPath.
    private def fixturePath(name: String)(using kyo.test.AssertScope): kyo.Path =
        val url = getClass.getResource(s"/parser-fixtures/$name")
        assert(url != null, s"fixture file not found: $name")
        kyo.Path(url.toURI.getPath)
    end fixturePath

    "bare scala block extracted with default modifiers (Isolated, Compiles, all platforms)" in {
        val path = fixturePath("01-bare-default.md")
        Abort.run(MarkdownParser.parse(path)).map {
            case Result.Success(blocks) =>
                assert(blocks.size >= 1, s"expected at least 1 block, got ${blocks.size}")
                val first = blocks(0)
                assert(first.visibility == Block.Visibility.Isolated)
                assert(first.expect == Block.Expectation.Compiles)
                assert(first.platform == Set(Block.Target.JVM, Block.Target.JS, Block.Target.Native))
                assert(first.carrier == Block.Carrier.Visible)
            case Result.Failure(err) =>
                fail(s"unexpected parse error: $err")
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getMessage}")
        }
    }

    "bare sbt block extracted and treated as Scala source" in {
        val content =
            """|# Example
               |
               |```sbt
               |val x = 42
               |```
               |""".stripMargin
        Abort.run(MarkdownParser.parseString(content, dummyFile)).map {
            case Result.Success(blocks) =>
                assert(blocks.size == 1, s"expected 1 block, got ${blocks.size}")
                val block = blocks(0)
                // sbt blocks are treated identically to scala blocks
                assert(block.visibility == Block.Visibility.Isolated)
                assert(block.expect == Block.Expectation.Compiles)
                assert(block.platform == Set(Block.Target.JVM, Block.Target.JS, Block.Target.Native))
                assert(block.carrier == Block.Carrier.Visible)
                assert(block.body == "val x = 42")
            case Result.Failure(err) =>
                fail(s"unexpected parse error: $err")
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getMessage}")
        }
    }

    "bash, markdown, and text blocks are not extracted" in {
        val path = fixturePath("03-non-scala-ignored.md")
        Abort.run(MarkdownParser.parse(path)).map {
            case Result.Success(blocks) =>
                assert(blocks.isEmpty, s"expected no blocks, got ${blocks.size}")
            case Result.Failure(err) =>
                fail(s"unexpected parse error: $err")
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getMessage}")
        }
    }

    "<details> wrap with one block gives Block.Carrier.Visible" in {
        val path = fixturePath("04-details-single-block.md")
        Abort.run(MarkdownParser.parse(path)).map {
            case Result.Success(blocks) =>
                assert(blocks.size == 1, s"expected 1 block, got ${blocks.size}")
                assert(blocks(0).carrier == Block.Carrier.Visible)
                assert(blocks(0).body == "val setup = true")
            case Result.Failure(err) =>
                fail(s"unexpected parse error: $err")
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getMessage}")
        }
    }

    "multiple scala blocks inside an HTML wrapper all parse as Carrier.Visible" in {
        val path = fixturePath("05-details-multi-block.md")
        Abort.run(MarkdownParser.parse(path)).map {
            case Result.Success(blocks) =>
                assert(blocks.size == 2, s"expected 2 blocks, got ${blocks.size}")
                assert(blocks(0).carrier == Block.Carrier.Visible)
                assert(blocks(1).carrier == Block.Carrier.Visible)
            case Result.Failure(err) =>
                fail(s"unexpected parse error: $err")
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getMessage}")
        }
    }

    "prose between code blocks is ignored" in {
        val path = fixturePath("05-details-multi-block.md")
        Abort.run(MarkdownParser.parse(path)).map {
            case Result.Success(blocks) =>
                assert(blocks.size == 2, s"expected 2 blocks, got ${blocks.size}")
                assert(blocks(0).body == "val a = 1")
                assert(blocks(1).body == "val b = 2")
            case Result.Failure(err) =>
                fail(s"unexpected parse error: $err")
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getMessage}")
        }
    }

    "non-scala code fences are ignored" in {
        val path = fixturePath("05-details-multi-block.md")
        Abort.run(MarkdownParser.parse(path)).map {
            case Result.Success(blocks) =>
                // Only 2 scala blocks; the bash block is ignored
                assert(blocks.size == 2)
                assert(blocks.forall(_.carrier == Block.Carrier.Visible))
            case Result.Failure(err) =>
                fail(s"unexpected parse error: $err")
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getMessage}")
        }
    }

    "unrecognized HTML wrapper tags do not affect block parsing" in {
        val path = fixturePath("08-html-comment-setup.md")
        Abort.run(MarkdownParser.parse(path)).map {
            case Result.Success(blocks) =>
                // fixture 08 has: 1 block inside nested HTML wrappers (Visible) + 2 HTML comment blocks (Hidden)
                assert(blocks.size == 3, s"expected 3 blocks, got ${blocks.size}")
                val visibleBlocks = blocks.filter(_.carrier == Block.Carrier.Visible)
                assert(visibleBlocks.size == 1, s"expected 1 Visible block")
                assert(visibleBlocks(0).body == "val inner = 1")
            case Result.Failure(err) =>
                fail(s"unexpected parse error: $err")
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getMessage}")
        }
    }

    "HTML comment doctest:setup produces Block.Carrier.Hidden block with Env(\"__doc__\") scope" in {
        val path = fixturePath("08-html-comment-setup.md")
        Abort.run(MarkdownParser.parse(path)).map {
            case Result.Success(blocks) =>
                val commentBlocks = blocks.filter(_.carrier == Block.Carrier.Hidden)
                assert(commentBlocks.size == 2, s"expected 2 HtmlComment blocks, got ${commentBlocks.size}")
                // First comment block has setup modifier -> scope=Env("__doc__")
                assert(commentBlocks(0).visibility == Block.Visibility.Env("__doc__"))
                assert(commentBlocks(0).body == "val fixture = \"test\"")
            case Result.Failure(err) =>
                fail(s"unexpected parse error: $err")
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getMessage}")
        }
    }

    "HTML comment with doctest:expect=fails-compile produces FailsCompile expectation" in {
        val path = fixturePath("08-html-comment-setup.md")
        Abort.run(MarkdownParser.parse(path)).map {
            case Result.Success(blocks) =>
                val commentBlocks = blocks.filter(_.carrier == Block.Carrier.Hidden)
                assert(commentBlocks.size == 2)
                // Second comment block has expect=fails-compile modifier
                assert(commentBlocks(1).expect == Block.Expectation.FailsCompile)
                assert(commentBlocks(1).body == "val bad: Int = \"oops\"")
            case Result.Failure(err) =>
                fail(s"unexpected parse error: $err")
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getMessage}")
        }
    }

    "multiple HTML comment blocks each produce distinct Hidden blocks" in {
        val path = fixturePath("08-html-comment-setup.md")
        Abort.run(MarkdownParser.parse(path)).map {
            case Result.Success(blocks) =>
                val commentBlocks = blocks.filter(_.carrier == Block.Carrier.Hidden)
                assert(commentBlocks.size == 2, s"expected 2 HtmlComment blocks, got ${commentBlocks.size}")
                // Each comment block produces exactly one block with distinct bodies
                assert(commentBlocks(0).body != commentBlocks(1).body)
            case Result.Failure(err) =>
                fail(s"unexpected parse error: $err")
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getMessage}")
        }
    }

    "lineStart=3 and lineEnd=5 for first block in 01-bare-default.md" in {
        val path = fixturePath("01-bare-default.md")
        Abort.run(MarkdownParser.parse(path)).map {
            case Result.Success(blocks) =>
                assert(blocks.size >= 1)
                val first = blocks(0)
                // 01-bare-default.md:
                //   line 1: # Example
                //   line 2: (blank)
                //   line 3: ```scala    <- lineStart
                //   line 4: val x = 42
                //   line 5: ```         <- lineEnd
                assert(first.lineStart == 3, s"expected lineStart=3 but got ${first.lineStart}")
                assert(first.lineEnd == 5, s"expected lineEnd=5 but got ${first.lineEnd}")
            case Result.Failure(err) =>
                fail(s"unexpected parse error: $err")
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getMessage}")
        }
    }

    "consecutive blocks have distinct non-overlapping line ranges" in {
        val path = fixturePath("01-bare-default.md")
        Abort.run(MarkdownParser.parse(path)).map {
            case Result.Success(blocks) =>
                assert(blocks.size == 2, s"expected 2 blocks, got ${blocks.size}")
                val first  = blocks(0)
                val second = blocks(1)
                assert(
                    first.lineEnd < second.lineStart,
                    s"blocks overlap: first.lineEnd=${first.lineEnd} second.lineStart=${second.lineStart}"
                )
                // 01-bare-default.md:
                //   line 6: ```scala    <- second lineStart
                //   line 7: val y = "hello"
                //   line 8: ```         <- second lineEnd
                assert(second.lineStart == 6, s"expected second block lineStart=6 but got ${second.lineStart}")
            case Result.Failure(err) =>
                fail(s"unexpected parse error: $err")
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getMessage}")
        }
    }

    "empty README returns empty Chunk" in {
        Abort.run(MarkdownParser.parseString("", dummyFile)).map {
            case Result.Success(blocks) =>
                assert(blocks.isEmpty)
            case Result.Failure(err) =>
                fail(s"unexpected parse error: $err")
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getMessage}")
        }
    }

end MarkdownParserTest
