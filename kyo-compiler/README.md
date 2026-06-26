# kyo-compiler

<!-- doctest:setup
```scala
import kyo.*

val uri = Compiler.Uri("Main.scala")
val text =
    """object Main:
      |  val xs = List(1, 2, 3)
      |  val total = xs.su""".stripMargin
val completionOffset = text.length                            // just after `xs.su`
val symbolOffset     = text.indexOf("xs.su")                  // on `xs`
val signatureOffset  = text.indexOf("List(") + "List(".length // inside List(...)

val config = Compiler.Config(
    toolchain = Compiler.Toolchain("3.8.4", Chunk(Path("/cp/scala3-presentation-compiler_3-3.8.4.jar"))),
    classpath = Chunk.empty,
    scalacOptions = Chunk.empty,
    sourceRoots = Chunk.empty
)

def withCompiler[A](f: Compiler => A < (Async & Abort[CompilerError]))(using
    Frame
): A < (Async & Abort[CompilerError]) =
    Scope.run(Compiler.Pool.init().map(pool => pool.compiler(config).map(f)))
end withCompiler
```
-->

A `Compiler` is a warm, per-config handle to the Scala 3 presentation compiler (`scala.meta.pc`, implemented by `dotty.tools.pc`). It exposes six IDE-intelligence ops over a single buffer: `compile` for diagnostics, `completions`, `hover`, `signatureHelp`, `symbol` for go-to-symbol, and `didClose` to drop a buffer's cache. Each op returns a neutral, offset-based result inside `Async & Abort[CompilerError]`. You pass the document text on every call (a uri, the text, and for position queries an offset); the handle keeps no buffer of its own and leans on the compiler's content-keyed typecheck cache. Offsets are UTF-16 code units into that text, so line/column mapping and cross-file go-to-definition stay the caller's job, and the surface stays free of `java.net.URI` and lsp4j. Every op is cancellable by interrupting the calling fiber, and ops on one handle run one at a time, because even a query mutates the compiler's lazy denotations.

You never construct a `Compiler` directly. You open a `Compiler.Pool` (Scope-managed) and ask it for the handle bound to a build `Config` with `pool.compiler(config)`. The pool owns the live compiler instances: it resolves each one lazily and single-flight on its first op, caps concurrency two ways (a global compile cap across all configs plus per-instance serialization), evicts and closes idle instances by LRU, and isolates configs from each other. A config that differs in any field (toolchain, classpath, scalac options, source roots) is a distinct instance. Whether a config runs in this JVM or in a forked worker JVM is chosen internally from the `isolate` policy and a Scala-version-match rule; the caller never names a backend. On scope close every live instance is closed and every forked worker is force-killed.

kyo-compiler is JVM-only: the presentation compiler is a JVM artifact, and the module has no JS or Native target.

```scala
// config, uri, and text are defined once; the sections below build them up.
val diagnostics: Chunk[Compiler.Diagnostic] < (Async & Abort[CompilerError]) =
    Scope.run {
        Compiler.Pool.init().map { pool =>
            pool.compiler(config).map { compiler =>
                compiler.compile(uri, text)
            }
        }
    }
```

`Pool.init` opens the Scope-managed pool, `pool.compiler(config)` resolves the warm handle, and `compile` returns the buffer's diagnostics. The sections below introduce each piece, then `## Putting it together` recombines them.

## Opening a pool and getting a handle

You do not run a compiler; you open a pool and ask it for a handle. The pool is the lifecycle owner, so the first thing a program does is open one inside a `Scope`, and the last thing the `Scope` does is close every instance the pool created.

### Opening the pool

`Compiler.Pool.init` returns a `Pool < (Sync & Scope)`: the pool is acquired when the effect runs and released when the enclosing `Scope` closes. Inside the pool, `pool.compiler(config)` hands back the per-config handle.

```scala
val opened: Compiler < Async =
    Scope.run {
        Compiler.Pool.init().map { pool =>
            pool.compiler(config) // : Compiler < Sync
        }
    }
```

`pool.compiler(config)` types as `Compiler < Sync`: resolving the handle is a cheap synchronous step, and after `Scope.run` discharges the pool's `Scope` the opened handle is a `Compiler < Async`. The heavier `Async` work shows up only when you call an op on the handle (the opening hook above shows the op result type, `Chunk[Compiler.Diagnostic] < (Async & Abort[CompilerError])`).

> **Note:** `pool.compiler(config)` creates no instance. The cold start (and for a forked worker the spawn, which can take up to about 30 seconds) lands on the first op, not on `compiler(...)`. A program can resolve a handle eagerly and pay nothing until it queries.

### The handle is a view, not a live instance

> **Unlike** a handle that pins a live resource, a `Compiler` is a view onto whatever instance the pool currently holds for that config. If the instance was evicted while idle, the next op transparently re-resolves and recreates it, paying cold start again. You hold no stale-handle obligation and never close a handle yourself.

### Pool settings

`Compiler.Pool.Settings` is the pool-wide policy: the default backend choice (`isolate`), the global compile cap (`maxConcurrentCompiles`), the live-instance bound before LRU eviction (`maxLiveCompilers`), and how long an unused instance stays warm (`idleEviction`). `Settings.default` is the value `init` uses when you pass nothing.

```scala
val settings = Compiler.Pool.Settings(
    isolate = false,
    maxConcurrentCompiles = 8,
    maxLiveCompilers = 32,
    idleEviction = 10.minutes
)
assert(settings.maxLiveCompilers == 32)
assert(Compiler.Pool.Settings.default == Compiler.Pool.Settings(true, 4, 16, 5.minutes))
```

Two of these knobs, `maxConcurrentCompiles` and `maxLiveCompilers`, govern behavior under concurrent load and across many configs. `## Concurrency, isolation, and eviction` covers what they do; the third, `isolate`, ties into backend selection, covered there too.

## Describing a build configuration

A handle is bound to exactly one configuration, and that configuration is the instance identity key. Before you can resolve a handle you describe the build: which toolchain, which classpath, which scalac options, which source roots.

### Toolchain: version plus presentation-compiler classpath

`Compiler.Toolchain` pairs the Scala version with the JAR paths the presentation compiler itself runs from.

```scala
val toolchain = Compiler.Toolchain(
    scalaVersion = "3.8.4",
    compilerClasspath = Chunk(Path("/cp/scala3-presentation-compiler_3-3.8.4.jar"))
)
assert(toolchain.scalaVersion == "3.8.4")
```

> **Note:** `compilerClasspath` is the caller-resolved `scala3-presentation-compiler_3:vN` (and its transitive) JAR paths. kyo-compiler does not resolve them. A caller that wants coursier resolution composes it above and passes the paths in.

### Config: the instance identity key

`Compiler.Config` carries the toolchain, the target `classpath`, the `scalacOptions`, the `sourceRoots`, and an optional per-config `isolate` override (defaulting `Absent`, meaning "use the pool default"). Any field that differs makes a distinct config, which makes a distinct instance.

```scala
val cfg = Compiler.Config(
    toolchain = Compiler.Toolchain("3.8.4", Chunk(Path("/cp/scala3-presentation-compiler_3-3.8.4.jar"))),
    classpath = Chunk.empty,
    scalacOptions = Chunk.empty,
    sourceRoots = Chunk.empty
)
val stricter = cfg.copy(scalacOptions = Chunk("-Wunused:all"))
assert(cfg != stricter) // distinct config -> distinct instance
assert(cfg.isolate == Absent)
```

> **Note:** `sourceRoots` are directories of `.scala` sources the presentation compiler reads through its `-sourcepath`. With them set, the compiler resolves a symbol whose definition lives in one of those roots even when that definition is not on the compiled `classpath`, so a `hover`, `symbol`, or `compile` on a buffer that references it resolves the source-root definition. An empty `sourceRoots` adds no `-sourcepath` and changes nothing. This holds identically whether the config runs in-process or in a forked worker, because the worker drives the same backend.

> **Caution:** `isolate` defaults to `true` at the pool level (forked workers, the stability default). A per-config `isolate = Present(false)` opts that config into the in-process path, but only on a Scala-version match: a `Toolchain.scalaVersion` that differs from kyo's own version forces a forked worker regardless of the override. `## Concurrency, isolation, and eviction` covers the rule.

Because the config is the identity key, a program that varies the config per file (per-file scalac options, say) gets one instance per variation. `## Concurrency, isolation, and eviction` covers how the pool bounds and evicts that set.

## Querying a buffer

Once you hold a handle, the six ops are how you ask the compiler about a buffer. Every op takes the document text on the call, returns a neutral offset-based result, and carries `CompilerError` on its `Abort` row. Group them by what you are asking: what is wrong, what can I type here, what is this, where is it defined, and forget this buffer.

Position queries take an `offset: Int`, a UTF-16 code-unit index into `text`, the same unit `String.length` and `String.indexOf` count in. The examples derive offsets with `text.indexOf(...)` so the index is unambiguous.

> **Note:** Ops on one handle run one at a time, even read-only ones like `hover` and `completions`, because a query still mutates the compiler's lazy denotations. Concurrency comes from distinct configs, not from parallel ops on one handle. `## Concurrency, isolation, and eviction` covers the two meters.

The examples below use a small helper, `withCompiler(f)`, which is `Scope.run(Compiler.Pool.init().map(pool => pool.compiler(config).map(f)))`: it opens a pool, resolves the handle for `config`, and runs one op, so each block shows only the op.

### Diagnostics: `compile`

`compile(uri, text)` typechecks the buffer and returns a `Chunk[Compiler.Diagnostic]`. An empty chunk means a clean buffer.

```scala
val onBuffer: Chunk[Compiler.Diagnostic] < (Async & Abort[CompilerError]) =
    withCompiler(_.compile(uri, text))

val onClean: Chunk[Compiler.Diagnostic] < (Async & Abort[CompilerError]) =
    withCompiler(_.compile(uri, "object Main"))
```

The running buffer ends in the unfinished `val total = xs.su`, so `compile` reports a diagnostic there; `object Main` is well-formed, so it reports an empty chunk. A `Compiler.Diagnostic` has a `span`, a `severity`, a `message`, and an optional `code`:

```scala
val sample = Compiler.Diagnostic(
    span = Compiler.Span(7, 9),
    severity = Compiler.Severity.Error,
    message = "Not found: su"
)
assert(sample.code == Absent)
```

`Compiler.Severity` is `Error`, `Warning`, `Info`, or `Hint`. A `Compiler.Span` is a `[start, end)` range in UTF-16 code units, the same offsets the ops take.

### Completions: `completions`

`completions(uri, text, offset)` returns the candidates valid at an offset as a `Chunk[Compiler.Completion]`; an empty chunk means none.

```scala
val candidates: Chunk[Compiler.Completion] < (Async & Abort[CompilerError]) =
    withCompiler(_.completions(uri, text, completionOffset))
```

`completionOffset` sits just after `xs.su`, so the compiler offers the `List` members that start with `su` (`sum`, `scanLeft`, and so on). A `Compiler.Completion` carries a `label`, a `Completion.Kind`, and three optional fields: `detail`, `insertText`, `documentation`. `Completion.Kind` is one of `Value`, `Method`, `Field`, `Class`, `Trait`, `Object`, `Type`, `Package`, `Keyword`, `Param`.

### Hover and signature help

Both `hover` and `signatureHelp` describe what is under the cursor, and they answer different questions. When the cursor sits on a name and you want its type and rendered documentation, use `hover`. When the cursor sits inside a call's argument list and you want the parameter currently being filled, use `signatureHelp`: its `activeParam` indexes into `params`.

```scala
val info: Maybe[Compiler.Hover] < (Async & Abort[CompilerError]) =
    withCompiler(_.hover(uri, text, symbolOffset))

val help: Maybe[Compiler.Signature] < (Async & Abort[CompilerError]) =
    withCompiler(_.signatureHelp(uri, text, signatureOffset))
```

Both return a `Maybe`: `Absent` means there is nothing to show at that offset. A `Compiler.Hover` is rendered `markdown` plus an optional `span`. A `Compiler.Signature` is a rendered `label`, a `Chunk[Signature.Param]`, and an optional `activeParam` index; each `Signature.Param` is a `label` and optional `documentation`.

### Go-to-symbol: `symbol`

`symbol(uri, text, offset)` returns the symbol at an offset as a `Maybe[Compiler.SymbolInfo]`.

```scala
val sym: Maybe[Compiler.SymbolInfo] < (Async & Abort[CompilerError]) =
    withCompiler(_.symbol(uri, text, symbolOffset))
```

A `Compiler.SymbolInfo` carries the `name`, the fully-qualified `fullName`, a `SymbolInfo.Kind` (`Class`, `Trait`, `Object`, `Method`, `Val`, `Var`, `Type`, `Package`, `Param`), and a `localDefinition`.

> **Note:** `localDefinition` is only the definition span inside this buffer, typed `Maybe[(Uri, Span)]`. A symbol defined in another file has `localDefinition == Absent`; resolve it across files yourself via `fullName`. Cross-file go-to-definition is the caller's job, which is what keeps the surface neutral.

### Cache management: `didClose`

`didClose(uri)` tells the compiler to drop its per-uri cache, for when an editor closes a document.

```scala
val dropped: Unit < (Async & Abort[CompilerError]) =
    withCompiler(_.didClose(uri))
```

> **Unlike** a purely local cache eviction, `didClose` returns `Unit` but still round-trips to the backend, so a comms failure surfaces as `Abort[CompilerError]`. A `Unit` op can still fail.

## Neutral results on the wire

Every result type is offset-based and serializable on purpose. Line/column mapping and cross-file resolution are the caller's concern, and the same codec that moves a result to a forked worker also rides the kyo-aeron `Topic` transport and an LSP wire with no adapter.

### Uri and the offset model

`Compiler.Uri` is the neutral file identity, and `Compiler.Span` is the neutral range. Both stay off `java.net.URI` and lsp4j.

```scala
val u = Compiler.Uri("Main.scala")
assert(u.asString == "Main.scala")

val span = Compiler.Span(7, 9)
assert(span.start == 7 && span.end == 9)
```

> **Note:** `Compiler.Uri` is opaque over `String`: `Uri(value)` builds one, `.asString` reads it, and on the wire it IS the string (via a `given ReadWriter[Uri]` bimap). A `Span` is a `[start, end)` range in UTF-16 code units, the same offsets the ops take, so an editor maps line/column to offsets once and works in offsets everywhere after.

### AsMessage: the wire codec

`Compiler.AsMessage[A]` is a type alias for upickle's `ReadWriter[A]`, and every result type derives it. That single derive is what lets a result round-trip to a forked worker, ride a `Topic`, or serialize onto an LSP connection.

```scala
import upickle.default.*

val diag = Compiler.Diagnostic(
    span = Compiler.Span(7, 9),
    severity = Compiler.Severity.Warning,
    message = "unused value xs"
)
val decoded = readBinary[Compiler.Diagnostic](writeBinary(diag))
assert(decoded == diag)
assert(diag.code == Absent)
```

`Compiler.AsMessage` is the exact codec kyo-aeron's `Topic.AsMessage` carries, so a `Diagnostic`, `Completion`, or `SymbolInfo` rides `Topic[Diagnostic]` (and an LSP wire) directly. This is the module's interop seam, not just internal plumbing.

> **Note:** A `given ReadWriter[Maybe[A]]` bridges every `Maybe`-valued result, so `Hover`, `Signature`, `SymbolInfo`, and the optional fields (`Diagnostic.code`, `Completion.detail`, and so on) all serialize without an adapter. An `Absent` becomes a JSON-`null`-equivalent and decodes back to `Absent`.

## Concurrency, isolation, and eviction

The pool's behavior under concurrent load and across Scala versions is a contract worth knowing before you put it under a request handler. Four things interact: where an op runs, how it is scheduled, when an instance is evicted, and how a failure is typed.

### In-process or forked: backend selection

When a config's `Toolchain.scalaVersion` matches kyo's own version and `isolate` is `false`, the op runs in this JVM, the fast path. When the version differs, or `isolate` stays `true`, it runs in a forked worker JVM connected over kyo-aeron, hard-killable and able to host any Scala version. The version mismatch wins: `isolate = false` is honored only on a version match. You never name a backend; the pool chooses from `Config.isolate.getOrElse(Settings.isolate)` and the version rule.

> **Note:** `Settings.isolate` defaults to `true`, so a config that says nothing about isolation runs in a forked worker. Opt a version-matched config into the in-process path with `Config.isolate = Present(false)`.

### Two meters: global cap and per-instance serialization

Throughput is bounded by both meters at once. An op holds its per-instance mutex inside the global semaphore, so at most `maxConcurrentCompiles` ops run across the whole pool, and at most one runs per config. Two distinct configs run in parallel; one config never runs two ops in parallel. When you want more parallelism, you add configs (or raise `maxConcurrentCompiles`), not parallel calls on one handle.

### Live instances and idle eviction

There is one instance per distinct `Config`. Once live instances exceed `maxLiveCompilers`, the least-recently-used one is evicted and closed; a later op on the evicted config recreates it (cold start again, and for a worker the recreate force-kills the old JVM and spawns a fresh one). Because the config is the identity key, many slightly different configs silently churn instances and re-pay cold start, so keep the config set small and stable. An `idleEviction` window decides how long an unused instance stays warm before the LRU evicts it on idle alone.

### `CompilerError`: the failure leaf

Every op's `Abort` row is `CompilerError`, an enum with exactly two cases. `InitializationFailed(message)` means the backend, the presentation compiler, or a forked worker could not start. `Fatal(message)` means a running op or its transport failed. The first points at config or environment (a missing classpath, a worker that could not spawn); the second points at the op or the wire.

```scala
val described: String < Async =
    Abort.run[CompilerError](withCompiler(_.compile(uri, text))).map {
        case Result.Success(diags) =>
            s"${diags.size} diagnostics"
        case Result.Failure(CompilerError.InitializationFailed(message)) =>
            s"backend did not start: $message"
        case Result.Failure(CompilerError.Fatal(message)) =>
            s"op failed: $message"
        case Result.Panic(error) =>
            s"unexpected: $error"
    }
```

A version-mismatched config whose worker cannot load its classpath surfaces as `Result.Failure(CompilerError.InitializationFailed("...worker..."))`; a pc that throws mid-op surfaces as `Result.Failure(CompilerError.Fatal(...))`, never an escaped throw.

## Putting it together

The sections above introduced each piece in isolation. This example opens one pool, resolves a handle for `config`, then compiles the buffer, asks for completions at the end of `xs.su`, and drops the cache, all inside one `Scope.run` so the pool and any worker are cleaned up on exit.

```scala
val session: (Chunk[Compiler.Diagnostic], Chunk[Compiler.Completion]) < (Async & Abort[CompilerError]) =
    Scope.run {
        Compiler.Pool.init().map { pool =>
            pool.compiler(config).map { compiler =>
                for
                    diags       <- compiler.compile(uri, text)
                    completions <- compiler.completions(uri, text, completionOffset)
                    _           <- compiler.didClose(uri)
                yield (diags, completions)
            }
        }
    }
```

Distinct configs run in parallel under the global cap. Here two configs differ only in their scalac options, so they resolve to two instances, and `Async.zip` compiles the same buffer under both at once.

```scala
val strict = config.copy(scalacOptions = Chunk("-Wunused:all"))

val both: (Chunk[Compiler.Diagnostic], Chunk[Compiler.Diagnostic]) < (Async & Abort[CompilerError]) =
    Scope.run {
        Compiler.Pool.init().map { pool =>
            for
                lenient <- pool.compiler(config)
                strictC <- pool.compiler(strict)
                result  <- Async.zip(lenient.compile(uri, text), strictC.compile(uri, text))
            yield result
        }
    }
```
