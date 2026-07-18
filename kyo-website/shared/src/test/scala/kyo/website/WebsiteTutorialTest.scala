package kyo.website

import kyo.*

/** Tests for the tutorial route model: `WebsiteTutorial.Declaration.init` field validation and the
  * four fail-loud declaration cases, `WebsiteModule.withTutorials` unique-attach and duplicate-slug
  * rejection, and `WebsiteContent.loadTutorials` current-version fail-loud plus legacy tolerance.
  */
class WebsiteTutorialTest extends WebsiteTest:

    private val currentVersion = WebsiteVersion("v1.0.0-RC2", "1.0.0-RC2", true)
    private val legacyVersion  = WebsiteVersion("v0.9.3", "0.9.3", false)

    private def baseModule(slug: String): WebsiteModule =
        WebsiteModule(slug, "Foundation", slug, "# readme", WebsiteModule.Platforms(true, true, true, true))

    /** Write the fixture files into a fresh temp tree, attach `tutorial` to a module via the real
      * `withTutorials` wither, and run `loadTutorials` over the resulting group set, returning the
      * caught `Result` (never propagating the Abort into the test body).
      */
    private def loadResult(
        files: Seq[(String, String)],
        version: WebsiteVersion,
        tutorial: WebsiteTutorial.Declaration,
        moduleSlug: String = "kyo-eventlog"
    )(using Frame): Result[WebsiteException, Chunk[WebsiteContent.Tutorial]] < (Sync & Scope & Abort[FileException]) =
        Path.run {
            Path.tempDir("kyo-tutorial-test").map { root =>
                Kyo.foreachDiscard(Chunk.from(files)) { case (rel, body) =>
                    (root / rel).write(body)
                }.map { _ =>
                    Abort.run[WebsiteException] {
                        baseModule(moduleSlug).withTutorials(Chunk(tutorial)).map { m =>
                            val groups = Chunk(WebsiteContent.Group("Foundation", Chunk(m)))
                            WebsiteContent.loadTutorials(root, version, groups)
                        }
                    }
                }
            }
        }

    // ---- Declaration.init field validation ----

    "Declaration.init validates a well-formed tutorial route and resolves the source to a Path" in {
        Abort.run[WebsiteException](
            WebsiteTutorial.Declaration.init("basic-eventlog", "Basic EventLog", "kyo-eventlog/docs/basic-eventlog.md")
        ).map {
            case Result.Success(d) =>
                assert(d.slug == "basic-eventlog", s"slug: ${d.slug}")
                assert(d.title == "Basic EventLog", s"title: ${d.title}")
                assert(d.source == Path("kyo-eventlog/docs/basic-eventlog.md"), s"source must resolve to a Path: ${d.source}")
            case other => fail(s"expected Success, got $other")
        }
    }

    "Declaration.init fails loud on an empty slug" in {
        Abort.run[WebsiteException](WebsiteTutorial.Declaration.init("", "Basic", "a/b.md")).map {
            case Result.Failure(e: WebsiteTutorialException) =>
                assert(e.field == "slug", s"field: ${e.field}")
                assert(e.detail == WebsiteTutorialException.TutorialFailure.Empty, s"detail: ${e.detail}")
            case other => fail(s"expected Failure(WebsiteTutorialException Empty), got $other")
        }
    }

    "Declaration.init fails loud on an invalid slug grammar (uppercase sub-case and slash sub-case)" in {
        for
            upper <- Abort.run[WebsiteException](WebsiteTutorial.Declaration.init("Basic-EventLog", "Basic", "a/b.md"))
            slash <- Abort.run[WebsiteException](WebsiteTutorial.Declaration.init("a/b", "Basic", "a/b.md"))
        yield
            upper match
                case Result.Failure(e: WebsiteTutorialException) =>
                    assert(e.field == "slug", s"uppercase field: ${e.field}")
                    assert(e.detail == WebsiteTutorialException.TutorialFailure.InvalidSlug, s"uppercase detail: ${e.detail}")
                case other => fail(s"uppercase: expected Failure(InvalidSlug), got $other")
            end match
            slash match
                case Result.Failure(e: WebsiteTutorialException) =>
                    assert(e.field == "slug", s"slash field: ${e.field}")
                    assert(e.detail == WebsiteTutorialException.TutorialFailure.InvalidSlug, s"slash detail: ${e.detail}")
                case other => fail(s"slash: expected Failure(InvalidSlug), got $other")
            end match
    }

    "Declaration.init fails loud on an empty title" in {
        Abort.run[WebsiteException](WebsiteTutorial.Declaration.init("basic", "", "a/b.md")).map {
            case Result.Failure(e: WebsiteTutorialException) =>
                assert(e.field == "title", s"field: ${e.field}")
                assert(e.detail == WebsiteTutorialException.TutorialFailure.Empty, s"detail: ${e.detail}")
            case other => fail(s"expected Failure(WebsiteTutorialException title Empty), got $other")
        }
    }

    "Declaration.init fails loud on an empty source" in {
        // The empty source is rejected before Path.apply, which would otherwise silently drop the empty part.
        Abort.run[WebsiteException](WebsiteTutorial.Declaration.init("basic", "Basic", "")).map {
            case Result.Failure(e: WebsiteTutorialException) =>
                assert(e.field == "source", s"field: ${e.field}")
                assert(e.detail == WebsiteTutorialException.TutorialFailure.Empty, s"detail: ${e.detail}")
            case other => fail(s"expected Failure(WebsiteTutorialException source Empty), got $other")
        }
    }

    // ---- WebsiteModule.withTutorials ----

    "WebsiteModule.withTutorials attaches unique entries" in {
        val entries = Chunk(
            WebsiteTutorial.Declaration("basic", "Basic", Path("a.md")),
            WebsiteTutorial.Declaration("raw", "Raw", Path("b.md"))
        )
        Abort.run[WebsiteException](baseModule("kyo-eventlog").withTutorials(entries)).map {
            case Result.Success(m) =>
                assert(m.tutorials == entries, s"tutorials: ${m.tutorials}")
            case other => fail(s"expected Success, got $other")
        }
    }

    "WebsiteModule.withTutorials fails loud on duplicate slugs" in {
        val entries = Chunk(
            WebsiteTutorial.Declaration("basic", "Basic", Path("a.md")),
            WebsiteTutorial.Declaration("basic", "Basic Again", Path("b.md"))
        )
        Abort.run[WebsiteException](baseModule("kyo-eventlog").withTutorials(entries)).map {
            case Result.Failure(e: WebsiteTutorialException) =>
                assert(e.field == "slug", s"field: ${e.field}")
                assert(e.detail == WebsiteTutorialException.TutorialFailure.DuplicateSlug, s"detail: ${e.detail}")
            case other => fail(s"expected Failure(WebsiteTutorialException DuplicateSlug), got $other")
        }
    }

    // ---- WebsiteContent.loadTutorials ----

    "loadTutorials loads a current-version tutorial's source content" in {
        val decl = WebsiteTutorial.Declaration("basic", "Basic", Path("kyo-eventlog/docs/basic.md"))
        for
            result <- loadResult(
                Seq("kyo-eventlog/docs/basic.md" -> "# Basic\ntext"),
                currentVersion,
                decl
            )
        yield result match
            case Result.Success(ts) =>
                assert(ts.size == 1, s"expected 1 tutorial, got ${ts.size}")
                assert(ts.head.module == "kyo-eventlog", s"module: ${ts.head.module}")
                assert(ts.head.declaration.slug == "basic", s"slug: ${ts.head.declaration.slug}")
                assert(ts.head.content == "# Basic\ntext", s"content: ${ts.head.content}")
            case other => fail(s"expected Success, got $other")
        end for
    }

    "loadTutorials fails loud when a current-version tutorial source is absent" in {
        // The declared source file is never written into the temp tree.
        val decl = WebsiteTutorial.Declaration("basic", "Basic", Path("kyo-eventlog/docs/absent.md"))
        for
            result <- loadResult(Seq.empty, currentVersion, decl)
        yield result match
            case Result.Failure(e: WebsiteReadmeException) =>
                assert(e.detail == WebsiteReadmeException.ReadmeFailure.Missing, s"expected Missing, got ${e.detail}")
                assert(
                    e.path.toString.contains("kyo-eventlog/docs/absent.md"),
                    s"path must name the absent source, got: ${e.path}"
                )
            case other => fail(s"expected Failure(WebsiteReadmeException Missing), got $other")
        end for
    }

    "loadTutorials tolerates a legacy version whose declared tutorial source is absent" in {
        // Same absent-source declaration as the fail-loud leaf, but the version is not latest.
        val decl = WebsiteTutorial.Declaration("basic", "Basic", Path("kyo-eventlog/docs/absent.md"))
        for
            result <- loadResult(Seq.empty, legacyVersion, decl)
        yield result match
            case Result.Success(ts) =>
                assert(ts.isEmpty, s"legacy version must emit no rail and never abort, got: $ts")
            case other => fail(s"expected Success (legacy tolerance, no abort), got $other")
        end for
    }

end WebsiteTutorialTest
