# kyo-ffi-plugin

sbt plugin for [kyo-ffi](../README.md). Generates the platform-specific Scala source for each `Ffi`-extending trait, compiles your C sources into a shared library, and packages the library under `META-INF/native/{os}-{arch}/` inside the JAR.

## Quickstart

```scala doctest:expect=skipped
lazy val demo = crossProject(JSPlatform, JVMPlatform, NativePlatform)
    .in(file("demo"))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraryId                        := "kyo_tcp",
        ffiCSources                         := (baseDirectory.value / ".." / "src" / "main" / "c" ** "*.c").get,
        libraryDependencies += "io.getkyo" %%% "kyo-ffi" % kyoVersion
    )
```

Platform detection is automatic: when the sub-project has `ScalaNativePlugin` enabled the plugin emits the Native backend, Scala.js projects get the JS backend, everything else defaults to JVM. Override via `ffiTargetPlatform := "JVM" | "Native" | "JS"` when needed.

## Settings reference

Every `SettingKey` contributed by the plugin. Types are Scala; defaults shown as emitted by the plugin.

### ffiLibraryId

- **Type**: `SettingKey[String]`
- **Default**: `"kyo_ffi"`
- **Purpose**: identifier for the single-library mode. Produces artifacts named `lib<id>-<os>-<arch>.<ext>` on POSIX and `<id>-<os>-<arch>.dll` on Windows, and is matched against each binding trait's resolved `library` (companion `Ffi.Config#library` or snake_case of the trait name).
- **Example**: `ffiLibraryId := "kyo_tcp"`

### ffiCSources

- **Type**: `SettingKey[Seq[File]]`
- **Default**: `Nil`
- **Purpose**: C source files to compile.
- **Example**: `ffiCSources := (baseDirectory.value / "src" / "main" / "c" ** "*.c").get`

### ffiCHeaders

- **Type**: `SettingKey[Seq[File]]`
- **Default**: `Nil`
- **Purpose**: C header files. Tracked as incremental-rebuild trigger inputs; their parent directories are added to `-I`. Rarely overridden directly, usually picked up transitively by `ffiIncludes`.
- **Example**: `ffiCHeaders := (baseDirectory.value / "src" / "main" / "c" ** "*.h").get`

### ffiIncludes

- **Type**: `SettingKey[Seq[File]]`
- **Default**: `Nil`
- **Purpose**: extra `-I` directories (appended after those derived from `ffiCHeaders`).
- **Example**: `ffiIncludes += baseDirectory.value / "vendor" / "openssl" / "include"`

### ffiLinkLibs

- **Type**: `SettingKey[Seq[String]]`
- **Default**: `Nil`
- **Purpose**: system libraries to link against (`-l` flags, no `lib` prefix, no extension).
- **Example**: `ffiLinkLibs ++= Seq("ssl", "crypto", "pthread")`

### ffiCCompiler

- **Type**: `SettingKey[String]`
- **Default**: value of the `CC` env var, else `"cc"`
- **Purpose**: the C compiler command. The plugin auto-detects `gcc`/`clang` vs Windows `cl` and adjusts flag syntax accordingly.
- **Example**: `ffiCCompiler := "clang-17"`

### ffiCFlags

- **Type**: `SettingKey[Seq[String]]`
- **Default**: `Seq("-O2", "-fPIC", "-Wall")`
- **Purpose**: additional C compilation flags. Replacing the list drops the defaults; use `+=` to append.
- **Example**: `ffiCFlags += "-DHAVE_CUSTOM_OPTION"`

### ffiLinkFlags

- **Type**: `SettingKey[Seq[String]]`
- **Default**: `Nil`
- **Purpose**: linker flags (passed after objects, before libraries).
- **Example**: `ffiLinkFlags += "-Wl,-z,noexecstack"`

### ffiStaticLink

- **Type**: `SettingKey[Boolean]`
- **Default**: `false`
- **Purpose**: statically link third-party libraries identified via `ffiLinkLibs`. Useful for distributing a self-contained JAR that does not depend on the target host having matching `.so`/`.dylib` files installed.
- **Example**: `ffiStaticLink := true`

### ffiScratchSize

- **Type**: `SettingKey[Int]`
- **Default**: `64 * 1024` (64 KiB)
- **Purpose**: per-thread scratch allocator size surfaced to the runtime via `-Dkyo.ffi.scratch.size=`. Sets the high-water mark for UTF-8 encodings, multi-value out-params, and struct marshalling performed inside a single FFI call.
- **Example**: `ffiScratchSize := 256 * 1024`

### ffiExtractDir

- **Type**: `SettingKey[Option[File]]`
- **Default**: `None` (platform temp dir)
- **Purpose**: override the directory kyo-ffi uses when extracting bundled shared libraries before loading. Surfaced via `-Dkyo.ffi.tmpdir=`. Useful on hardened platforms where the default temp dir is no-exec.
- **Example**: `ffiExtractDir := Some(baseDirectory.value / "target" / "ffi-extract")`

### ffiStrictBlocking

- **Type**: `SettingKey[Boolean]`
- **Default**: `false`
- **Purpose**: promote blocking-allowlist warnings to build errors. When a method resolves to a C symbol on kyo-ffi's known-blocking list (e.g. `read`, `write`, `pthread_mutex_lock`) and the method lacks `@Ffi.blocking`, a warning is emitted by default. Setting this flag turns that warning into an error.
- **Example**: `ffiStrictBlocking := true`

### ffiStrictCallbacks

- **Type**: `SettingKey[Boolean]`
- **Default**: `false`
- **Purpose**: promote callback-retention-allowlist warnings to build errors. When a method takes a function parameter, resolves to a known-retaining C symbol (e.g. `epoll_ctl`, `signal`, `pthread_create`) and lacks an `Ffi.Guard` parameter, a warning is emitted. Setting this flag makes it a build error.
- **Example**: `ffiStrictCallbacks := true`

### ffiTargetPlatform

- **Type**: `SettingKey[String]`
- **Default**: auto-detected (`"JVM"`, `"Native"`, or `"JS"`)
- **Purpose**: picks which emitter the generator runs. Normally left at default, inspecting the enabled auto-plugin list yields the right answer for cross-projects. Override only when the detection fails (e.g. custom project shape).
- **Example**: `ffiTargetPlatform := "Native"`

### ffiLibraries

- **Type**: `SettingKey[Seq[FfiLibrary]]`
- **Default**: `Nil`
- **Purpose**: multi-library mode. When non-empty, takes precedence over `ffiLibraryId`/`ffiCSources`/`ffiCHeaders`/`ffiLinkLibs`/`ffiStaticLink`, each `FfiLibrary` declares one shared library with its own set of sources, headers, link libs, flags, and static-link preference.
- **Example**:
  ```scala doctest:expect=skipped
ffiLibraries := Seq(
    FfiLibrary(
        id = "kyo_tcp",
        cSources = (baseDirectory.value / "c" / "tcp" ** "*.c").get,
        linkLibs = Seq("pthread")
    ),
    FfiLibrary(
        id = "kyo_tls",
        cSources = (baseDirectory.value / "c" / "tls" ** "*.c").get,
        linkLibs = Seq("ssl", "crypto"),
        staticLink = true
    )
)
  ```

The `FfiLibrary` case class is re-exported from `autoImport` so no extra imports are needed inside `build.sbt`.

## Tasks

### ffiGenerate

- **Type**: `TaskKey[Seq[File]]`
- **Purpose**: invoke the codegen (Scala 3, loaded reflectively via a classloader built from the plugin's bundled resources). Produces one `{Trait}Impl.scala` file per `Ffi`-extending trait under `(Compile / sourceManaged) / "kyo-ffi"`. Incremental: trait changes re-run it; C changes do not.
- **Wiring**: appended to `Compile / sourceGenerators` automatically.

### ffiCompile

- **Type**: `TaskKey[Seq[File]]`
- **Purpose**: compile C sources into a platform-native shared library under `target/ffi/`. Branches by `ffiTargetPlatform`:
  - **JVM / JS**: invoke `cc` with OS/arch-aware flags; artifact lands in `target/ffi/lib<id>-<os>-<arch>.<ext>`.
  - **Native**: no-op, C sources are fed into the enclosing Scala Native sub-project's `nativeCompileOptions` and compiled into the final binary by the Scala Native linker.

  Multi-library mode iterates each entry in `ffiLibraries`.

### ffiPackage

- **Type**: `TaskKey[Seq[File]]`
- **Purpose**: copy the artifacts produced by `ffiCompile` into `(Compile / resourceManaged) / "META-INF" / "native" / "<os>-<arch>"`. Invoked automatically via `Compile / resourceGenerators`, explicit calls are for scripted tests and debugging.

### ffiClean

- **Type**: `TaskKey[Unit]`
- **Purpose**: delete generated sources (`sourceManaged / "kyo-ffi"`) and compiled artifacts (`target/ffi`). Complements `clean`, which would also remove them.

### ffiCiWorkflow

- **Type**: `TaskKey[File]`
- **Purpose**: emit a starter `.github/workflows/ffi-native.yml` template that builds `ffiCompile` across a five-row matrix (Linux x86_64/aarch64, macOS Intel/Apple Silicon, Windows x86_64) and uploads the per-row artifacts.
- **Usage**: `sbt ffiCiWorkflow` from the project root. Edit the emitted YAML to plug in your publish flow.

### ffiDumpCcCommand

- **Type**: `TaskKey[Seq[Seq[String]]]`
- **Purpose**: diagnostic, return the `cc` argv the plugin would execute for the current library configuration, one `Seq[String]` per library (multi-lib mode). Does not invoke the compiler; useful in tests and for debugging build issues.
- **Example**: `show ffiDumpCcCommand`

## Multi-library example

Single module hosting two `Ffi` traits targeting different shared libraries:

```scala noformat doctest:expect=skipped
// shared/src/main/scala/demo/TcpBindings.scala
trait TcpBindings extends Ffi:
    def tcpConnect(host: String, port: Int): Int
object TcpBindings extends Ffi.Config(library = "kyo_tcp")

// shared/src/main/scala/demo/TlsBindings.scala
trait TlsBindings extends Ffi:
    def tlsHandshake(fd: Int): Int
object TlsBindings extends Ffi.Config(library = "kyo_tls")
```

```scala doctest:expect=skipped
// build.sbt
lazy val demo = crossProject(JSPlatform, JVMPlatform, NativePlatform)
    .in(file("demo"))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraries := Seq(
            FfiLibrary("kyo_tcp", (baseDirectory.value / "c" / "tcp" ** "*.c").get, linkLibs = Seq("pthread")),
            FfiLibrary("kyo_tls", (baseDirectory.value / "c" / "tls" ** "*.c").get, linkLibs = Seq("ssl", "crypto"))
        ),
        libraryDependencies += "io.getkyo" %%% "kyo-ffi" % kyoVersion
    )
```

The plugin generates `TcpBindingsImpl` and `TlsBindingsImpl` in the same module; each resolves its own shared library at runtime via the `library` value in the companion config.

## Path-override runtime switch

At runtime, the loader consults system properties before falling back to the JAR-bundled library:

```
java -Dkyo.ffi.kyo_tcp.path=/custom/libkyo_tcp.so -jar app.jar
```

Each library id is independent.

## Architecture

- **Scala 2.12 plugin**, sbt's own compile target.
- **Scala 3 codegen**, bundled as opaque resources inside the plugin JAR. At task execution time the plugin extracts the bundled JARs to a cache directory and constructs a fresh classloader to invoke the codegen reflectively.
- No user-visible Scala 3 dependency beyond what `kyo-ffi` itself pulls in.

## See also

- [kyo-ffi/README.md](../README.md), user-facing quickstart.
- [kyo-ffi-codegen/README.md](../codegen/README.md), non-sbt invocation path.
