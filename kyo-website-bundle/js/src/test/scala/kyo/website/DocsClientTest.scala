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
