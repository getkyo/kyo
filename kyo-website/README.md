# kyo-website

`kyo-website` is the static-site generator that builds [getkyo.io](https://getkyo.io). It reads the repository's own Markdown (each released git tag's root `README.md` plus its per-module `<slug>/README.md` files), parses the root README's `## Modules` table into a navigation model, transpiles all of that Markdown to HTML, and writes a complete GitHub-Pages-ready directory: a landing page, per-version and per-module documentation pages, JSON manifests, a client search index, a sitemap, robots and CNAME files, logos, and the compiled browser bundle. READMEs go in, a deployable `site/` tree comes out.

Building and previewing it locally is a three-command pipeline. Link the browser bundle, render the site into an output directory, then serve that directory:

```sh
# 1. Link the browser bundle (produces main.js under kyo-website-bundle's target tree)
sbt 'kyo-website-bundleJS/Compile/fullLinkJS'

# 2. Render the site (reads the content dir, writes the output dir)
sbt 'kyo-websiteJVM/run --out site --content content --repo-root .'

# 3. Serve the rendered directory for preview (default port 8474)
sbt 'kyo-httpJVM/Test/runMain demo.ServeSite site'
```

The order is not arbitrary: step 2 copies the `main.js` that step 1 produces, and step 3 reads the files step 2 wrote.

The architecturally central idea, the one that explains how the JVM half and the JS half cooperate, is the hydration-parity contract. One shared Scala renderer (`SiteApp`) produces both the build-time HTML on the JVM and the browser's first paint in the JS bundle, so a server-rendered page and the bundle's initial render are structurally identical and the page hydrates without a flicker. Two halves cooperate around that shared shell: a JVM side that runs only at build time (the generator `WebsiteGenerator`, the CLI `WebsiteMain`, the scalameta-backed Markdown transpiler `DocsMarkdownRender`), and a shared side that compiles to both platforms (the view layer `SiteApp` / `LandingApp` / `DocsApp`, the content model, the stylesheet). The companion module `kyo-website-bundle` re-links the shared code as the browser ESModule (`WebsiteBundleMain`) that hydrates the generated pages.

This module is an application, not an importable library (`publish / skip := true`): there is no dependency to add and no public API a downstream project calls. The types named below are the internals of the build pipeline, described so you can read, build, and preview the site. It targets JVM and JS only (Native is intentionally not a target: the generator needs one host and the deploy runs on the JVM). For how to change this module safely (the parity-contract mechanics, the extension recipes, the test guards), read [CONTRIBUTING.md](CONTRIBUTING.md); this README answers what it is, how to build and preview it, and how the pieces fit.

## What this builds

Start with the input and output shape, because the rest of the module is the transformation between them. The input is a directory of extracted git tags. The deploy workflow runs `git archive <tag> README.md '*/README.md' | tar -x` per released tag, so each `<content>/<tag>/` directory holds that tag's root `README.md` and a `<slug>/README.md` per module. The output is a flat directory of static files ready for GitHub Pages.

`WebsiteGenerator` is the generator, and `emit` is its single public entry point. It consumes a `Chunk[WebsiteContent]` (one render input per documentation version) and sequences the full artifact tree: the landing page, a 404 page, the per-version documentation routes, a `latest/` mirror of the newest stable version, the version manifest, the artifact-root files (sitemap, robots, CNAME, `.nojekyll`), and the asset copy. Every per-file helper (`emitLanding`, `writeManifest`, `buildSitemapXml`, `escJson`, and the rest) is private to the generator; `emit` is the surface, and the helpers are its internal sequence.

Content comes from git tags, but the renderer lives on `main`. `WebsiteContent` is a value, not a set of files, so the renderer on `main` can render any tag's content without checking that tag out. The companion `kyo-website-bundle` (`WebsiteBundleMain`) is the browser single-page app that boots from the generated pages and takes over navigation once the site is loaded; it re-links the same shared renderer for the browser.

## Build and preview the site

The three-command pipeline above is the local workflow; this section is the operating contract behind it.

The CLI is `WebsiteMain`, a `KyoApp` that drives `WebsiteGenerator.emit`. It takes four flags:

| Flag | Meaning |
|------|---------|
| `--out <dir>` | Output directory (required). Created if absent. |
| `--content <dir>` | Directory with one `<tag>/` subdirectory per version, each an extracted tag tree. When absent or empty, only the landing page and artifact-root files are written, with no docs versions. |
| `--repo-root <dir>` | Repo root, used to locate `kyo.png` and `kyo-website/assets/kyo.ico`. When absent, the root is discovered by walking up from the working directory to the nearest ancestor holding a `build.sbt`. |
| `--bundle-dir <dir>` | Directory holding the compiled `main.js`. Optional. |

> **Note:** When `--bundle-dir` is absent (the deploy default), the generator discovers the bundle under `<repo-root>/kyo-website-bundle/js/target/scala-<version>/`, choosing the `fullLinkJS` `-opt` output with sorted-deterministic discovery so a stale `-fastopt` directory never wins. This is why step 1 (`fullLinkJS`) must run before step 2: a missing `main.js` raises a `WebsiteEmitException`, so a missing bundle fails the build loudly rather than emitting a site with a broken script reference.

`emit` writes one output tree. For each documentation version under a route `<prefix>` (the version's own tag, e.g. `v1.0.0-RC2`, plus the duplicated `latest`), it writes the page HTML, the raw Markdown the client fetches, and the pre-rendered article each docs route serves:

```
<outDir>/index.html                       landing page
<outDir>/<prefix>/index.html              per-version overview page
<outDir>/<prefix>/content.md              raw root-README intro Markdown
<outDir>/<prefix>/content.html            pre-rendered article JSON for the SPA
<outDir>/<prefix>/<slug>/index.html       per-module docs page
<outDir>/<prefix>/<slug>/content.md       raw module README Markdown
<outDir>/<prefix>/<slug>/content.html     pre-rendered article JSON
<outDir>/<prefix>/manifest.json           module list + TOC + prev/next
<outDir>/<prefix>/search-index.json       per-section ranked search index
<outDir>/versions.json                    version manifest
<outDir>/sitemap.xml  robots.txt  CNAME  .nojekyll
<outDir>/kyo.svg  kyo.png  kyo.ico  main.js  main.js.map
```

The preview server in step 3 is `demo.ServeSite`. It is a kyo-http test demo (`kyo-http/shared/src/test/scala/demo/ServeSite.scala`), not a `kyo-website` source: it loads a directory into memory and answers every GET by resolving the path to a file, mapping directory paths to `index.html`. Run it with the rendered directory and an optional port: `sbt 'kyo-httpJVM/Test/runMain demo.ServeSite site 8474'`.

Two traps when iterating locally, both consequences of how the pieces load:

> **Caution:** `ServeSite` loads the directory into memory once at startup. After every regeneration (a re-run of step 2), restart `ServeSite` or it keeps serving the previous files.

> **Caution:** A CSS change needs BOTH a `fullLinkJS` and a regen, not just a regen. The stylesheet is one shared value injected client-side from the bundle as well as embedded at build time, so a CSS edit that skips relinking the bundle leaves the browser running the old styles.

## How the pieces fit (architecture)

With the input, output, and workflow in hand, here is how the source splits and why. The module is a JVM + JS cross-project with three source trees, each defined by what it is allowed to reach.

| Tree | Holds | Reaches |
|------|-------|---------|
| `shared/` | The renderer and content model: `SiteApp`, `LandingApp`, `DocsApp`, `WebsitePage`, `WebsiteStyles`, `WebsiteContent`, `WebsiteModule`, `WebsiteVersion`, and the `DocsMarkdown.Heading` carrier | Both JVM and JS |
| `jvm/` | Build-time-only machinery: the generator (`WebsiteGenerator`), the CLI (`WebsiteMain`), and the scalameta-backed Markdown transpiler (`DocsMarkdownRender`) | JVM only |
| `kyo-website-bundle` | The browser SPA (`WebsiteBundleMain`, `DocsClient`) re-linked from the shared code as an ESModule | JS only |

The split between `shared/` and `jvm/` is enforced by a classpath wall. scalameta (the Scala syntax highlighter behind `DocsMarkdownRender`) is a JVM-only library, and the transpiler lives in `jvm/` so scalameta never reaches the JS link classpath. The shared `DocsMarkdown` object holds only the `Heading` carrier (level, plain text, anchor slug), the one render-namespace type both shared and JS sources may reference.

> **Caution:** Moving transpiler code into `shared/` breaks the JS link, because scalameta would then be pulled onto the bundle's classpath. The transpiler stays JVM-only by design. When you need a render type in both worlds, the rule is placement: only `DocsMarkdown.Heading` crosses the wall. `WebsiteBuildGraphTest` fails the build if scalameta appears outside the JVM settings.

When you need to flatten or highlight a README into HTML at build time, that is `DocsMarkdownRender` (JVM): it transpiles a README string into a kyo-ui article subtree plus a heading outline. When you only need the heading outline that crosses to the browser, that is `DocsMarkdown.Heading` (shared): the manifest, the boot islands, and the search index all thread it. The transpiler runs once at build time and never ships to the client; the browser consumes the pre-rendered article it produced.

The transpiler degrades, it does not fail. Unknown inline constructs become plain text, unknown blocks become a verbatim paragraph, and empty input renders empty. Its effect row is `Sync` only, with no `Abort`, by design.

> **Note:** A malformed README never aborts the build at the transpile step. The transpiler always produces some article. The build-time aborts come from the content model (a missing required README) and the emit step (a write failure), described below, never from a Markdown construct the transpiler does not recognize.

### The hydration-parity contract

The reason `SiteApp` lives in `shared/` is the central architectural idea. `SiteApp.view` is the one shell both the JVM generator and the JS bundle render: one persistent header above a single route-reactive content slot. The JVM render of a route and the bundle's first render produce a structurally identical `data-kyo-path` tree, so the SPA boots over the server HTML without a flicker, then takes over navigation in the browser without remounting the header.

This works because the shell carries no `org.scalajs.dom`. Everything it needs is passed in as plain values, and its side-effecting behavior is injected as callback parameters. The generator's `siteShell` passes no-ops (a no-op navigate, a constant empty search index); the bundle passes live JS effects (a real `UILocation.push`, the live search index signal). Because a no-op and the live effect produce the same DOM structure, the first render matches.

> **Unlike** a typical SPA that renders a different initial tree than its server prerender, `SiteApp` renders the same compiled code on both paths. A new interactive feature on the shell must pass through the callback seam: a directly-wired DOM handler in `SiteApp` would not compile on the JVM and would break parity. The `ChromeParityTest` guard fails the build when the two renders diverge.

The shell wraps two content bodies. `LandingApp.body` builds the evergreen landing-page body, and `DocsApp.body` assembles the 2-pane documentation body (a left rail of module groups plus a content area). `WebsitePage.wrap` is the single HTML-document boundary for every route: it maps a route's `Options` (title, description, canonical URL, bundle href, JSON-LD, noindex, data islands) to a page head and returns the document stream. `WebsiteStyles.sheet` is the single global stylesheet, a kyo-ui `Stylesheet` value the page embeds at build time and the bundle injects client-side, so there is no parallel CSS file to keep in sync.

> **Note:** `DocsApp.body` has two public overloads, not one signature. The canonical 7-argument form takes a `navOpenRef: SignalRef[Boolean]` (the mobile drawer's open state), which the bundle owns so it can wire the section-link close. The 6-argument convenience overload creates a fresh internally-owned `navOpenRef` and is what the SSG and tests call, since they render statically and never open the drawer.

## The content model and the README table

This is how a tag's READMEs become the navigation a reader clicks through. `WebsiteContent` is the complete render input for one documentation version: the full root-README Markdown (`intro`), the grouped modules, and the version record. `WebsiteContent.fromRepo` reads an extracted tag tree and parses the root README's `## Modules` section into the sidebar model.

Each `### <Group>` heading under `## Modules`, plus its GFM table, becomes one `WebsiteContent.Group` (the group name and its modules in README order). Each table row becomes a `WebsiteModule`: its URL slug, the group it belongs to, its display title, the raw README Markdown read from `<slug>/README.md`, and its `Platforms` flags. `WebsiteModule.displayName` derives the nav-rail label from the slug (strip the leading `kyo-`, split on `-`, capitalize, join), so `kyo-core` shows as `Core`.

`WebsiteModule.Platforms` is the per-module JVM / JS / Native / WASM support, read positionally from the platform columns (`JVM | JS | Native | WASM`). WASM is the fourth platform column and is present only on tags whose table carries it.

> **Note:** A legacy tag whose table predates the WASM column parses with `wasm = false`, so older release tags still build. The size guard reads WASM only when the row has enough cells; on a shorter legacy row, the cell that would hold WASM is the decorative Identity column, not a platform flag.

The model splits between failing loud and degrading, depending on whether the input signals a real problem or a legacy shape:

> **Note:** A root README with no `## Modules` section degrades to an intro-only version (empty groups), which is the common shape for the 87 pre-`v1.0.0-RC2` tags. A bare-directory link (no `<slug>/README.md`) is dropped, not aborted. But a genuinely-absent referenced README aborts the build (`WebsiteReadmeException.Missing`), and a malformed module table aborts (`MalformedTable` / `MalformedGroups`). Loud failure for real problems, graceful degrade for legacy tags.

Version order has one definition, owned by `WebsiteVersion`. `WebsiteVersion.parse` totally parses a `vMAJOR[.MINOR[.PATCH]][-PRE]` tag into `Parsed` components and returns `Absent` for anything that does not start with `v`, never throwing. `WebsiteVersion.tagOrdering` sorts oldest-first: a pre-release sorts before the stable release of the same `major.minor.patch`, and unparseable tags sort before all parseable ones (so they land oldest and are never selected as latest). When you need to test whether a string is a version tag, reach for `parse` (it answers with a `Maybe[Parsed]`); when you need to order a set of tags or pick the newest, reach for `tagOrdering`. "Latest" is the newest stable release, or the newest pre-release when no stable tag exists.

## Search, SEO, and the emitted JSON

Not everything the generator emits is HTML. The client search, the SPA's boot data, and the SEO surface are JSON, XML, and plain text, and they exist because the browser and the crawlers need machine-readable forms of the site.

`DocsSearch` is the client-side ranked search engine. Its index is flat at the section level: every `##` / `###` section of every module is one searchable document carrying its heading, anchor slug, prose body, and the API symbols it mentions (so a type name like `Abort` resolves to the section that documents it). `DocsSearch.filter` runs a field-boosted TF-IDF query and returns hits best-first; `DocsSearch.seed` builds the synchronous title-only seed the SPA uses before the full index finishes loading. The carrier types (`Index`, `Entry`, `Section`, `Hit`, `Heading`) are what the emitted `search-index.json` deserializes into.

The SPA reads its route data from the JSON the generator emits. Each docs page embeds two boot islands (`#docs-island` and `#versions-island`, JSON `<script>` blocks injected before `</body>`); `WebsiteBundleMain` reads the docs island at entry to seed the first paint, and `DocsClient` fetches `versions.json` and `manifest.json` for the route table and `content.html` for each navigated page. The `DocsMarkdown.Heading` outline is the carrier threaded through the islands, the manifest, and the search index alike.

The SEO surface is the sitemap, robots, JSON-LD, and the canonical rules. The sitemap lists the canonical-indexable routes with a single build-date `<lastmod>`; robots is allow-all plus a sitemap directive.

> **Note:** `latest/` is a duplicate emission, not a symlink, because GitHub Pages serves files and not symlinks. The current-latest version's own `/v<X>/<slug>/` pages canonicalize to `/latest/<slug>/`, and only the `/latest/` tree appears in the sitemap, so search engines index one copy and never the duplicate.

> **Note:** Everything visual in the site is a kyo-ui DSL value, but the dependency set carries no JSON encoder, so all island, manifest, endpoint, and JSON-LD output funnels through a single `escJson` helper. XML (the sitemap), plain text (robots), and JSON are the three sanctioned raw-string emit sites; everything else is a typed kyo-ui value.

## Build-time failures

The build either produces a correct site or aborts loud; it never ships a half-built one. Failures are a single sealed family, `WebsiteException`, produced by the generator (a reader never constructs one):

- `WebsiteReadmeException(path, detail)`: a README could not be read or parsed. The `detail` is a typed `ReadmeFailure` (`Missing`, `MalformedGroups`, or `MalformedTable`), so a missing required README, a malformed group structure, and a malformed module table are distinct, inspectable cases rather than one opaque string.
- `WebsiteMarkdownException(slug, detail)`: a genuinely unexpected failure in the render pipeline. This is rare, because the transpiler degrades unknown constructs to plain text rather than raising; it signals a problem the degrade path did not cover.
- `WebsiteEmitException(route, cause)`: writing a route's file failed (for example, the missing `main.js` that a skipped `fullLinkJS` leaves behind).

`emit` aborts over `WebsiteException` on the first failure, so the whole build stops at the first real problem instead of writing a partial tree. The `WebsiteMain` entry point reports the typed failure to the console.
