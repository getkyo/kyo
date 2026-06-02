package kyo.website

import kyo.*

class WebsiteContentTest extends Test:

    val version0 = WebsiteVersion("v0.1.0", "0.1.0", false)
    val version1 = WebsiteVersion("v1.0.0-RC2", "1.0.0-RC2", true)

    val moduleA = WebsiteModule("kyo-data", "Foundation", "kyo-data", "# kyo-data", WebsiteModule.Platforms(true, true, true))
    val moduleB = WebsiteModule("kyo-kernel", "Foundation", "kyo-kernel", "# kyo-kernel", WebsiteModule.Platforms(true, true, true))
    val moduleC = WebsiteModule("kyo-core", "Application runtime", "kyo-core", "# kyo-core", WebsiteModule.Platforms(true, true, false))

    "empty-groups WebsiteContent is valid (INV-007 model-shape half)" - {
        "groups.isEmpty is true" in {
            val c = WebsiteContent("intro text", Chunk.empty, version0)
            assert(c.groups.isEmpty == true)
        }

        "intro is preserved" in {
            val c = WebsiteContent("intro text", Chunk.empty, version0)
            assert(c.intro == "intro text")
        }
    }

    "non-empty grouped content" - {
        "groups.head.modules.size is correct" in {
            val g1 = WebsiteContent.Group("Foundation", Chunk(moduleA, moduleB))
            val g2 = WebsiteContent.Group("Application runtime", Chunk(moduleC))
            val c  = WebsiteContent("intro", Chunk(g1, g2), version1)
            assert(c.groups.head.modules.size == 2)
        }

        "group order is preserved" in {
            val g1 = WebsiteContent.Group("Foundation", Chunk(moduleA, moduleB))
            val g2 = WebsiteContent.Group("Application runtime", Chunk(moduleC))
            val c  = WebsiteContent("intro", Chunk(g1, g2), version1)
            assert(c.groups.map(_.name) == Chunk("Foundation", "Application runtime"))
        }
    }

    "in-memory render input carries no IO (INV-006 signature half)" - {
        "all fields are plain values" in {
            val c = WebsiteContent("intro", Chunk.empty, version1)
            // Accessing fields requires no effect row: they are plain String/Chunk/WebsiteVersion values.
            val _: String                      = c.intro
            val _: Chunk[WebsiteContent.Group] = c.groups
            val _: WebsiteVersion              = c.version
            succeed
        }
    }

    "Group equality and CanEqual" - {
        "two equal Groups are equal" in {
            val g1 = WebsiteContent.Group("Foundation", Chunk(moduleA))
            val g2 = WebsiteContent.Group("Foundation", Chunk(moduleA))
            assert(g1 == g2)
        }

        "a Group with a different module is not equal" in {
            val g1 = WebsiteContent.Group("Foundation", Chunk(moduleA))
            val g2 = WebsiteContent.Group("Foundation", Chunk(moduleB))
            assert(g1 != g2)
        }
    }

    // ---- fromRepo (render-from-tags) leaves (Phase 7) ----

    /** Write `README.md` files into a fresh temp tree via the cross-platform kyo Path API, then run
      * `fromRepo` over it and return the `Result` (the Abort caught, never propagated to the test body).
      */
    private def fromRepoResult(
        files: Seq[(String, String)],
        version: WebsiteVersion = version1
    )(using Frame): Result[WebsiteException, WebsiteContent] < (Sync & Abort[FileFsException | FileWriteException]) =
        for
            root <- Path.tempDir("kyo-fromrepo-test")
            _ <- Kyo.foreachDiscard(Chunk.from(files)) { case (rel, body) =>
                (root / rel).write(body)
            }
            result <- Abort.run[WebsiteException](WebsiteContent.fromRepo(root, version))
        yield result

    private val fullTreeReadme =
        """# Kyo
          |
          |## Introduction
          |
          |Kyo is an effect system.
          |
          |## Modules
          |
          |### Foundation
          || Module | JVM | JS | Native | Identity |
          || ------ | --- | -- | ------ | -------- |
          || [kyo-data](kyo-data/README.md) | ✅ | ✅ | ✅ | Data types |
          |""".stripMargin

    "fromRepo on a full tree parses groups (INV-006) (P7-1)" in run {
        for
            result <- fromRepoResult(Seq(
                "README.md"          -> fullTreeReadme,
                "kyo-data/README.md" -> "# kyo-data\n\nData types."
            ))
        yield result match
            case Result.Success(content) =>
                assert(content.groups.size == 1, s"expected 1 group, got ${content.groups.size}")
                assert(content.groups.head.name == "Foundation", s"group name: ${content.groups.head.name}")
                assert(content.groups.head.modules.size == 1, "expected 1 module")
                val mod = content.groups.head.modules.head
                assert(mod.slug == "kyo-data", s"slug: ${mod.slug}")
                assert(mod.readme == "# kyo-data\n\nData types.", s"module readme: ${mod.readme}")
                assert(mod.platforms == WebsiteModule.Platforms(true, true, true), s"platforms: ${mod.platforms}")
            case other => fail(s"expected Success, got $other")
        end for
    }

    "fromRepo degrades on no ## Modules (INV-007) (P7-2)" in run {
        for
            result <- fromRepoResult(Seq(
                "README.md" -> "# Kyo\n\n## Introduction\n\nAn old-tag README with no module table.\n"
            ))
        yield result match
            case Result.Success(content) =>
                assert(content.groups == Chunk.empty, s"expected empty groups, got ${content.groups}")
                assert(content.intro.contains("old-tag README"), s"intro: ${content.intro}")
                assert(!content.intro.contains("## Modules"), "intro must not contain the modules marker")
            case other => fail(s"expected Success (degrade, no abort), got $other")
        end for
    }

    "fromRepo aborts Missing on absent referenced README (INV-007) (P7-3)" in run {
        // README references kyo-x/README.md but only kyo-data/README.md exists.
        val readme =
            """# Kyo
              |
              |## Modules
              |
              |### Foundation
              || Module | JVM | JS | Native | Identity |
              || ------ | --- | -- | ------ | -------- |
              || [kyo-x](kyo-x/README.md) | ✅ | ✅ | ✅ | Missing module |
              |""".stripMargin
        for
            result <- fromRepoResult(Seq("README.md" -> readme))
        yield result match
            case Result.Failure(e: WebsiteReadmeException) =>
                assert(e.detail == WebsiteReadmeException.ReadmeFailure.Missing, s"expected Missing, got ${e.detail}")
            case other => fail(s"expected Failure(WebsiteReadmeException Missing), got $other")
        end for
    }

    "fromRepo aborts MalformedTable on a corrupt table (P7-4)" in run {
        // The module row has too few pipe cells (only 2), so it cannot be parsed.
        val readme =
            """# Kyo
              |
              |## Modules
              |
              |### Foundation
              || Module | JVM | JS | Native | Identity |
              || ------ | --- | -- | ------ | -------- |
              || just-one-cell |
              |""".stripMargin
        for
            result <- fromRepoResult(Seq("README.md" -> readme))
        yield result match
            case Result.Failure(e: WebsiteReadmeException) =>
                assert(e.detail == WebsiteReadmeException.ReadmeFailure.MalformedTable, s"expected MalformedTable, got ${e.detail}")
            case other => fail(s"expected Failure(WebsiteReadmeException MalformedTable), got $other")
        end for
    }

    "fromRepo aborts MalformedGroups on a group heading with no table (P7-4b)" in run {
        // The `### Foundation` heading inside `## Modules` is followed by prose, not a GFM pipe table,
        // so buildGroup finds zero pipe rows and aborts MalformedGroups (distinct from MalformedTable,
        // which is a present-but-corrupt table row).
        val readme =
            """# Kyo
              |
              |## Modules
              |
              |### Foundation
              |This group heading has no module table beneath it, only this sentence.
              |""".stripMargin
        for
            result <- fromRepoResult(Seq("README.md" -> readme))
        yield result match
            case Result.Failure(e: WebsiteReadmeException) =>
                assert(
                    e.detail == WebsiteReadmeException.ReadmeFailure.MalformedGroups,
                    s"expected MalformedGroups, got ${e.detail}"
                )
            case other => fail(s"expected Failure(WebsiteReadmeException MalformedGroups), got $other")
        end for
    }

    "fromRepo preserves README group order (P7-5)" in run {
        val readme =
            """# Kyo
              |
              |## Modules
              |
              |### Foundation
              || Module | JVM | JS | Native | Identity |
              || ------ | --- | -- | ------ | -------- |
              || [kyo-data](kyo-data/README.md) | ✅ | ✅ | ✅ | Data |
              |
              |### Tooling
              || Module | JVM | JS | Native | Identity |
              || ------ | --- | -- | ------ | -------- |
              || [kyo-bench](kyo-bench/README.md) | ✅ | ❌ | ❌ | Bench |
              |""".stripMargin
        for
            result <- fromRepoResult(Seq(
                "README.md"           -> readme,
                "kyo-data/README.md"  -> "# kyo-data",
                "kyo-bench/README.md" -> "# kyo-bench"
            ))
        yield result match
            case Result.Success(content) =>
                assert(content.groups.map(_.name) == Chunk("Foundation", "Tooling"), s"group order: ${content.groups.map(_.name)}")
                val bench = content.groups(1).modules.head
                assert(bench.platforms == WebsiteModule.Platforms(true, false, false), s"bench platforms: ${bench.platforms}")
            case other => fail(s"expected Success, got $other")
        end for
    }

    "fromRepo intro is between Introduction and Modules (P7-6)" in run {
        val readme =
            """# Kyo
              |
              |## Introduction
              |
              |My intro text.
              |
              |## Modules
              |
              |### Foundation
              || Module | JVM | JS | Native | Identity |
              || ------ | --- | -- | ------ | -------- |
              || [kyo-data](kyo-data/README.md) | ✅ | ✅ | ✅ | Data |
              |""".stripMargin
        for
            result <- fromRepoResult(Seq(
                "README.md"          -> readme,
                "kyo-data/README.md" -> "# kyo-data"
            ))
        yield result match
            case Result.Success(content) =>
                assert(content.intro.contains("My intro text."), s"intro: ${content.intro}")
                assert(!content.intro.contains("## Modules"), s"intro must not contain the modules table: ${content.intro}")
                assert(!content.intro.contains("kyo-data"), s"intro must not contain module rows: ${content.intro}")
            case other => fail(s"expected Success, got $other")
        end for
    }

    // ---- Real-README format regression (locks the actual `## Modules` shape so the parser bugs
    //      fixed here cannot regress: slug is the DIRECTORY, platform columns are JVM/JS/Native) ----

    /** A VERBATIM copy of the real root README `## Modules` section's `### Foundation` group
      * (README.md lines 223 to 235): the `## Modules` heading and intro prose, the `### Foundation`
      * heading and prose, then the GFM table with the real `[kyo-data](kyo-data/README.md)` /
      * `[kyo-kernel](kyo-kernel/README.md)` / `[kyo-prelude](kyo-prelude/README.md)` rows and the real
      * `| Module | JVM | JS | Native | Identity |` header. Copied exactly, alignment padding included,
      * so the parser is tested against the real format, not a fixture written to the code's old
      * assumptions.
      */
    private val realFoundationReadme =
        """# Kyo
          |
          |## Modules
          |
          |Every module ships its own README. Open the linked README for the full surface, capabilities, callouts, and worked examples. The tables below name each module's identity in one sentence so you can pick the right one fast. Each identity cell names types and operations defined inside that module; expect unfamiliar names on first scan and treat the linked README as the source for what each one does. Platform columns mean published artifacts: ✅ = supported, ❌ = not built for that platform.
          |
          |### Foundation
          |
          |The substrate the rest of the ecosystem builds on. Most application code never depends on these directly; they ride in transitively through `kyo-core`.
          |
          || Module                                       | JVM | JS  | Native | Identity                                                                                                  |
          || -------------------------------------------- | --- | --- | ------ | --------------------------------------------------------------------------------------------------------- |
          || [kyo-data](kyo-data/README.md)               | ✅   | ✅   | ✅      | Low-allocation values: `Maybe`, `Result`, `Chunk`, `Span`, `Duration`, `Instant`, `Schedule`, `TypeMap`  |
          || [kyo-kernel](kyo-kernel/README.md)           | ✅   | ✅   | ✅      | Algebraic-effects substrate; defines `A < S`, `ArrowEffect`, `ContextEffect`, multi-shot continuations    |
          || [kyo-prelude](kyo-prelude/README.md)         | ✅   | ✅   | ✅      | Strictly-pure effect layer: `Abort`, `Env`, `Var`, `Memo`, `Choice`, `Emit`, `Poll`, `Stream`, `Layer`    |
          |""".stripMargin

    "fromRepo parses the real README Foundation group: slugs are directories, not link targets" in run {
        for
            result <- fromRepoResult(Seq(
                "README.md"             -> realFoundationReadme,
                "kyo-data/README.md"    -> "# kyo-data\n\nLow-allocation values.",
                "kyo-kernel/README.md"  -> "# kyo-kernel\n\nEffect substrate.",
                "kyo-prelude/README.md" -> "# kyo-prelude\n\nPure effect layer."
            ))
        yield result match
            case Result.Success(content) =>
                assert(content.groups.size == 1, s"expected 1 group, got ${content.groups.size}")
                val foundation = content.groups.head
                assert(foundation.name == "Foundation", s"group name: ${foundation.name}")
                val slugs = foundation.modules.map(_.slug)
                // The slug is the module DIRECTORY, NOT the verbatim link target `kyo-data/README.md`.
                assert(slugs == Chunk("kyo-data", "kyo-kernel", "kyo-prelude"), s"slugs: $slugs")
                assert(!slugs.exists(_.contains("README.md")), s"no slug may carry the README.md suffix: $slugs")
                // Every Foundation module is ✅ on all three platforms (the real column values).
                foundation.modules.foreach { m =>
                    assert(
                        m.platforms == WebsiteModule.Platforms(true, true, true),
                        s"${m.slug} platforms: ${m.platforms}"
                    )
                }
                // The module READMEs are read from `root/<slug>/README.md` (no Missing abort).
                assert(
                    foundation.modules.map(_.readme.linesIterator.next()) == Chunk("# kyo-data", "# kyo-kernel", "# kyo-prelude"),
                    "module READMEs must be read"
                )
            case other => fail(s"expected Success on the real README format, got $other")
        end for
    }

    // ---- Directory-link degrade (the real deploy case: the root README lists `kyo-examples` and
    //      `kyo-bench` as bare-directory links with no `<slug>/README.md`; the tag extraction ships no
    //      README for them, so they must degrade out, not abort the whole site) ----

    /** A `### Domain modules` group mixing a normal `<slug>/README.md` module (`kyo-parse`) with a
      * bare-directory-link module (`[kyo-examples](kyo-examples)`), exactly the shape the real
      * README.md line 332 uses. The deploy's `git archive` README pathspec extracts no
      * `kyo-examples/README.md` (none exists), so the extracted tree has no such file.
      */
    private val directoryLinkReadme =
        """# Kyo
          |
          |## Modules
          |
          |### Domain modules
          || Module | JVM | JS | Native | Identity |
          || ------ | --- | -- | ------ | -------- |
          || [kyo-parse](kyo-parse/README.md) | ✅ | ✅ | ✅ | Parser combinators |
          || [kyo-examples](kyo-examples) | ✅ | ❌ | ❌ | Two runnable programs |
          |""".stripMargin

    "fromRepo degrades a bare-directory-link module with no README (kyo-examples), not aborts (deploy reality)" in run {
        // Only kyo-parse has a README; kyo-examples is a directory link with no kyo-examples/README.md,
        // mirroring the extracted v1.0.0-RC2 tree. This must NOT abort Missing: kyo-examples is dropped,
        // kyo-parse is parsed.
        for
            result <- fromRepoResult(Seq(
                "README.md"           -> directoryLinkReadme,
                "kyo-parse/README.md" -> "# kyo-parse\n\nParser combinators."
            ))
        yield result match
            case Result.Success(content) =>
                assert(content.groups.size == 1, s"expected 1 group, got ${content.groups.size}")
                val modules = content.groups.head.modules
                // kyo-examples degrades out (no doc-page README); only kyo-parse remains.
                assert(modules.map(_.slug) == Chunk("kyo-parse"), s"expected only kyo-parse, got ${modules.map(_.slug)}")
                assert(!modules.exists(_.slug == "kyo-examples"), "kyo-examples must be dropped, not rendered")
                assert(
                    modules.head.platforms == WebsiteModule.Platforms(true, true, true),
                    s"kyo-parse platforms: ${modules.head.platforms}"
                )
                assert(modules.head.readme == "# kyo-parse\n\nParser combinators.", s"kyo-parse readme: ${modules.head.readme}")
            case other => fail(s"expected Success (degrade kyo-examples, no abort), got $other")
        end for
    }

    "fromRepo still aborts Missing when a `<slug>/README.md` link's README is genuinely absent (typo guard, P7-3 preserved)" in run {
        // A README-link (`[kyo-typo](kyo-typo/README.md)`) whose file is absent is a real breakage
        // (a typo or a forgotten file), distinct from a bare-directory link: it must still abort
        // Missing so the degrade does not swallow genuine errors.
        val readme =
            """# Kyo
              |
              |## Modules
              |
              |### Foundation
              || Module | JVM | JS | Native | Identity |
              || ------ | --- | -- | ------ | -------- |
              || [kyo-typo](kyo-typo/README.md) | ✅ | ✅ | ✅ | Typo'd README link |
              |""".stripMargin
        for
            result <- fromRepoResult(Seq("README.md" -> readme))
        yield result match
            case Result.Failure(e: WebsiteReadmeException) =>
                assert(e.detail == WebsiteReadmeException.ReadmeFailure.Missing, s"expected Missing, got ${e.detail}")
            case other => fail(s"expected Failure(WebsiteReadmeException Missing) for an absent README-link target, got $other")
        end for
    }

end WebsiteContentTest
