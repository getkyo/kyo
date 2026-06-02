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
          || Module | Description | JVM | JS | Native |
          || ------ | ----------- | --- | -- | ------ |
          || [kyo-data](kyo-data) | Data types | ✅ | ✅ | ✅ |
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
              || Module | Description | JVM | JS | Native |
              || ------ | ----------- | --- | -- | ------ |
              || [kyo-x](kyo-x) | Missing module | ✅ | ✅ | ✅ |
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
              || Module | Description | JVM | JS | Native |
              || ------ | ----------- | --- | -- | ------ |
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
              || Module | Description | JVM | JS | Native |
              || ------ | ----------- | --- | -- | ------ |
              || [kyo-data](kyo-data) | Data | ✅ | ✅ | ✅ |
              |
              |### Tooling
              || Module | Description | JVM | JS | Native |
              || ------ | ----------- | --- | -- | ------ |
              || [kyo-bench](kyo-bench) | Bench | ✅ | ❌ | ❌ |
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
              || Module | Description | JVM | JS | Native |
              || ------ | ----------- | --- | -- | ------ |
              || [kyo-data](kyo-data) | Data | ✅ | ✅ | ✅ |
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

end WebsiteContentTest
