# kyo-doctest

kyo-doctest validates the Scala code blocks inside your Markdown by compiling them, so the examples in your docs cannot rot. `KyoDoctestPlugin` auto-enables on every JVM project. Running `sbt doctest` finds the project's `README.md`, extracts every ```` ```scala ```` block, and type-checks each one against the project's own classpath, failing the build on any unexpected outcome. A project opts out with `.disablePlugins(KyoDoctestPlugin)`. Each fenced block is treated as an independent compile unit by default, so examples stay copy-pasteable. An info-string DSL (`doctest:scope=...`, `doctest:expect=...`, `doctest:platform=...`, `doctest:setup`) lets a doc author chain blocks, declare blocks that must fail to compile, hide a setup fixture inside an HTML comment, or restrict a block to a single platform.

There are two surfaces. `kyo-doctest-plugin` is the user-facing sbt plugin: it exposes the task and setting keys that almost every user touches. `kyo-doctest` is the JVM-only runner library that the plugin forks into over a temp JSON config, never called directly by users. Anyone wiring doctest into a non-sbt build (Mill, a CI script, an editor integration) calls `kyo.doctest.Doctest.check` directly with a `Doctest.Config`.

A single fenced block is the smallest unit of validation. The block below is type-checked against the project's classpath, and the build fails if it stops compiling:

```scala
val xs = List(1, 2, 3)
xs.sum
```

## Getting started with sbt

The 95% path is the sbt plugin. You add the plugin to `project/plugins.sbt` and call `sbt doctest`. No Scala code is needed: everything is sbt setting/task wiring.

### Adding the plugin

In `project/plugins.sbt`:

```text
addSbtPlugin("io.getkyo" % "kyo-doctest-plugin" % "VERSION")
```

That is all the wiring required. The plugin auto-enables on every JVM project, so no `.enablePlugins` call is needed.

> **Note:** doctest runs on every JVM project by default. To exclude one, add `.disablePlugins(KyoDoctestPlugin)` to that project.

### The three tasks

You will use three tasks in practice. They differ only in cache behavior.

```text
sbt doctest        # Format the blocks, validate, write the cache; fail the build on any block failure.
sbt doctestFresh   # Same validation, but use a throwaway cache directory.
sbt doctestClean   # Empty the cache directory. Next run is fully cold.
```

`doctest` is the everyday command. Before validating, it reformats every scala block in place with scalafmt and the repository's `.scalafmt.conf`, so doc examples stay in the codebase's style with no separate command. Formatting is a no-op when there is no `.scalafmt.conf`; a block that fails to parse (an intentionally broken `expect=fails-compile` example, pseudo-code) or carries a bare `noformat` token on its fence info string (for example ` ```scala noformat `) is left untouched. The content-hash cache means that running `doctest` again after editing one paragraph re-validates only the blocks whose content changed.

`doctestFresh` runs in a throwaway cache directory for CI configurations that want guaranteed cold runs. Same validation, same exit code, no cache write. If your CI cache layer already saves the project's `target/` directory, `doctest` is faster and equally clean.

`doctestClean` is the manual reset. Use it when you suspect a stale cache entry (for example, after upgrading the plugin or a transitive library that changes block outcomes).

### Tuning settings

The setting keys cover the dimensions a real project needs to control. Defaults are listed alongside each key.

`doctestSources` (`Seq[File]`, default: `Seq(baseDirectory.value / "README.md")`). The list of Markdown files to validate. The default is the project's own README; widening to every `*.md` in the tree is a common override:

```text
lazy val myLib = project
    .settings(
        doctestSources := (baseDirectory.value ** "*.md").get
    )
```

The default first looks for `README.md` in the project base directory, then falls back one directory up (so a cross-project JVM sub-directory such as `kyo-data/jvm/` resolves to `kyo-data/README.md`). If neither exists and `doctestSources` is left unset, the run fails with `Doctest.Error.NoSourcesConfigured`. A configured-but-missing source path, by contrast, fails with `Doctest.Error.SourceNotFound`.

`doctestScalacOptions` (`Seq[String]`, default: `Seq("-release", "25")`). Scalac options forwarded to the doctest compiler for every block. **This is NOT `scalacOptions.value`.** Warning flags, source-level upgrades (`-source:future`), custom compiler plugins, none of these are inherited from the project's main scalac options. If you want them in doctest, set them on this key too:

```text
lazy val myLib = project
    .settings(
        doctestScalacOptions := Seq(
            "-release", "25",
            "-source:future",
            "-Wunused:imports"
        )
    )
```

`doctestPredef` (`Seq[String]`, default: empty). Lines auto-injected at the top of every block's wrapped source. **kyo-doctest itself ships no library defaults; if you want `import kyo.*` in every block of your kyo library's README, you set it here.** Predef lines are visible to all block scopes, including `scope=env:NAME` groups.

```text
lazy val myKyoLib = project
    .settings(
        doctestPredef := Seq(
            "import kyo.*",
            "import kyo.doctest.*"
        )
    )
```

`doctestCacheDir` (`File`, default: `target / "doctest-cache"`). The content-hash cache directory.

`doctestParallel` (`Int`, default: `1`). Max concurrent block compiles. Raising it does not speed up cold runs: dotty's `ContextBase` carries a thread-ownership check that pins every compile to a single thread, so a higher value only adds fibers that queue on that one thread. The parallelism you actually get comes from cache hits and I/O, not from concurrent compilation.

`doctestFreshDriver` (`Boolean`, default: `false`). Whether to rebuild the Dotty `Compiler` per block instead of reusing one warm instance.

> **Caution:** `freshDriver=false` is the fast path because the Dotty driver is reused across blocks. Modules whose macros register denotations into the compiler's symbol table (notably anything depending on dotty-cps-async) trip a `denotation invalid in run N` assertion on the second compile. For those modules you must set `doctestFreshDriver := true`. The compile cost grows roughly linearly with block count.

`doctestForkJavaOptions` (`Seq[String]`, default: `Seq("-Xmx8G", "-Xss10M", "-XX:ActiveProcessorCount=2")`). JVM options for the forked doctest driver. Raise the heap for inline-heavy macro expansion (kyo-http, kyo-flow).

`doctestExtraClasspath` (`Seq[File]`, default: empty). Extra jars appended to the fork's classpath. This is how the plugin injects the kyo-doctest runner library into the fork.

`DoctestTag` (`Tags.Tag`). The concurrency tag the plugin attaches to every doctest task to cap doctest to a single fork build-wide. A build that replaces `Global / concurrentRestrictions` with `:=` should include `Tags.limit(KyoDoctestPlugin.DoctestTag, N)` in the replacement list to keep that bound.

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

````markdown
<!--
```scala doctest:expect=skipped
val secret = "internal fixture"
```
-->
````

> **Note:** the carrier controls visibility only. A `doctest:setup` block hidden in `<!-- ... -->` is compiled identically and injects its prelude identically to a `Visible` setup block. The DSL and the validator treat both carriers as equivalent inputs.

### Unknown modifiers: hard fail vs silent ignore

The parser distinguishes between "this is a doctest modifier I don't recognize" and "this is some other tool's directive."

An unknown `doctest:` token (for example `doctest:randomize=true`) raises `Doctest.Error.ParseError` and aborts the whole run.

An unknown non-`doctest:` token on the info string (for example `mdoc:reset`, `nowarn`) is silently ignored. This is intentional: kyo-doctest is meant to coexist with other Markdown tooling that may decorate the same info strings.

## Validating Markdown links

`sbt doctest` does not stop at code. The same run also validates the Markdown links in every source file, so a renamed file or a heading that drifted out from under a `#anchor` fails the build the same way a broken code block does. A broken relative link or a dangling anchor surfaces as a `Doctest.Failure` in the report, merged alongside the per-block compile failures.

Three kinds of links are recognised:

- **External links** are skipped. A target whose scheme is one of `http`, `https`, `mailto`, `tel`, `ftp`, `ws`, `wss`, or `data` is left alone; network reachability is out of scope.
- **Same-document anchors** (`#section-name`) are checked against the heading slugs of the same file, using GitHub's slug algorithm (lowercase, formatting punctuation stripped, whitespace to `-`).
- **Relative links** (`kyo-core/README.md`, `../CONTRIBUTING.md#scope`) are resolved against the containing file's parent directory. The target file must exist, and if the link carries an `#anchor` suffix that anchor must exist in the target file's heading set.

Links inside fenced code blocks and inside inline-code spans are masked out before extraction, so a Scala signature like `` `Abort.run[E1 | E2]` `` is treated as code rather than a `[text](target)` link and does not produce a false positive. Reference-style links and autolinks are not validated.

## Embedding the validator

When sbt is not your build (Mill, a CI script, an editor integration, a custom rule in Bazel), call the validator directly. The entry point is `Doctest.check`, which takes a `Doctest.Config` and produces a `Report`.

> **Note:** `kyo-doctest` (the runner) is JVM-only. Under sbt the plugin forks it and communicates over a temp JSON config/result file. Calling `Doctest.check` directly is the path only for non-sbt builds.

```scala
import kyo.*
import kyo.doctest.*

val config = Doctest.Config(
    sources = Chunk(Path("docs/README.md"), Path("docs/guide.md")),
    classpath = Chunk(Path("target/scala-3.7.4/classes")),
    scalaOpts = Chunk("-release", "25"),
    cache = Path(".doctest-cache"),
    parallel = 8,
    predef = Chunk("import myproject.*"),
    freshDriver = false
)

val report: Doctest.Report < (Sync & Async & Scope & Abort[Doctest.Error]) =
    Doctest.check(config)
```

The `Config` fields are 1:1 with the sbt plugin's setting keys. `sources` and `classpath` are mandatory inputs. The remaining fields take defaults that match the plugin (`parallel = 1`, `predef = Chunk.empty`, `freshDriver = false`).

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
    end if
}
```

`totalBlocks` is the count parsed from the sources; `cacheHits` is the subset whose content hash matched a previous run; `compiled` is the count actually sent to the driver. `cacheHits + compiled == totalBlocks - skipped` (skipped blocks are parsed but neither cached nor compiled). `warnings` is the number of blocks that compiled but emitted a compiler warning (blocks marked `doctest:expect=warns` are excluded, since their warning is expected); a non-zero value means an example teaches a warning-producing pattern and should be fixed.

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
        case Result.Success(report)                             => report
        case Result.Failure(Doctest.Error.SourceNotFound(path)) => Doctest.Report(0, 0, 0, 0, Chunk.empty)
        case Result.Failure(err)                                => throw new RuntimeException(s"doctest setup failed: $err")
    }
```

> **Caution:** a handler that only inspects the `Abort` channel will miss every type error in user blocks. Per-block failures are data, not errors. To make the validator fail-on-any-block, check `report.failures.nonEmpty` on the success side.

The six `Doctest.Error` variants:

```scala doctest:expect=skipped
enum Error:
    case DriverInitFailed(cause: Throwable)
    case SourceNotFound(path: Path)
    case CacheCorrupt(path: Path, cause: Throwable)
    case ParseError(file: Path, line: Int, message: String)
    case NoSourcesConfigured
    case IoError(path: Path, operation: String, cause: Throwable)
end Error
```

`DriverInitFailed` indicates a corrupt or incompatible classpath: the Dotty `Compiler` could not initialise. `SourceNotFound` means a path in `Config.sources` does not exist on disk. `CacheCorrupt` means an on-disk cache entry could not be deserialised, so delete the cache directory and rerun. `ParseError` is a structural problem in the Markdown itself (an unknown `doctest:` modifier, malformed `<!-- doctest:default -->` block) and includes the line number for the offending location. `NoSourcesConfigured` is raised when `Config.sources` is empty, which always indicates a build error. `IoError` is an I/O failure reading sources or writing the cache.

### Working with failures

Each `Doctest.Failure` in `report.failures` carries three fields: `file` (the Markdown source path), `line` (the 1-indexed line number of the opening ` ```scala ` marker), and `message` (a human-readable description with line numbers mapped back to the Markdown source).

```scala
import kyo.*
import kyo.doctest.*

val r: Doctest.Report = Doctest.Report(
    totalBlocks = 2,
    cacheHits = 0,
    compiled = 2,
    warnings = 0,
    failures = Chunk(Doctest.Failure(Path("README.md"), line = 42, message = "type mismatch"))
)
r.failures.foreach { f =>
    println(s"FAIL ${f.file}:${f.line}: ${f.message}")
}
```

A typical reporter pattern: group `report.failures` by `f.file` for per-file summaries. The `message` field already contains the full diagnostic text with Markdown-relative line numbers.

## How it works

Under sbt, the plugin (`kyo-doctest-plugin`) forks a JVM running the runner (`kyo-doctest`). It writes the run's configuration to a temp JSON file, forks, and reads the result back from a second temp JSON file. That fork is where the actual compilation happens.

Inside the fork, a single warm Dotty `Driver` is built once and reused across every block. Dotty's `ContextBase` pins a compiler context to the thread that created it, so all compiles are dispatched to one dedicated compiler thread regardless of which fiber invoked them. The cost of parsing scalac options and initialising the compiler is paid once, not once per block.

Re-runs are incremental. A content-hash `BlockCache` keys each block on a SHA-256 of its body, its scope-closure bodies, the classpath fingerprint, the Scala version, and the sorted scalac options. A block whose hash matches a stored entry serves its prior outcome from disk and skips recompilation. Editing one paragraph re-validates only the blocks whose content actually changed, which is why a warm `doctest` run is faster than recompiling every block the way mdoc does.

## Putting it together

A single Markdown file composes the DSL surfaces into one queue of validated blocks. The document below sets a file-level default, hides a setup fixture inside an HTML comment so it compiles into the prelude but stays invisible to readers, and then chains, negates, and platform-restricts blocks off that fixture:

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
