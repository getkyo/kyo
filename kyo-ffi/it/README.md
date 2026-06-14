# kyo-ffi-it, Integration tests

End-to-end integration tests for `kyo-ffi` that exercise the **actual
`KyoFfiPlugin`** against real system libraries (libc, libm, POSIX) and a
small bundled C surface. Tests run on **JVM, Scala Native, and Scala.js**
through the same code paths a downstream consumer would hit.

## Purpose

- Prove the plugin → codegen → runtime pipeline lights up end-to-end on
  every supported platform using the published plugin mechanism
  (`enablePlugins(KyoFfiPlugin)`), not by bypassing the plugin.
- Give us coverage against C we did not write (libc, libm, POSIX),
  complementing the unit tests in `kyo-ffi` / `kyo-ffi-codegen` and the
  scripted suite in `kyo-ffi-plugin`.
- Participate in `sbt test`, no `scripted` isolation, no fresh-sbt
  overhead.

## Layout

```
kyo-ffi-it/
  shared/src/main/scala/  # binding traits + shared SystemLibraryInit (common to all 3)
  shared/src/main/c/      # bundled C sources
  shared/src/test/scala/  # cross-platform tests (run on all 3 platforms)
  jvm/src/main/scala/     # JVM-side SystemLibraryInitImpl (no-op)
  native/src/main/scala/  # Native-side SystemLibraryInitImpl (no-op)
  js/src/main/scala/      # JS-side SystemLibraryInitImpl (koffi env-var priming)
  jvm-native/src/test/    # tests needing POSIX, runs on JVM + Native, excluded from JS
```

The cross-project is declared in `build.sbt` from day one; each
platform aggregate (`kyoJVM`, `kyoNative`, `kyoJS`) picks up the
corresponding subproject.

## How to run

Plugin compile (only needed if `kyo-ffi-plugin` sources changed):

```
sbt kyo-ffi-plugin/compile
```

Per-platform tests:

```
sbt 'kyo-ffi-it/test'                       # JVM
sbt -Dplatform=NATIVE 'kyo-ffi-itNative/test'
sbt -Dplatform=JS 'kyo-ffi-itJS/test'
```

The `-Dplatform=` flag selects the root aggregate (see `build.sbt`
~L85-95). Without it sbt defaults to `kyoJVM`.

## Known platform quirks

### JVM, libc resolution via Foreign Linker

`LibCBindings.Ffi.Config.library = "c"` resolves at runtime through the
JVM's Foreign Linker, which uses `SymbolLookup.libraryLookup` →
`dlopen(3)`. Works on macOS and Linux out of the box. `SystemLibraryInit`
is a no-op on JVM.

### Native, implicit libc linking

Scala Native auto-links libc for any `@extern` declaration. The codegen
emits `@link("c")` redundantly; Scala Native silently folds it into the
default libc link, no warning observed in 0.5.x on macOS.
`SystemLibraryInit` is a no-op on Native.

### JS, koffi library path injection

`koffi` (Node.js FFI library) loads shared libraries by absolute path.
A bare `"c"` fails on macOS (libc is folded into `libSystem`). The
JS-side `SystemLibraryInitImpl` detects `process.platform` and primes
`process.env.KYO_FFI_C_PATH` / `KYO_FFI_M_PATH` with the correct path
(`/usr/lib/libSystem.B.dylib` on darwin, `libc.so.6` on linux) at module
init. `NativeLoader.jsResolve` consults these env vars before falling
through to npm-package resolution or the bare library name. Tests
**must** call `SystemLibraryInit.force()` in `beforeAll` so the init
block fires before the first `Ffi.load[_]`.

The Scala.js linker is configured to emit a CommonJS module
(`ModuleKind.CommonJSModule`) so Node's `require('koffi')` resolves at
runtime. `build.sbt` auto-installs `koffi` into
`kyo-ffi/it/js/target/node_modules` the first time `kyo-ffi-itJS/test`
runs; subsequent runs are idempotent.

### Windows, documented gap

POSIX bindings (`getpid`, `getenv`, `time`) are unavailable on native
Windows without platform-specific shims. Scripted + IT coverage is
macOS / Linux until a Windows CI runner is added.

## Scripted overlap

`kyo-ffi-it` complements, but does not replace, the 24 scripted
fixtures under `kyo-ffi/plugin/src/sbt-test/kyo-ffi/`. Scripted tests
boot a fresh sbt and exercise **plugin-lifecycle behavior** (task
wiring, incremental caching, sbt-setting plumbing, cross-project
platform detection, publish-local → Ivy round-trips). Those behaviors
cannot be exercised from within `sbt test` because `sbt test` reuses
the running build. Integration tests (`kyo-ffi-it`) exercise the
**runtime + codegen feature surface** through the same plugin code
paths a downstream consumer hits, with per-assertion ScalaTest
granularity. The two suites cover different risk axes and both are
kept.

Per-fixture classification:

| Scripted fixture                 | Status                      | Rationale |
|----------------------------------|-----------------------------|-----------|
| `simple`                         | Unique to scripted          | Asserts plugin-setting defaults (`ffiLibraryId`, `ffiTargetPlatform`, `ffiCFlags`, `ffiIncludes`), sbt-settings reflection, not runtime. |
| `end-to-end`                     | Overlaps kyo-ffi-it (JVM)   | `LibCTest` + `ItStructsTest` cover the same code path with more cases; scripted verifies single-pass `compile && run` from a fresh sbt. |
| `callbacks-end-to-end`           | Overlaps kyo-ffi-it         | `ItCallbacksTest` covers transient + retained callbacks on JVM/Native/JS; scripted verifies fresh-sbt invocation. |
| `structs-end-to-end`             | Overlaps kyo-ffi-it         | `ItStructsTest` covers nested / packed structs with more assertions; scripted stresses packaged run. |
| `multi-library`                  | Overlaps kyo-ffi-it         | `ffiLibraries` mode is exercised by kyo-ffi-it's `kyo_it_bundled` + libc/libm; scripted asserts both library JAR resources land under `META-INF/native`. |
| `multi-library-end-to-end`       | Overlaps kyo-ffi-it         | Same as above plus end-to-end `run`. |
| `cross-project-end-to-end`       | Overlaps kyo-ffi-it         | kyo-ffi-it IS a cross-project against the plugin; scripted asserts external consumer cross-project reloading from scratch. |
| `js-only-end-to-end`             | Overlaps kyo-ffi-it (JS)    | kyo-ffi-itJS covers JS emitter paths; scripted asserts scalajs-linker + koffi npm install in a fresh sandbox. |
| `native-only-end-to-end`         | Overlaps kyo-ffi-it (Native)| kyo-ffi-itNative covers Native emitter paths; scripted asserts `nativeLink` → binary produced → run. |
| `platform-jvm`                   | Unique to scripted          | Asserts default `ffiTargetPlatform = JVM` when neither Native nor JS plugin is enabled. |
| `platform-native`                | Unique to scripted          | Asserts `ScalaNativePlugin` → `ffiTargetPlatform = Native`, `ffiCompile` is no-op. |
| `platform-js`                    | Unique to scripted          | Asserts `ScalaJSPlugin` → `ffiTargetPlatform = JS`. |
| `incremental-c-only`             | Unique to scripted          | Tests `ffiCompile` input-hash caching (file mtime unchanged on re-run; invalidated on content change). Caching is a plugin-task behavior `sbt test` cannot observe. |
| `incremental-trait-only`         | Unique to scripted          | Tests `ffiGenerate` input-hash caching across two same-state invocations. |
| `includes`                       | Unique to scripted          | Asserts `ffiCHeaders` parents expand to `-I<dir>` on the C compiler command. |
| `static-link`                    | Unique to scripted          | Asserts `ffiStaticLink := true` surfaces `-static` in the cc command; can't run the build (macOS rejects `-static -shared`). |
| `extract-dir`                    | Unique to scripted          | Tests `ffiExtractDir` + `ffiScratchSize` propagate as `-D` system properties to forked JVMs. |
| `sys-prop-override`              | Unique to scripted          | Tests `-Dkyo.ffi.<id>.path=<abs>` runtime override bypasses JAR extraction. |
| `strict-blocking`                | Unique to scripted          | Asserts `ffiStrictBlocking := true` promotes the allowlist warning to a task failure. |
| `strict-callbacks`               | Unique to scripted          | Asserts `ffiStrictCallbacks := true` promotes the retention warning to a task failure. |
| `compiler-zig`                   | Unique to scripted          | Asserts `zig cc` is accepted as `ffiCCompiler` when zig is installed; skip-when-absent. |
| `example-sqlite`                 | Unique to scripted          | Worked downstream example (in-memory SQLite stub); documents the recommended consumer shape. |
| `example-openssl`                | Unique to scripted          | Worked downstream example (OpenSSL init + RAND_bytes stub). |
| `example-sdl2`                   | Unique to scripted          | Worked downstream example (SDL2 window + event loop stub). |

Summary: 9 fixtures overlap kyo-ffi-it coverage (none strictly
duplicated, scripted always adds fresh-sbt / lifecycle validation),
15 are unique to scripted (plugin lifecycle + sbt-setting
plumbing + consumer examples). All 24 fixtures are kept.
