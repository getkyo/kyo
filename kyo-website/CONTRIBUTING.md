# Contributing to kyo-website

Module-specific guidance for `kyo-website`. This complements the root
[CONTRIBUTING.md](../CONTRIBUTING.md), which covers the global conventions this
guide assumes: `using`-clause ordering, `inline` guidelines, naming, the Kyo
primitive types (`Maybe`, `Result`, `Chunk`), `Frame` requirements, the
`KyoException` convention, the safe/unsafe two-tier API pattern, the test
framework (base trait, leaf/group DSL, assert-on-concrete-values), file
organization, and scaladoc formatting. Read it first. This guide records only
what is specific to kyo-website.

## The one thing to internalize: the hydration-parity contract

`kyo-website` is a static-site generator (JVM) and a single-page-app bundle (JS)
that **share one shell**. `SiteApp.view` is the one shell both the JVM static-site
generator and the JS bundle render, so a route's server-rendered HTML and the
bundle's first render produce a structurally identical `data-kyo-path` tree
(`SiteApp.scala:11-19`). This is the hydration-parity contract, and it is the
single judgment every change in this module is measured against: **the same shared
`SiteApp` / `LandingApp` / `WebsiteStyles` code runs on both paths, so a change
must keep both paths producing identical structure.** A separate JVM-render branch
and JS-render branch that drift is the one failure this module is built to
prevent. The dedicated parity test (`ChromeParityTest`, INV-003) fails the build
when they diverge (`ChromeParityTest.scala:55-66`).

Read the deep treatment in [The hydration-parity contract](#the-hydration-parity-contract-deep)
before touching `SiteApp`, `LandingApp`, `WebsiteStyles`, or the bundle entry.

## Table of contents

- [Architecture at a glance](#architecture-at-a-glance)
- [The hydration-parity contract (deep)](#the-hydration-parity-contract-deep)
- [The content model](#the-content-model)
- [The shared/JVM classpath wall](#the-sharedjvm-classpath-wall)
- [Conventions](#conventions)
- [Extension recipes](#extension-recipes)
- [Build, serve, regen](#build-serve-regen)
- [Testing](#testing)
- [Adding a new X: decision checklist](#adding-a-new-x-decision-checklist)

## Architecture at a glance

The module is a `crossProject(JSPlatform, JVMPlatform)` with `CrossType.Full`
(`build.sbt:1387-1390`). Native is intentionally not a target: the generator needs
one host and the deploy runs on JVM (`build.sbt:1385-1386`). It depends on
`kyo-ui` and `kyo-parse` (`build.sbt:1391-1392`); it does not directly depend on
`kyo-http` (that is reachable only transitively via `kyo-ui`, `build.sbt:1331`).

The three source trees split by what each can reach:

| Tree | Holds | Why |
|------|-------|-----|
| `shared/` | The renderer + content model that compiles to both JVM and JS: `SiteApp`, `LandingApp`, `WebsiteStyles`, `WebsitePage`, `WebsiteContent`, `WebsiteModule`, `WebsiteVersion`, the `DocsMarkdown.Heading` type, and the JS bundle entry surface | `UI.runRenderPage` is shared and `WebsiteStyles.sheet` is a pure value (`WebsitePage.scala:13-16`) |
| `jvm/` | Build-time-only surface: the generator (`WebsiteGenerator`), the CLI (`WebsiteMain`), and the scalameta-backed Markdown transpiler / syntax highlighter (`DocsMarkdownRender`) | `DocsMarkdownRender` lives in `kyo-website/jvm/` so scalameta never reaches the JS link classpath (`DocsMarkdownRender.scala:24-25`) |
| `js/src/test` | Empty | All tests are cross-platform or JVM-only; see [Testing](#testing) |

A fourth project, `kyo-website-bundle`, is a separate JS-only crossProject that
re-links the shared code as a browser-loadable ESModule (Chrome only). Its Compile
classpath holds `kyo-website.js` + `kyo-ui.js` so the linked bundle has no
Node-only `require` calls and loads in Chrome as `<script type="module">`
(`build.sbt:1417-1432`); `fullLinkJS` runs in deploy. The bundle re-links as
ESModule for Chrome (`build.sbt:1406-1411`).

### Build-time vs client-time data flow

```
git tag (v*) --git archive--> root README.md + <slug>/README.md tree
   |
   |  WebsiteContent.fromRepo (shared) parses the README's ## Modules table
   v
WebsiteContent value (intro markdown + grouped modules + version)
   |
   |  WebsiteGenerator.emit (JVM) transpiles markdown, renders SiteApp.view
   v
output dir: index.html, per-version/per-module index.html + content.md + content.html,
            manifest.json, versions.json, sitemap.xml, robots.txt, CNAME, .nojekyll,
            logos, main.js (the bundle)
   |
   |  GitHub Pages serves the files; the bundle (main.js) boots in the browser
   v
WebsiteBundleMain reads the #docs-island JSON, mounts SiteApp.view ONCE,
   drives one nav fiber that fetches content.html and swaps article + TOC in place
```

Content comes from git tags; the renderer lives on `main`. `WebsiteContent` is a
value (not files) so the renderer on `main` can render any tag's content
(render-from-tags, INV-006) (`WebsiteContent.scala:5-7`).

## The hydration-parity contract (deep)

### Goal

One shell, two renderers, identical structure. The SSG (JVM) emits the HTML at
build time; the SPA (JS) renders the same shell as its first paint, then takes
over navigation in the browser without remounting the page. If the two renders
disagree structurally, the bundle's first render disturbs the server HTML and the
page flickers or breaks. Keeping them identical is the contract.

### Mechanism 1: one shell, route info passed as plain values

`SiteApp` carries no `org.scalajs.dom`. The route information it needs is passed in
as plain values: `content` is a `Signal[UI]` the caller already built, the JVM
passes a constant signal, and the JS bundle passes a `SignalRef` updated by its nav
fiber (`SiteApp.scala:21-25`). Because the shell never references the JS-only
router or DOM directly, the exact same compiled code runs on both platforms.

The shell structure is a bare flex-column wrapper (base div rule): the full-bleed
header stacks above the one content slot, which is a single reactive boundary at a
fixed position (`SiteApp.scala:118-141`). The header is rendered once by
`SiteApp.view`; the nav fiber only ever rewrites the content signal (and, for docs
pages, the shared `articleRef` / `tocRef`), so the header never remounts on
navigation (`WebsiteBundleMain.scala:95-98`).

### Mechanism 2: renderer-vs-content injection via constructor callbacks

The shell takes its side-effecting behavior as callback parameters. The SSG passes
no-ops; the bundle passes live JS effects (`SiteApp.scala:100-111`):

- The SSG generator's `siteShell` passes `(_: String) => Kyo.unit`, `Kyo.unit`,
  `Kyo.unit`, and `Signal.initConst(DocsSearch.Index(Chunk.empty))`
  (`WebsiteGenerator.scala:284-302`).
- The bundle passes `target => UILocation.push(target).andThen(scrollToHash())`,
  `toggleTheme`, and the live `searchIndex` / `content` signals
  (`WebsiteBundleMain.scala:169-182`).

Because the no-op and the live effect produce the same DOM structure, the first
render matches. This is why a new interactive feature on the shell must pass
through this callback seam: a directly-wired DOM handler in `SiteApp` would not
compile on the JVM and would break parity.

### Mechanism 3: the stylesheet is the same value on both paths

`WebsiteStyles.sheet` is a pure `Stylesheet` value. The SSG puts it in
`PageHead.css`; the same value is injected client-side via `UI.runStylesheet` by
the bundle entry (`WebsitePage.scala:6-11`, `WebsiteStyles.scala:11-12`). There is
no parallel CSS file to keep in sync.

### Mechanism 4: the article is transpiled once at build time, never re-run client-side

The SPA consumes pre-rendered article HTML emitted by the SSG; it does not call the
Markdown transpiler on navigation (`DocsClient.scala:11-14`). The client fetches
the SSG's exact HTML and must not HTML-escape it again: use `UI.rawHtml`, never
`UI.text` (`DocsClient.scala:112-113`). The transpiler (`DocsMarkdownRender`) is
JVM-only and never ships to the browser.

### Mechanism 5: the boot islands seed the SPA

Each docs page embeds a `#docs-island` and a `#versions-island` JSON
`<script type="application/json">`, injected immediately before `</body>`. The
islands are built as `UI.DataIsland` values via `UI.dataIsland(...)` and carried on
`PageHead.dataIslands` (`WebsiteGenerator.scala:502-505`). The kyo-ui
`HtmlRenderer` renders them and owns the single `escScript` implementation that
escapes `<` and `>` to `&lt;`/`&gt;`, ensuring a `</script>` substring in any
field cannot close the element early (`HtmlRenderer.scala:82-97`). The website
contains no `escScript` call of its own. The `#docs-island` is the first-paint
payload schema: `WebsiteBundleMain` reads it at bundle entry via `readDocsIsland`
to seed the SPA with the current page's content before navigation, and the
`article` field carries the pre-rendered HTML so the client never needs to call
the transpiler. The same rendered bytes are produced by the new path, so the
bundle's island reader (`WebsiteBundleMain.scala:63-82`) is unaffected. Reading
the island at JS entry is a synchronous parse before any Kyo fiber is running,
marked Unsafe (`WebsiteBundleMain.scala:78-81`).

Island JSON is parsed by a hand-rolled depth-aware scanner in `DocsClient`
(`splitJsonArray` tracks brace/bracket depth and string state,
`DocsClient.scala:403-446`); there is no JSON library. The manifest/island JSON
carries only slug/group/title (the fields the client nav needs); it does not
serialize per-platform support or the raw README, and `Platforms(true, true, true,
true)` is an unused placeholder there, not a claim that every module is
cross-platform (`DocsClient.scala:391-401`).

### The bundle boot sequence

`WebsiteBundleMain` (`WebsiteBundleMain.scala:8-29`):

1. Inject `WebsiteStyles.sheet` into `<head>`.
2. Read the islands + `UILocation.current`.
3. Mount `SiteApp` **once** around one reactive content slot driven by a single
   nav fiber.

The JS-bootstrap island reads and the mount are the Unsafe-marked crossings
(`WebsiteBundleMain.scala:85-90`). Beyond these boot crossings the bundle
holds zero `js.Dynamic` calls; all remaining browser interactions use the typed
kyo-ui members. Five typed DOM boundaries that the kyo-ui DSL does not cover
remain as confined casts or direct DOM calls in the bundle:

1. **`data-theme` + `color-scheme` writes** on `<html>`: `setTheme` calls
   `UIWindow.storageGet/Set`, `UIWindow.prefersColorScheme`, and a single
   `root.asInstanceOf[dom.html.Element].style.setProperty(...)` for the CSS
   `color-scheme` property, which `Element` does not expose
   (`WebsiteBundleMain.scala:191-241`).
2. **Canonical link update**: `updateHead` calls `UIWindow.setTitle` for
   the title and a direct `dom.document.querySelector` + `setAttribute` for
   the `<link rel=canonical>` element, which has no kyo-ui counterpart
   (`WebsiteBundleMain.scala:485-519`).
3. **URL-hash read**: `scrollToHash` / `maybeScrollToHash` call
   `dom.window.location.hash` because `UILocation.current` omits the fragment
   (`WebsiteBundleMain.scala:556-570`).
4. **`UI.rawHtml` article injection**: the pre-rendered article HTML is injected
   via `UI.rawHtml` (the named escape hatch for trusted HTML content) so the
   Markdown transpiler never runs on the client
   (`WebsiteBundleMain.scala:137,471`).
5. **JS-bootstrap island reads** (read `#docs-island` / `#versions-island`
   via `document.getElementById` then parse JSON): these are the Unsafe-marked
   synchronous crossings at bundle entry before any Kyo fiber starts
   (`WebsiteBundleMain.scala:63-82,612-621`).

The nav fiber classifies each route into four kinds (`RouteKind` enum: `Landing`,
`Module`, `Intro`, `OffTree`, `WebsiteBundleMain.scala:351-352`) and dispatches.
`classifyRoute` is a pure, testable decision (`WebsiteBundleMain.scala:354-369`).
Off-tree routes hand off to a full browser navigation so the server resolves the
real page or a clean 404 instead of fetching a missing `content.html` into a broken
docs shell (`WebsiteBundleMain.scala:423-429`).

`Module` and `Intro` routes share `showContentRoute`
(`WebsiteBundleMain.scala:443-494`): they fetch `content.html` cache-first and swap
article + TOC in place. The article cache is a module-level mutable `Map` seeded
from the island (single-threaded JS, Unsafe-marked, `WebsiteBundleMain.scala:45-50`).
`loadingRef` gates the prev/next pager and is lowered on every exit via `Sync.ensure`
(`WebsiteBundleMain.scala:455-471`).

### Two head-divergence traps the contract pins down

1. **The `/` (landing) route must resolve into the `latest` physical tree.** The
   bundle must use the same `latest` prefix for `/`, NOT the island's version tag;
   using the version tag would point `docsHome` at `/v1.0.0-RC2/...` and diverge
   from the SSG header, breaking hydration parity on `/`
   (`WebsiteBundleMain.scala:36-43`).
2. **After every in-shell swap, the nav fiber updates `document.title` and
   `<link rel=canonical>` using the SAME formats the SSG emitted.** The in-browser
   head must never diverge from what crawlers indexed
   (`WebsiteBundleMain.scala:23-27`).

### The decision rule for any shell change

Before changing `SiteApp`, `LandingApp`, or the bundle entry, ask: does this change
produce a different `data-kyo-path` tree on the JVM render than on the JS render? If
the change adds interactivity, route it through a callback parameter (Mechanism 2),
default it to a no-op on the SSG, and confirm the empty/initial state renders the
same structure on both. The header search box is the worked example: at the empty
query the dropdown is an empty container, so the SSG shell and the bundle's first
render are structurally identical (`SiteApp.scala:36-37`). Then run
`ChromeParityTest`.

## The content model

### `WebsiteContent` is the complete render input for one version

`WebsiteContent` is the complete render input for one documentation version: the
full root-README markdown, the grouped modules, and the version record. It is a
value (not files) so the renderer on `main` can render any tag's content
(`WebsiteContent.scala:5-13`). The case class is
`WebsiteContent(intro: String, groups: Chunk[WebsiteContent.Group], version:
WebsiteVersion)` (`WebsiteContent.scala:14-18`).

- `intro` holds the entire root README verbatim, rendered as the Overview page with
  fidelity (the transpiler is the only transformation); no section is sliced out
  (`WebsiteContent.scala:8-12`, `WebsiteContent.scala:50`).
- A `Group` is one sidebar group: a root-README `### <Group>` heading name plus its
  modules in README order (`WebsiteContent.scala:22-24`).
- A `WebsiteModule` is one module's doc-page input: its URL slug, the group it
  belongs to, its display title, the raw README Markdown, and which platforms it
  supports (`WebsiteModule.scala:3-12`).
- `displayName` is a derived nav-rail label: strip the leading `kyo-`, split on `-`,
  capitalize each segment, join with spaces (`kyo-core` becomes `Core`,
  `kyo-stats-registry` becomes `Stats Registry`, `WebsiteModule.scala:13-21`).
- `title = slug` by design: the root README module table has no separate title
  column, and the slug (`kyo-core`, `kyo-data`, ...) is the display title
  (`WebsiteContent.scala:182-185`).

### How the README table is parsed

`fromRepo` reads the extracted tag tree and parses the root README's `## Modules`
table into sidebar groups (`WebsiteContent.scala:10-12`). The upstream half of this
data flow is the deploy `git archive` extraction: the deploy flow extracts each
`v*` tag into `root` with `git archive <tag> README.md '*/README.md' | tar -x`, so
`root` holds the tag's root `README.md` and a `<slug>/README.md` per module
(`WebsiteContent.scala:28-30`, `.github/workflows/deploy-site.yml`).

The `## Modules` section is found as a standalone heading, sliced to the next
top-level `##`; each `### <Group>` plus GFM table becomes a `Group`
(`parseGroups` `WebsiteContent.scala:63-75`; `sectionMarker`
`WebsiteContent.scala:80-86`; `sliceUntilTopLevelSection`
`WebsiteContent.scala:91-101`; `splitGroups` `WebsiteContent.scala:109-121`). For
each table:

- `pipeCells` trims cells and removes leading/trailing empty cells
  (`WebsiteContent.scala:194-201`).
- `buildGroup` filters to `|`-starting lines, drops the header and separator rows;
  the remaining rows are modules (`WebsiteContent.scala:127-140`). A separator row
  is one whose every cell is non-empty and consists only of `-`/`:`
  (`WebsiteContent.scala:142-145`).
- A module table link `[slug](slug/README.md)` is read from `root/<slug>/README.md`
  (`WebsiteContent.scala:199-218`).

### The four-platform positional parse

Per-module platform support mirrors the root README's platform table columns
(`JVM | JS | Native | WASM`, `WebsiteModule.scala:26-31`). The current columns are
`| [slug](target) | JVM | JS | Native | WASM | Identity |`; the flags are read
positionally (JVM/JS/Native at cells 1/2/3, WASM at cell 4 when the row has at least
6 cells), and the trailing Identity column is decorative, not consumed
(`WebsiteContent.scala:148-154`, `WebsiteContent.scala:172-181`).

The size guard is load-bearing for backward compatibility: WASM is the 4th platform
column, present only on tags whose table carries it (>= 6 cells: slug + 4 platforms
+ Identity). A legacy 5-cell row has no WASM column, so `cells(4)` there is the
Identity prose, not a platform flag; the size guard keeps it from being misread as
WASM support (`WebsiteContent.scala:176-180`). A legacy tag whose table predates the
WASM column parses with `wasm = false`, so older release tags still build
(`WebsiteModule.scala:26-29`). The regression guards confirm a 6-column `❌` parses
`wasm=false` and a 5-column legacy Identity cell holding a `✅` still yields
`wasm=false` (`WebsiteContentTest.scala:367`, `WebsiteContentTest.scala:380-385`).

`isSupported` accepts `✅`, case-insensitive `yes`, or `x`
(`WebsiteContent.scala:229-232`). A `cells.size < 5` guard rejects genuinely
too-short rows as `MalformedTable` (`WebsiteContent.scala:165`).

`WebsiteModule.Platforms` is fully parsed but currently NOT rendered by any client
surface; the client never reads `module.platforms`
(`DocsClient.scala:391-401`). The landing page's "platforms" band
(`LandingApp.platforms`, `WebsiteStyles.landingPlatforms`) is hand-authored
marketing copy, unrelated to `WebsiteModule.Platforms`.

### Degrade-not-fail (INV-007)

Missing tables and bare-directory links degrade; only a genuinely-absent referenced
README aborts:

- **Degrade:** a root README with no `## Modules` section yields an intro-only
  `WebsiteContent` (empty groups), the common shape for the 87 pre-`v1.0.0-RC2`
  tags (`WebsiteContent.scala:29-35`, `case Absent => Chunk.empty` at
  `WebsiteContent.scala:68-69`).
- **Degrade:** a bare-directory link ships no doc README and is dropped, not
  aborted (`WebsiteContent.scala:134-138`).
- **Abort `Missing`:** a genuinely absent referenced README
  (`WebsiteContent.scala:33-34`, `readRequired` at `WebsiteContent.scala:56-61`).
- **Abort `MalformedTable`:** cell-count abort (`WebsiteContent.scala:165`),
  unparseable-link abort (`WebsiteContent.scala:168`).
- **Abort `MalformedGroups`:** guard at `WebsiteContent.scala:131-132`.

### Version ordering: one definition of "stable" and "latest"

`WebsiteVersion` owns version ordering and is reused everywhere:
`WebsiteVersion(tag, label, latest)` (`WebsiteVersion.scala:5-8`). `parse` parses
`vMAJOR[.MINOR[.PATCH]][-pre]` totally: it returns `Absent` for any tag that does
not start with `v` and never throws (`WebsiteVersion.scala:25-50`). `tagOrdering`
sorts oldest-first; a pre-release sorts BEFORE the stable release of the same
`major.minor.patch`, and tags that do not parse sort before all parseable tags (so
they land oldest and are never selected as latest); it is pure and `Frame`-free
(`WebsiteVersion.scala:67-91`, `WebsiteVersion.scala:77-80`). "Stable" is defined
once (`parse(...).preRelease.isEmpty`) and reused by both `WebsiteMain.pickLatestTag`
and `WebsiteGenerator.pickLatest` (`WebsiteGenerator.scala:204-206`). `pickLatest`
honors an explicit `version.latest = true` flag first, then falls back to the newest
stable; reusing `WebsiteVersion.parse` keeps one definition of stable
(`WebsiteGenerator.scala:192-210`).

## The shared/JVM classpath wall

scalameta is a JVM-only build-time library: it powers the Scala syntax highlighter
and must not reach the JS link classpath (`build.sbt:1397-1404`). The dependency is
`("org.scalameta" %% "scalameta" % "4.13.4").exclude("com.lihaoyi",
"sourcecode_2.13")`; the exclude resolves the `_2.13` vs `_3` cross-version conflict
that arises because `scalameta_3` transitively pulls in
`trees_2.13 -> common_2.13 -> sourcecode_2.13` (`build.sbt:1400-1402`).

Markdown is rendered with kyo-parse, not a third-party Markdown library (no
flexmark). The block-level recognizers (headings, list markers, table cells, fence
info-strings, badge/link/image inline tokens) are genuine kyo-parse parsers run via
`Parse.runResult` (`DocsMarkdownRender.scala:18-23`). The build comment records this
as a decision: cross-platform kyo-parse Markdown transpiler, `DocsMarkdown` in
`shared/`, no third-party Markdown dependency (`build.sbt:1383-1384`).

The wall: only `DocsMarkdown.Heading` crosses into shared/JS. `DocsMarkdown` (shared)
holds only the `Heading` type; the Markdown transpiler, syntax highlighter, and
rendered-article types live in `DocsMarkdownRender` (JVM-only) so scalameta never
reaches the JS link classpath (`DocsMarkdown.scala:6-22`). The `Heading` type is the
only render-namespace type reachable from shared and JS sources (INV-009)
(`DocsMarkdown.scala:15-16`). `Heading(level, text, slug)`: `level` is 1..4; `slug`
is lowercased, non-alphanumeric mapped to `-`, duplicates disambiguated with `-2`,
`-3`, ... (`DocsMarkdown.scala:25-32`).

This wall is mechanically guarded by `WebsiteBuildGraphTest`:

- **Guard A:** scalameta must appear ONLY in the `kyo-website` `.jvmSettings` block,
  never in the shared/js/bundle source trees (`WebsiteBuildGraphTest.scala:79-83`).
- **Guard B (INV-013):** if the `fullLinkJS`-optimized bundle output is present,
  grep it for any scalameta symbol references and assert zero occurrences
  (`WebsiteBuildGraphTest.scala:155-157`).
- **INV-002:** the client-side (bundle/js) trees must not reference the JVM-only
  transpiler surface (`WebsiteBuildGraphTest.scala:180-181`).

If you add a JVM-only dependency or move a transpiler type, expect these guards to
fire. The fix is placement, not a guard edit.

## Conventions

### Error and exception hierarchy

One typed exception family, sealed, in one file. Build-time failures extend a single
sealed `WebsiteException` base:
`sealed abstract class WebsiteException(...)(using Frame) extends KyoException(...)`
(`WebsiteException.scala:11-12`). Each leaf is a top-level `final case class` named
`Website<Mode>Exception` carrying typed structured fields, never a free-text string
(`WebsiteException.scala:17-18`, `WebsiteException.scala:37-38`):

- `WebsiteReadmeException(path: Path, detail: WebsiteReadmeException.ReadmeFailure)`
- `WebsiteMarkdownException`
- `WebsiteEmitException(route: String, cause: Throwable)`

A leaf's failure "detail" is a typed enum nested in that leaf's companion:
`enum ReadmeFailure derives CanEqual: case Missing, MalformedGroups, MalformedTable`
(`WebsiteException.scala:25-27`).

### Abort over the module's own exception type

Effectful methods that can fail abort over `WebsiteException`, never a raw
filesystem exception:
`def emit(...)(using Frame): Unit < (Async & Abort[WebsiteException])`
(`WebsiteGenerator.scala:74`),
`def parseContent(...)(using Frame): Chunk[WebsiteContent] < (Sync &
Abort[WebsiteException])` (`WebsiteMain.scala:76`).

The canonical adapter from the kyo-data filesystem effect to
`Abort[WebsiteException]` runs `Abort.run[File...Exception](...)` and maps the three
`Result` arms: `Success` to the value, `Failure` to the typed module exception,
`Panic` re-raised via `Abort.error(p)`:

- Read adapter: `Failure -> WebsiteReadmeException(path, ...Missing)`
  (`WebsiteGenerator.scala:109-112`).
- Write adapter: `Failure -> WebsiteEmitException(path.toString, e)`
  (`WebsiteGenerator.scala:601-604`).
- Degrade arm: `Success -> value; Failure -> Chunk.empty; Panic -> Abort.error(p)`
  (`WebsiteMain.scala:93-97`).

The `KyoApp` entry (`WebsiteMain`) wraps the emit in `Abort.run[WebsiteException]`
and reports the three `Result` arms to the console
(`WebsiteMain.scala:46-62`).

### TRAP: `WebsiteMain` is a `KyoApp` inside `kyo.website`

`Frame` cannot be auto-derived inside the `kyo.*` namespace (root CONTRIBUTING.md
Common Gotchas). `WebsiteMain` works around this with `Frame.internal` plus a
`// Unsafe:` rationale comment:
`run(program(using Frame.internal))(using Frame.internal, summon[Render[Unit]])`
(`WebsiteMain.scala:30-35`).

### Visibility

Methods for test/intra-module use only are scoped `private[website]`, never
`protected`: `private[website] def parseContent(...)`,
`private[website] def flagValue(...)` (`WebsiteMain.scala:76`,
`WebsiteMain.scala:194`).

### Everything visual is a kyo-ui value

No raw CSS, HTML, or SVG strings anywhere in the site. Elements opt into
`WebsiteStyles.sheet` rules via `UI.cssClass(...)` (`LandingApp.scala:17-18`); no
raw CSS string is used anywhere in the site (`WebsiteStyles.scala:11-12`).

That rule is about CSS, HTML, and SVG specifically. `sitemap.xml` (raw XML,
`WebsiteGenerator.buildSitemapXml`), `robots.txt` (raw plain text, `buildRobotsTxt`),
and the island and manifest JSON (no JSON encoder is in the dependency set) are
sanctioned non-DSL emit sites: no kyo-ui DSL covers XML, plain text, or JSON, so each
is isolated behind a named builder function carrying an in-source `// Justified:`
comment. They are the carve-out the no-raw-markup rule implies, not an exception to it.

- **The single global stylesheet is one `Stylesheet` value.** `lazy val sheet:
  Stylesheet = buildSheet` (`WebsiteStyles.scala:33`); new styling extends
  `buildSheet`, never a CSS file (`WebsiteStyles.scala:144-174`).
- **A rule is added with `.rule("class-name", Style....)`** or
  `.rule(Selector.cls(...).descendant(Selector.tag(...)), Style....)`; raw selector
  strings are never used (`WebsiteStyles.scala:204`, `WebsiteStyles.scala:301-304`).
- **A color literal is produced once via the private `hex` helper**, with the
  resolved `Color` stored in a `private val`:
  `private def hex(s: String): Color = Color.hex(s).getOrElse(Color.transparent)`,
  `private val inkSection = hex("#16150F")` (`WebsiteStyles.scala:37`,
  `WebsiteStyles.scala:41`).
- **SVG icons use the kyo-ui `Svg` DSL,** never an inline `<svg>` string
  (`SiteApp.scala:49-59`, `SiteApp.scala:76-84`).

Every CSS class emitted by a `cssClass` call MUST have a matching rule;
`WebsiteStylesCoverageTest` renders both apps and asserts every emitted class has a
matching rule, so a future unstyled class fails the build
(`WebsiteStyles.scala:9-13`).

### The kyo-ui flex-reset trap (recurring)

kyo-ui's baseline reset gives every element `display: flex` (block-level tags lay
out as a column, inline tags as a row, `WebsiteStyles.scala:18-21`). This shapes
several conventions you must follow:

- **Mixed text + inline children:** use the `Style.block` / `Style.inline`
  flex-reset escape. The hero h1 is `Style.block.fontFamily(...)`; the accent span
  inside it is `Style.inline` (`WebsiteStyles.scala:389,398`,
  `WebsiteStyles.scala:877,882`).
- **Transpiled prose:** the base reset makes EVERY element `display: flex`, which
  shatters flowing prose, so the `docsProse` rules opt the article back into normal
  CSS flow (`WebsiteStyles.scala:1179-1188`). Every HTML element in the transpiled
  article must be opted back in.
- **`<nav>` / `<ul>` alignment:** `Style.column` flips the direction but leaves
  `align-items: center`; `align(_.stretch)` is required so every group fills the
  sidebar width and shares one left edge (`WebsiteStyles.scala:1031-1039`).
- **Showing/hiding:** needs an explicit `display` (`Style.block` /
  `Style.displayNone`), not `Style.row` / `Style.column`. `Style.row` only sets the
  flex props and leaves `display` to the reset, so it could not override `none`
  (`WebsiteStyles.scala:1454-1469`).

### Theme model

`lightVars` is the `:root` default; `darkVars` (same keys) is applied both by the
`prefers-color-scheme: dark` media block and by the explicit `data-theme` toggle
(`WebsiteStyles.scala:52-58`). `themeOverrides` is emitted LAST in the sheet so
that, at equal specificity with the media block's `:root`, source order makes the
explicit choice win (`WebsiteStyles.scala:120-127`). `--btn` / `--btn-deep` are
kept separate from `--accent` so the button stays dark enough for white label text
while `--accent` (links, emphasis) lightens in dark mode for contrast
(`WebsiteStyles.scala:56-58`).

### The per-file `html(...)` splat-lift helper

Each UI file that builds children dynamically copies a private `html(cs: Seq[UI])`
helper: the implicit `UI -> HtmlChildVal` conversion does not apply element-wise
through a splat, so this lifts a dynamically-built `Seq[UI]` into the splat type an
HTML container accepts (`LandingApp.scala:54-56`, `SiteApp.scala:41-44`). It is
always applied as `container(html(seq)*)`, with fixed leading/trailing children
prepended/appended to the `Seq` first (`LandingApp.scala:263-267,363`).

### Component method shape

A `UI`-producing component method takes `(using Frame)` and returns `UI` (pure) or
`UI < Sync` (when it builds a Signal or uses `defer`):
`def hero(docsHome: String)(using Frame): UI` (pure),
`def body(docsHome: String)(using Frame): UI < Sync = Sync.defer {...}`
(`LandingApp.scala:60`, `LandingApp.scala:37`).

Hand-tokenized landing code uses the exact same `tok-*` class names as the docs
highlighter, so both share the highlighter color rules
(`LandingApp.scala:271-331`, `DocsMarkdownRender.scala:963-970`,
`WebsiteStyles.scala:1428-1436`).

## Extension recipes

### Add a new module doc page

Edit the root `README.md` `## Modules` table only; no website code changes
(`WebsiteContent.scala:11-12,159-176`). The link kind decides whether a page is
emitted: a `<slug>/README.md` link is a documentation module; a bare-directory link
yields `Absent` and the module is dropped (`WebsiteContent.scala:159-176,208-218`).
The module `title` is the slug by design; there is no place to set a different title
in the table (`WebsiteContent.scala:182-184`).

### Add a new landing-page section

1. Write a `private def <name>(using Frame): UI` returning a `UI.section` /
   `UI.div`. The nine existing section functions are the templates
   (`LandingApp.scala:60,154,180,335,368,406,418,432,449`).
2. Add it to `LandingApp.body` in document order (`LandingApp.scala:37-52`).
3. A section needing the active docs prefix takes the `mod: String => String`
   function (`val mod = (slug: String) => s"/$prefix/$slug/"`); a section needing
   only the docs home takes the `docsHome` string (`LandingApp.scala:39-49`).
4. Add the section's CSS rules (see below) so `WebsiteStylesCoverageTest` passes.

### Add a highlighted code snippet inside a section

Build from the `tKey` / `tType` / `tStr` / `tCom` / `tOp` / `tNum` token helpers
inside `UI.div.cssClass("code")(UI.pre(UI.code(...)))`
(`LandingApp.scala:271-331`, `WebsiteStyles.scala:1426-1437`). These reuse the same
`tok-*` classes as the docs highlighter.

### Add or change a CSS rule

Pick the matching sub-stylesheet `private def` and add `.rule("<class>", Style...)`
(`WebsiteStyles.scala:374-440,619-691`). Constraints:

- Every class a `cssClass` call emits MUST have a rule in `WebsiteStyles.sheet`;
  `WebsiteStylesCoverageTest` asserts `landing classes with no stylesheet rule` is
  empty (`WebsiteStyles.scala:11-13`, `WebsiteStylesCoverageTest.scala:156-164`).
- `buildSheet` cascade assembly order matters: source order is the cascade order
  (`WebsiteStyles.scala:144-174`). `landingLadder` is appended after the other
  landing sheets so its refinements win at equal specificity
  (`WebsiteStyles.scala:156-159`). `themeOverrides` is emitted LAST; inserting a new
  sheet after it would shadow the explicit theme choice
  (`WebsiteStyles.scala:120-122,172-173`).

### Add a responsive override or breakpoint

Add a `.media(Stylesheet.MediaQuery.maxWidth(<n>.px))(...)` block inside the
`responsive` def (`WebsiteStyles.scala:1473-1571`). Existing breakpoints are at
820/880/900/560/640/860px (`maxWidth`) and 861/1024px (`minWidth`)
(`WebsiteStyles.scala:1473-1572`). Two traps:

- A flex card sized by `flexBasis` must be re-sized in a media block with
  `flexBasis`, not `width` (a width override is ignored while flexBasis stays at the
  3-up 30%) (`WebsiteStyles.scala:1502-1507`).
- `docs-sidebar-open` is declared AFTER `docs-sidebar` so, when both classes are
  present, its `display:block` wins the equal-specificity cascade
  (`WebsiteStyles.scala:1540-1560`).

### The manifesto is a synthetic docs page wired inside the generator

The MANIFESTO is read once from repo root (not a tag tree) and appended as the final
docs group of every version. It is REQUIRED: a missing `MANIFESTO.md` aborts the
build (`WebsiteReadmeException.Missing`) rather than silently shipping a site
without it (`WebsiteGenerator.scala:82-89`, `WebsiteGenerator.scala:103-114`). A
contributor who moves or renames it breaks deploy loud by design.
`withManifestoGroup` appends it as a final one-page group named "Manifesto" with
`slug = "manifesto"`, but only to versions that actually have a docs menu
(`withManifesto = content.map(c => if c.groups.nonEmpty then withManifestoGroup(c,
manifesto) else c)`, `WebsiteGenerator.scala:84-89,116-129`). README links to the
manifesto are rewritten by `DocsMarkdownRender.rewriteReadmePath`
(`if path == "MANIFESTO.md" then "manifesto/" + fragment`,
`DocsMarkdownRender.scala:751-773`). Intra-repo README links use the
`<dir>/README.md` form so they resolve to the site route
(`../kyo-prelude/README.md` becomes `../kyo-prelude/`,
`DocsMarkdownRender.scala:751-772`).

### Add a new per-route emit-time output file

Call `writeString` / `writeRoute` and wire it into `emitDocs` or `emit`, alongside
the existing `writeManifest` / `writeSearchIndex` calls
(`WebsiteGenerator.scala:145-160,398-410,439-449`). The generator is JVM-only; the
apps and stylesheet are in `shared` (`WebsiteGenerator.scala:5-6`,
`LandingApp.scala:1-2`, `WebsiteStyles.scala:1-2`).

## Build, serve, regen

### The generator entry and CLI

`WebsiteGenerator.emit` is the single entry point; it sequences the full artifact
tree: landing + 404 + each version + latest mirror + versions.json + artifact-root
files + sitemap + robots + copy assets (`WebsiteGenerator.scala:70-101`). The full
output layout is documented in the generator scaladoc
(`WebsiteGenerator.scala:30-39`). Notable emit-time behaviors:

- The SSG HTML is the first emission of `WebsitePage.wrap`. The document is produced
  by draining the first emission of `WebsitePage.wrap(opts)(view)`; taking only the
  first emission gives the initial static render, and subsequent reactive re-renders
  are irrelevant for SSG (`WebsiteGenerator.scala:12-15`,
  `WebsiteGenerator.scala:597-598`). `WebsitePage.wrap` is the single HTML-document
  boundary for every route; it builds the `<head>` and returns a `Stream[String,
  Async]` (`WebsitePage.scala:6-12,37-77`).
- Each docs route ships three sibling files: `index.html`, `content.md`, and
  `content.html` (`emitIntroPage` `WebsiteGenerator.scala:212-243`, `emitModulePage`
  `WebsiteGenerator.scala:245-275`).
- `latest/` is a duplicate emission, not a symlink (Pages serves files, not
  symlinks); it mirrors the newest stable version (or newest pre-release when no
  stable tag exists) (`WebsiteGenerator.scala:177-190`). A current-latest version's
  own `/v<X>/<slug>/` pages canonicalize to `/latest/<slug>/`
  (DECISION-SEO-A, `WebsiteGenerator.scala:168-170,314-337`).
- The sitemap `<lastmod>` is the build date, computed ONCE and threaded to the pure
  builders so emitted artifacts are deterministic for a given run
  (DECISION-SEO-C, `WebsiteGenerator.scala:78-80`).
- `copyAssets` copies `kyo.svg`, `kyo.png`, `kyo.ico`, `main.js`, `main.js.map` at
  emit time (`WebsiteGenerator.scala:719-733`); a missing `main.js` reports a
  `WebsiteEmitException`, so a missing bundle fails loud rather than emitting a site
  with a broken script reference (`WebsiteMain.scala:131-137`).
- `writeArtifactRootFiles` emits `CNAME` (`getkyo.io`) and `.nojekyll` so the custom
  domain survives the Pages cutover (`WebsiteGenerator.scala:655-659`).
- The 404 page deliberately ships NO bundle (`bundleHref = ""`) so the SPA does not
  boot on an unknown path and loop into a full-navigate
  (`WebsiteGenerator.scala:561-579`).

The CLI is `WebsiteMain`, a `KyoApp` that drives `WebsiteGenerator.emit`
(`WebsiteMain.scala:5,28`). Flags: `--out <dir>` (required output dir),
`--content <dir>` (one `<tag>/` subdir per version), `--repo-root` (for assets),
`--bundle-dir` (optional, `WebsiteMain.scala:12-26`). When `--bundle-dir` is absent
the directory is discovered under
`<repo-root>/kyo-website-bundle/js/target/scala-<version>/` (the `fullLinkJS` `-opt`
output holding `main.js`), using sorted-deterministic discovery so a stale
`-fastopt` directory never wins (`WebsiteMain.scala:14-17,128-130`). The repo root
is found as the nearest ancestor directory holding a `build.sbt`, because under
`sbt 'kyo-websiteJVM/run ...'` the forked cwd is `kyo-website/jvm`, not the repo
root (`WebsiteMain.scala:170-176`). `parseContent` reads one `WebsiteContent` per
tag dir, sorted by SEMANTIC version (`WebsiteVersion.tagOrdering`, oldest-first), not
lexicographically (`WebsiteMain.scala:67-74`); `pickLatestTag` chooses the newest
stable, else the newest pre-release, order-independently via `max`
(`WebsiteMain.scala:105-117`).

### Rebuild and preview locally (three steps)

1. `sbt 'kyo-website-bundleJS/Compile/fullLinkJS'` (link the bundle,
   `deploy-site.yml:39-40`, `WebsiteMain.scala:6-7`).
2. `sbt 'kyo-websiteJVM/run --out <dir> --content <dir> --repo-root <root>'`
   (render, `WebsiteMain.scala:9-27`, `deploy-site.yml:47-48`).
3. `sbt 'kyo-http/Test/runMain demo.ServeSite <rootDir> [port]'` (serve, default
   port 8474, `ServeSite.scala:5-9,60-62`).

Two traps when iterating locally:

- `ServeSite` loads the directory into memory ONCE and must be restarted after each
  regen (`ServeSite.scala:36-49,64-71`).
- A CSS change needs BOTH `fullLinkJS` AND a regen, because the stylesheet is
  injected client-side from the bundle (`runStylesheetUnsafe` runs
  `UI.runStylesheet(WebsiteStyles.sheet)`) as well as embedded at build time
  (`WebsitePage.scala:10-11,73`, `WebsiteBundleMain.scala:11,88,636-640`).

### The deploy pipeline

`deploy-site.yml` is the regen pipeline, triggered on release published, push to
`main`, and manual dispatch. Steps:

1. `sbt 'kyo-website-bundleJS/Compile/fullLinkJS'`.
2. Per-tag `git archive "$tag" README.md '*/README.md' | tar -x -C "content/$tag"`.
3. `sbt 'kyo-websiteJVM/run --out site --content content --repo-root
   "$GITHUB_WORKSPACE"'`.
4. `actions/upload-pages-artifact@v3`.

The render step omits `--bundle-dir` on purpose: when the flag is absent,
`WebsiteMain` discovers the `fullLinkJS` output directory under
`kyo-website-bundle/js/target/scala-<version>/` (the `-opt` sibling holding
`main.js`). Nothing in the workflow writes the generated output back to the repo
with git; the site ships only as the Pages artifact (INV-011).

Search is title-only at boot and upgraded eagerly: `titleIndex` seeds from the boot
island, then `Fiber.initUnscoped(refreshSearchIndex(...))` upgrades it
(`WebsiteBundleMain.scala:160-167`); `refreshSearchIndex` keeps the seed on any
failure, with no Abort widening (`WebsiteBundleMain.scala:314-319`).

## Testing

Every suite extends `WebsiteTest`, the module-local base:
`abstract class WebsiteTest extends kyo.test.Test[Any]`
(`WebsiteTest.scala:3`). There is no Native target; tests run on JVM and JS only
(`build.sbt:1387-1390`), with `sbt 'kyo-websiteJVM/test'` and
`sbt 'kyo-websiteJS/test'`.

### The platform split for tests

`js/src/test` is empty and there is no `native/` tree; a test needing the JVM-only
surface must live in `jvm/src/test`. `ChromeParityTest` is JVM-only because
`RecordingBackend` uses `HtmlRenderer` directly, which is `private[kyo]`
(`ChromeParityTest.scala:17-19`). `DocsMarkdownRender.transpile` is JVM-only after
the split, so a shared leaf must build the `UI` AST by hand instead of transpiling
(`DocsAppTest.scala:194-199`, `WebsiteStylesCoverageTest.scala:125-126`).

### Rendering in tests

JVM render assertions drive the SSG renderer and take the first emission:
`html <- UI.runRender(view).take(1).run` then `html.headMaybe.getOrElse("")`
(`LandingAppTest.scala:9-13`, `DocsAppTest.scala:26-28`, `ChromeParityTest.scala:59`).
`RecordingBackend` is the JVM test helper that renders `UI` to the body fragment
`DomBackend.mountInto` would set, via `HtmlRenderer.render(ui, Seq.empty)`; use it
in JVM tests instead of the JS-only `UI.runMount`
(`RecordingBackend.scala:16-27`). Render-state inputs are supplied as `Signal`
values: `Signal.initRef(...)` for mutable, `Signal.initConst(...)` for fixed
(`DocsAppTest.scala:26`, `SiteAppTest.scala:27-40`, `ChromeParityTest.scala:41-52`).

### The parity guard (INV-003)

The hydration-parity guard asserts the SSG render and the mount render produce
byte-identical normalized HTML:
`assert(normalize(ssg) == normalize(mount), "SSG (runRender) and mount
(RecordingBackend) must produce identical HTML for the landing route shell
(INV-003)")` (`ChromeParityTest.scala:55-66,11-15`). Parity and equality comparisons
normalize away positional `data-kyo-path` values
(`html.replaceAll("""data-kyo-path="[^"]*"""", "data-kyo-path=\"\"")`,
`ChromeParityTest.scala:32-34`).

### Generator and content tests

- `WebsiteGeneratorTest` carries a byte-exact golden string for `manifest.json`:
  `assert(manifest == expectedManifest, ...)` (`WebsiteGeneratorTest.scala:1374-1392`).
- Generator tests assert concrete file contents, not existence; each test emits into
  a fresh temp directory; no git commits; output directories are ephemeral
  (`WebsiteGeneratorTest.scala:7-11,86-99`). The fixture creates a fresh temp out-dir
  plus a stub bundle dir holding a `main.js` / `main.js.map`
  (`WebsiteGeneratorTest.scala:47-56,70-75`).
- `WebsiteContentTest` exercises `fromRepo` by writing README fixtures into a fresh
  `Path.tempDir` tree (`WebsiteContentTest.scala:72-82`), and the fixtures paste a
  VERBATIM slice of the actual root `README.md`
  (`WebsiteContentTest.scala:283-299,343-353`).
- JVM tests locate the repo root by walking up from `user.dir` until `build.sbt` is
  found (`WebsiteBuildGraphTest.scala:9-16`, `DeployWorkflowTest.scala:20-27`). A
  `readFile` helper maps `FileReadException` into `WebsiteEmitException` and
  re-raises `Panic` (`WebsiteGeneratorTest.scala:77-82`,
  `WebsiteMainTest.scala:31-36`).
- Error-path leaves assert on the typed failure via `Abort.run[WebsiteException](...)`
  then match the typed leaves:
  `case Result.Failure(e: WebsiteReadmeException) => assert(e.detail ==
  ...ReadmeFailure.Missing, ...)` (`WebsiteContentTest.scala:132-150`,
  `WebsiteGeneratorTest.scala:217-241`).

### Build-graph and workflow guards

`WebsiteBuildGraphTest` enforces the JVM-only-dependency invariants by grepping the
source trees and `build.sbt` (`WebsiteBuildGraphTest.scala:24-37,84-98,182-195`). A
guard depending on a build artifact `cancel(...)`s when the artifact is absent, not
`fail(...)`s: `if !Files.exists(jsPath) then cancel("Guard B skipped: bundle-opt
main.js not present (run fullLinkJS first)")` (`WebsiteBuildGraphTest.scala:158-164`).
`DeployWorkflowTest` treats the workflow as a textual contract, matching substrings
rather than re-parsing the YAML, and scopes the "no `git commit` / `git push`" check
to the executed `run:` shell (`DeployWorkflowTest.scala:32-33,46-70,115-120`).
`WebsiteStylesCoverageTest` is the CSS-coverage guard
(`val missing = emitted -- styled -- kyoUiInternalClasses`,
`WebsiteStylesCoverageTest.scala:5-15,160-162`), paired with a non-vacuity assertion
that confirms the fixture actually emits those classes
(`WebsiteStylesCoverageTest.scala:176-187,189-200`).

### Other patterns

- Substring counting uses a local `countOccurrences` helper
  (`WebsiteGeneratorTest.scala:1044-1051`, `SiteAppTest.scala:197-204`).
- `DocsMarkdown` JVM tests transpile then render through helper pipelines
  (`transpileHtml`), reusing them rather than re-wiring per leaf
  (`DocsMarkdownTest.scala:9-20,517-519`). Token-highlight assertions match the
  rendered HTML span shape with HTML-encoding
  (`assert(html.contains("tok-keyword\">val</"), ...)`,
  `DocsMarkdownTest.scala:564-565,635`).
- Determinism/idempotence is pinned by emitting twice and asserting byte-identical
  output (`assert(html1 == html2, "index.html must be byte-identical across two
  emits")`, `WebsiteGeneratorTest.scala:247-264`, `ChromeParityTest.scala:207-218`).
- Forward-progress guards assert a wall-clock bound with a generous budget
  (`assert(elapsed < 30000L, ...)`, `DocsMarkdownTest.scala:470-510`).
- Type-level contracts are asserted with an explicit type annotation that fails to
  compile if the effect row widens
  (`val _: String => Frame ?=> UI < Sync = h => LandingApp.body(h)`,
  `LandingAppTest.scala:118`, `WebsiteContentTest.scala:42-50`).

## Adding a new X: decision checklist

Run through this before writing code:

1. **Am I adding content (a module page) or renderer behavior?** Content: edit the
   root `README.md` `## Modules` table only, no website code
   (`WebsiteContent.scala:11-12`). Renderer: continue below.
2. **Does my change render structure on the shell?** If yes, it must produce an
   identical `data-kyo-path` tree on the JVM render and the JS render
   (`SiteApp.scala:11-19`). Route any interactivity through a `SiteApp.view`
   callback parameter, default it to a no-op on the SSG
   (`SiteApp.scala:100-111`), confirm the empty/initial state matches on both paths,
   and run `ChromeParityTest` (`ChromeParityTest.scala:55-66`).
3. **Am I adding a JVM-only dependency or transpiler type?** It must live in `jvm/`,
   never in `shared/` or the bundle. Only `DocsMarkdown.Heading` crosses into
   shared/JS (`DocsMarkdown.scala:15-16`). `WebsiteBuildGraphTest` Guards A/B and
   INV-002 enforce this (`WebsiteBuildGraphTest.scala:79-83,155-157,180-181`).
4. **Am I styling something?** Use a kyo-ui value, never a raw CSS/HTML/SVG string
   (`WebsiteStyles.scala:11-12`). Add the rule to a `WebsiteStyles` sub-stylesheet
   `private def`; every `cssClass` you emit must have a matching rule or
   `WebsiteStylesCoverageTest` fails (`WebsiteStyles.scala:11-13`). Mind the
   flex-reset trap: mixed text + inline children need `Style.block` / `Style.inline`
   (`WebsiteStyles.scala:18-21`), and show/hide needs an explicit `display`
   (`WebsiteStyles.scala:1454-1469`). Mind cascade order: `themeOverrides` stays last
   (`WebsiteStyles.scala:120-127`).
5. **Can my code fail at build time?** Abort over `WebsiteException` (a typed leaf
   with structured fields), never a raw filesystem exception, using the
   `Abort.run[File...Exception]` adapter pattern (`WebsiteGenerator.scala:74,109-112`).
   Decide degrade vs abort per INV-007 (`WebsiteContent.scala:29-35`).
6. **What does my new component method return?** `UI` if it is pure;
   `UI < Sync` if it builds a Signal or uses `defer`
   (`LandingApp.scala:37,60`). It takes `(using Frame)`.
7. **Am I emitting a new output file?** Call `writeString` / `writeRoute` and wire
   it into `emitDocs` or `emit`; keep output deterministic (thread the build date,
   do not call `now` in a builder) (`WebsiteGenerator.scala:78-80,145-160`).
8. **Did I add a test?** Place it 1:1 with the source it covers (`FooTest.scala` for
   `Foo.scala`), extend `WebsiteTest`, put JVM-only-surface tests in `jvm/src/test`
   (`WebsiteTest.scala:3`, `ChromeParityTest.scala:17-19`), and assert on concrete
   values. If you changed the shell, the manifest, or the article HTML, update the
   parity/golden tests (`ChromeParityTest.scala:55-66`,
   `WebsiteGeneratorTest.scala:1374-1392`).
