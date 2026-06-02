package kyo.website

import kyo.*
import kyo.internal.BaseKyoCoreTest
import kyo.internal.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext

/** Tests for [[DocsClient]] using a stubbed fetch function.
  *
  * Runs in JS only (JS placement via `kyo-website-bundle/js/src/test/`). The real
  * `DocsClient.fetchFn` is replaced with a synchronous stub before each test; it is
  * restored to a no-op after each test to avoid cross-test interference.
  */
class DocsClientTest extends AsyncFreeSpec with NonImplicitAssertions with BaseKyoCoreTest:

    type Assertion = org.scalatest.Assertion
    def assertionSuccess              = succeed
    def assertionFailure(msg: String) = fail(msg)

    override given executionContext: ExecutionContext = Platform.executionContext

    private def withFetch[A](responses: Map[String, String])(block: => A < Async)(using Frame): A < Async =
        // Install the stub, run the block to completion, then restore. The restore must run AFTER the
        // suspended Async finishes, so it is sequenced inside the effect (a plain try/finally around
        // the suspension would restore before the async fetches execute, leaking the stub).
        Sync.defer {
            val saved = DocsClient.fetchFn
            DocsClient.fetchFn = url =>
                responses.getOrElse(url, throw new RuntimeException(s"Unexpected fetch: $url"))
            Abort.run[Throwable](Abort.catching[Throwable](block)).map { result =>
                DocsClient.fetchFn = saved
                result match
                    case Result.Success(a) => a
                    case Result.Failure(e) => throw e
                    case Result.Panic(e)   => throw e
                end match
            }
        }
    end withFetch

    // Leaf 19: fetchMarkdown returns route content.md body
    "fetchMarkdown returns the content.md body for a route (leaf 19)" in run {
        val fixtureMarkdown = "## kyo-core\n\nThe core effects module.\n"
        withFetch(Map("/latest/kyo-core/content.md" -> fixtureMarkdown)) {
            for
                body <- DocsClient.fetchMarkdown("/latest/kyo-core/")
            yield assert(body == fixtureMarkdown, s"Expected fixture body, got: $body")
            end for
        }
    }

    // Leaf 20: routeTable parses versions.json + manifest.json
    "routeTable parses versions.json and manifest.json (leaf 20)" in run {
        val versionsJson =
            """[{"tag":"v1.0.0","label":"1.0.0","latest":true},{"tag":"v0.9.3","label":"0.9.3","latest":false}]"""
        val manifestJson =
            """[{"slug":"kyo-data","group":"Foundation","title":"kyo-data"},{"slug":"kyo-kernel","group":"Foundation","title":"kyo-kernel"},{"slug":"kyo-core","group":"Effects","title":"kyo-core"}]"""
        withFetch(Map(
            "/versions.json"        -> versionsJson,
            "/v1.0.0/manifest.json" -> manifestJson
        )) {
            for
                table <- DocsClient.routeTable
            yield
                assert(table.versions.size == 2, s"Expected 2 versions, got: ${table.versions.size}")
                assert(table.modules.size == 3, s"Expected 3 modules, got: ${table.modules.size}")
                assert(table.versions(0).tag == "v1.0.0", s"First version tag: ${table.versions(0).tag}")
                assert(table.versions(1).tag == "v0.9.3", s"Second version tag: ${table.versions(1).tag}")
                assert(table.modules(0).slug == "kyo-data", s"First module slug: ${table.modules(0).slug}")
            end for
        }
    }

    // routeTable exposes per-module section headings parsed from the manifest `toc` for the search
    // index. Each module slug maps to its headings (text + anchor slug); `level` is dropped (the
    // search index does not use it), and a module with no `toc` maps to an empty Chunk.
    "routeTable parses per-module toc headings into headingsBySlug" in run {
        val versionsJson = """[{"tag":"v1.0.0","label":"1.0.0","latest":true}]"""
        val manifestJson =
            """[""" +
                """{"slug":"kyo-core","group":"Effects","title":"kyo-core","prev":null,"next":"kyo-data",""" +
                """"toc":[{"level":1,"text":"kyo-core","slug":"kyo-core"},{"level":2,"text":"Channels and queues","slug":"channels-and-queues"}]},""" +
                """{"slug":"kyo-data","group":"Foundation","title":"kyo-data","prev":"kyo-core","next":null,"toc":[]}""" +
                """]"""
        withFetch(Map(
            "/versions.json"        -> versionsJson,
            "/v1.0.0/manifest.json" -> manifestJson
        )) {
            for
                table <- DocsClient.routeTable
            yield
                val coreHeadings = table.headingsBySlug.getOrElse("kyo-core", Chunk.empty)
                assert(coreHeadings.size == 2, s"kyo-core must have 2 headings, got: $coreHeadings")
                assert(
                    coreHeadings(1) == DocsSearch.Heading("Channels and queues", "channels-and-queues"),
                    s"second heading must carry text + anchor slug, got: ${coreHeadings(1)}"
                )
                assert(
                    table.headingsBySlug.getOrElse("kyo-data", Chunk(DocsSearch.Heading("x", "x"))) == Chunk.empty,
                    s"a module with an empty toc must map to an empty Chunk, got: ${table.headingsBySlug.get("kyo-data")}"
                )
            end for
        }
    }

    // Leaf 21: unknown route surfaces as failure (edge case)
    "fetchMarkdown fails for a route that throws (leaf 21)" in run {
        // Install via withFetch (empty map) so the stub is restored after the async completes; a
        // map miss throws "Unexpected fetch", which is the failure this leaf verifies surfaces.
        withFetch[Assertion](Map.empty) {
            Abort.run[Throwable](
                Abort.catching[Throwable](DocsClient.fetchMarkdown("/unknown/route/"))
            ).map { result =>
                result match
                    case Result.Failure(_) | Result.Panic(_) =>
                        succeed
                    case Result.Success(body) =>
                        fail(s"Expected failure for unknown route, got: $body")
                end match
            }
        }
    }

    // Escaped-quote handling in extractString
    "extractString handles escaped double-quotes inside JSON string values" in run {
        val versionsJson =
            """[{"tag":"v1.0.0","label":"The \"latest\" release","latest":true}]"""
        withFetch(Map(
            "/versions.json"        -> versionsJson,
            "/v1.0.0/manifest.json" -> "[]"
        )) {
            for
                table <- DocsClient.routeTable
            yield
                assert(table.versions.size == 1, s"Expected 1 version, got: ${table.versions.size}")
                assert(
                    table.versions(0).label.contains("latest"),
                    s"Expected label containing 'latest', got: ${table.versions(0).label}"
                )
            end for
        }
    }

    // Island round-trip: the exact JSON shape WebsiteGenerator.docsIsland emits parses back to the
    // seeded content (version, grouped modules, full version list, raw Markdown). This is the parse
    // half of the emit -> parse boot-island round-trip (emit half asserted in WebsiteGeneratorTest).
    "parseDocsIsland parses the generator's docs-island JSON back to seeded content" in run {
        // Mirrors WebsiteGenerator.docsIsland output (note the `"latest": true` space the SSG emits).
        val islandJson =
            """{"version": {"tag": "v1.0.0-RC2", "label": "1.0.0-RC2", "latest": true}, """ +
                """"intro": "intro text", """ +
                """"groups": [{"name": "Foundation", "modules": [{"slug": "kyo-data", "group": "Foundation", "title": "kyo-data"}, {"slug": "kyo-kernel", "group": "Foundation", "title": "kyo-kernel"}]}], """ +
                """"versions": [{"tag": "v1.0.0-RC2", "label": "1.0.0-RC2", "latest": true}, {"tag": "v0.9.3", "label": "0.9.3", "latest": false}], """ +
                """"markdown": "# kyo-data\n## Overview\nData types.\n"}"""
        for
            island <- DocsClient.parseDocsIsland(islandJson)
        yield
            assert(island.content.version.tag == "v1.0.0-RC2", s"version tag: ${island.content.version.tag}")
            assert(island.content.version.latest, "version.latest must round-trip true through the spaced JSON")
            assert(island.content.intro == "intro text", s"intro: ${island.content.intro}")
            assert(island.content.groups.size == 1, s"groups: ${island.content.groups.size}")
            assert(island.content.groups.head.name == "Foundation", s"group name: ${island.content.groups.head.name}")
            assert(
                island.content.groups.head.modules.map(_.slug) == Chunk("kyo-data", "kyo-kernel"),
                s"module slugs: ${island.content.groups.head.modules.map(_.slug)}"
            )
            assert(island.versions.size == 2, s"versions: ${island.versions.size}")
            assert(island.versions(1).tag == "v0.9.3", s"second version: ${island.versions(1).tag}")
            assert(island.markdown == "# kyo-data\n## Overview\nData types.\n", s"markdown must unescape \\n, got: ${island.markdown}")
        end for
    }

    // WARN-2 regression: a manifest whose string VALUES carry unbalanced structural brackets
    // (`[`, `]`, `{`, `}`) and an escaped `\"` must still split into the correct number of elements
    // with each bracket-laden value intact. Before the fix, splitJsonArray counted brackets inside
    // string literals, so a lone `]` in a title desynced the depth counter and merged elements.
    "parseManifest splits correctly when titles contain unbalanced brackets and escaped quotes (WARN-2)" in run {
        // title 0 has a lone `]` then a lone `[` (unbalanced); title 1 has `{`/`}` plus an escaped `\"`.
        val manifestJson =
            """[""" +
                """{"slug":"kyo-data","group":"Foundation","title":"Layout[A] and ]weird["},""" +
                """{"slug":"kyo-core","group":"Effects","title":"Map{K} say \"hi\" }now{"},""" +
                """{"slug":"kyo-http","group":"Apps","title":"plain"}""" +
                """]"""
        withFetch(Map(
            "/versions.json"        -> """[{"tag":"v1.0.0","label":"1.0.0","latest":true}]""",
            "/v1.0.0/manifest.json" -> manifestJson
        )) {
            for
                table <- DocsClient.routeTable
            yield
                assert(table.modules.size == 3, s"unbalanced brackets must not change the element count, got: ${table.modules.size}")
                assert(table.modules(0).slug == "kyo-data", s"first slug: ${table.modules(0).slug}")
                assert(
                    table.modules(0).title == "Layout[A] and ]weird[",
                    s"bracket-laden title must survive intact, got: ${table.modules(0).title}"
                )
                assert(table.modules(1).slug == "kyo-core", s"second slug: ${table.modules(1).slug}")
                assert(
                    table.modules(1).title == """Map{K} say "hi" }now{""",
                    s"brace+escaped-quote title must survive intact, got: ${table.modules(1).title}"
                )
                assert(table.modules(2).slug == "kyo-http", s"third slug: ${table.modules(2).slug}")
                assert(table.modules(2).title == "plain", s"third title: ${table.modules(2).title}")
            end for
        }
    }

    // WARN-2 regression (versions side): a versions array whose label values carry unbalanced
    // brackets must split into the correct number of versions with labels intact.
    "parseVersions splits correctly when labels contain unbalanced brackets (WARN-2)" in run {
        val versionsJson =
            """[""" +
                """{"tag":"v1.0.0","label":"1.0.0 ]edge[","latest":true},""" +
                """{"tag":"v0.9.3","label":"0.9.3 }brace{","latest":false}""" +
                """]"""
        withFetch(Map(
            "/versions.json"        -> versionsJson,
            "/v1.0.0/manifest.json" -> "[]"
        )) {
            for
                table <- DocsClient.routeTable
            yield
                assert(table.versions.size == 2, s"unbalanced brackets must not change the version count, got: ${table.versions.size}")
                assert(table.versions(0).tag == "v1.0.0", s"first tag: ${table.versions(0).tag}")
                assert(table.versions(0).label == "1.0.0 ]edge[", s"first label must survive intact, got: ${table.versions(0).label}")
                assert(table.versions(0).latest, "first version must be latest=true")
                assert(table.versions(1).tag == "v0.9.3", s"second tag: ${table.versions(1).tag}")
                assert(table.versions(1).label == "0.9.3 }brace{", s"second label must survive intact, got: ${table.versions(1).label}")
                assert(!table.versions(1).latest, "second version must be latest=false")
            end for
        }
    }

    // Empty-parse vs absent: a non-object payload parses to an empty island (the SPA mounts empty),
    // distinct from the absent-#docs-island-element case the DOM reader handles before parsing.
    "parseDocsIsland returns an empty island for a non-object payload" in run {
        for
            island <- DocsClient.parseDocsIsland("")
        yield
            assert(island.content.groups == Chunk.empty, "empty payload yields empty groups")
            assert(island.content.version.tag == "latest", "empty payload yields the latest placeholder version")
            assert(island.markdown.isEmpty, "empty payload yields empty markdown")
        end for
    }

end DocsClientTest
