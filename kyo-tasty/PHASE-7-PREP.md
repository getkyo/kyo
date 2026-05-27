# Phase 7 Prep — sbt plugin via fork-JVM

Source of truth: `kyo-reflect/execution-plan-perf.md` Phase 7 section.
Structural reference: `build.sbt` lines 1269-1296 (`kyo-compat` plugin definition).
Plugin precedent: `kyo-compat/plugin/src/main/scala/io/getkyo/compat/CompatPlugin.scala`.

---

## The two-module structure

Phase 7 creates TWO new sbt subprojects:

### 1. `kyo-reflect-sbt-plugin` (Scala 2.12 — sbt 1.x requires 2.12)

- Path: `kyo-reflect-sbt/plugin/src/main/scala/kyo/KyoReflectPlugin.scala`
- Defines `object KyoReflectPlugin extends AutoPlugin`
- Setting key: `reflectSnapshotDir: SettingKey[File]` (default: `target.value / "kyo-reflect-snapshots"`)
- Task key: `reflectSnapshot: TaskKey[File]` (returns the produced snapshot file path)
- The task:
  1. Collects `(Compile / fullClasspath).value` entries as `Seq[File]`
  2. Locates the runner JAR (see Runner Deployment below)
  3. Spawns a forked JVM via `sbt.Fork.java` running `kyo.internal.reflect.sbt.SnapshotRunner`
  4. Passes two arguments: (a) colon-separated classpath root paths, (b) absolute output file path
  5. Parses exit code 0 = success; non-zero = failure (throws `sbt.internal.util.MessageOnlyException`)

### 2. `kyo-reflect-sbt-runner` (Scala 3 — depends on `kyo-reflect.jvm`)

- Path: `kyo-reflect-sbt/runner/src/main/scala/kyo/internal/reflect/sbt/SnapshotRunner.scala`
- `object SnapshotRunner`
- `def main(args: Array[String]): Unit`
  - `args(0)`: colon-separated classpath root paths (split on `File.pathSeparator`)
  - `args(1)`: absolute path to output directory (becomes the `cacheDir` argument)
  - Invokes `Reflect.Classpath.openCached(roots, cacheDir)` via `KyoApp.run` (or `Sync.Unsafe.evalOrThrow`)
  - Exits with code 0 on success, 1 on failure with a printed error message

---

## build.sbt mirror of kyo-compat

The `kyo-compat` definition at lines 1269-1296 is the structural template. Verbatim for reference:

```scala
lazy val `kyo-compat` = (project in file("kyo-compat/plugin"))
    .enablePlugins(SbtPlugin)
    .settings(
        moduleName         := "kyo-compat",
        scalaVersion       := "2.12.20",
        crossScalaVersions := Seq("2.12.20"),
        sbtPlugin          := true,
        addSbtPlugin("com.eed3si9n"       % "sbt-projectmatrix"             % "0.10.1"),
        addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject"      % "1.3.2"),
        addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.3.2"),
        scriptedLaunchOpts := Seq(
            "-Xmx1024M",
            "-Dplugin.version=" + version.value
        ),
        scriptedBufferLog := false
    )
```

Phase 7 adds two analogous entries to build.sbt. The plugin module:

```scala
lazy val `kyo-reflect-sbt-plugin` = (project in file("kyo-reflect-sbt/plugin"))
    .enablePlugins(SbtPlugin)
    .settings(
        moduleName         := "kyo-reflect-sbt",
        scalaVersion       := "2.12.20",
        crossScalaVersions := Seq("2.12.20"),
        sbtPlugin          := true,
        scriptedLaunchOpts := Seq(
            "-Xmx1024M",
            "-Dplugin.version=" + version.value,
            "-Drunner.version=" + version.value
        ),
        scriptedBufferLog  := false
    )
```

The runner module (regular Scala 3 JVM-only project):

```scala
lazy val `kyo-reflect-sbt-runner` = (project in file("kyo-reflect-sbt/runner"))
    .settings(
        moduleName := "kyo-reflect-sbt-runner",
        `kyo-settings`
    )
    .jvmSettings(mimaCheck(false))
    .dependsOn(`kyo-reflect`.jvm)
```

Note: `kyo-reflect` is a `CrossProject`; the runner depends on `\`kyo-reflect\`.jvm` (the JVM project reference). The runner is a plain `project`, not a `crossProject`, because sbt plugins and their runners run on JVM only.

Both new subprojects are aggregated into `kyoJVM` (not `kyoJS` / `kyoNative`) for the same reason `kyo-compat` is — they are JVM-only build-tool artifacts. Add both to the `kyoJVM.aggregate(...)` list.

---

## Verbatim API signatures

### `Reflect.Classpath.openCached`

From `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` line 828:

```scala
def openCached(roots: Seq[String], cacheDir: String)(using Frame): Classpath < (Sync & Async & Scope & Abort[ReflectError]) =
    openCachedImpl(roots, cacheDir)
```

The runner invokes this and must discharge all four effects. The effect stack discharged by `KyoApp.run` (via `KyoAppPlatformSpecific`):

- `Scope` discharged by `Scope.run` inside `KyoAppRunnerWithInterrupts.handle`
- `Async` discharged by `KyoApp.runAndBlock(timeout)(...)` which calls `Fiber.initUnscoped` + `fiber.block`
- `Abort[ReflectError]` discharged by wrapping in `Abort.run[ReflectError]`
- `Sync` discharged by `Sync.Unsafe.evalOrThrow` inside `KyoAppRunnerPlatform.registerEffect`

The standard `extends KyoApp` pattern handles all of this automatically. The runner's `main` method comes from `KyoApp.Base.main`, which calls `runInitCode()` after `run { ... }` registers the effect block.

Full runner skeleton:

```scala
package kyo.internal.reflect.sbt

import kyo.*

object SnapshotRunner extends KyoApp:
    run {
        val roots   = args.toSeq.head.split(java.io.File.pathSeparatorChar).toSeq.filterNot(_.isEmpty)
        val cacheDir = args.toSeq(1)
        Reflect.Classpath.openCached(roots, cacheDir).map { _ =>
            java.lang.System.out.println(s"kyo-reflect-sbt: snapshot written to $cacheDir")
        }
    }
```

If `args` is shorter than 2 elements, `run` will throw and `KyoApp` will call `exitHook(1)`. The plugin should also validate argument count before forking.

### `sbt.Fork.java`

From `sbt/run_2.12-1.11.7.jar` (sbt 1.x stable API, unchanged since 1.0):

```scala
// sbt.Fork (Scala 2.12 companion object singleton)
Fork.java: sbt.Fork       // pre-built Fork instance for "java" executable

// Apply method (blocking — waits for process exit):
def apply(options: ForkOptions, arguments: Seq[String]): Int
// Fork method (non-blocking — returns Process):
def fork(options: ForkOptions, arguments: Seq[String]): scala.sys.process.Process
```

`ForkOptions` constructor (the `apply()` with no args returns a default):

```scala
ForkOptions()
    .withJavaHome(javaHome: Option[File])        // typically javaHome.value from sbt
    .withOutputStrategy(outputStrategy: Option[OutputStrategy])
    .withBootJars(bootJars: Vector[File])        // JARs prepended before -classpath
    .withWorkingDirectory(wd: Option[File])      // baseDirectory.value
    .withRunJVMOptions(opts: Vector[String])     // JVM flags (e.g., -Xmx256m)
    .withConnectInput(b: Boolean)
    .withEnvVars(vars: Map[String, String])
```

The `arguments` passed to `Fork.java.apply(opts, args)` are:
- `-classpath <cp>`  — runner JAR plus kyo-reflect JARs
- `kyo.internal.reflect.sbt.SnapshotRunner`  — main class name
- `<roots>`  — colon-separated root paths
- `<cacheDir>`  — output directory

Note: `Fork.java` already inserts the `-classpath` and main class from its `arguments` seq starting at index 0. The actual invocation in the plugin task:

```scala
val exitCode = Fork.java(
    ForkOptions()
        .withJavaHome(javaHome.value)
        .withOutputStrategy(Some(StdoutOutput))
        .withWorkingDirectory(Some(baseDirectory.value)),
    Seq(
        "-classpath", runnerClasspath.mkString(java.io.File.pathSeparator),
        "kyo.internal.reflect.sbt.SnapshotRunner",
        roots.mkString(java.io.File.pathSeparator),
        snapshotDir.getAbsolutePath
    )
)
if (exitCode != 0)
    throw new sbt.internal.util.MessageOnlyException(
        s"kyo-reflect-sbt: SnapshotRunner exited with code $exitCode. " +
        s"Check output above for details."
    )
```

`sbt.internal.util.MessageOnlyException` is in `util-control_2.12`. sbt exposes it in the plugin compile classpath automatically when `sbtPlugin := true`. Its `toString` omits the class name, producing a clean error message in the sbt console.

### `KyoApp.run` (Scala 3 entry point)

From `kyo-core/shared/src/main/scala/kyo/KyoApp.scala`:

```scala
abstract class KyoApp extends KyoAppPlatformSpecific
// KyoAppPlatformSpecific extends KyoApp.Base[Async & Scope & Abort[Any]]
//   with KyoAppRunnerWithInterrupts with KyoAppRunnerPlatform

object KyoApp:
    // The run block registration method (inherited via KyoAppPlatformSpecific):
    protected def run[A](v: => A < (Async & Scope & Abort[Any]))(using Frame, Render[A]): Unit
    // The base main method fires registerEffect for each run { ... } block.
```

Usage pattern from `kyo-pod/shared/src/test/scala/demo/CodeSandbox.scala`:

```scala
object MyRunner extends KyoApp:
    run {
        // A < (Async & Scope & Abort[Any]) computation here
        someEffect
    }
```

`KyoApp.Base.main(args: Array[String])` calls `runInitCode()`, which runs each registered `run { ... }` block via `KyoApp.runAndBlock(runTimeout)(handle(effect))` on the platform scheduler. On JVM, `Sync.Unsafe.evalOrThrow` drives the block synchronously.

The `args` are accessible inside `run { ... }` via `protected def args: Chunk[String]` (inherited from `KyoApp.Base`).

---

## Scripted test layout

Scripted test format is established by `kyo-compat/plugin/src/sbt-test/kyo-compat/`. Each test is a directory containing:
- `test` — scripted commands (one per line; `>` = sbt task; `->` = expected-failure task)
- `build.sbt` — test build definition
- `project/plugins.sbt` — plugin wiring
- `project/build.properties` — sbt version pin

### Verbatim example: `kyo-compat/plugin/src/sbt-test/kyo-compat/jvm-only/project/plugins.sbt`

```scala
sys.props.get("plugin.version") match {
    case Some(x) => addSbtPlugin("io.getkyo" % "kyo-compat" % x)
    case _       => sys.error("plugin.version not set")
}
addSbtPlugin("com.eed3si9n"       % "sbt-projectmatrix"             % "0.10.1")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject"      % "1.3.2")
addSbtPlugin("org.scala-js"       % "sbt-scalajs"                   % "1.20.2")
addSbtPlugin("org.scala-native"   % "sbt-scala-native"              % "0.5.10")
addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.3.2")
```

The `plugin.version` system property is injected by `scriptedLaunchOpts := Seq("-Dplugin.version=" + version.value)` in the plugin's build.sbt settings. The scripted test reads it to resolve the locally-published plugin artifact.

### Test 1: `basic` (positive case)

`kyo-reflect-sbt/plugin/src/sbt-test/kyo-reflect-sbt/basic/project/plugins.sbt`:

```scala
sys.props.get("plugin.version") match {
    case Some(x) => addSbtPlugin("io.getkyo" % "kyo-reflect-sbt" % x)
    case _       => sys.error("plugin.version not set")
}
```

`kyo-reflect-sbt/plugin/src/sbt-test/kyo-reflect-sbt/basic/project/build.properties`:

```
sbt.version=1.12.5
```

`kyo-reflect-sbt/plugin/src/sbt-test/kyo-reflect-sbt/basic/build.sbt`:

```scala
ThisBuild / scalaVersion := "3.7.0"

lazy val root = (project in file("."))
    .enablePlugins(KyoReflectPlugin)
    .settings(
        organization := "com.example",
        version      := "0.1.0-TEST",
        name         := "basic-scripted-test"
    )
```

`kyo-reflect-sbt/plugin/src/sbt-test/kyo-reflect-sbt/basic/src/main/scala/Foo.scala`:

```scala
object Foo { val x = 1 }
```

`kyo-reflect-sbt/plugin/src/sbt-test/kyo-reflect-sbt/basic/test`:

```
# Positive case: plugin produces a snapshot file and the file exists with size > 0.
> compile
> reflectSnapshot
$ exists target/kyo-reflect-snapshots
```

The `$ exists` command in scripted asserts a path exists on the filesystem. There is no built-in "file size > 0" assertion in scripted; verify non-emptiness by adding a custom `checkSnapshot: TaskKey[Unit]` assertion task to `build.sbt` that opens the file and checks its length, then calling `> checkSnapshot`.

Extended `build.sbt` with assertion task:

```scala
val checkSnapshot = taskKey[Unit]("assert snapshot file is non-empty")
checkSnapshot := {
    val snapshotDir = (Compile / reflectSnapshotDir).value
    val files = Option(snapshotDir.listFiles()).getOrElse(Array.empty)
        .filter(_.getName.endsWith(".krfl"))
    if (files.isEmpty)
        sys.error(s"No .krfl snapshot file found in $snapshotDir")
    val f = files.head
    if (f.length() == 0L)
        sys.error(s"Snapshot file ${f.getName} has size 0")
    println(s"checkSnapshot OK: ${f.getName} (${f.length()} bytes)")
}
```

Extended `test` file:

```
> compile
> reflectSnapshot
> checkSnapshot
```

### Test 2: `missing-runner` (failure-mode case)

`kyo-reflect-sbt/plugin/src/sbt-test/kyo-reflect-sbt/missing-runner/build.sbt`:

```scala
ThisBuild / scalaVersion := "3.7.0"

lazy val root = (project in file("."))
    .enablePlugins(KyoReflectPlugin)
    .settings(
        name := "missing-runner-scripted-test"
    )
```

`kyo-reflect-sbt/plugin/src/sbt-test/kyo-reflect-sbt/missing-runner/test`:

```
# Failure-mode case: when runner JAR is absent, task fails with useful message.
-> reflectSnapshot
```

The `->` prefix means sbt scripted expects this command to fail. To force the runner JAR to be absent, the `build.sbt` must override `reflectRunnerJar` (a `SettingKey[Option[File]]` or equivalent) to point at a non-existent path. The plugin task must check runner JAR existence before forking and throw `MessageOnlyException` with a message that names the missing JAR path, so the user sees a useful error rather than a `NullPointerException`.

---

## Runner deployment

This is the principal design question Phase 7 must resolve before implementation starts.

### Option A: Separate Maven artifact (production approach)

The plugin declares the runner as a `libraryDependency` with the `runner.version` system property injected via `scriptedLaunchOpts`:

```scala
// Inside KyoReflectPlugin.reflectSnapshot task implementation:
val runnerVersion = getClass.getPackage.getImplementationVersion
val runnerDeps = Seq(
    "io.getkyo" % "kyo-reflect-sbt-runner" % runnerVersion
)
val runnerJar: File = resolveRunnerJar(runnerDeps, streams.value.log)
```

Resolution uses sbt's `update` key or a standalone `DependencyResolution` call. The runner JAR and all its transitive dependencies (kyo-reflect.jvm, kyo-core.jvm, etc.) are placed on the forked JVM classpath.

Advantage: works in any project that publishes kyo artifacts (CI, Maven Central).
Disadvantage: requires the runner to be published before the plugin can be tested end-to-end. During local development within the kyo build, this requires `publishLocal` of both artifacts before running `scripted`.

### Option B: In-tree JAR path (development shortcut)

The plugin reads `runner.jar` system property (injected via `scriptedLaunchOpts := Seq("-Drunner.jar=" + ...)`) pointing at the locally-built runner assembly JAR. This avoids Maven resolution for local testing.

```scala
// In plugin task:
val runnerJarPath = sys.props.getOrElse("runner.jar",
    throw new MessageOnlyException("kyo-reflect-sbt: runner.jar system property not set"))
val runnerJar = file(runnerJarPath)
if (!runnerJar.exists())
    throw new MessageOnlyException(
        s"kyo-reflect-sbt: runner JAR not found at $runnerJarPath. " +
        s"Run `kyo-reflect-sbt-runner/assembly` first.")
```

The `build.sbt` plugin definition injects this path:

```scala
scriptedLaunchOpts := Seq(
    "-Xmx1024M",
    "-Dplugin.version=" + version.value,
    "-Drunner.jar=" + (`kyo-reflect-sbt-runner` / assembly).value.getAbsolutePath
)
```

This requires `sbt-assembly` to be added to `project/plugins.sbt`. Note: `(`kyo-reflect-sbt-runner` / assembly).value` forces the runner assembly to be built before `scripted` runs.

Advantage: no Maven resolution; deterministic in-tree path; reliable for CI.
Disadvantage: requires `sbt-assembly` as an additional dependency; the runner assembly must be built before scripted runs.

### Recommendation

Use **Option B** for the initial implementation. It is simpler, deterministic, and avoids the publish-before-test bootstrapping problem. The production deployment (Option A) can be layered on top once the plugin works end-to-end. For the scripted `missing-runner` test, override the `runner.jar` property to a non-existent path.

---

## Forked JVM classpath construction

The runner JAR needs kyo-reflect.jvm and all transitive dependencies on the forked JVM classpath. With Option B (assembly JAR), this is automatic — `sbt-assembly` packages kyo-reflect and all dependencies into one fat JAR.

With Option A (Maven artifacts), the plugin task must construct the full classpath:

```scala
// Conceptual — actual sbt API:
val runnerClasspath: Seq[File] = runnerJar +: runnerTransitiveDeps
Fork.java(
    ForkOptions().withJavaHome(javaHome.value),
    Seq(
        "-classpath", runnerClasspath.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator),
        "kyo.internal.reflect.sbt.SnapshotRunner",
        compileClasspathRoots.mkString(java.io.File.pathSeparator),
        snapshotDir.getAbsolutePath
    )
)
```

The compile classpath roots (passed as argument 0 to the runner) come from `(Compile / fullClasspath).value.map(_.data.getAbsolutePath)`. These are the project's class directories and JAR dependencies — what `openCached` will index.

---

## Snapshot location convention

`openCached(roots, cacheDir)` writes the snapshot to `cacheDir/<hexDigest>.krfl`. The plugin sets `cacheDir = (Compile / reflectSnapshotDir).value.getAbsolutePath`, which defaults to `target/kyo-reflect-snapshots`. This is:

- Per-project (inside `target/`), so different projects with different classpaths get independent caches
- Gitignored by default (sbt's `.gitignore` template ignores `target/`)
- Cleaned by `sbt clean`

For shared snapshot caches across projects (e.g., CI layer caching), users can override `reflectSnapshotDir` to a shared directory such as `sys.props.getOrElse("user.home", ".") + "/.cache/kyo-reflect"`.

The scripted `basic` test runs in a fresh `target/` directory (scripted creates a temp directory per test), so no stale digest hits are possible during scripted testing.

---

## Edge cases and gotchas

**Plugin Scala version**: sbt 1.x plugins compile against Scala 2.12. The plugin can ONLY use 2.12-compatible code in `KyoReflectPlugin.scala`. No Scala 3 features: no `given`/`using`, no extension methods, no `enum`, no opaque types, no union/intersection types. The plugin file must be valid Scala 2.12 as compiled by sbt's internal `zinc` + Scala 2.12.x.

**kyo-compat uses `sbt.internal.ProjectMatrix`**: the `kyo-compat` plugin imports `sbt.internal.ProjectMatrix` (internal sbt API). `KyoReflectPlugin` does NOT need this — it only uses stable `sbt.Keys` (`fullClasspath`, `baseDirectory`, `target`, etc.) and `sbt.Fork`. Stay on stable APIs.

**`autoImport` visibility**: settings and tasks added to `autoImport` are automatically imported into the user's `build.sbt`. Do not put helper types into `autoImport` unless they need to be user-facing. `reflectSnapshotDir` and `reflectSnapshot` should be in `autoImport`; internal helpers stay inside the plugin object.

**`override def trigger`**: use `noTrigger` (not `allRequirements`). The plugin should be explicitly enabled via `enablePlugins(KyoReflectPlugin)`. Automatic activation on all projects (like `kyo-compat`'s `allRequirements`) is wrong here — not every project wants snapshot generation.

**`override def requires`**: `sbt.plugins.JvmPlugin` is sufficient. No additional plugin requirements.

**Runner `System.exit`**: `KyoApp` calls `exit(code)` (which calls `internal.Platform.exit`) on non-success results. On JVM, `Platform.exit` delegates to `java.lang.Runtime.getRuntime.halt(code)` or `System.exit(code)`. The forked JVM receives this as a process exit code. The plugin checks the exit code via `Fork.java.apply(...): Int`.

**Path separators**: use `java.io.File.pathSeparator` (`:` on Unix, `;` on Windows) to join root paths. Do NOT hardcode `:`. The runner splits on `java.io.File.pathSeparatorChar`.

**Spaces in paths**: individual root paths may contain spaces. Joining with `pathSeparator` and passing as a single argument string to the forked JVM is fine as long as the argument is passed as one element of the `Seq[String]` (not interpolated into a shell command). `Fork.java` uses `ProcessBuilder` internally, which handles args without shell expansion.

**Output strategy**: use `Some(StdoutOutput)` in `ForkOptions` so runner output (including error messages) is visible in the sbt console. `sbt.StdoutOutput` pipes the forked process's stdout and stderr to sbt's output stream.

**JVM startup cost**: approximately 1-2 seconds per `reflectSnapshot` task execution. Acceptable for build-time use. The snapshot file persists in `target/kyo-reflect-snapshots/`; subsequent `openCached` calls on an unchanged classpath hit the cache (O(jars) stat calls) rather than re-running the forked JVM.

**Snapshot cross-platform loadability**: the plugin runs on JVM only (sbt runs on JVM). The `.krfl` snapshot it produces must be loadable by `Reflect.Classpath.openCached` on JVM, JS, and Native. The scripted test covers JVM loadability. JS and Native loadability is covered by the existing `SnapshotRoundTripTest` suite, which already exercises the snapshot read path on all three platforms.

**Two-level fork depth in scripted tests**: sbt scripted runs in a forked sbt process (level 1). The plugin task spawns a second forked JVM for the runner (level 2). Level 1 is controlled by `scriptedLaunchOpts`; level 2 is controlled by `ForkOptions` inside the plugin task. Both forks are sequential, not concurrent, so port/resource conflicts are not an issue.

**Digest-keyed snapshot naming**: `openCached` names the snapshot `<hexDigest>.krfl` where the digest is computed from classpath root metadata (mtime + size after Phase 2). If Phase 2 is not yet committed when Phase 7 is implemented, the digest computation may still open every JAR. The scripted test does not assert specific digest values; it only checks that a `.krfl` file exists with size > 0.

---

## Anti-flakiness notes

- JVM startup time varies (1-3s). Do not assert wall-clock budgets in scripted tests.
- The snapshot file is keyed by digest. Scripted tests run in a fresh `target/` (sbt scripted creates a temp project directory per test run), so stale digest hits cannot occur.
- The `missing-runner` test relies on `-> reflectSnapshot` (expected failure). The runner JAR path must be reliably absent for this test to pass consistently. Use a hardcoded non-existent path such as `file("/nonexistent/kyo-reflect-sbt-runner.jar")` rather than trying to delete a real file.
- `Fork.java.apply` blocks until the forked process exits. Scripted tests have a timeout (default 5 minutes per step in sbt 1.x scripted). Runner execution on a minimal classpath (one class file) takes under 5 seconds.

---

## Concerns requiring supervisor attention

### Concern 1: Runner JAR resolution strategy must be decided before implementation starts

The plugin task needs to locate the runner JAR at task execution time. Option A (Maven resolution) and Option B (assembly JAR via system property) have different build.sbt implications:

- Option A requires: no `sbt-assembly`; `libraryDependencies` resolution inside the plugin task; runner must be published (or in Maven local) before scripted runs. Bootstrapping for `publishLocal` requires `kyo-reflect-sbt-runner` to be built and published first.
- Option B requires: `sbt-assembly` plugin added to `project/plugins.sbt`; `scriptedLaunchOpts` injects `-Drunner.jar=...` pointing at the assembled fat JAR; `scripted` depends on `kyo-reflect-sbt-runner/assembly` task completing first.

The recommendation is Option B. Supervisor should confirm before implementation so the build.sbt additions are correct.

### Concern 2: `kyo-reflect-sbt-runner` as plain `project` vs `crossProject`

`kyo-reflect-sbt-runner` is proposed as a plain `project in file("kyo-reflect-sbt/runner")` (not a `crossProject`), because the runner is JVM-only (sbt runs on JVM). This means it cannot use the `kyo-settings` macro that `crossProject`s use. The runner's `build.sbt` settings need to include `scalaVersion` explicitly. Confirm the correct Scala 3 version string (the rest of kyo uses `3.7.0` or the `dottyVersion` variable in build.sbt).

### Concern 3: Snapshot directory convention — per-project vs shared

The default `target/kyo-reflect-snapshots` is cleaned by `sbt clean`, which means every clean build regenerates the snapshot (one fork per build). For projects with large classpaths, this adds 1-2 seconds to each post-clean build. An alternative default of `sys.props("user.home") + "/.cache/kyo-reflect"` would persist across clean builds but requires users to manually evict stale snapshots. The plan says `target/kyo-reflect-snapshots`; confirm this is the intended default.

### Concern 4: `trigger = noTrigger` vs `allRequirements`

The plan (execution-plan-perf.md Phase 7) says the plugin "does NOT add itself as a compile or test dependency to the project it is applied to". This implies `noTrigger` so users opt in via `enablePlugins(KyoReflectPlugin)`. The `kyo-compat` plugin uses `allRequirements` because it injects `compatKyoVersion` into every `JvmPlugin`-enabled project. `KyoReflectPlugin` should NOT do this. Confirm `noTrigger` is the right choice.

### Concern 5: Effect discharge in `SnapshotRunner.main`

`Reflect.Classpath.openCached` returns `Classpath < (Sync & Async & Scope & Abort[ReflectError])`. The runner must discharge all four effects before `main` returns. Using `object SnapshotRunner extends KyoApp` with `run { openCached(...) }` is the cleanest approach (all discharge happens inside `KyoApp`). However, `KyoApp` exits with code 1 and prints a stack trace on `Abort[Any]` failure. The runner needs `Abort[ReflectError]` failure to produce a human-readable message without a stack trace. Override `onResult` or use `Abort.run[ReflectError](openCached(...)).map { case Failure(e) => java.lang.System.err.println(...); exit(1); case _ => () }` inside the `run { ... }` block. Confirm the failure-output format before implementation.
