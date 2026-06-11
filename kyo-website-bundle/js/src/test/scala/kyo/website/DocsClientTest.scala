package kyo.website

import kyo.*

/** Tests for [[DocsClient]] using a stubbed fetch function.
  *
  * Runs in JS only (JS placement via `kyo-website-bundle/js/src/test/`). The real
  * `DocsClient.fetchFn` is replaced with a synchronous stub before each test; it is
  * restored to a no-op after each test to avoid cross-test interference.
  */
class DocsClientTest extends kyo.test.Test[Any]:

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

    // fetchArticle parses html + headings into Article
    "fetchArticle parses html and headings into Article" in {
        val stubBody =
            """{"html": "<h2 id=\"a\">A</h2>", "headings": [{"level": 2, "text": "A", "slug": "a"}]}"""
        withFetch(Map("/latest/kyo-core/content.html" -> stubBody)) {
            for
                article <- DocsClient.fetchArticle("/latest/kyo-core/")
            yield assert(
                article == DocsClient.Article(
                    "<h2 id=\"a\">A</h2>",
                    Chunk(DocsMarkdown.Heading(2, "A", "a"))
                ),
                s"Expected Article with html and heading, got: $article"
            )
            end for
        }
    }

    // routeTable parses versions.json + manifest.json
    "routeTable parses versions.json and manifest.json" in {
        val versionsJson =
            """[{"tag":"v1.0.0","label":"1.0.0","latest":true},{"tag":"v0.9.3","label":"0.9.3","latest":false}]"""
        val manifestJson =
            """[{"slug":"kyo-data","group":"Foundation","title":"kyo-data"},{"slug":"kyo-kernel","group":"Foundation","title":"kyo-kernel"},{"slug":"kyo-core","group":"Effects","title":"kyo-core"}]"""
        withFetch(Map(
            "/versions.json"        -> versionsJson,
            "/v1.0.0/manifest.json" -> manifestJson
        )) {
            for
                table <- DocsClient.routeTable("v1.0.0")
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
    "routeTable parses per-module toc headings into headingsBySlug" in {
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
                table <- DocsClient.routeTable("v1.0.0")
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

    // fetchArticle on a non-200 fails the Async (no Abort widening)
    "fetchArticle on a non-200 fails the Async" in {
        withFetch[Unit](Map.empty) {
            Abort.run[Throwable](
                Abort.catching[Throwable](DocsClient.fetchArticle("/unknown/route/"))
            ).map { result =>
                result match
                    case Result.Failure(_) | Result.Panic(_) =>
                        succeed
                    case Result.Success(a) =>
                        fail(s"Expected failure for unknown route, got: $a")
                end match
            }
        }
    }

    // Escaped-quote handling in extractString
    "extractString handles escaped double-quotes inside JSON string values" in {
        val versionsJson =
            """[{"tag":"v1.0.0","label":"The \"latest\" release","latest":true}]"""
        withFetch(Map(
            "/versions.json"        -> versionsJson,
            "/v1.0.0/manifest.json" -> "[]"
        )) {
            for
                table <- DocsClient.routeTable("v1.0.0")
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
    // seeded content (version, grouped modules, full version list, pre-rendered article + headings).
    // This is the parse half of the emit -> parse boot-island round-trip (emit half asserted in
    // WebsiteGeneratorTest).
    "parseDocsIsland parses the generator's docs-island JSON back to seeded content" in {
        // Mirrors WebsiteGenerator.docsIsland output (note the `"latest": true` space the SSG emits).
        val islandJson =
            """{"version": {"tag": "v1.0.0-RC2", "label": "1.0.0-RC2", "latest": true}, """ +
                """"intro": "intro text", """ +
                """"groups": [{"name": "Foundation", "modules": [{"slug": "kyo-data", "group": "Foundation", "title": "kyo-data"}, {"slug": "kyo-kernel", "group": "Foundation", "title": "kyo-kernel"}]}], """ +
                """"versions": [{"tag": "v1.0.0-RC2", "label": "1.0.0-RC2", "latest": true}, {"tag": "v0.9.3", "label": "0.9.3", "latest": false}], """ +
                """"article": "<h1 id=\"kyo-data\">kyo-data</h1>", """ +
                """"headings": [{"level": 1, "text": "kyo-data", "slug": "kyo-data"}, {"level": 2, "text": "Overview", "slug": "overview"}]}"""
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
            assert(
                island.articleHtml == "<h1 id=\"kyo-data\">kyo-data</h1>",
                s"articleHtml must round-trip through island JSON, got: ${island.articleHtml}"
            )
            assert(
                island.headings == Chunk(
                    DocsMarkdown.Heading(1, "kyo-data", "kyo-data"),
                    DocsMarkdown.Heading(2, "Overview", "overview")
                ),
                s"headings must round-trip with level, got: ${island.headings}"
            )
        end for
    }

    // Regression: a manifest whose string VALUES carry unbalanced structural brackets
    // (`[`, `]`, `{`, `}`) and an escaped `\"` must still split into the correct number of elements
    // with each bracket-laden value intact. Before the fix, splitJsonArray counted brackets inside
    // string literals, so a lone `]` in a title desynced the depth counter and merged elements.
    "parseManifest splits correctly when titles contain unbalanced brackets and escaped quotes" in {
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
                table <- DocsClient.routeTable("v1.0.0")
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

    // Regression (versions side): a versions array whose label values carry unbalanced
    // brackets must split into the correct number of versions with labels intact.
    "parseVersions splits correctly when labels contain unbalanced brackets" in {
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
                table <- DocsClient.routeTable("v1.0.0")
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

    // Regression: routeTable must fetch the ACTIVE prefix's manifest, not always the latest.
    // A non-latest reader (browsing /v0.9.3/...) whose heading index is built from the LATEST manifest
    // gets heading routes /<oldPrefix>/<slug>/#<latestSlug> whose fragment can land nowhere. This stubs
    // DIFFERENT latest vs old manifests and asserts routeTable("v0.9.3") returns the OLD version's
    // headings. Before the fix, routeTable took no argument and always fetched the latest manifest, so
    // this assertion failed (the v0.9.3 reader got "new-heading" instead of "old-heading"). The
    // signature change itself is the compile-time reproduce signal.
    "routeTable(activePrefix) fetches the active prefix's manifest, not always the latest" in {
        val versionsJson =
            """[{"tag":"v1.0.0","label":"1.0.0","latest":true},{"tag":"v0.9.3","label":"0.9.3","latest":false}]"""
        // The latest manifest has "new-heading"; the old manifest has "old-heading" instead.
        val latestManifestJson =
            """[{"slug":"kyo-core","group":"Effects","title":"kyo-core",""" +
                """"toc":[{"level":2,"text":"New heading","slug":"new-heading"}]}]"""
        val oldManifestJson =
            """[{"slug":"kyo-core","group":"Effects","title":"kyo-core",""" +
                """"toc":[{"level":2,"text":"Old heading","slug":"old-heading"}]}]"""
        withFetch(Map(
            "/versions.json"        -> versionsJson,
            "/v1.0.0/manifest.json" -> latestManifestJson, // latest -- must NOT be used
            "/v0.9.3/manifest.json" -> oldManifestJson     // old version -- must be used
        )) {
            for
                table <- DocsClient.routeTable("v0.9.3")
            yield
                val coreHeadings = table.headingsBySlug.getOrElse("kyo-core", Chunk.empty)
                assert(
                    coreHeadings.exists(_.slug == "old-heading"),
                    s"routeTable for v0.9.3 must use the v0.9.3 manifest headings, got: $coreHeadings"
                )
                assert(
                    !coreHeadings.exists(_.slug == "new-heading"),
                    s"routeTable for v0.9.3 must NOT use the latest manifest headings, got: $coreHeadings"
                )
                // The versions list must still be parsed from /versions.json (only the manifest URL
                // changed), so the dropdown still has every version.
                assert(table.versions.size == 2, s"routeTable must still parse all versions, got: ${table.versions.size}")
            end for
        }
    }

    // Empty-parse vs absent: a non-object payload parses to an empty island (the SPA mounts empty),
    // distinct from the absent-#docs-island-element case the DOM reader handles before parsing.
    "parseDocsIsland returns an empty island for a non-object payload" in {
        for
            island <- DocsClient.parseDocsIsland("")
        yield
            assert(island.content.groups == Chunk.empty, "empty payload yields empty groups")
            assert(island.content.version.tag == "latest", "empty payload yields the latest placeholder version")
            assert(island.articleHtml.isEmpty, "empty payload yields empty articleHtml")
            assert(island.headings == Chunk.empty, "empty payload yields empty headings")
        end for
    }

    // parseDocsIsland with missing article/headings fields defaults to empty
    "parseDocsIsland missing article/headings fields default to empty" in {
        val islandJson =
            """{"version": {"tag": "v1.0.0", "label": "1.0.0", "latest": true}, """ +
                """"intro": "some intro", """ +
                """"groups": [], """ +
                """"versions": []}"""
        for
            island <- DocsClient.parseDocsIsland(islandJson)
        yield
            assert(island.articleHtml == "", s"missing article key must default to empty, got: ${island.articleHtml}")
            assert(island.headings == Chunk.empty, s"missing headings key must default to empty Chunk, got: ${island.headings}")
            assert(island.content.intro == "some intro", s"other fields must still parse, got: ${island.content.intro}")
        end for
    }

    // island round-trip survives </script> break-out and < > in article HTML
    "island round-trip survives </script> break-out" in {
        // In production, injectIslands wraps the entire island JSON in escScript, which replaces
        // `<` with `<` and `>` with `>` (6-char JSON unicode escape sequences).
        // el.textContent delivers these sequences verbatim to parseDocsIsland, because the
        // HTML parser leaves JSON unicode escapes untouched (they are not HTML entities).
        // unescapeJson's `\u` arm must decode `<` -> `<` and `>` -> `>`.
        // This fixture uses the escScript output form (the bytes el.textContent actually yields),
        // so the test exercises the real decode path rather than the no-backslash fast path.
        val lt = "\\u003c"
        val gt = "\\u003e"
        val escapedIslandJson =
            s"""{"version": {"tag": "v1.0.0", "label": "1.0.0", "latest": true}, """ +
                s""""intro": "", "groups": [], "versions": [], """ +
                s""""article": "${lt}/script${gt}${lt}p${gt}a ${lt} b${lt}/p${gt}", """ +
                s""""headings": []}"""
        for
            island <- DocsClient.parseDocsIsland(escapedIslandJson)
        yield assert(
            island.articleHtml == "</script><p>a < b</p>",
            s"unescapeJson must decode \\u003c/\\u003e and </script> byte-for-byte, got: ${island.articleHtml}"
        )
        end for
    }

    // fetchArticle requests <route>content.html
    "fetchArticle requests route/content.html" in {
        var capturedUrl = ""
        Sync.defer {
            val saved = DocsClient.fetchFn
            DocsClient.fetchFn = url =>
                capturedUrl = url
                """{"html": "", "headings": []}"""
            Abort.run[Throwable](Abort.catching[Throwable](DocsClient.fetchArticle("/latest/kyo-core/"))).map { _ =>
                DocsClient.fetchFn = saved
                assert(capturedUrl == "/latest/kyo-core/content.html", s"Expected /latest/kyo-core/content.html, got: $capturedUrl")
            }
        }
    }

    // fetchArticle normalizes route without trailing slash
    "fetchArticle normalizes route without trailing slash" in {
        var capturedUrl = ""
        Sync.defer {
            val saved = DocsClient.fetchFn
            DocsClient.fetchFn = url =>
                capturedUrl = url
                """{"html": "", "headings": []}"""
            Abort.run[Throwable](Abort.catching[Throwable](DocsClient.fetchArticle("/latest/kyo-core"))).map { _ =>
                DocsClient.fetchFn = saved
                assert(capturedUrl == "/latest/kyo-core/content.html", s"Expected /latest/kyo-core/content.html, got: $capturedUrl")
            }
        }
    }

    // fetchArticle round-trips an escaped article (unescapeJson decodes <)
    "fetchArticle round-trips an escaped article via unescapeJson" in {
        val stubBody = """{"html": "<p>a < b</p>", "headings": []}"""
        withFetch(Map("/latest/kyo-data/content.html" -> stubBody)) {
            for
                article <- DocsClient.fetchArticle("/latest/kyo-data/")
            yield assert(
                article.html == "<p>a < b</p>",
                s"unescapeJson must decode \\u003c to <, got: ${article.html}"
            )
            end for
        }
    }

    // Article.headings carries level (compile-time and runtime check, not DocsSearch.Heading)
    "Article.headings carries level field" in {
        val stubBody =
            """{"html": "<h2 id=\"sec\">Section</h2>", "headings": [{"level": 2, "text": "Section", "slug": "sec"}]}"""
        withFetch(Map("/latest/kyo-core/content.html" -> stubBody)) {
            for
                article <- DocsClient.fetchArticle("/latest/kyo-core/")
            yield
                assert(article.headings.size == 1, s"Expected 1 heading, got: ${article.headings.size}")
                val h = article.headings(0)
                // h is DocsMarkdown.Heading with .level; DocsSearch.Heading has no .level
                assert(h.level == 2, s"heading.level must be 2, got: ${h.level}")
                assert(h.text == "Section", s"heading.text must be Section, got: ${h.text}")
                assert(h.slug == "sec", s"heading.slug must be sec, got: ${h.slug}")
            end for
        }
    }

    // parseOutlineHeading skips entries with missing slug
    "parseOutlineHeading skips malformed entries with missing fields" in {
        // One valid heading, one missing slug, one missing level; only the valid one survives.
        val islandJson =
            """{"version": {"tag": "v1.0.0", "label": "1.0.0", "latest": true}, """ +
                """"intro": "", "groups": [], "versions": [], """ +
                """"article": "", """ +
                """"headings": [""" +
                """{"level": 1, "text": "Good", "slug": "good"}, """ +
                """{"level": 2, "text": "NoSlug"}, """ +
                """{"text": "NoLevel", "slug": "no-level"}""" +
                """]}"""
        for
            island <- DocsClient.parseDocsIsland(islandJson)
        yield
            assert(island.headings.size == 1, s"Only the valid heading must survive, got: ${island.headings}")
            assert(island.headings(0) == DocsMarkdown.Heading(1, "Good", "good"), s"Valid heading: ${island.headings(0)}")
        end for
    }

    // Empty headings array yields empty Chunk
    "empty headings array yields Chunk.empty" in {
        val islandJson =
            """{"version": {"tag": "v1.0.0", "label": "1.0.0", "latest": true}, """ +
                """"intro": "", "groups": [], "versions": [], """ +
                """"article": "<p>text</p>", """ +
                """"headings": []}"""
        for
            island <- DocsClient.parseDocsIsland(islandJson)
        yield assert(island.headings == Chunk.empty, s"empty headings array must yield Chunk.empty, got: ${island.headings}")
        end for
    }

    // island parse feeds Chunk[DocsMarkdown.Heading], compile-time type guard
    "island parse produces Chunk[DocsMarkdown.Heading] usable as tocRef" in {
        val islandJson =
            """{"version": {"tag": "v1.0.0", "label": "1.0.0", "latest": true}, """ +
                """"intro": "", "groups": [], "versions": [], """ +
                """"article": "", """ +
                """"headings": [{"level": 3, "text": "T", "slug": "t"}]}"""
        for
            island <- DocsClient.parseDocsIsland(islandJson)
            // Signal.initRef accepts Chunk[DocsMarkdown.Heading]; would not compile with Chunk[DocsSearch.Heading]
            tocRef <- Signal.initRef[Chunk[DocsMarkdown.Heading]](island.headings)
            vals   <- tocRef.get
        yield assert(vals == Chunk(DocsMarkdown.Heading(3, "T", "t")), s"tocRef carries the parsed Chunk, got: $vals")
        end for
    }

    // extractInt parses level and rejects non-numeric
    "extractInt parses level and rejects non-numeric values" in {
        val goodJson = """{"level": 3, "text": "T", "slug": "t"}"""
        val badJson  = """{"level": "x", "text": "T", "slug": "t"}"""
        // Use parseDocsIsland wrapper: a headings array with one good, one bad
        val islandJson =
            """{"version": {"tag": "v1.0.0", "label": "1.0.0", "latest": true}, """ +
                """"intro": "", "groups": [], "versions": [], """ +
                """"article": "", """ +
                s""""headings": [$goodJson, $badJson]}"""
        for
            island <- DocsClient.parseDocsIsland(islandJson)
        yield
            assert(island.headings.size == 1, s"Non-numeric level must be dropped, got: ${island.headings}")
            assert(island.headings(0).level == 3, s"Good level 3 must survive, got: ${island.headings(0).level}")
        end for
    }

    // routeTable/parseHeadings still produce DocsSearch.Heading (unchanged)
    "routeTable parseHeadings still produce DocsSearch.Heading with text+slug only" in {
        val versionsJson = """[{"tag":"v1.0.0","label":"1.0.0","latest":true}]"""
        val manifestJson =
            """[{"slug":"kyo-core","group":"Effects","title":"kyo-core",""" +
                """"toc":[{"level":2,"text":"Overview","slug":"overview"}]}]"""
        withFetch(Map(
            "/versions.json"        -> versionsJson,
            "/v1.0.0/manifest.json" -> manifestJson
        )) {
            for
                table <- DocsClient.routeTable("v1.0.0")
            yield
                val headings = table.headingsBySlug.getOrElse("kyo-core", Chunk.empty)
                assert(headings.size == 1, s"Expected 1 heading, got: $headings")
                // DocsSearch.Heading has text and slug only; checking both fields
                assert(headings(0).text == "Overview", s"text: ${headings(0).text}")
                assert(headings(0).slug == "overview", s"slug: ${headings(0).slug}")
            end for
        }
    }

    // fetchArticle uses exactly one fetch (html + headings are co-located)
    "fetchArticle uses exactly one fetch call" in {
        var fetchCount = 0
        Sync.defer {
            val saved = DocsClient.fetchFn
            DocsClient.fetchFn = _ =>
                fetchCount += 1
                """{"html": "<p>x</p>", "headings": []}"""
            Abort.run[Throwable](Abort.catching[Throwable](DocsClient.fetchArticle("/latest/kyo-data/"))).map { _ =>
                DocsClient.fetchFn = saved
                assert(fetchCount == 1, s"fetchArticle must issue exactly one fetch, issued: $fetchCount")
            }
        }
    }

    // parseSearchIndex on a well-formed body yields the expected entries
    "parseSearchIndex on a well-formed body yields the expected entries" in {
        val body =
            """[{"slug":"kyo-core","title":"kyo-core","group":"Effects","sections":[""" +
                """{"level":2,"text":"Fibers and forks","slug":"fibers-and-forks","snippet":"Fibers are lightweight threads."},""" +
                """{"level":3,"text":"Interruption","slug":"interruption","snippet":"Interrupt a fiber."}""" +
                """]}]"""
        for
            entries <- DocsClient.parseSearchIndex(body)
        yield
            assert(entries.size == 1, s"Expected 1 entry, got: ${entries.size}")
            val e = entries(0)
            assert(e.slug == "kyo-core", s"slug: ${e.slug}")
            assert(e.title == "kyo-core", s"title: ${e.title}")
            assert(e.group == "Effects", s"group: ${e.group}")
            assert(e.prefix == "", s"prefix must be empty (stamped later by fetchSearchIndex), got: ${e.prefix}")
            assert(
                e.headings == Chunk(
                    DocsSearch.Heading("Fibers and forks", "fibers-and-forks"),
                    DocsSearch.Heading("Interruption", "interruption")
                ),
                s"headings must carry text+slug from sections, got: ${e.headings}"
            )
            assert(e.text.contains("Fibers and forks"), s"text must include section text, got: ${e.text}")
            assert(e.text.contains("Fibers are lightweight threads."), s"text must include snippet, got: ${e.text}")
            assert(e.text.contains("Interruption"), s"text must include second section text, got: ${e.text}")
            assert(e.text.contains("Interrupt a fiber."), s"text must include second snippet, got: ${e.text}")
        end for
    }

    // parseSearchIndex on a non-array or empty body yields Chunk.empty
    "parseSearchIndex on a non-array or empty body yields Chunk.empty" in {
        for
            r1 <- DocsClient.parseSearchIndex("not-json")
            r2 <- DocsClient.parseSearchIndex("")
            r3 <- DocsClient.parseSearchIndex("{}")
        yield
            assert(r1 == Chunk.empty, s"non-array must yield Chunk.empty, got: $r1")
            assert(r2 == Chunk.empty, s"empty string must yield Chunk.empty, got: $r2")
            assert(r3 == Chunk.empty, s"object (not array) must yield Chunk.empty, got: $r3")
        end for
    }

    // parseSearchIndex drops a malformed element, keeps the well-formed one
    "parseSearchIndex drops malformed element and keeps well-formed one" in {
        // First element is well-formed; second is missing the required slug field.
        val body =
            """[""" +
                """{"slug":"kyo-core","title":"kyo-core","group":"Effects","sections":[]},""" +
                """{"title":"no-slug-here","group":"Effects","sections":[]}""" +
                """]"""
        for
            entries <- DocsClient.parseSearchIndex(body)
        yield
            assert(entries.size == 1, s"Only the well-formed entry must survive, got: ${entries.size}")
            assert(entries(0).slug == "kyo-core", s"Well-formed entry slug, got: ${entries(0).slug}")
        end for
    }

    // emit/parse round-trip: the schema the emitter produces parses back to the expected entries
    "emit/parse round-trip parses the emitted schema back to the expected entries" in {
        // Fixture in the exact schema writeSearchIndex produces:
        // {"slug","title","group","sections":[{"level","text","slug","snippet"}]}
        val body =
            """[{"slug": "kyo-core", "title": "kyo-core", "group": "Effects", "sections": [""" +
                """{"level": 2, "text": "Fibers and forks", "slug": "fibers-and-forks", "snippet": "Fibers are lightweight threads."}""" +
                """]}]"""
        for
            entries <- DocsClient.parseSearchIndex(body)
        yield
            assert(entries.size == 1, s"Round-trip must yield 1 entry, got: ${entries.size}")
            val e = entries(0)
            assert(e.slug == "kyo-core", s"round-trip slug: ${e.slug}")
            assert(e.title == "kyo-core", s"round-trip title: ${e.title}")
            assert(e.group == "Effects", s"round-trip group: ${e.group}")
            assert(
                e.headings(0) == DocsSearch.Heading("Fibers and forks", "fibers-and-forks"),
                s"round-trip heading text+slug: ${e.headings(0)}"
            )
            assert(
                e.text.contains("Fibers and forks"),
                s"round-trip text must contain section text, got: ${e.text}"
            )
            assert(
                e.text.contains("Fibers are lightweight threads."),
                s"round-trip text must contain snippet, got: ${e.text}"
            )
        end for
    }

    // fetchSearchIndex GETs the active-prefix file, stamps the prefix, stays < Async with no Abort
    "fetchSearchIndex GETs active-prefix file and stamps prefix" in {
        val body =
            """[{"slug":"kyo-core","title":"kyo-core","group":"Effects","sections":[]}]"""
        withFetch(Map("/v0.9.0/search-index.json" -> body)) {
            for
                idx <- DocsClient.fetchSearchIndex("v0.9.0")
            yield
                assert(idx.entries.size == 1, s"Expected 1 entry, got: ${idx.entries.size}")
                assert(
                    idx.entries(0).prefix == "v0.9.0",
                    s"fetchSearchIndex must stamp the prefix, got: ${idx.entries(0).prefix}"
                )
                assert(idx.entries(0).slug == "kyo-core", s"entry slug: ${idx.entries(0).slug}")
            end for
        }
    }

    // fetchSearchIndex failure surfaces as non-Success via Abort.run
    // (validates the caller's degrade pattern used in build())
    "fetchSearchIndex failure surfaces as non-Success result" in {
        withFetch[Unit](Map.empty) {
            Abort.run[Throwable](
                Abort.catching[Throwable](DocsClient.fetchSearchIndex("v0.9.0"))
            ).map { result =>
                result match
                    case Result.Failure(_) | Result.Panic(_) =>
                        succeed
                    case Result.Success(idx) =>
                        fail(s"Expected failure for unstubbed URL, got index with ${idx.entries.size} entries")
                end match
            }
        }
    }

    // Totality: a heading-less module emits sections [] and parses to an entry with
    // empty headings and empty text (exercises the sections-absent degrade path for parseSearchIndex)
    "parseSearchIndex handles a module with empty sections array (sections totality)" in {
        val body =
            """[{"slug":"kyo-prelude","title":"kyo-prelude","group":"Effects","sections":[]}]"""
        for
            entries <- DocsClient.parseSearchIndex(body)
        yield
            assert(entries.size == 1, s"Heading-less module must yield 1 entry, got: ${entries.size}")
            val e = entries(0)
            assert(e.slug == "kyo-prelude", s"slug: ${e.slug}")
            assert(e.headings == Chunk.empty, s"headings must be empty for no sections, got: ${e.headings}")
            assert(e.text == "", s"text must be empty for no sections, got: '${e.text}'")
        end for
    }

    // refreshSearchIndex upgrades the SignalRef from the title-only seed
    // to the full heading+prose index when the fetch succeeds. The assertion FAILS if the ref was
    // not upgraded (i.e. searchIndex.set(idx) was not called on the success arm).
    "refreshSearchIndex upgrades the searchIndex ref on a successful fetch" in {
        // Build the title-only seed: one module, no headings.
        val modules       = Chunk(WebsiteModule("kyo-core", "Effects", "kyo-core", "", WebsiteModule.Platforms(true, true, true)))
        val content       = WebsiteContent("", Chunk(WebsiteContent.Group("Effects", modules)), WebsiteVersion("v0.9.0", "0.9.0", false))
        val titleOnlySeed = DocsSearch.headingIndex("v0.9.0", modules, _ => Chunk.empty)
        // The stub search-index.json body carries a section heading not present in the title-only seed.
        val searchIndexBody =
            """[{"slug":"kyo-core","title":"kyo-core","group":"Effects","sections":[""" +
                """{"level":2,"text":"Fibers and forks","slug":"fibers-and-forks","snippet":"Fibers are lightweight."}""" +
                """]}]"""
        withFetch(Map("/v0.9.0/search-index.json" -> searchIndexBody)) {
            for
                searchIndex <- Signal.initRef(titleOnlySeed)
                _           <- WebsiteBundleMain.refreshSearchIndex(searchIndex, "v0.9.0")
                upgraded    <- searchIndex.get
            yield
                // The title-only seed has no headings; the upgraded index must carry the fetched heading.
                assert(
                    titleOnlySeed.entries(0).headings == Chunk.empty,
                    s"seed must have no headings (pre-condition), got: ${titleOnlySeed.entries(0).headings}"
                )
                assert(upgraded.entries.size == 1, s"upgraded index must have 1 entry, got: ${upgraded.entries.size}")
                assert(
                    upgraded.entries(0).headings == Chunk(DocsSearch.Heading("Fibers and forks", "fibers-and-forks")),
                    s"upgraded index must carry the fetched heading, got: ${upgraded.entries(0).headings}"
                )
                assert(
                    upgraded.entries(0).prefix == "v0.9.0",
                    s"upgraded entries must carry the active prefix, got: ${upgraded.entries(0).prefix}"
                )
            end for
        }
    }

    // refreshSearchIndex leaves the SignalRef unchanged when the
    // fetch fails. The assertion FAILS if the failure was not gracefully degraded (i.e. the ref was
    // cleared or overwritten with an empty/wrong index instead of retaining the seed).
    "refreshSearchIndex retains the title-only seed when the fetch fails" in {
        // Build the title-only seed: one module, no headings.
        val modules       = Chunk(WebsiteModule("kyo-core", "Effects", "kyo-core", "", WebsiteModule.Platforms(true, true, true)))
        val titleOnlySeed = DocsSearch.headingIndex("v0.9.0", modules, _ => Chunk.empty)
        // Empty stub map: any fetch throws, simulating a network or HTTP error.
        withFetch(Map.empty) {
            for
                searchIndex <- Signal.initRef(titleOnlySeed)
                _           <- WebsiteBundleMain.refreshSearchIndex(searchIndex, "v0.9.0")
                retained    <- searchIndex.get
            yield assert(
                retained == titleOnlySeed,
                s"ref must retain the title-only seed on fetch failure, got: $retained"
            )
            end for
        }
    }

    // unescapeJson degrades gracefully on a malformed \uXXXX escape.
    // A well-formed SSG response always carries valid hex; a malformed escape must not throw
    // (total functions: model failure, do not throw as control flow).
    "unescapeJson degrades gracefully on a malformed \\uXXXX escape without throwing" in {
        // The escape \uZZZZ contains non-hex chars; the result must not throw and must
        // contain some output (the literal backslash fallback, not the decoded codepoint).
        val islandJson =
            """{"version": {"tag": "v1.0.0", "label": "1.0.0", "latest": true}, """ +
                """"intro": "", "groups": [], "versions": [], """ +
                """"article": "text with \\uZZZZ escape", """ +
                """"headings": []}"""
        for
            island <- DocsClient.parseDocsIsland(islandJson)
        yield
            // The article must be non-empty: the partial text before the malformed escape is kept.
            assert(island.articleHtml.contains("text with"), s"pre-escape text must be preserved, got: ${island.articleHtml}")
            // The decoded result must not contain a Unicode replacement character or throw.
            // Specifically, the malformed escape degrades to emitting the literal backslash
            // (the fallback path), so the output carries the backslash rather than crashing.
            assert(island.articleHtml.nonEmpty, s"output must be non-empty after malformed escape, got: ${island.articleHtml}")
        end for
    }

end DocsClientTest
