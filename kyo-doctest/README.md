# kyo-doctest

kyo-doctest validates the Scala code blocks inside your README (and any other Markdown files) by sending each one to the Scala 3 compiler and asserting the outcome you declared. The typical adopter never instantiates `Doctest.Config` directly: they add `.enablePlugins(KyoDoctestPlugin)` to an sbt project, the plugin defaults `doctestSources` to `README.md`, and `sbt doctest` forks a JVM, compiles every block, and fails the build on any unexpected outcome. Each fenced block is treated as an independent compile unit by default, so examples in docs stay copy-pasteable; an info-string DSL (`doctest:scope=...`, `doctest:expect=...`, `doctest:platform=...`, `doctest:setup`) lets a doc author chain blocks, declare blocks that must fail to compile, hide a setup fixture inside an HTML comment, or restrict a block to a single platform.

Under the hood there are two surfaces. The sbt plugin (`KyoDoctestPlugin`) exposes the task and setting keys that almost every user touches. The validator JVM tool (`kyo.doctest.Doctest.check`) is the embedded entry point: anyone wiring doctest into a non-sbt build (Mill, a CI script, an editor integration) calls it directly with a `Doctest.Config`.

The composed picture: a Markdown file becomes a queue of validated blocks.

````markdown
<!-- File-level defaults: every block below inherits prior names unless it opts out. -->
<!-- doctest:default scope=inherited -->

# Counter

<!-- doctest:setup
```scala
case class Counter(value: Int):
    def inc: Counter = Counter(value + 1)
```
-->

```scala doctest:expect=skipped
// Visible to readers, sees the Counter case class via setup prelude.
val c0 = Counter(0)
```

```scala doctest:expect=skipped
// Continues the inherited chain; sees c0.
val c1 = c0.inc
```

```scala doctest:expect=skipped
// Negative example. Must produce a type error; the build fails if it compiles.
val wrong: Counter = 0
```

```scala doctest:expect=skipped
// JVM-only API. Skipped on JS and Native compile passes.
val t = java.lang.System.currentTimeMillis()
```
````

That single document exercises four of the five DSL surfaces (setup, expectation, platform, per-file default) and the implicit happy-path scope (`scope=inherited` from the file default). The setup fixture is wrapped in an HTML comment so it compiles into the prelude but stays invisible to readers.

## Getting started with sbt

The 95% path is the sbt plugin. You add the plugin to `project/plugins.sbt`, enable it on the projects whose READMEs you want validated, and call `sbt doctest`. No Scala code is needed; everything is sbt setting/task wiring.

### Adding the plugin

In `project/plugins.sbt`:

```text
addSbtPlugin("io.getkyo" % "sbt-kyo-doctest" % "VERSION")
```

In `build.sbt`, enable the plugin on each project whose Markdown should be validated:

```text
lazy val myLib = project
    .enablePlugins(KyoDoctestPlugin)
```

> **Note:** the plugin uses `trigger = noTrigger`, so adding it to `plugins.sbt` does not auto-enable anything. Every project that needs doctest validation must call `.enablePlugins(KyoDoctestPlugin)` explicitly. This is intentional: you choose which modules pay the compile cost.

### The three tasks

You will use three tasks in practice. They differ only in cache behavior.

```text
sbt doctest        # Validate; write the cache; fail the build on any block failure.
sbt doctestFresh   # Same validation, but use a throwaway cache directory.
sbt doctestClean   # Empty the cache directory. Next run is fully cold.
```

`doctest` is the everyday command. The content-hash cache means that running `doctest` again after editing one paragraph re-validates only the blocks whose content changed.

`doctestFresh` runs in a throwaway cache directory for CI configurations that want guaranteed cold runs. Same validation, same exit code, no cache write. If your CI cache layer already saves the project's `target/` directory, `doctest` is faster and equally clean.

`doctestClean` is the manual reset. Use it when you suspect a stale cache entry (for example, after upgrading the plugin or a transitive library that changes block outcomes).

### Tuning settings

The setting keys cover the dimensions a real project needs to control. Defaults are listed alongside each key.

`doctestSources` (`Seq[File]`, default: `Seq(baseDirectory.value / "README.md")`). The list of Markdown files to validate. The default is the project's own README; widening to every `*.md` in the tree is a common override:

```text
lazy val myLib = project
    .enablePlugins(KyoDoctestPlugin)
    .settings(
        doctestSources := (baseDirectory.value ** "*.md").get
    )
```

If the default `README.md` does not exist, the build fails with `Doctest.Error.SourceNotFound`. Enabling the plugin means "I want my README validated"; a missing README is a build error, not a silent no-op.

`doctestScalacOptions` (`Seq[String]`, default: `Seq("-release", "17")`). Scalac options forwarded to the doctest compiler for every block. **This is NOT `scalacOptions.value`.** Warning flags, source-level upgrades (`-source:future`), custom compiler plugins, none of these are inherited from the project's main scalac options. If you want them in doctest, set them on this key too:

```text
lazy val myLib = project
    .enablePlugins(KyoDoctestPlugin)
    .settings(
        doctestScalacOptions := Seq(
            "-release", "17",
            "-source:future",
            "-Wunused:imports"
        )
    )
```

`doctestPredef` (`Seq[String]`, default: empty). Lines auto-injected at the top of every block's wrapped source. **kyo-doctest itself ships no library defaults; if you want `import kyo.*` in every block of your kyo library's README, you set it here.** Predef lines are visible to all block scopes, including `scope=env:NAME` groups.

```text
lazy val myKyoLib = project
    .enablePlugins(KyoDoctestPlugin)
    .settings(
        doctestPredef := Seq(
            "import kyo.*",
            "import kyo.doctest.*"
        )
    )
```

`doctestCacheDir` (`File`, default: `target / "doctest-cache"`). The content-hash cache directory.

`doctestParallel` (`Int`, default: `Runtime.getRuntime.availableProcessors`). Max concurrent block compiles. Lower it on a memory-constrained CI runner.

`doctestFreshDriver` (`Boolean`, default: `false`). Whether to rebuild the Dotty `Compiler` per block instead of reusing one warm instance.

> **Caution:** `freshDriver=false` is the fast path because the Dotty driver is reused across blocks. Modules whose macros register denotations into the compiler's symbol table (notably anything depending on dotty-cps-async) trip a `denotation invalid in run N` assertion on the second compile. For those modules you must set `doctestFreshDriver := true`. The compile cost grows roughly linearly with block count.

## Writing validatable Markdown

The Markdown DSL lives entirely on the code block's info string (`scala doctest:KEY=VALUE`) and on optional file-level `<!-- doctest:default -->` comments. Three orthogonal axes (scope, expectation, platform) and a `setup` sugar cover the cases that come up writing real READMEs. A separate axis, the **carrier**, controls whether readers see the block at all.

The defaults you get without writing any DSL are deliberate: `scope=isolated`, `expect=compiles`, `platform=jvm,js,native` (or the subset of those the project supports). That means a bare `scala` fenced block must type-check on its own, with no leakage from prior blocks. **The default is "compiles," not "runs." A block whose body throws at runtime still passes validation unless you opt in with `expect=runs` or `expect=crashes`.**

### Scope: what names a block sees

Scope answers the question "when this block is compiled, which other blocks' bindings are visible?" Four values cover the spectrum from "nothing" to "everything in named bucket."

`scope=isolated` (default): the block compiles standalone. It sees the predef and any setup-block bindings (those are file-wide preludes, not scope contributions), but no other blocks.

```scala
val xs = List(1, 2, 3)
xs.sum
```

`scope=inherited`: the block sees names introduced by all prior `scope=inherited` blocks in document order. This is the notebook-style chain.

````markdown
```scala doctest:expect=skipped
val xs = List(1, 2, 3)
```

```scala doctest:expect=skipped
// xs is in scope here.
xs.sum
```
````

`scope=nested`: prior names are visible (like `inherited`), but bindings introduced in this block do NOT leak to the next block. Useful for "show one variation, then continue from the prior block."

`scope=env:NAME`: blocks sharing the same name form one cumulative scope, independent of document order. Two named environments in the same file are completely isolated from each other.

````markdown
```scala doctest:expect=skipped
val client = HttpClient.default
```

```scala doctest:expect=skipped
val store = InMemoryStore.empty
```

```scala doctest:expect=skipped
// Only client is visible here; store is in a different env.
client.shutdown()
```
````

> **Note:** `scope=isolated` blocks STILL see setup-block bindings. The setup body is injected as a prelude in the synthetic source the compiler sees, regardless of the block's chosen scope. "Isolated" means "no other non-setup block leaks in," not "no preamble at all."

### Expectation: what success means

Expectation answers "what counts as a valid outcome for this block?" The default is `Compiles`, which is the cheapest assertion and the right answer for most documentation prose.

`expect=compiles` (default): the block must type-check without error. The body is not executed.

`expect=runs`: the block must type-check AND execute without throwing. Use this when the example value is the point of the example (numeric results, side effects on a fixture).

```scala
List(1, 2, 3).map(_ * 2) // doctest:expect=runs
// The block compiles AND runs; the validator throws if execution raises.
```

`expect=fails-compile`: the block must produce at least one type error. **Negative examples in documentation.** The build fails if the block accidentally compiles.

````markdown
```scala doctest:expect=skipped
val n: Int = "not an int"
```
````

`expect=warns`: the block must compile and produce at least one compiler warning. Useful for showing deprecation behavior.

`expect=crashes`: the block must throw at runtime. Symmetrical to `fails-compile`: the example IS the failure being demonstrated.

`expect=skipped`: the block is parsed (so its line range surfaces in error reports) but neither compiled nor executed. Use for `scala` blocks that hold pseudocode or partial fragments you want to keep in the document.

> **Note:** `expect=compiles` is the default precisely because executing every block is expensive and most documentation blocks are illustrations, not assertions about a runtime value. If you want runtime guarantees on an example, you must mark it `expect=runs` explicitly; doctest will not silently upgrade.

### Platform: which compile targets the block applies to

Platform restricts which scalac targets see the block. Three values, plus `all`, combine with commas.

`doctest:platform=jvm` selects JVM only.
`doctest:platform=js` selects Scala.js only.
`doctest:platform=native` selects Scala Native only.
`doctest:platform=jvm,js` selects both JVM and JS, but not Native.
`doctest:platform=all` selects all three (the default when no platform is specified).

```scala
// JVM-only example using java.lang.System.
// doctest:platform=jvm
val started = java.lang.System.currentTimeMillis()
```

Use platform restriction sparingly. If a block needs to be marked platform-specific, that is also a signal to the reader that the API is not cross-platform, and prose around the block should say so.

### `doctest:setup`: file-wide prelude injection

`doctest:setup` is the only modifier that does not use `KEY=VALUE` form. It is sugar for `scope=env:__doc__` and triggers a file-wide prelude: the body of every setup block in the file is injected at the top of every non-setup block's wrapped source.

This is the right tool when you have a small fixture (one case class, one helper) that every example needs but should not be visible in the reader-facing prose. Wrap it in an HTML comment to hide it entirely from rendered markdown.

````markdown
<!-- doctest:setup
```scala
case class User(name: String, age: Int)
val alice = User("Alice", 30)
```
-->

```scala doctest:expect=skipped
// alice is visible because of the setup-block prelude.
alice.name
```
````

> **Note:** there is no `expect=setup` value. The setup mechanism is purely about scope (cumulative inside `env:__doc__`) plus a prelude side effect on every other block.

### Per-file defaults

When most blocks in a file share the same scope or expectation, repeating the modifier on every block adds noise. A single HTML comment at the top of the file sets defaults that every block inherits.

````markdown
<!-- doctest:default scope=inherited expect=runs -->

# My Document

```scala doctest:expect=skipped
// Inherits scope=inherited, expect=runs from the file default.
val x = 1
```

```scala doctest:expect=skipped
// Overrides expect just for this block; still scope=inherited.
val y: Int = x + 1
```
````

The resolution rule is three-tier: per-block tokens win, then per-file defaults, then the hardcoded defaults (`Isolated`, `Compiles`, all three platforms).

> **Caution:** the `<!-- doctest:default ... -->` comment must appear in the first non-blank lines of the file, before any non-comment content. A defaults block that appears after the first content line is treated as an ordinary HTML comment and silently ignored.

### Carriers: reader visibility, orthogonal to compilation

A code block can sit in the document body two ways:

`Visible` (default): a plain ```` ```scala ```` fenced block, or a block inside a `<details>` element. Both are visible to readers. The `<details>` form is collapsed by default in GitHub's rendering, expandable on click; that is standard markdown behavior with no doctest-specific meaning.

`Hidden`: inside an `<!-- ... -->` block. Invisible to readers entirely. Use when you need a block to compile but do not want it shown (negative-control type checks, fixture state the reader does not need to see).

```markdown
<!--
```scala doctest:expect=skipped
val secret = "internal fixture"
```
-->
```

> **Note:** the carrier controls visibility only. A `doctest:setup` block hidden in `<!-- ... -->` is compiled identically and injects its prelude identically to a `Visible` setup block. The DSL and the validator treat both carriers as equivalent inputs.

### Unknown modifiers: hard fail vs silent ignore

The parser distinguishes between "this is a doctest modifier I don't recognize" and "this is some other tool's directive."

An unknown `doctest:` token (for example `doctest:randomize=true`) raises `Doctest.Error.ParseError` and aborts the whole run.

An unknown non-`doctest:` token on the info string (for example `mdoc:reset`, `nowarn`) is silently ignored. This is intentional: kyo-doctest is meant to coexist with other Markdown tooling that may decorate the same info strings.

## Embedding the validator

When sbt is not your build (Mill, a CI script, an editor integration, a custom rule in Bazel), call the validator directly. The entry point is `Doctest.check`, which takes a `Doctest.Config` and produces a `Report`.

```scala
import kyo.*
import kyo.doctest.*

val config = Doctest.Config(
    sources    = Chunk(Path("docs/README.md"), Path("docs/guide.md")),
    classpath  = Chunk(Path("target/scala-3.7.4/classes")),
    scalaOpts  = Chunk("-release", "17"),
    cache      = Path(".doctest-cache"),
    parallel   = 8,
    predef     = Chunk("import myproject.*"),
    freshDriver = false
)

val report: Doctest.Report < (Sync & Async & Scope & Abort[Doctest.Error]) =
    Doctest.check(config)
```

The `Config` fields are 1:1 with the sbt plugin's setting keys. `sources` and `classpath` are mandatory inputs; the remaining fields take defaults that match the plugin (`parallel = availableProcessors`, `predef = Chunk.empty`, `freshDriver = false`).

### Interpreting the `Report`

A successful run produces a `Report` describing what happened across all blocks.

```scala doctest:expect=skipped
report.map { r =>
    println(s"validated ${r.totalBlocks} blocks")
    println(s"  ${r.cacheHits} served from cache")
    println(s"  ${r.compiled} actually compiled")
    if r.failures.nonEmpty then
        r.failures.foreach { f =>
            println(s"FAIL: ${f.file}:${f.line}: ${f.message}")
        }
}
```

`totalBlocks` is the count parsed from the sources; `cacheHits` is the subset whose content hash matched a previous run; `compiled` is the count actually sent to the driver. `cacheHits + compiled == totalBlocks - skipped` (skipped blocks are parsed but neither cached nor compiled).

`failures: Chunk[Failure]` is the per-block outcome list. Empty on full success. Each `Failure` carries the source `file`, the `line` number of the opening code-block marker, and a human-readable `message` whose line numbers are mapped back to the Markdown source.

### `Failure` vs `Doctest.Error`: per-block vs whole-run

There are two kinds of failure, and they surface in different places.

A per-block compile failure (a type error inside one fenced block, a missed expectation, a runtime exception in an `expect=runs` block) lands in `Report.failures` as a `Doctest.Failure`. It does NOT raise `Abort`. The validator continues processing the remaining blocks.

A run-aborting failure (the Dotty driver could not be initialised, a source path does not exist, the cache file is corrupt, the Markdown is structurally unparseable) raises `Abort[Doctest.Error]`. The validator does not attempt to produce a partial report.

```scala doctest:expect=skipped
import kyo.*
import kyo.doctest.*

val handled: Doctest.Report < (Sync & Async & Scope) =
    Abort.run(Doctest.check(config)).map {
        case Result.Success(report)                              => report
        case Result.Failure(Doctest.Error.SourceNotFound(path)) => Doctest.Report(0, 0, 0, Chunk.empty)
        case Result.Failure(err)                                 => throw new RuntimeException(s"doctest setup failed: $err")
    }
```

> **Caution:** a handler that only inspects the `Abort` channel will miss every type error in user blocks. Per-block failures are data, not errors. To make the validator fail-on-any-block, check `report.failures.nonEmpty` on the success side.

The five `Doctest.Error` variants:

```scala doctest:expect=skipped
enum Error:
    case DriverInitFailed(cause: Throwable)
    case SourceNotFound(path: Path)
    case CacheCorrupt(path: Path, cause: Throwable)
    case ParseError(file: Path, line: Int, message: String)
    case NoSourcesConfigured
```

`DriverInitFailed` indicates a corrupt or incompatible classpath: the Dotty `Compiler` could not initialise. `SourceNotFound` means a path in `Config.sources` does not exist on disk. `CacheCorrupt` means an on-disk cache entry could not be deserialised; delete the cache directory and rerun. `ParseError` is a structural problem in the Markdown itself (an unknown `doctest:` modifier, malformed `<!-- doctest:default -->` block) and includes the line number for the offending location. `NoSourcesConfigured` is raised when `Config.sources` is empty; enabling doctest without any sources to validate is always a build error.

### Working with failures

Each `Doctest.Failure` in `report.failures` carries three fields: `file` (the Markdown source path), `line` (the 1-indexed line number of the opening ` ```scala ` marker), and `message` (a human-readable description with line numbers mapped back to the Markdown source).

```scala
import kyo.*
import kyo.doctest.*

val r: Doctest.Report = Doctest.Report(
    totalBlocks = 2, cacheHits = 0, compiled = 2,
    failures = Chunk(Doctest.Failure(Path("README.md"), line = 42, message = "type mismatch"))
)
r.failures.foreach { f =>
    println(s"FAIL ${f.file}:${f.line}: ${f.message}")
}
```

A typical reporter pattern: group `report.failures` by `f.file` for per-file summaries. The `message` field already contains the full diagnostic text with Markdown-relative line numbers.
