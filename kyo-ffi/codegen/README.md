# kyo-ffi-codegen

Build-tool-agnostic TASTy-driven code generator for [kyo-ffi](../README.md).

`FfiGenerator.generate(...)` is a plain Scala function that walks TASTy files for a bindings module, extracts every `Ffi`-extending trait into a `TraitSpec`, validates, and emits one platform-specific Scala source file per trait. It is invoked from the `kyo-ffi-plugin` sbt plugin by default; non-sbt build tools can call it directly.

## Entry point

```scala
import kyo.ffi.codegen.FfiGenerator

val result = FfiGenerator.generate(
    tastyFiles = Seq("/path/to/TcpBindings.tasty"),
    classpath = Seq("/path/to/kyo-ffi_3.jar", "/path/to/kyo-data_3.jar"),
    outputDir = java.nio.file.Path.of("out/src_managed"),
    platform = FfiGenerator.Platform.JVM,
    config = FfiGenerator.Config.default
)

val files    = result.files    // Seq[Path], emitted source files
val warnings = result.warnings // Seq[String], blocking/callback allowlist misses under non-strict config
val traits   = result.traits   // Seq[TraitSpec], the extracted model (useful for tooling)
```

The function is idempotent, re-running against the same TASTy produces byte-stable output. Strictness toggles live on `Config`:

```scala
import kyo.ffi.codegen.FfiGenerator

FfiGenerator.Config(
    libraryId = Some("kyo_tcp"),
    extraLibraries = Nil,
    strictBlocking = false, // true → allowlist misses throw instead of warn
    strictCallbacks = false // true → retention allowlist misses throw
)
```

## Mill

```scala noformat doctest:expect=skipped
import mill._, scalalib._
import $ivy.`io.getkyo::kyo-ffi-codegen::<version>`
import kyo.ffi.codegen.FfiGenerator

object demo extends ScalaModule {
    def scalaVersion = "3.3.4"
    def ivyDeps      = Agg(ivy"io.getkyo::kyo-ffi::<version>")

    def ffiGen = T {
        val tastys = os.walk(compile().classes.path).filter(_.ext == "tasty").map(_.toString)
        val cp     = compileClasspath().map(_.path.toString)
        val out    = T.dest.toNIO
        FfiGenerator.generate(tastys, cp, out, FfiGenerator.Platform.JVM).files.map(os.Path(_))
        PathRef(T.dest)
    }

    override def generatedSources = T { super.generatedSources() :+ ffiGen() }
}
```

## scala-cli

```
//> using scala 3.3.4
//> using dep io.getkyo::kyo-ffi::<version>
//> using dep io.getkyo::kyo-ffi-codegen::<version>
//> using resourceDir ./resources
//> using buildInfo
```

Run the generator as a preamble step (for example via `scala-cli run .` against a small driver that invokes `FfiGenerator.generate` and writes to `src_managed/`). scala-cli does not yet offer a native code-generation hook, so users typically wire a small script under `scripts/gen.scala` and run it before build.

## Bleep

Bleep's `@plugin` mechanism accepts any plain Scala entry point. Add `kyo-ffi-codegen` as a module dependency and call `FfiGenerator.generate` from a build script. See the [Bleep user guide](https://bleep.build/docs/) for the latest plugin wiring syntax.

## Internals

The extractor uses [`scala3-tasty-inspector`](https://www.scala-lang.org/api/current/scala/tasty/inspector.html) to walk class definitions and read companion-object config fields. `FfiInspector` is the reflection scope that collects `TraitSpec`/`StructSpec`/`MethodSpec` values.

## See also

- [kyo-ffi/README.md](../README.md), user-facing quickstart.
- [kyo-ffi-plugin/README.md](../plugin/README.md), sbt plugin settings reference.
