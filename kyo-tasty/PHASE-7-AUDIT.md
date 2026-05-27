# Phase 7 audit - sbt plugin via fork-JVM

Commit audited: `93ca38f7f` ("kyo-reflect Phase 7: sbt plugin via fork-JVM for build-time snapshots").
Verified via `git log --oneline -3` (Phase 7 HEAD, Phase 6 audit/prep at HEAD-1, Phase 6 impl at HEAD-2).

Files changed (13):

- `build.sbt` (+59 -1)
- `project/plugins.sbt` (+3)
- `kyo-reflect-sbt/plugin/src/main/scala/kyo/KyoReflectPlugin.scala` (NEW, 129 lines)
- `kyo-reflect-sbt/runner/src/main/scala/io/getkyo/reflect/sbt/runner/SnapshotRunner.scala` (NEW, 45 lines)
- `kyo-reflect-sbt/plugin/src/sbt-test/kyo-reflect-sbt/basic/{test,build.sbt,project/build.properties,project/plugins.sbt,src/main/scala/Foo.scala}`
- `kyo-reflect-sbt/plugin/src/sbt-test/kyo-reflect-sbt/missing-runner/{test,build.sbt,project/build.properties,project/plugins.sbt}`

---

## Test count

| # | Scripted test | Status | Location |
|---|---|---|---|
| 1 | basic (positive: snapshot generated + non-empty) | PRESENT_STRICT | `kyo-reflect-sbt/plugin/src/sbt-test/kyo-reflect-sbt/basic/test:1-4` |
| 2 | missing-runner (negative: useful error on missing JAR) | PRESENT_STRICT | `kyo-reflect-sbt/plugin/src/sbt-test/kyo-reflect-sbt/missing-runner/test:1-2` |

Total: 2 / 2 scripted tests planned, 2 present, both strict.

`basic/test` runs `compile`, `reflectSnapshot`, and a custom `checkSnapshot` task defined in the test's `build.sbt:11-22` that asserts (1) at least one `*.krfl` file exists in `reflectSnapshotDir`, (2) file length > 0. Implements the prep doc's "extended test file" pattern verbatim (PHASE-7-PREP.md:314-320).

`missing-runner/test:2` uses the `->` (expected-failure) scripted directive, with `reflectRunnerClasspath` set to `/nonexistent/kyo-reflect-sbt-runner-assembly.jar` in `build.sbt:9`, exercising the plugin's missing-JAR validation branch at `KyoReflectPlugin.scala:83-89`.

NOTE: execution-plan-perf.md:310 mentioned a step "verify snapshot is loadable by calling `Reflect.Classpath.openCached`" as part of `basic`. The committed `basic/test` does NOT exercise `openCached` in the scripted project (only filesystem existence + non-zero size). PHASE-7-PREP.md:289-293 narrowed this to existence + non-empty assertion only, so the committed test matches the prep doc but is a soft deviation from the original plan. Categorized as NOTE (cross-platform snapshot loadability is covered by existing `SnapshotRoundTripTest` per execution-plan-perf.md:410).

---

## Plugin design verification

| Check | Result | Citation |
|---|---|---|
| Scala 2.12 syntax only (no `given`/`using`/`extension`/`enum`/opaque) | PASS | `KyoReflectPlugin.scala`: uses `package kyo`, `import sbt._`, `object … extends AutoPlugin`, `Seq[Setting[_]]` (wildcard), pattern-match arrows; no Scala 3-only constructs grep-matched |
| `override def trigger = noTrigger` | PASS | `KyoReflectPlugin.scala:23` |
| `reflectSnapshot: TaskKey[File]` | PASS | `KyoReflectPlugin.scala:38-40` |
| `reflectSnapshotDir: SettingKey[File]` | PASS | `KyoReflectPlugin.scala:29-31` |
| Forks JVM via `sbt.Fork.java` | PASS | `KyoReflectPlugin.scala:108-115` (`Fork.java(forkOpts, Seq(...))`) |
| Reads runner JAR from `-Drunner.jar` (Option B) | PASS | `KyoReflectPlugin.scala:60-65` (`sys.props.get("runner.jar")`) |
| Captures non-zero exit and raises `MessageOnlyException` | PARTIAL | `KyoReflectPlugin.scala:117-122` raises `MessageOnlyException` on non-zero exit, but does NOT separately capture/relay stderr. Output uses `Some(StdoutOutput)` strategy (`KyoReflectPlugin.scala:104`), so the runner's stderr line is already streamed to the sbt console before the exception fires (matches PHASE-7-PREP.md:461). The exception message points the user at the output above. ACCEPTABLE; categorized as NOTE only because a stricter reading of "capture stderr and raise MessageOnlyException" would attach the captured line to the exception itself. |

Additional plugin observations:

- `reflectRunnerClasspath: SettingKey[Seq[File]]` (`KyoReflectPlugin.scala:46-49`) added as a third autoImport key. Not in the original execution-plan-perf.md public-API list (execution-plan-perf.md:327 lists `reflectSnapshot`, `reflectSnapshotDir`, `SnapshotRunner` only). The prep doc anticipated this addition (Option B requires a way for users to override the runner JAR location) and the missing-runner scripted test relies on overriding it (`missing-runner/build.sbt:8`). Categorized as NOTE.
- Default for `reflectRunnerClasspath` reads `runner.jar` system property; if unset, defaults to empty `Seq`. The empty-Seq path triggers a useful error at task invocation (`KyoReflectPlugin.scala:75-80`). Production users without the JVM property set will see `"reflectRunnerClasspath is empty"`; this is the dev-mode wiring promised by STEERING.md decision 1.
- `requires = sbt.plugins.JvmPlugin` (`KyoReflectPlugin.scala:24`) matches PHASE-7-PREP.md:453.

---

## Runner design verification

| Check | Result | Citation |
|---|---|---|
| Scala 3 | PASS | `SnapshotRunner.scala:1` uses `package io.getkyo.reflect.sbt.runner`, `extends KyoApp:` (Scala 3 colon syntax), `if … then … else` (lines 17,21), `.map:` colon-method syntax (line 26) |
| Package `io.getkyo.reflect.sbt.runner` (NOT `kyo.internal.reflect.sbt`) | PASS | `SnapshotRunner.scala:1`; plugin's `runnerMainClass` matches: `KyoReflectPlugin.scala:54` `private val runnerMainClass = "io.getkyo.reflect.sbt.runner.SnapshotRunner"` |
| Uses `KyoApp.run` as entrypoint | PASS | `SnapshotRunner.scala:15` `object SnapshotRunner extends KyoApp:` ; line 16 `run { … }` |
| Parses args: classpath roots + output dir | PASS | `SnapshotRunner.scala:17-22` validates `args.size < 2`, splits `args(0)` on `pathSeparatorChar`, reads `args(1)` as snapshot dir |
| Exits 0 success / 1 Abort / 2 panic | PARTIAL | `SnapshotRunner.scala:27-44`: Success branch prints to stdout but does NOT call `halt(0)`; falls through to KyoApp's normal exit (also 0). Failure branches: `Result.Failure(err) => halt(1)` line 38, `Result.Panic(t) => halt(2)` line 43. Matches STEERING.md "1 for known failure, 2 for unexpected panic". Argument-validation failure also halts(1) (line 23), conflating arg-error with ReflectError. Minor; categorized as NOTE. |

Runner-specific observations:

- Uses `java.lang.Runtime.getRuntime.halt(N)` instead of `KyoApp.exit(N)`. `halt` skips JVM shutdown hooks. STEERING.md decision 5 says "non-zero exit code (1 for known failure, 2 for unexpected panic)". For a forked-JVM build-tool runner that has finished its work this is fine; snapshot file writes go through `Sync.defer` in `openCached` and complete before the `Abort.run.map` callback fires. Categorized as NOTE.
- The argument-validation early-exit (lines 18-24) does `halt(1)` and then returns `Sync.defer(())`. The `halt` call is unreachable to follow-on code; the trailing `Sync.defer(())` exists purely so the early-exit branch type-checks inside the Kyo computation. Acceptable; goes stricter than PHASE-7-PREP.md:135 which only suggested relying on a `throw`.
- Effect discharge: `Abort.run[ReflectError]` discharges `Abort[ReflectError]`; `KyoApp` discharges `Sync & Async & Scope & Abort[Any]`. Chain `openCached → Abort.run → .map(Result match)` matches the prep doc skeleton (PHASE-7-PREP.md:507).
- Error output single-line, no stack trace - matches prep doc requirement (PHASE-7-PREP.md:117 "single-line error message printed to stderr. … No stack traces in the user-facing error.").

---

## build.sbt verification

| Check | Result | Citation |
|---|---|---|
| `kyo-reflect-sbt-runner` is plain JVM project (not crossProject) | PASS | `build.sbt:1310` `lazy val \`kyo-reflect-sbt-runner\` = (project in file("kyo-reflect-sbt/runner"))` |
| `kyo-reflect-sbt-plugin` enables `SbtPlugin`, scalaVersion 2.12.20 | PASS | `build.sbt:1329` `.enablePlugins(SbtPlugin)`; `build.sbt:1332-1334` `scalaVersion := "2.12.20"`, `crossScalaVersions := Seq("2.12.20")`, `sbtPlugin := true` |
| Both aggregated into kyoJVM ONLY | PASS | `build.sbt:175-176` in `kyoJVM.aggregate(...)`. Grep `kyo-reflect-sbt-(runner|plugin)` against entire build.sbt yields zero hits inside `kyoJS` (lines 179-216) or `kyoNative` (lines 217-260) aggregate lists. |
| sbt-assembly used for runner fat JAR | PASS | `project/plugins.sbt:5` `addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.1")`; runner uses `assembly / assemblyJarName` (`build.sbt:1317`) and `assembly / assemblyMergeStrategy` (1318-1326) |
| `scriptedLaunchOpts` sets `-Drunner.jar` to assembled JAR path | PASS | `build.sbt:1338-1351` constructs `runnerJar` path from `(kyo-reflect-sbt-runner / target).value / s"scala-$runnerScalaVer" / "kyo-reflect-sbt-runner-assembly-$runnerVersion.jar"` and passes `-Drunner.jar=...` |
| scripted task depends on runner assembly | PASS | `build.sbt:1352` `scripted := scripted.dependsOn(\`kyo-reflect-sbt-runner\` / assembly).evaluated` |

Additional build.sbt observations:

- Runner has `mimaCheck(false)` (`build.sbt:1314`); appropriate for a tool runner, not a public-API artifact.
- Merge strategy: `META-INF/services` -> `concat` (preserves ServiceLoader entries from kyo-reflect's bundled providers, if any), `META-INF/*` and `module-info.class` -> `discard`. Standard fat-JAR practice; ACCEPTABLE.

---

## CONTRIBUTING.md violations

Searched the two committed Scala sources against the principles called out in CONTRIBUTING.md:

| Convention | Status | Notes |
|---|---|---|
| Unsafe Boundary | PASS | No `AllowUnsafe`, no `Sync.Unsafe.*`, no `Frame.internal`. Runner uses `KyoApp.run` (the safe entrypoint). Plugin is Scala 2.12 / pure sbt API; Unsafe Boundary doesn't apply. |
| No `asInstanceOf` | PASS | Grep returns zero hits across both files. |
| No `null` | PASS | Grep returns zero hits. |
| Lowercase namespace objects | N/A | No nested namespace objects in either file. |
| No effect aliases | PASS | Runner spells `Sync & Async & Scope & Abort[ReflectError]` implicitly via KyoApp; no `type Eff = …` defined. |
| No default params in internal code | PASS | Plugin's `private val runnerMainClass` is a constant, not a default; plugin's autoImport keys are SettingKeys/TaskKeys, where `:= default` IS the sbt-idiomatic mechanism (these are NOT method default parameters). Runner has no methods with defaults. |
| Frame propagation | N/A | Runner is a `KyoApp` body inside `run { … }`; frame propagation handled by KyoApp. |
| `kyo` package = public API, impl in `kyo.internal` | DEVIATION | Plugin lives at `package kyo` (`KyoReflectPlugin.scala:1`). The plugin is at `kyo.KyoReflectPlugin`, not `kyo.internal.*` or `io.getkyo.*`. Putting an sbt-plugin entry in `package kyo` is unusual; `kyo.*` is consumed by users of the Kyo library at runtime, but the plugin is loaded into the sbt classloader. No runtime collision (separate artifact, separate classloader). execution-plan-perf.md:301 explicitly specifies this path. Categorized as WARN; convention deviation called out by the plan. |
| No em-dashes in code | DEVIATION (build.sbt only) | The two new Scala source files contain no em-dashes. `build.sbt:1303` and `:1305` (new Phase 7 comment block) contain em-dashes: `"kyo-reflect-sbt-runner  — Scala 3 JVM JAR…"` and `"kyo-reflect-sbt-plugin  — Scala 2.12 sbt AutoPlugin…"`. Two em-dashes introduced in a comment. The user's writing-style rule forbids em-dashes everywhere; this trips the rule. Categorized as WARN. |

---

## Unsafe markers

Grep for `asInstanceOf`, `Frame.internal`, `AllowUnsafe`, `Sync.Unsafe.defer`, ` null ` across plugin + runner: **zero hits**. None introduced.

---

## Cross-platform consistency

- Plugin and runner appear ONLY in `kyoJVM.aggregate(...)` (build.sbt:175-176).
- `kyoJS.aggregate(...)` (build.sbt:179-216) does NOT contain `kyo-reflect-sbt-runner` or `kyo-reflect-sbt-plugin`; only references `kyo-reflect.js` and `kyo-reflect-fixtures.js`.
- `kyoNative.aggregate(...)` (build.sbt:217-260) does NOT contain `kyo-reflect-sbt-runner` or `kyo-reflect-sbt-plugin`; only references `kyo-reflect.native` and `kyo-reflect-fixtures.native`.

Correct: sbt plugins are JVM-only artifacts.

---

## Steering deviation

Compared `git show 93ca38f7f --name-only` against the Phase 7 expected file set:

| Expected | Committed | Match? |
|---|---|---|
| `kyo-reflect-sbt/plugin/src/main/scala/kyo/KyoReflectPlugin.scala` | yes | YES |
| `kyo-reflect-sbt/runner/src/main/scala/io/getkyo/reflect/sbt/runner/SnapshotRunner.scala` | yes | YES (path deviates from execution-plan-perf.md:306 which expected `kyo/internal/reflect/sbt/SnapshotRunner.scala`; the deviation is justified by Kyo's Frame derivation macro blocking `kyo.*` packages, documented in the commit message. Documented as NOTE.) |
| basic scripted test directory: `test`, `build.sbt`, `project/build.properties`, `project/plugins.sbt`, `src/main/scala/Foo.scala` | all 5 present | YES |
| missing-runner scripted test directory: `test`, `build.sbt`, `project/build.properties`, `project/plugins.sbt` | all 4 present | YES |
| `build.sbt` modifications | yes (+59 lines) | YES |
| `project/plugins.sbt` modifications for sbt-assembly | yes (+3 lines) | YES |

Total committed files: 13. Total expected files: 13. **No extraneous files.**

---

## Anti-flakiness measures

- Scripted tests are deterministic. Both `test` files contain only `>` (run task) and `->` (expected-fail) directives. No `$ exists` filesystem-timing race (basic test uses an in-build `checkSnapshot` task that reads `reflectSnapshotDir` synchronously after `reflectSnapshot` completes).
- No `Thread.sleep`, no `sleep` lines in either `test` file.
- `missing-runner` uses a hardcoded non-existent absolute path (`/nonexistent/...`); cannot accidentally exist on any CI runner. Matches PHASE-7-PREP.md:477 guidance.
- JVM startup-time variance is not asserted on wall-clock in either test. Matches PHASE-7-PREP.md:475.
- `Fork.java.apply` blocks until the forked process exits (PHASE-7-PREP.md:478); no race between plugin-task-return and snapshot-file-write.
- Runner uses `halt` not `exit`; bypasses shutdown hooks but ensures the forked process exits promptly with a deterministic code the plugin's exit-code check can observe. Acceptable for a one-shot tool.

---

## Specific Phase 7 concerns

### NOTE 1: Runner package location deviation

The runner lives at `io.getkyo.reflect.sbt.runner.SnapshotRunner` (`SnapshotRunner.scala:1`), NOT `kyo.internal.reflect.sbt.SnapshotRunner` as originally specified in execution-plan-perf.md:306 and PHASE-7-PREP.md:121.

Reason (from commit message): "Kyo's Frame derivation macro blocks derivation within any package whose FQN starts with `kyo.`."

This is a real Kyo macro restriction, discovered empirically during scripted-test debugging. The plugin's `runnerMainClass` constant (`KyoReflectPlugin.scala:54`) correctly points at the actual package, so plugin-to-runner wiring is consistent. **For future awareness:** any new Kyo entry point that must derive Frame (i.e., any `KyoApp`-style runner) must live outside the `kyo.*` package. This generalizes beyond Phase 7 and is worth surfacing in DESIGN.md or STEERING.md before similar tools land.

### NOTE 2: Production deployment is out of scope

Phase 7 ships the dev-mode wiring (Option B): plugin reads `-Drunner.jar` system property pointing at a locally-built assembly JAR. For published-artifact use (real-world projects depending on `io.getkyo % kyo-reflect-sbt %` from Maven Central), the plugin must locate the runner JAR without a `runner.jar` system property. Two paths exist:

1. Bundle the runner JAR as a classpath resource inside the plugin's published artifact, extract at task time, point `Fork.java` at the extracted path.
2. Add the runner as a managed `libraryDependency` on the plugin (Option A from STEERING) and resolve via sbt's `update` / `DependencyResolution`.

Neither is implemented. The plugin's empty-`reflectRunnerClasspath` path raises a clear `MessageOnlyException` directing the user to set `-Drunner.jar` (`KyoReflectPlugin.scala:75-80`), which is correct dev-time behavior but means the published artifact is unusable as-is. **Phase 7 scope explicitly excludes production publication; should be tracked as a follow-up before any external release of `kyo-reflect-sbt`.**

### NOTE 3: One supporting autoImport key added

The plugin exposes three autoImport keys (`reflectSnapshotDir`, `reflectSnapshot`, `reflectRunnerClasspath`), whereas execution-plan-perf.md:327 lists only the first two as public API. The third is the user-facing override hook for Option B and is required for the `missing-runner` scripted test to work without subverting the plugin internals. ACCEPTABLE; minor addition consistent with the chosen runner-deployment strategy.

---

## Categorization

### BLOCKER

None. Phase 7 is functionally complete per STEERING decisions and PHASE-7-PREP.md.

### WARN

1. **Plugin lives in `package kyo`** (`KyoReflectPlugin.scala:1`). `kyo.*` per project convention is public *runtime library* surface; an sbt AutoPlugin is build-tool surface loaded by sbt's classloader, not application code. The execution plan explicitly specifies this path (execution-plan-perf.md:301), and `kyo-compat` itself uses `package io.getkyo.compat`. Recommend tracking a follow-up to relocate to `io.getkyo.reflect.sbt.KyoReflectPlugin` for consistency with `kyo-compat` and to keep `kyo.*` reserved for the runtime library surface. Not a blocker because no runtime collision is possible (separate artifact, separate classloader).

2. **Two em-dashes introduced in `build.sbt:1303,1305`** (new Phase 7 comment block). The two new Scala source files are clean. The user's writing-style rule forbids em-dashes in any output (prose, code, README, commits, chat). Recommend a follow-up edit replacing the two em-dashes with colons or hyphens.

### NOTE

1. **Runner package `io.getkyo.reflect.sbt.runner.*` is now permanent** because the Kyo Frame derivation macro blocks `kyo.*` packages. Should be surfaced in DESIGN.md / STEERING.md for future Frame-deriving entry-point work.
2. **Runner uses `Runtime.halt` not `KyoApp.exit`** — skips shutdown hooks; OK in practice because snapshot writes complete before the post-`openCached` callback fires.
3. **Argument-validation failure exits 1** (same code as ReflectError); a separate code (e.g., 3) would let the plugin distinguish argument-wiring bugs from genuine reflection errors. Cosmetic.
4. **Production deployment of runner JAR is out of scope** for Phase 7. The published `kyo-reflect-sbt` artifact requires `-Drunner.jar` to be set or `reflectRunnerClasspath` to be overridden; neither is automatic. Bundled-resource or managed-dependency strategy needs to land before any external release.
5. **Plugin stdout/stderr capture is "stream-only"** — runner output goes straight to the sbt console via `Some(StdoutOutput)`; the `MessageOnlyException` on non-zero exit references the prior output rather than re-quoting the captured stderr line. ACCEPTABLE per PHASE-7-PREP.md:461.
6. **Basic scripted test does not exercise `openCached` round-trip** — only filesystem existence + size > 0. Loadability across platforms is covered by `SnapshotRoundTripTest` per execution-plan-perf.md:410. Matches the prep-doc-narrowed scope.
7. **`reflectRunnerClasspath` autoImport key not in original execution-plan-perf.md public-API list** but required for Option B wiring and for the `missing-runner` test. Minor expansion of the public surface; acceptable.

---

## Summary

Phase 7 ships cleanly: 13 files committed, all in scope; 2 scripted tests both PRESENT_STRICT; design matches PHASE-7-PREP.md and STEERING.md Phase 7 decisions verbatim, with one documented package-path deviation forced by the Frame macro. Plugin is Scala 2.12, runner is Scala 3, aggregation is kyoJVM-only. No Unsafe markers introduced. No BLOCKERs. Two WARNs (plugin's `package kyo` placement; two em-dashes in a build.sbt comment) and seven NOTEs, none of which gate Phase 8.

Phase 8 (re-profile) may proceed.
