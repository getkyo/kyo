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

    private def withFetch(responses: Map[String, String])(block: => Any < Async): Any < Async =
        val saved = DocsClient.fetchFn
        DocsClient.fetchFn = url =>
            responses.getOrElse(url, throw new RuntimeException(s"Unexpected fetch: $url"))
        val result = block
        DocsClient.fetchFn = saved
        result
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
        DocsClient.fetchFn = url => throw new RuntimeException(s"Not found: $url")
        Abort.run[Throwable](
            Abort.catching[Throwable](DocsClient.fetchMarkdown("/unknown/route/"))
        ).map { result =>
            result match
                case Result.Fail(_) | Result.Panic(_) =>
                    succeed
                case Result.Success(body) =>
                    fail(s"Expected failure for unknown route, got: $body")
            end match
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

end DocsClientTest
