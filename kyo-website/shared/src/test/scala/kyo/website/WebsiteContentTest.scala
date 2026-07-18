package kyo.website

import kyo.*

class WebsiteContentTest extends WebsiteTest:

    val version0 = WebsiteVersion("v0.1.0", "0.1.0", false)
    val version1 = WebsiteVersion("v1.0.0-RC2", "1.0.0-RC2", true)

    val moduleA = WebsiteModule("kyo-data", "Foundation", "kyo-data", "# kyo-data", WebsiteModule.Platforms(true, true, true, true))
    val moduleB = WebsiteModule("kyo-kernel", "Foundation", "kyo-kernel", "# kyo-kernel", WebsiteModule.Platforms(true, true, true, true))
    val moduleC =
        WebsiteModule("kyo-core", "Application runtime", "kyo-core", "# kyo-core", WebsiteModule.Platforms(true, true, false, false))

    "empty-groups WebsiteContent is valid" - {
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

    "in-memory render input carries no IO" - {
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

    // ---- fromRepo (render-from-tags) leaves ----

    /** Write `README.md` files into a fresh temp tree via the cross-platform kyo Path API, then run
      * `fromRepo` over it and return the `Result` (the Abort caught, never propagated to the test body).
      */
    private def fromRepoResult(
        files: Seq[(String, String)],
        version: WebsiteVersion = version1
    )(using Frame): Result[WebsiteException, WebsiteContent] < (Sync & Scope & Abort[FileException]) =
        Path.run {
            Path.tempDir("kyo-fromrepo-test").map { root =>
                Kyo.foreachDiscard(Chunk.from(files)) { case (rel, body) =>
                    (root / rel).write(body)
                }.map { _ =>
                    Abort.run[WebsiteException](WebsiteContent.fromRepo(root, version))
                }
            }
        }

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

    "fromRepo on a full tree parses groups" in {
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
                // 5-column legacy table (no WASM column), so wasm parses as false.
                assert(mod.platforms == WebsiteModule.Platforms(true, true, true, false), s"platforms: ${mod.platforms}")
            case other => fail(s"expected Success, got $other")
        end for
    }

    "fromRepo degrades on no ## Modules" in {
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

    "fromRepo aborts Missing on absent referenced README" in {
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

    "fromRepo aborts MalformedTable on a corrupt table" in {
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

    "fromRepo aborts MalformedGroups on a group heading with no table" in {
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

    "fromRepo preserves README group order" in {
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
                assert(bench.platforms == WebsiteModule.Platforms(true, false, false, false), s"bench platforms: ${bench.platforms}")
            case other => fail(s"expected Success, got $other")
        end for
    }

    "fromRepo overview is the full root README, rendered with fidelity not sliced" in {
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
              |
              |## Getting Started
              |
              |Add the dependency and go.
              |""".stripMargin
        for
            result <- fromRepoResult(Seq(
                "README.md"          -> readme,
                "kyo-data/README.md" -> "# kyo-data"
            ))
        yield result match
            case Result.Success(content) =>
                // Fidelity: the Overview is the entire README verbatim. Nothing is dropped: the title,
                // the introduction, the `## Modules` section, the module rows, AND every section after
                // `## Modules` are all kept. The post-Modules section is the regression guard: an earlier
                // slice silently truncated everything after the module table (Getting Started here).
                assert(content.intro == readme, s"overview must equal the full README verbatim: ${content.intro}")
                assert(content.intro.contains("# Kyo"), "overview keeps the title")
                assert(content.intro.contains("My intro text."), "overview keeps the introduction")
                assert(content.intro.contains("## Modules"), "overview keeps the modules section (no slicing)")
                assert(content.intro.contains("kyo-data"), "overview keeps the module rows")
                assert(content.intro.contains("## Getting Started"), "overview keeps sections after ## Modules")
                assert(content.intro.contains("Add the dependency and go."), "overview keeps post-modules prose")
                // The catalog still drives the sidebar (parseGroups), unaffected by the overview change.
                assert(content.groups.head.modules.head.slug == "kyo-data", "modules still parsed for the sidebar")
            case other => fail(s"expected Success, got $other")
        end for
    }

    // ---- Real-README format regression (locks the actual `## Modules` shape so the parser bugs
    //      fixed here cannot regress: slug is the DIRECTORY, platform columns are JVM/JS/Native/WASM) ----

    /** A copy of the real root README `## Modules` section's `### Core` group (README.md lines 268 to
      * 279): the `## Modules` heading and intro prose, the `### Core` heading and prose, then the GFM
      * table with the real `[kyo-core]` / `[kyo-system]` / `[kyo-prelude]` / `[kyo-data]` /
      * `[kyo-kernel]` / `[kyo-scheduler]` rows and the real `| Module | JVM | JS | Native | WASM |
      * Identity |` 6-column header (alignment padding included). The table rows are copied exactly so
      * the parser is tested against the current real format (including the WASM column added when
      * WebAssembly became a published platform), not a fixture written to the code's old three-platform
      * assumptions.
      */
    private val realCoreReadme =
        """# Kyo
          |
          |## Modules
          |
          |Every module ships its own README. Open the linked README for the full surface, features, callouts, and worked examples. The tables below name each module's identity in one sentence so you can pick the right one fast. Each identity cell names types and operations defined inside that module; expect unfamiliar names on first scan and treat the linked README as the source for what each one does. Platform columns mean published artifacts: ✅ marks the platforms each module is published for.
          |
          |### Core
          |
          |What every Kyo program uses. `kyo-core` and `kyo-prelude` carry the effects you touch most, `kyo-data` the value types they return. `kyo-kernel` defines `A < S` itself and is where effect authors look. `kyo-scheduler` is the engine fibers run on, also usable as a standalone jar. `kyo-data` also works standalone: `Maybe`, `Result`, and `Chunk` without the effect system.
          |
          || Module                                       | JVM | JS  | Native | WASM | Identity                                                                                                  |
          || -------------------------------------------- | --- | --- | ------ | ---- | --------------------------------------------------------------------------------------------------------- |
          || [kyo-core](kyo-core/README.md)               | ✅   | ✅   | ✅      | ✅   | I/O and concurrency: `Sync`, `Async`, `Scope`, `Fiber`, `Channel`, `Hub`, `Queue`, `Clock`, `Log`         |
          || [kyo-system](kyo-system/README.md)           | ✅  | ✅  | ✅     | ✅   | File system, OS processes, and environment: `Path`, `Command`, `Process`, `System`, `FileException`        |
          || [kyo-prelude](kyo-prelude/README.md)         | ✅   | ✅   | ✅      | ✅   | Strictly-pure effect layer: `Abort`, `Env`, `Var`, `Memo`, `Choice`, `Emit`, `Poll`, `Stream`, `Layer`    |
          || [kyo-data](kyo-data/README.md)               | ✅   | ✅   | ✅      | ✅   | Low-allocation values: `Maybe`, `Result`, `Chunk`, `Span`, `Duration`, `Instant`, `Schedule`, `TypeMap`  |
          || [kyo-kernel](kyo-kernel/README.md)           | ✅   | ✅   | ✅      | ✅   | Algebraic-effects substrate; defines `A < S`, `ArrowEffect`, `ContextEffect`, multi-shot continuations    |
          || [kyo-scheduler](kyo-scheduler/README.md)     | ✅   | ✅   | ✅      | ✅   | Adaptive work-stealing pool with automatic blocking detection and admission control                       |
          |""".stripMargin

    "fromRepo parses the real README Core group: slugs are directories, WASM column is read" in {
        for
            result <- fromRepoResult(Seq(
                "README.md"               -> realCoreReadme,
                "kyo-core/README.md"      -> "# kyo-core\n\nI/O and concurrency.",
                "kyo-system/README.md"    -> "# kyo-system\n\nFile system, OS processes, and environment.",
                "kyo-prelude/README.md"   -> "# kyo-prelude\n\nPure effect layer.",
                "kyo-data/README.md"      -> "# kyo-data\n\nLow-allocation values.",
                "kyo-kernel/README.md"    -> "# kyo-kernel\n\nEffect substrate.",
                "kyo-scheduler/README.md" -> "# kyo-scheduler\n\nWork-stealing pool."
            ))
        yield result match
            case Result.Success(content) =>
                assert(content.groups.size == 1, s"expected 1 group, got ${content.groups.size}")
                val core = content.groups.head
                assert(core.name == "Core", s"group name: ${core.name}")
                val slugs = core.modules.map(_.slug)
                // The slug is the module DIRECTORY, NOT the verbatim link target `kyo-core/README.md`.
                assert(
                    slugs == Chunk("kyo-core", "kyo-system", "kyo-prelude", "kyo-data", "kyo-kernel", "kyo-scheduler"),
                    s"slugs: $slugs"
                )
                assert(!slugs.exists(_.contains("README.md")), s"no slug may carry the README.md suffix: $slugs")
                // Every Core module is ✅ on all four platforms, WASM included (the real column values):
                // proves the 6-column table is parsed and the WASM cell is read, not dropped.
                core.modules.foreach { m =>
                    assert(
                        m.platforms == WebsiteModule.Platforms(true, true, true, true),
                        s"${m.slug} platforms: ${m.platforms}"
                    )
                }
                // The module READMEs are read from `root/<slug>/README.md` (no Missing abort).
                assert(
                    core.modules.map(_.readme.linesIterator.next()) ==
                        Chunk("# kyo-core", "# kyo-system", "# kyo-prelude", "# kyo-data", "# kyo-kernel", "# kyo-scheduler"),
                    "module READMEs must be read"
                )
            case other => fail(s"expected Success on the real README format, got $other")
        end for
    }

    /** A README mixing a current 6-column group (`JVM | JS | Native | WASM | Identity`) with a legacy
      * 5-column group (`JVM | JS | Native | Identity`), to lock the WASM-column parse: the 4th platform
      * cell is read as WASM only when the row carries it, and a legacy Identity cell that happens to hold
      * a checkmark is never misread as WASM support.
      */
    private val wasmColumnReadme =
        """# Kyo
          |
          |## Modules
          |
          |### Current
          || Module | JVM | JS | Native | WASM | Identity |
          || ------ | --- | -- | ------ | ---- | -------- |
          || [kyo-everywhere](kyo-everywhere/README.md) | ✅ | ✅ | ✅ | ✅ | All four platforms |
          || [kyo-nowasm](kyo-nowasm/README.md) | ✅ | ✅ | ✅ | ❌ | Not built for WASM |
          |
          |### Legacy
          || Module | JVM | JS | Native | Identity |
          || ------ | --- | -- | ------ | -------- |
          || [kyo-legacy](kyo-legacy/README.md) | ✅ | ✅ | ✅ | Ships a ✅ inside the identity cell |
          |""".stripMargin

    "fromRepo reads the WASM platform column and never misreads a legacy identity cell as WASM" in {
        for
            result <- fromRepoResult(Seq(
                "README.md"                -> wasmColumnReadme,
                "kyo-everywhere/README.md" -> "# kyo-everywhere",
                "kyo-nowasm/README.md"     -> "# kyo-nowasm",
                "kyo-legacy/README.md"     -> "# kyo-legacy"
            ))
        yield result match
            case Result.Success(content) =>
                assert(content.groups.map(_.name) == Chunk("Current", "Legacy"), s"groups: ${content.groups.map(_.name)}")
                val current = content.groups.head.modules
                val legacy  = content.groups(1).modules.head
                // 6-column group: WASM is read positionally (cell 4); ✅ -> true, ❌ -> false.
                assert(current.head.platforms == WebsiteModule.Platforms(true, true, true, true), s"everywhere: ${current.head.platforms}")
                assert(current(1).platforms == WebsiteModule.Platforms(true, true, true, false), s"nowasm: ${current(1).platforms}")
                // 5-column legacy group: cell 4 is the Identity prose (with a stray ✅), NOT a platform
                // column, so the `cells.size >= 6` guard keeps wasm = false instead of misreading it.
                assert(legacy.platforms == WebsiteModule.Platforms(true, true, true, false), s"legacy: ${legacy.platforms}")
            case other => fail(s"expected Success, got $other")
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

    "fromRepo degrades a bare-directory-link module with no README (kyo-examples), not aborts (deploy reality)" in {
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
                    modules.head.platforms == WebsiteModule.Platforms(true, true, true, false),
                    s"kyo-parse platforms: ${modules.head.platforms}"
                )
                assert(modules.head.readme == "# kyo-parse\n\nParser combinators.", s"kyo-parse readme: ${modules.head.readme}")
            case other => fail(s"expected Success (degrade kyo-examples, no abort), got $other")
        end for
    }

    "fromRepo still aborts Missing when a `<slug>/README.md` link's README is genuinely absent (typo guard)" in {
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

    // ---- tutorial attachment (WebsiteTutorials registry wired into buildModule) ----

    private val tutorialTreeReadme =
        """# Kyo
          |
          |## Modules
          |
          |### Application
          || Module | JVM | JS | Native | Identity |
          || ------ | --- | -- | ------ | -------- |
          || [kyo-eventlog](kyo-eventlog/README.md) | ✅ | ✅ | ✅ | Event streaming |
          |""".stripMargin

    "fromRepo attaches the three registered tutorials to the parsed kyo-eventlog module with loaded content" in {
        for
            result <- fromRepoResult(Seq(
                "README.md"                                     -> tutorialTreeReadme,
                "kyo-eventlog/README.md"                        -> "# kyo-eventlog\n\nEvent streaming.",
                "kyo-eventlog/docs/tutorials/basic-eventlog.md" -> "# Basic EventLog\n\nTyped event log.",
                "kyo-eventlog/docs/tutorials/raw-journal.md"    -> "# Raw Journal\n\nEnvelope layer.",
                "kyo-eventlog/docs/tutorials/custom-storage.md" -> "# Custom storage\n\nBackend SPI."
            ))
        yield result match
            case Result.Success(content) =>
                val module = content.groups.head.modules.head
                assert(module.slug == "kyo-eventlog", s"module slug: ${module.slug}")
                assert(module.tutorials.size == 3, s"expected 3 tutorials, got ${module.tutorials.size}")
                assert(
                    module.tutorials.map(_.slug) == Chunk("basic-eventlog", "raw-journal", "custom-storage"),
                    s"tutorial slugs: ${module.tutorials.map(_.slug)}"
                )
                assert(
                    module.tutorials.map(_.title) == Chunk("Basic EventLog", "Raw Journal", "Custom storage"),
                    s"tutorial titles: ${module.tutorials.map(_.title)}"
                )
                assert(content.tutorials.size == 3, s"expected 3 loaded tutorials, got ${content.tutorials.size}")
                assert(
                    content.tutorials.map(_.module) == Chunk("kyo-eventlog", "kyo-eventlog", "kyo-eventlog"),
                    s"tutorial modules: ${content.tutorials.map(_.module)}"
                )
                assert(content.tutorials.forall(_.content.nonEmpty), "every loaded tutorial must carry non-empty content")
            case other => fail(s"expected Success, got $other")
        end for
    }

    private val unregisteredModuleReadme =
        """# Kyo
          |
          |## Modules
          |
          |### Foundation
          || Module | JVM | JS | Native | Identity |
          || ------ | --- | -- | ------ | -------- |
          || [kyo-data](kyo-data/README.md) | ✅ | ✅ | ✅ | Data types |
          |""".stripMargin

    "fromRepo leaves a co-listed unregistered module with an empty tutorial rail" in {
        for
            result <- fromRepoResult(Seq(
                "README.md"          -> unregisteredModuleReadme,
                "kyo-data/README.md" -> "# kyo-data\n\nData types."
            ))
        yield result match
            case Result.Success(content) =>
                val module = content.groups.head.modules.head
                assert(module.slug == "kyo-data", s"module slug: ${module.slug}")
                assert(module.tutorials.isEmpty, s"expected an empty tutorial rail, got ${module.tutorials}")
                assert(content.tutorials.isEmpty, s"expected no loaded tutorials, got ${content.tutorials}")
            case other => fail(s"expected Success, got $other")
        end for
    }

end WebsiteContentTest
