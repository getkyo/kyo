import WasmCrossProject.*
import WithKyoTest._
import com.github.sbt.git.SbtGit.GitKeys.useConsoleForROGit
import kyo.build.ScalacOption
import kyo.build.ScalacOptions
import kyo.build.ScalaVersion
import org.scalajs.jsenv.nodejs.*
import sbtdynver.DynVerPlugin.autoImport.*
import scala.scalanative.build.NativeConfig

val scala3Version    = "3.8.4"
val scala3LTSVersion = "3.3.8"
val scala213Version  = "2.13.18"

// Scaladoc runs from a newer release than the compiler that produced the code. It reads TASTy, and
// TASTy is backward compatible, so the tool version moves independently of `scala3Version`. This one
// carries scala/scala3#25779, without which rendering a method signature can fail with a null
// SignatureBuilder.content on Linux x86_64 and abort the publish.
val scaladocVersion = "3.9.0-RC4"

// The scaladoc release used for a module: the fixed one for the current series, the module's own
// everywhere else. Only the current series can read what the fixed tool carries.
val scaladocToolVersion = Def.setting {
    if (scalaVersion.value == scala3Version) scaladocVersion else scalaVersion.value
}

// Holds the scaladoc tool and its dependencies. Hidden so it stays out of published poms, and
// separate from the compile classpath so the tool's own Scala version never reaches user code.
lazy val ScaladocTool = config("scaladocTool").hide

val zioVersion       = "2.1.26"
val catsVersion      = "3.7.0"
val oxVersion        = "1.0.5"
val scalaTestVersion = "3.2.20"

val compilerOptionFailDiscard = "-Wconf:msg=(unused.*value|discarded.*value|pure.*statement):error"

val compilerOptions = Set(
    ScalacOptions.encoding("utf8"),
    ScalacOptions.feature,
    ScalacOptions.unchecked,
    ScalacOptions.deprecation,
    ScalacOptions.warnValueDiscard,
    ScalacOptions.warnNonUnitStatement,
    ScalacOptions.languageStrictEquality,
    ScalacOptions.release("25"),
    ScalacOptions.advancedKindProjector
)

ThisBuild / scalaVersion := scala3Version
publish / skip           := true

inThisBuild(List(
    organization := "io.getkyo",
    homepage     := Some(url("https://getkyo.io")),
    licenses     := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers := List(
        Developer(
            "fwbrasil",
            "Flavio Brasil",
            "fwbrasil@gmail.com",
            url("https://github.com/fwbrasil/")
        )
    ),
    resolvers += Resolver.sonatypeCentralSnapshots,
    resolvers += Resolver.sonatypeCentralRepo("staging")
))

ThisBuild / useConsoleForROGit := (baseDirectory.value / ".git").isFile

Global / commands += Repeat.command
Global / commands += TestKyo.command
Global / commands += TestKyo.doneCommand

// Cap concurrent scaladoc runs. Each one is a forked JVM holding a whole module's TASTy graph
// (see `Compile / doc` in kyo-settings), so a handful in parallel is enough to exhaust a 16GB
// runner. Every project tags its `Compile / doc` with DocTag and concurrentRestrictions caps it
// at 1, so docs build one module at a time while compilation and tests stay parallel.
lazy val DocTag = Tags.Tag("doc")

// CI concurrency controls:
// - SBT_TASK_LIMIT: serialize ALL tasks (for OOM prevention on memory-constrained runners)
// - SBT_UPDATE_LIMIT: serialize only dependency resolution (for Windows file lock avoidance)
// - Test limit: cap concurrent test projects. On CI (detected via the `CI` env
//   var set by GitHub Actions, Travis, CircleCI, etc.) use 50% of cores to
//   reduce contention on slow runners. On local dev use 80% of cores — fewer
//   than the full count, to leave headroom for sbt's scalatest reporter sockets
//   and other background work, but higher than CI for faster iteration.
// Replace sbt's default concurrentRestrictions wholesale (rather than appending), because sbt
// resolves multiple Tags.limit on the same tag by taking the most-restrictive one. The default
// `Tags.limit(Tags.ForkedTestGroup, 1)` would otherwise shadow our larger forkLimit. Per-project
// `Global / concurrentRestrictions ++=` (e.g. Scala.JS linker locks) still appends as expected.
Global / concurrentRestrictions := {
    val taskLimit   = sys.env.getOrElse("SBT_TASK_LIMIT", "0")
    val updateLimit = sys.env.getOrElse("SBT_UPDATE_LIMIT", "0")
    val cores       = java.lang.Runtime.getRuntime.availableProcessors()
    val isCI        = sys.env.contains("CI")
    val testLimit   = 1 max (if (isCI) cores / 2 else math.ceil(cores * 0.8).toInt)
    // Forked-test cap: how many forked test JVMs run concurrently. kyo-pod splits each suite into a
    // podman fork and a docker fork (KYO_POD_RUNTIME pinning), so this bounds container-daemon
    // contention. It is a numeric, daemon-blind cap (it does NOT guarantee one fork per daemon); real
    // CI additionally serializes via SBT_TASK_LIMIT=1 (limitAll below). CI caps at 2; locally cores/2.
    val forkLimit = if (isCI) 2 else 1 max cores / 2
    Seq(
        Tags.limitAll(if (taskLimit != "0") taskLimit.toInt else cores),
        Tags.limit(Tags.Update, if (updateLimit != "0") updateLimit.toInt else 1),
        Tags.limit(Tags.Test, testLimit),
        Tags.limit(Tags.ForkedTestGroup, forkLimit),
        // Cap concurrent doctest forks. Each fork already uses dotty's internal
        // multi-thread backend; allowing 2 keeps cross-module work overlapping
        // without saturating the host. The plugin adds this same limit via
        // `+=` in globalSettings, but our `:=` above replaces
        // concurrentRestrictions wholesale, so we restate it here. See
        // KyoDoctestPlugin.scala for the tag's role.
        Tags.limit(DoctestTag, 2),
        // Serialize scaladoc: each run is a forked JVM sized by the module it documents.
        // See DocTag above.
        Tags.limit(DocTag, 1)
    )
}

// The build targets `-release 25` (JDK 25), set globally in compilerOptions. It is required, not a
// choice: kyo-data and the other foreign modules call java.lang.foreign, final in JDK 22, so the
// whole build needs a JDK >= 25 to compile (see the Global/onLoad guard and the CI setup action).
// Modules that must hold their own API surface to JDK 17 override back with release17.
lazy val release17 = Seq(
    scalacOptions --= scalacOptionTokens(Set(ScalacOptions.release("25"))).value,
    scalacOptions ++= scalacOptionTokens(Set(ScalacOptions.release("17"))).value
)

lazy val `kyo-settings` = Seq(
    fork               := true,
    scalaVersion       := scala3Version,
    crossScalaVersions := List(scala3Version),
    scalacOptions ++= scalacOptionTokens(compilerOptions).value,
    // Re-check every macro expansion against the compiler's tree invariants. The macros kyo does ship sit
    // where most programs land (Tag, Frame, Schema derivation, Sql `.run`, `assert`) and read trees that
    // inline, which is pervasive, fills with the compiler's own bindings and proxies. A malformed
    // expansion reaches users as a broken build in their code, not ours. The Scala 2.13 cross-builds (the
    // kyo-scheduler family) do not have the flag.
    scalacOptions ++= (if (scalaVersion.value.startsWith("3")) Seq("-Xcheck-macros") else Nil),
    Test / scalacOptions --= scalacOptionTokens(Set(ScalacOptions.warnNonUnitStatement)).value,
    // Not in CI: parallel cross-version compilations of one module format the same shared
    // sources concurrently, and the loser logs "scalafmt: failed for 1 sources" on every
    // Native job. The scalafmt workflow (scalafmtAll plus a dirty-tree check) is the CI
    // enforcement; compile-time formatting is a local convenience only.
    scalafmtOnCompile := !insideCI.value,
    ivyConfigurations += ScaladocTool,
    // The tool ships its own standard library, so it can only read a module whose library it agrees
    // with. That holds for the current series and not for the LTS one, whose `scala.caps` differs
    // and leaves two of it on the classpath, at which point resolving anything from `Predef` fails.
    // The LTS modules therefore document with their own version and forgo the fix, which they have
    // never needed: the crash it addresses appears in the current series.
    libraryDependencies ++= (
        if (!scalaVersion.value.startsWith("3")) Nil
        else Seq("org.scala-lang" % "scaladoc_3" % scaladocToolVersion.value % ScaladocTool.name)
    ),
    // Render the API from TASTy with a forked scaladoc rather than sbt's in-process one. Forking is
    // what bounds a tool crash to the module that provoked it: sbt's `doc` shares one JVM across
    // every module, so a single failure there takes the rest of the platform with it.
    Compile / doc := Def.task {
        val out     = (Compile / doc / target).value
        val log     = streams.value.log
        val project = name.value
        val srcs    = (Compile / doc / sources).value
        val sep     = java.io.File.pathSeparator
        val tool    = update.value.select(configurationFilter(ScaladocTool.name))
        val deps    = (Compile / dependencyClasspath).value.map(_.data)
        // `products` rather than `classDirectory`: it carries the same directories but is a task, so
        // depending on it is what compiles this module before its TASTy is read.
        val classes     = (Compile / products).value
        val opts        = (Compile / doc / scalacOptions).value
        val toolVersion = scaladocToolVersion.value
        // This tool reads TASTy, which only the Scala 3 series emits, so the 2.13 and 2.12 modules
        // (the kyo-scheduler family and the sbt plugins) have nothing it can read. They document
        // empty, the same way modules that opt out via `Compile / doc / sources := Seq.empty` do:
        // Maven Central requires a javadoc artifact to exist, not to have content.
        if (srcs.isEmpty || !scalaVersion.value.startsWith("3")) {
            IO.createDirectory(out)
            out
        } else {
            IO.createDirectory(out)
            log.info(s"Documenting $project with scaladoc $toolVersion")
            val exit = Fork.java(
                ForkOptions().withRunJVMOptions(Vector("-cp", tool.mkString(sep))),
                Seq(
                    "dotty.tools.scaladoc.Main",
                    "-d",
                    out.getAbsolutePath,
                    "-project",
                    project,
                    "-classpath",
                    deps.mkString(sep)
                ) ++ opts ++ classes.map(_.getAbsolutePath)
            )
            if (exit != 0) sys.error(s"scaladoc failed for $project")
            // Scaladoc exits 0 when handed nothing to read, so success alone does not mean a module
            // was documented. Without this an empty api directory reaches the published javadoc jar.
            if (PathFinder(out).allPaths.get.forall(!_.getName.endsWith(".html")))
                sys.error(s"scaladoc produced no pages for $project")
            out
        }
    }.tag(DocTag).value,
    scalacOptions += compilerOptionFailDiscard,
    // Treat compiler warnings as errors on the Scala 3 series. The Scala 2.13 cross-builds (the kyo-scheduler
    // family) carry a different, noisier warning set that is out of scope, so the flag is gated on Scala 3.
    scalacOptions ++= (if (scalaVersion.value.startsWith("3")) Seq("-Werror") else Nil),
    Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oDG"),
    ThisBuild / versionScheme := Some("early-semver"),
    Test / javaOptions += "--add-opens=java.base/java.lang=ALL-UNNAMED",
    // The process-lifetime default HttpClient is never closed, so its pool relies on the idle-expiry reaper to release
    // sockets. Its 60s production idle timeout outlasts the leak check's 30s fd-drain window, so a connection pooled just
    // before a fork ends would still be open at the check. Shorten it in the test fork only (production keeps 60s) so a
    // reaped default-client connection drains well within the window. See kyo.http.client.defaultIdleTimeout.
    Test / javaOptions += "-Dkyo.http.client.defaultIdleTimeout=2seconds",
    // Exclude generated FFI binding impls (src_managed *BindingsImpl from the kyo-ffi codegen): measuring
    // them tracks the generator, not hand-written code.
    coverageExcludedFiles := ".*src_managed.*",
    // Compact object headers (JEP 519) are buggy on JDK 25 under the forked test workload: heavy fiber
    // concurrency + pervasive arraycopy (Chunk/Span) + G1GC hit JDK-8380060 and a G1 concurrent-mark
    // metadata corruption, surfacing as a rare ClassNotFoundError for a class present on disk (the
    // io_uring test flake). Force COH OFF in the forks explicitly so it stays off regardless of the
    // driver's opts. The DRIVER JVM keeps COH ON (.jvmopts / CI JAVA_OPTS): it runs no forked-test
    // workload, only compile and the Scala.js/Wasm linker, whose large graph needs COH's header
    // savings to fit the 12 GB driver heap (without it the kyo-ui Wasm linker GC-thrashes to a hang).
    Test / javaOptions += "-XX:-UseCompactObjectHeaders",
    // Forked test JVMs otherwise inherit no -Xmx and fall back to 25% of RAM (4GB on the 16GB CI
    // runners), too little for the heavy classpath-loading suites (kyo-tasty loads 80k-symbol
    // classpaths under globalK-way leaf concurrency). Pin an explicit fork heap on CI; with the
    // ForkedTestGroup cap at 2, two 5GB forks plus the floor-less driver fit the 16GB box. Local dev
    // keeps the auto-scaling default so small machines are not over-committed.
    Test / javaOptions ++= (if (sys.env.contains("CI")) Seq("-Xmx5g") else Nil),
    doctestPredef := Seq("import kyo.*"),
    // Non-LTS modules pick up kyo-doctest through Test/unmanagedJars so Test/fullClasspath
    // dedups naturally. LTS fallback modules (3.3.7) must NOT have kyo-doctest on the Test
    // compile classpath, because its scala3-library 3.8.3 clashes with the project's 3.3.7
    // ("package scala contains object and package with same name: caps"). For those the
    // plugin's doctestExtraClasspath path supplies kyo-doctest at fork time only, and
    // reconcileClasspath strips the mismatched scala3-library before the fork starts.
    Test / unmanagedJars ++= {
        if (scalaVersion.value == scala3Version)
            (LocalProject("kyo-doctestJVM") / Compile / fullClasspath).value
        else
            Seq.empty[Attributed[File]]
    },
    doctestExtraClasspath := {
        if (scalaVersion.value == scala3Version)
            Seq.empty[File]
        else
            (LocalProject("kyo-doctestJVM") / Compile / fullClasspath).value.files
    }
)

Global / excludeLintKeys += doctestPredef
Global / excludeLintKeys += doctestExtraClasspath
// coverageExcludedFiles is read only under `sbt coverage ...`; a plain build would lint it as unused.
Global / excludeLintKeys += coverageExcludedFiles

Global / onLoad := {

    val javaVersion  = System.getProperty("java.version")
    val majorVersion = javaVersion.split("\\.")(0).toInt
    // The build compiles at -release 25, which requires a JDK >= 25 (the foreign modules call
    // java.lang.foreign, final in JDK 22). So the whole build needs JDK 25.
    if (majorVersion < 25) {
        throw new IllegalStateException(
            s"Java version $javaVersion is not supported. Please use Java 25 (LTS) or higher."
        )
    }

    // Guards publishability of the sbt plugins, which ship only by virtue of being aggregated here.
    // A scripted suite cannot cover this: scriptedDependencies publishLocals the plugin project
    // directly and passes whether or not any aggregate contains it.
    locally {
        // The expected type picks ProjectDefinition.aggregate over Project.aggregate(refs*).
        val refs: Seq[ProjectReference] = kyoJVM.aggregate
        val aggregated                  = refs.collect { case LocalProject(id) => id }.toSet
        val missing                     = Set("kyo-test-sbt", "kyo-test-sbt-publish").diff(aggregated)
        if (missing.nonEmpty) {
            throw new IllegalStateException(
                s"kyoJVM must aggregate ${missing.toList.sorted.mkString(", ")}; " +
                    "projects outside the aggregate are never published by ci-release."
            )
        }
    }

    val project =
        System.getProperty("platform", "JVM").toUpperCase match {
            case "JVM"    => kyoJVM
            case "JS"     => kyoJS
            case "NATIVE" => kyoNative
            case "WASM"   => kyoWasm
            case platform => throw new IllegalArgumentException("Invalid platform: " + platform)
        }

    (Global / onLoad).value andThen { state =>
        "project " + project.id :: state
    }
}

lazy val kyoJVM: Project = project
    .in(file("."))
    .enablePlugins(ScalaUnidocPlugin)
    .settings(
        name := "kyoJVM",
        `kyo-settings`,
        // Document everything kyoJVM aggregates, minus the projects that have
        // no public API surface or cannot produce Scala 3 scaladoc:
        //   - kyo-bench:    benchmarks, not public API
        //   - kyo-examples: examples, not public API
        //   - kyo-compat:   meta-aggregator; individual kyo-compat-*
        //                   bindings are included via the aggregate set
        ScalaUnidoc / unidoc / unidocProjectFilter :=
            inAggregates(kyoJVM) -- inProjects(
                `kyo-bench`.jvm,
                `kyo-examples`.jvm,
                `kyo-compat-plugin`,
                `kyo-test-api`.jvm,
                `kyo-test-runner`.jvm,
                `kyo-test-prop`.jvm,
                `kyo-test-snapshot`.jvm
            ),
        ScalaUnidoc / unidoc / scalacOptions ++= Seq(
            "-project",
            "Kyo",
            "-project-version",
            version.value,
            "-source-links:github://getkyo/kyo/" + git.gitHeadCommit.value.getOrElse("main"),
            // Hide any package named `internal` or nested under one, anywhere in the
            // namespace tree (kyo.internal, kyo.stats.internal, kyo.compat.internal,
            // kyo.kernel.internal, ...). Public-API only.
            "-skip-by-regex:.*\\.internal(\\..*)?",
            // CalibanHttpUtils is declared `package caliban` only to reach
            // caliban's package-private types: internal plumbing, not public
            // API. Strip the resulting top-level `caliban` namespace.
            "-skip-by-id:caliban"
        )
        // Known limitations of Scala 3 scaladoc (no upstream issue filed yet):
        //
        //   1. opaque-type class-level docstrings are dropped from the rendered
        //      site whenever a companion object exists. Opaque types do not
        //      get a dedicated `.html` page (the companion's `Foo$.html` is
        //      the only landing page), and the companion-page emitter never
        //      folds in the type's docstring. Docs are kept on the type for
        //      source readability; users see them in IDE hover and ScalaDex
        //      source view, just not on the unidoc site.
        //
        //   2. `-skip-by-id` and `-skip-by-regex` only match packages and
        //      top-level classes, so `Var.internal`, `Local.internal`, and
        //      `Batch.internal` (nested objects holding effect Op types) still
        //      appear in the unidoc index. Relocating them to actual
        //      `kyo.internal.*` top-level objects would hide them.
        //
        //   3. The unidoc sidebar has no per-artifact / per-module grouping.
        //      The flat alphabetical index is the only layout scaladoc emits.
    )
    .disablePlugins(MimaPlugin, KyoDoctestPlugin)
    .aggregate(
        `kyo-scheduler`.jvm,
        `kyo-scheduler-zio`.jvm,
        `kyo-scheduler-finagle`.jvm,
        `kyo-scheduler-pekko`.jvm,
        `kyo-data`.jvm,
        `kyo-kernel`.jvm,
        `kyo-prelude`.jvm,
        `kyo-parse`.jvm,
        `kyo-core`.jvm,
        `kyo-system`.jvm,
        `kyo-offheap`.jvm,
        `kyo-ffi`.jvm,
        `kyo-ffi-codegen`,
        `kyo-ffi-plugin`,
        `kyo-ffi-bench`,
        `kyo-ffi-it`.jvm,
        `kyo-net`.jvm,
        `kyo-direct`.jvm,
        `kyo-stm`.jvm,
        `kyo-stats-registry`.jvm,
        `kyo-config`.jvm,
        `kyo-stats-otlp`.jvm,
        `kyo-stats-machine`.jvm,
        `kyo-logging-jpl`.jvm,
        `kyo-logging-slf4j`.jvm,
        `kyo-reactive-streams`.jvm,
        `kyo-aeron`.jvm,
        `kyo-compiler`.jvm,
        `kyo-schema`.jvm,
        `kyo-schema-json`.jvm,
        `kyo-schema-protobuf`.jvm,
        `kyo-schema-msgpack`.jvm,
        `kyo-schema-bson`.jvm,
        `kyo-schema-ion`.jvm,
        `kyo-schema-yaml`.jvm,
        `kyo-schema-tests`.jvm,
        `kyo-sql`.jvm,
        `kyo-sql-postgres`.jvm,
        `kyo-sql-mysql`.jvm,
        `kyo-sql-tests`.jvm,
        `kyo-http`.jvm,
        `kyo-flow`.jvm,
        `kyo-ai`.jvm,
        `kyo-jsonrpc`.jvm,
        `kyo-jsonrpc-http`.jvm,
        `kyo-mcp`.jvm,
        `kyo-lsp`.jvm,
        `kyo-caliban`.jvm,
        `kyo-bench`.jvm,
        `kyo-zio-test`.jvm,
        `kyo-zio`.jvm,
        `kyo-combinators`.jvm,
        `kyo-browser`.jvm,
        `kyo-slack`.jvm,
        `kyo-ui`.jvm,
        `kyo-markdown`.jvm,
        `kyo-i18n`.jvm,
        `kyo-case-app`.jvm,
        `kyo-pod`.jvm,
        `kyo-examples`.jvm,
        `kyo-actor`.jvm,
        `kyo-tasty`.jvm,
        `kyo-tasty-fixtures-internal`.jvm,
        `kyo-compat-future`.jvm,
        `kyo-compat-kyo`.jvm,
        `kyo-compat-zio`.jvm,
        `kyo-compat-ox`.jvm,
        `kyo-compat-twitter-future`.jvm,
        `kyo-compat-plugin`,
        `kyo-doctest`.jvm,
        `kyo-doctest-plugin`,
        // ci-release publishes from this root; an unaggregated project builds and tests but never ships.
        `kyo-test-sbt`,
        `kyo-test-sbt-publish`,
        `kyo-test-api`.jvm,
        `kyo-test-runner`.jvm,
        `kyo-test-prop`.jvm,
        `kyo-test-snapshot`.jvm,
        `root-readme`,
        `kyo-website`.jvm
    )

lazy val kyoJS = project
    .in(file("js"))
    .settings(
        name := "kyoJS",
        `kyo-settings`,
        publish / skip := true
    )
    .disablePlugins(MimaPlugin, KyoDoctestPlugin)
    .aggregate(
        `kyo-scheduler`.js,
        `kyo-data`.js,
        `kyo-kernel`.js,
        `kyo-prelude`.js,
        `kyo-parse`.js,
        `kyo-core`.js,
        `kyo-system`.js,
        `kyo-ffi`.js,
        `kyo-ffi-it`.js,
        `kyo-net`.js,
        `kyo-direct`.js,
        `kyo-stm`.js,
        `kyo-stats-registry`.js,
        `kyo-config`.js,
        `kyo-reactive-streams`.js,
        `kyo-stats-otlp`.js,
        `kyo-stats-machine`.js,
        `kyo-zio-test`.js,
        `kyo-zio`.js,
        `kyo-combinators`.js,
        `kyo-case-app`.js,
        `kyo-actor`.js,
        `kyo-tasty`.js,
        `kyo-tasty-fixtures-internal`.js,
        `kyo-schema`.js,
        `kyo-schema-json`.js,
        `kyo-schema-protobuf`.js,
        `kyo-schema-msgpack`.js,
        `kyo-schema-bson`.js,
        `kyo-schema-ion`.js,
        `kyo-schema-yaml`.js,
        `kyo-schema-tests`.js,
        `kyo-sql`.js,
        `kyo-sql-postgres`.js,
        `kyo-sql-mysql`.js,
        `kyo-sql-tests`.js,
        `kyo-http`.js,
        `kyo-aeron`.js,
        `kyo-flow`.js,
        `kyo-ai`.js,
        `kyo-jsonrpc`.js,
        `kyo-jsonrpc-http`.js,
        `kyo-mcp`.js,
        `kyo-lsp`.js,
        `kyo-browser`.js,
        `kyo-slack`.js,
        `kyo-ui`.js,
        `kyo-markdown`.js,
        `kyo-i18n`.js,
        `kyo-website`.js,
        `kyo-website-bundle`.js,
        `kyo-pod`.js,
        `kyo-compat-future`.js,
        `kyo-compat-kyo`.js,
        `kyo-compat-zio`.js,
        `kyo-test-api`.js,
        `kyo-test-runner`.js,
        `kyo-test-prop`.js,
        `kyo-test-snapshot`.js
    )

lazy val kyoNative = project
    .in(file("native"))
    .settings(
        name := "kyoNative",
        `native-settings-base`,
        publish / skip := true
    )
    .disablePlugins(MimaPlugin, KyoDoctestPlugin)
    .aggregate(
        `kyo-data`.native,
        `kyo-prelude`.native,
        `kyo-parse`.native,
        `kyo-kernel`.native,
        `kyo-stats-registry`.native,
        `kyo-config`.native,
        `kyo-scheduler`.native,
        `kyo-core`.native,
        `kyo-system`.native,
        `kyo-offheap`.native,
        `kyo-ffi`.native,
        `kyo-ffi-it`.native,
        `kyo-net`.native,
        `kyo-direct`.native,
        `kyo-combinators`.native,
        `kyo-case-app`.native,
        `kyo-reactive-streams`.native,
        `kyo-actor`.native,
        `kyo-tasty`.native,
        `kyo-tasty-fixtures-internal`.native,
        `kyo-schema`.native,
        `kyo-schema-json`.native,
        `kyo-schema-protobuf`.native,
        `kyo-schema-msgpack`.native,
        `kyo-schema-bson`.native,
        `kyo-schema-ion`.native,
        `kyo-schema-yaml`.native,
        `kyo-schema-tests`.native,
        `kyo-sql`.native,
        `kyo-sql-postgres`.native,
        `kyo-sql-mysql`.native,
        `kyo-sql-tests`.native,
        `kyo-http`.native,
        `kyo-aeron`.native,
        `kyo-flow`.native,
        `kyo-ai`.native,
        `kyo-jsonrpc`.native,
        `kyo-jsonrpc-http`.native,
        `kyo-mcp`.native,
        `kyo-lsp`.native,
        `kyo-scheduler-zio`.native,
        `kyo-zio`.native,
        `kyo-zio-test`.native,
        `kyo-stm`.native,
        `kyo-stats-otlp`.native,
        `kyo-stats-machine`.native,
        `kyo-browser`.native,
        `kyo-slack`.native,
        `kyo-ui`.native,
        `kyo-markdown`.native,
        `kyo-i18n`.native,
        `kyo-pod`.native,
        `kyo-compat-future`.native,
        `kyo-compat-kyo`.native,
        `kyo-compat-zio`.native,
        `kyo-test-api`.native,
        `kyo-test-runner`.native,
        `kyo-test-prop`.native,
        `kyo-test-snapshot`.native
    )

// WebAssembly aggregator (mirrors kyoJS).
lazy val kyoWasm = project
    .in(file("wasm"))
    .settings(
        name := "kyoWasm",
        `kyo-settings`,
        publish / skip := true
    )
    .disablePlugins(MimaPlugin, KyoDoctestPlugin)
    .aggregate(
        `kyo-config`.wasm,
        `kyo-stats-registry`.wasm,
        `kyo-data`.wasm,
        `kyo-kernel`.wasm,
        `kyo-prelude`.wasm,
        `kyo-parse`.wasm,
        `kyo-schema`.wasm,
        `kyo-schema-json`.wasm,
        `kyo-schema-protobuf`.wasm,
        `kyo-schema-msgpack`.wasm,
        `kyo-schema-bson`.wasm,
        `kyo-schema-ion`.wasm,
        `kyo-schema-yaml`.wasm,
        `kyo-schema-tests`.wasm,
        `kyo-sql`.wasm,
        `kyo-sql-postgres`.wasm,
        `kyo-sql-mysql`.wasm,
        `kyo-sql-tests`.wasm,
        `kyo-scheduler`.wasm,
        `kyo-core`.wasm,
        `kyo-system`.wasm,
        `kyo-ffi`.wasm,
        `kyo-direct`.wasm,
        `kyo-stm`.wasm,
        `kyo-combinators`.wasm,
        `kyo-actor`.wasm,
        `kyo-reactive-streams`.wasm,
        `kyo-zio`.wasm,
        `kyo-zio-test`.wasm,
        `kyo-case-app`.wasm,
        `kyo-compat-future`.wasm,
        `kyo-compat-kyo`.wasm,
        `kyo-compat-zio`.wasm,
        `kyo-http`.wasm,
        `kyo-net`.wasm,
        `kyo-stats-otlp`.wasm,
        `kyo-stats-machine`.wasm,
        `kyo-aeron`.wasm,
        `kyo-flow`.wasm,
        `kyo-ai`.wasm,
        `kyo-jsonrpc`.wasm,
        `kyo-jsonrpc-http`.wasm,
        `kyo-mcp`.wasm,
        `kyo-lsp`.wasm,
        `kyo-pod`.wasm,
        `kyo-browser`.wasm,
        `kyo-slack`.wasm,
        `kyo-ui`.wasm,
        `kyo-markdown`.wasm,
        `kyo-i18n`.wasm,
        `kyo-test-api`.wasm,
        `kyo-test-runner`.wasm,
        `kyo-test-prop`.wasm,
        `kyo-test-snapshot`.wasm,
        `kyo-tasty`.wasm,
        `kyo-tasty-fixtures-internal`.wasm
    )

lazy val `kyo-scheduler` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-stats-registry`)
        .in(file("kyo-scheduler"))
        .settings(
            `kyo-settings`,
            release17,
            libraryDependencies += "org.scalatest" %%% "scalatest" % scalaTestVersion % Test,
            scalacOptions ++= scalacOptionToken(ScalacOptions.source3).value,
            crossScalaVersions := List(scala3LTSVersion, scala213Version)
        )
        .jvmSettings(mimaCheck(false))
        .nativeSettings(
            `native-settings`,
            crossScalaVersions                         := List(scala3LTSVersion),
            libraryDependencies += "org.scala-native" %%% "scala-native-java-logging" % "1.0.0"
        )
        .jsSettings(
            `js-settings`,
            libraryDependencies += "org.scala-js" %%% "scala-js-macrotask-executor" % "1.1.1"
        )
        .wasmSettings(
            `wasm-settings`,
            // WASM uses the same single-threaded, event-loop scheduler as JS, which drives
            // execution through the macrotask executor.
            libraryDependencies += "org.scala-js" %%% "scala-js-macrotask-executor" % "1.1.1"
        )

lazy val `kyo-scheduler-zio` = sbtcrossproject.CrossProject("kyo-scheduler-zio", file("kyo-scheduler-zio"))(JVMPlatform, NativePlatform)
    .crossType(CrossType.Full)
    .dependsOn(`kyo-scheduler`)
    .settings(
        `kyo-settings`,
        release17,
        scalacOptions ++= scalacOptionToken(ScalacOptions.source3).value,
        crossScalaVersions                      := List(scala3LTSVersion, scala213Version),
        libraryDependencies += "dev.zio"       %%% "zio"       % zioVersion,
        libraryDependencies += "org.scalatest" %%% "scalatest" % scalaTestVersion % Test
    )
    .jvmSettings(mimaCheck(false))
    .nativeSettings(
        `native-settings`,
        crossScalaVersions := List(scala3LTSVersion)
    )

lazy val `kyo-scheduler-pekko` =
    crossProject(JVMPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-scheduler`)
        .in(file("kyo-scheduler-pekko"))
        .settings(
            `kyo-settings`,
            release17,
            libraryDependencies += "org.apache.pekko" %%% "pekko-actor"   % "1.6.0",
            libraryDependencies += "org.apache.pekko" %%% "pekko-testkit" % "1.6.0"          % Test,
            libraryDependencies += "org.scalatest"    %%% "scalatest"     % scalaTestVersion % Test
        )
        .jvmSettings(mimaCheck(false))
        .settings(
            scalacOptions ++= scalacOptionToken(ScalacOptions.source3).value,
            crossScalaVersions := List(scala3LTSVersion, scala213Version)
        )

lazy val `kyo-scheduler-finagle` =
    crossProject(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-scheduler-finagle"))
        .settings(
            `kyo-settings`,
            release17,
            libraryDependencies += "org.scalatest" %%% "scalatest" % scalaTestVersion % Test,
            libraryDependencies ++= {
                if (scalaVersion.value == scala213Version)
                    Seq("com.twitter" %% "finagle-core" % "24.2.0")
                else
                    Seq.empty
            },
            scalacOptions ++= scalacOptionToken(ScalacOptions.source3).value,
            crossScalaVersions := Seq(scala213Version, scala3LTSVersion),
            publish / skip     := scalaVersion.value != scala213Version,
            Compile / unmanagedSourceDirectories := {
                if (scalaVersion.value == scala213Version)
                    (Compile / unmanagedSourceDirectories).value
                else
                    Seq.empty
            },
            Test / unmanagedSourceDirectories := {
                if (scalaVersion.value == scala213Version)
                    (Test / unmanagedSourceDirectories).value
                else
                    Seq.empty
            }
        )
        .jvmSettings(mimaCheck(false))
        .jvmConfigure(_.disablePlugins(KyoDoctestPlugin))
        .dependsOn(`kyo-scheduler`)

lazy val `kyo-data` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-stats-registry`)
        .in(file("kyo-data"))
        .withKyoTest
        .settings(
            `kyo-settings`,
            libraryDependencies += "com.lihaoyi" %%% "pprint" % "0.9.6"
        )
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`)
        .wasmSettings(`wasm-settings`)

lazy val `kyo-kernel` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-data`)
        .withKyoTest
        .in(file("kyo-kernel"))
        .settings(
            `kyo-settings`,
            libraryDependencies += "org.javassist" % "javassist" % "3.32.0-GA" % Test,
            Test / sourceGenerators += TestVariant.generate.taskValue
        )
        .jvmSettings(mimaCheck(false))
        .jvmConfigure(_.settings(
            doctestFreshDriver := true
        ))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`)
        .wasmSettings(`wasm-settings`)

lazy val `kyo-prelude` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-kernel`)
        .withKyoTest
        .in(file("kyo-prelude"))
        .settings(
            `kyo-settings`,
            libraryDependencies += "dev.zio" %%% "zio-laws-laws" % "1.0.0-RC47" % Test,
            libraryDependencies += "dev.zio" %%% "zio-test-sbt"  % zioVersion   % Test
        )
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`)
        .wasmSettings(`wasm-settings`)

lazy val `kyo-parse` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-prelude`)
        .withKyoTest
        .in(file("kyo-parse"))
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`)
        .wasmSettings(`wasm-settings`)

lazy val `kyo-schema` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-data` % "test->test;compile->compile")
        .dependsOn(`kyo-core` % "test->compile")
        .in(file("kyo-schema"))
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        // kyo-schema/README.md documents the whole module family (core + every format), so its
        // blocks need classpaths the core does not have; kyo-schema-tests validates it instead.
        .jvmConfigure(_.settings(doctestSources := Seq.empty))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`, Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)))
        .wasmSettings(`wasm-settings`)

lazy val `kyo-schema-json` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-schema` % "test->test;compile->compile")
        .dependsOn(`kyo-system`)
        .in(file("kyo-schema-json"))
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(
            mimaCheck(false),
            Test / javaOptions ~= (_.filterNot(_ == "--add-opens=java.base/java.lang=ALL-UNNAMED"))
        )
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`, Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)))
        .wasmSettings(`wasm-settings`)

// Unpublished home for suites that exercise multiple serialization formats at once
// (sbt cannot express mutual test-scope dependencies between sibling format modules).
// Also validates kyo-schema/README.md doctest blocks, which span every format.
lazy val `kyo-schema-tests` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-schema` % "test->test;compile->compile")
        .dependsOn(`kyo-schema-json`)
        .dependsOn(`kyo-schema-protobuf`)
        .dependsOn(`kyo-schema-msgpack`)
        .dependsOn(`kyo-schema-bson`)
        .dependsOn(`kyo-schema-ion`)
        .dependsOn(`kyo-schema-yaml`)
        .dependsOn(`kyo-core` % "test->compile")
        .in(file("kyo-schema-tests"))
        .withKyoTest
        .settings(`kyo-settings`, publish / skip := true)
        .jvmSettings(mimaCheck(false))
        // The shared kyo-schema README exercises every format; only this project's Test
        // classpath sees the core plus all six format modules, so it hosts the validation.
        .jvmConfigure(_.settings(
            doctestSources := Seq((ThisBuild / baseDirectory).value / "kyo-schema" / "README.md")
        ))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`, Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)))
        .wasmSettings(`wasm-settings`)

lazy val `kyo-schema-protobuf` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-schema` % "test->test;compile->compile")
        .dependsOn(`kyo-core` % "test->compile")
        .in(file("kyo-schema-protobuf"))
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`, Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)))
        .wasmSettings(`wasm-settings`)

lazy val `kyo-schema-msgpack` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-schema` % "test->test;compile->compile")
        .dependsOn(`kyo-core` % "test->compile")
        .in(file("kyo-schema-msgpack"))
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`, Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)))
        .wasmSettings(`wasm-settings`)

lazy val `kyo-schema-bson` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-schema` % "test->test;compile->compile")
        .dependsOn(`kyo-core` % "test->compile")
        .in(file("kyo-schema-bson"))
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`, Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)))
        .wasmSettings(`wasm-settings`)

lazy val `kyo-schema-ion` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-schema` % "test->test;compile->compile")
        .dependsOn(`kyo-core` % "test->compile")
        .dependsOn(`kyo-system` % "test->compile")
        .in(file("kyo-schema-ion"))
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`, Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)))
        .wasmSettings(`wasm-settings`)

lazy val `kyo-schema-yaml` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-schema` % "test->test;compile->compile")
        .dependsOn(`kyo-core` % "test->compile")
        .in(file("kyo-schema-yaml"))
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`, Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)))
        .wasmSettings(`wasm-settings`)

lazy val `kyo-sql` =
    // Backend-agnostic core: the Postgres and MySQL wire drivers below depend on this module and are never
    // depended on by it, so nothing here may name a type either declares. Compiling this module with no backend
    // on the classpath is the gate for that (a same-package name is invisible to a grep).
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-core`)
        // kyo-schema-json (not kyo-schema): SqlSchema is a self-contained codec typeclass; kyo-schema enters
        // only at the JSON tier, for Sql.jsonColumn's Schema-based overload.
        .dependsOn(`kyo-schema-json`)
        .dependsOn(`kyo-net`)
        .dependsOn(`kyo-pod` % "test->compile")
        .dependsOn(`kyo-system` % "test->compile")
        .in(file("kyo-sql"))
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        // CommonJS: the test path reaches Node builtins (kyo.Path -> node:fs, kyo-pod -> node:child_process,
        // kyo-core entropy -> node:crypto); without it fastLinkJS fails to link them.
        .jsSettings(
            `js-settings`,
            scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
        )
        // openssl-native-settings: kyo-net's Native C shims reference TLS symbols (TLS_client_method,
        // X509_free, ...), so a Native binary that reaches them must link libssl/libcrypto or nativeLink fails.
        .nativeSettings(`native-settings`, `openssl-native-settings`)
        .wasmSettings(`wasm-settings`)

// The two backend modules below are deliberately symmetric (same platforms, edges, settings); a difference is
// a bug unless it names a wire feature only one engine has. Both take `test->test` on `kyo-sql` (not just
// `compile->compile`) to reuse its test fixtures (`Test`, `FakeServer`, `OwnContainer`, `StubConnection`, the
// mocks) rather than duplicate them. Other edges (`kyo-core`, `kyo-net`, `kyo-schema-json`) arrive transitively;
// `kyo-pod` is redeclared because `kyo-sql` takes it test-scope only and that does not propagate.
lazy val `kyo-sql-postgres` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-sql` % "test->test;compile->compile")
        .dependsOn(`kyo-pod` % "test->compile")
        .dependsOn(`kyo-system` % "test->compile")
        .in(file("kyo-sql-postgres"))
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        // CommonJS, same as kyo-sql: the test path reaches Node builtins (kyo-pod's harness, kyo-core entropy).
        .jsSettings(
            `js-settings`,
            scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
        )
        // openssl-native-settings: this module's auth/TLS sources reach kyo-net's Native C shims, so a test
        // binary that touches them must link libssl/libcrypto or nativeLink fails.
        .nativeSettings(`native-settings`, `openssl-native-settings`)
        .wasmSettings(`wasm-settings`)

lazy val `kyo-sql-mysql` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-sql` % "test->test;compile->compile")
        .dependsOn(`kyo-pod` % "test->compile")
        .dependsOn(`kyo-system` % "test->compile")
        .in(file("kyo-sql-mysql"))
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        .jsSettings(
            `js-settings`,
            scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
        )
        .nativeSettings(`native-settings`, `openssl-native-settings`)
        .wasmSettings(`wasm-settings`)

// Unpublished; it holds the suites whose SUBJECT spans both engines and so have no single-module home: the
// cross-backend suites that name both clients/factories to prove they behave the same through one abstract
// surface, and the container-driven suites sharing `internal/SqlSharedContainers`. That fixture connects to
// both engines directly, so it can live neither in core (which must compile with no backend) nor in one engine
// module (the other's suites could not see it). `test->test` on all three lets the suites reuse core's `Test`
// base and mocks plus each engine's fixtures; `publish / skip` keeps the shipped artifact count at three.
lazy val `kyo-sql-tests` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-sql` % "test->test;compile->compile")
        .dependsOn(`kyo-sql-postgres` % "test->test;compile->compile")
        .dependsOn(`kyo-sql-mysql` % "test->test;compile->compile")
        .dependsOn(`kyo-pod` % "test->compile")
        .in(file("kyo-sql-tests"))
        .withKyoTest
        .settings(`kyo-settings`, publish / skip := true)
        .jvmSettings(mimaCheck(false))
        // No README, so nothing to validate. Left unset the plugin would look for one that does not exist.
        .jvmConfigure(_.settings(doctestSources := Seq.empty))
        .jsSettings(
            `js-settings`,
            scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
        )
        .nativeSettings(
            `native-settings`,
            `openssl-native-settings`,
            // Scala Native resolves ServiceLoader at LINK time, so a META-INF/services provider works only when
            // also enlisted here; without this, runtime backend discovery finds nothing on Native.
            Test / nativeConfig ~= (_.withServiceProviders(Map("kyo.db.Backend" -> Seq(
                "kyo.internal.postgres.PostgresBackendFactory",
                "kyo.internal.mysql.MysqlBackendFactory"
            ))))
        )
        .wasmSettings(`wasm-settings`)

lazy val `kyo-core` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-scheduler`)
        .dependsOn(`kyo-prelude`)
        .in(file("kyo-core"))
        .withKyoTest
        .settings(
            `kyo-settings`
        )
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(
            `js-settings`,
            libraryDependencies += ("org.scala-js" %%% "scalajs-java-logging" % "1.0.0").cross(CrossVersion.for3Use2_13),
            scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
        )
        .wasmSettings(
            `wasm-settings`,
            // Same java.util.logging shim as JS.
            libraryDependencies += ("org.scala-js" %%% "scalajs-java-logging" % "1.0.0").cross(CrossVersion.for3Use2_13)
        )

lazy val `kyo-system` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-system"))
        .dependsOn(`kyo-core`)
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`, scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) })
        .wasmSettings(`wasm-settings`)

lazy val `kyo-offheap` =
    crossProject(JVMPlatform, NativePlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-offheap"))
        .dependsOn(`kyo-core`)
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        .jvmConfigure(_.settings(
            doctestScalacOptions := Seq("-release", "25")
        ))
        .nativeSettings(
            `native-settings`,
            Compile / doc / sources := Seq.empty
        )

lazy val `kyo-ffi` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-ffi"))
        .dependsOn(`kyo-core`)
        .dependsOn(`kyo-system` % Test)
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(
            mimaCheck(false),
            doctestScalacOptions := Seq("-release", "25"),
            Test / javaOptions += "--enable-native-access=ALL-UNNAMED",
            // Hint to module-path consumers that this JAR uses java.lang.foreign.
            Compile / packageBin / packageOptions +=
                Package.ManifestAttributes("Enable-Native-Access" -> "ALL-UNNAMED")
        )
        .nativeSettings(
            `native-settings`,
            Compile / doc / sources := Seq.empty,
            // Generate the Native retained-callback shape catalog from `project/CallbackShapesGen.scala`.
            Compile / sourceGenerators += Def.task {
                CallbackShapesGen.generate((Compile / sourceManaged).value)
            }.taskValue
        )
        .jsSettings(
            `js-settings`,
            // The node:fs mmap facade is an @JSImport module (koffi itself is resolved dynamically, not via
            // @JSImport), so the JS backend needs a module kind (the default NoModule cannot link an @JSImport).
            // Use ESModule to match the wasm backend: under a
            // CommonJS module Node keeps `require` module-scoped, which the browser-gate reads (and its
            // BrowserDetectionTest simulation) cannot observe, whereas ESModule has no `require` and the gate
            // behaves identically to the wasm axis.
            scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) }
        )
        .wasmSettings(`wasm-settings`)

// Declared at top level so the key resolves in the crossProject's native sub-project scope.
lazy val buildKyoItBundled =
    taskKey[File]("Compile kyo-ffi-it bundled C sources into libkyo_it_bundled.{so,dylib,dll} and return its directory.")

lazy val `kyo-ffi-it` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-ffi/it"))
        .enablePlugins(KyoFfiPlugin)
        .dependsOn(`kyo-ffi`)
        .dependsOn(`kyo-system` % Test)
        .withKyoTest
        .settings(
            `kyo-settings`,
            publish / skip := true,
            // In-repo bootstrap: hand the plugin the codegen project's own classpath so a cold
            // `kyo-ffi-it/test` builds the codegen first and generates the impls directly, with no
            // bundled plugin resource and no reload. External consumers leave this at its default
            // (Nil) and the plugin uses its bundled codegen resource instead.
            ffiCodegenClasspath := (LocalProject("kyo-ffi-codegen") / Compile / fullClasspath).value.map(_.data),
            // Bundled C sources live under the shared cross-project directory; system bindings
            // (LibC/LibM/Posix) bypass the plugin and resolve to OS libraries directly.
            ffiLibraries := Seq(
                FfiLibrary(
                    id = "kyo_it_bundled",
                    cSources = (baseDirectory.value / ".." / "shared" / "src" / "main" / "c" ** "*.c").get
                )
            )
        )
        .jvmSettings(
            mimaCheck(false),
            Test / javaOptions += "--enable-native-access=ALL-UNNAMED"
        )
        .nativeSettings(
            `native-settings`,
            // The plugin's ffiCompile is a no-op on Native: the Scala Native linker handles C.
            // Build libkyo_it_bundled here and surface its directory via nativeConfig so
            // `@link("kyo_it_bundled")` bindings resolve at link time.
            buildKyoItBundled := {
                val log    = streams.value.log
                val cDir   = baseDirectory.value / ".." / "shared" / "src" / "main" / "c"
                val cSrcs  = (cDir ** "*.c").get
                val outDir = target.value / "nativelib"
                IO.createDirectory(outDir)
                val osName = sys.props.getOrElse("os.name", "").toLowerCase
                val (ext, flag) =
                    if (osName.contains("mac")) ("dylib", "-dynamiclib")
                    else if (osName.contains("win")) ("dll", "-shared")
                    else ("so", "-shared")
                val outLib = outDir / s"libkyo_it_bundled.$ext"
                val newest = cSrcs.map(_.lastModified()).foldLeft(0L)(math.max)
                if (!outLib.exists() || outLib.lastModified() < newest) {
                    val cc  = sys.env.getOrElse("CC", "cc")
                    val cmd = Seq(cc, flag, "-fPIC", "-O2", "-o", outLib.getAbsolutePath) ++ cSrcs.map(_.getAbsolutePath)
                    log.info(s"[kyo-ffi-it Native] ${cmd.mkString(" ")}")
                    val rc = scala.sys.process.Process(cmd).!
                    if (rc != 0) sys.error(s"cc failed with exit code $rc building libkyo_it_bundled")
                }
                outDir
            },
            nativeConfig := {
                val base   = nativeConfig.value
                val libDir = buildKyoItBundled.value.getAbsolutePath
                base.withLinkingOptions(base.linkingOptions ++ Seq(s"-L$libDir", s"-Wl,-rpath,$libDir"))
            }
        )
        .jsSettings(
            `js-settings`,
            // koffi is loaded via CommonJS `require` at runtime, so align the linker.
            scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) },
            // Point the JS runtime at the plugin-compiled library via KYO_FFI_<LIBID>_PATH. The os/arch/ext
            // tags mirror the plugin's own CCompiler output name so the path matches the file ffiCompile
            // wrote, including the linux-musl split and the empty (no `lib`) prefix on Windows.
            Test / jsEnv := {
                val ffiOut = target.value / "ffi"
                val osName = sys.props.getOrElse("os.name", "").toLowerCase
                val osTag =
                    if (osName.contains("mac")) "darwin"
                    else if (osName.contains("win")) "windows"
                    else if (osName.contains("linux"))
                        if (
                            new java.io.File("/lib/ld-musl-x86_64.so.1").exists()
                            || new java.io.File("/lib/ld-musl-aarch64.so.1").exists()
                        ) "linux-musl"
                        else "linux"
                    else osName
                val ext    = if (osTag == "darwin") "dylib" else if (osTag == "windows") "dll" else "so"
                val prefix = if (osTag == "windows") "" else "lib"
                val arch = sys.props.getOrElse("os.arch", "") match {
                    case "x86_64" | "amd64"  => "x86_64"
                    case "aarch64" | "arm64" => "aarch64"
                    case other               => other
                }
                val bundled = ffiOut / s"${prefix}kyo_it_bundled-$osTag-$arch.$ext"
                new NodeJSEnv(
                    NodeJSEnv.Config()
                        .withArgs(List("--max_old_space_size=5120"))
                        .withEnv(Map("KYO_FFI_KYO_IT_BUNDLED_PATH" -> bundled.getAbsolutePath))
                )
            },
            // koffi bootstrap (idempotent npm install, hooked on Test / compile) via the kyo-ffi plugin.
            ffiKoffiJsBootstrap("kyo-ffi-it-js-test")
        )

lazy val `kyo-ffi-codegen` =
    project
        .in(file("kyo-ffi/codegen"))
        .dependsOn(`kyo-ffi`.jvm % Test)
        .settings(
            `kyo-settings`,
            libraryDependencies += "org.scala-lang" %% "scala3-tasty-inspector" % scalaVersion.value,
            libraryDependencies += "org.scala-lang" %% "scala3-compiler"        % scalaVersion.value % Test,
            // kyo-test framework wiring (the JVM-only equivalent of .withKyoTest, which only applies to crossProjects).
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-test-runnerJVM") / Test / fullClasspath).value,
            Test / testFrameworks +=
                new TestFramework("kyo.test.runner.SbtFramework"),
            Test / javaOptions += s"-Dkyo.ffi.codegen.test.classes=${(Test / classDirectory).value.getAbsolutePath}",
            Test / javaOptions += s"-Dkyo.ffi.codegen.test.classpath=${(Test / fullClasspath).value.map(_.data.getAbsolutePath).mkString(java.io.File.pathSeparator)}"
        )

lazy val `kyo-ffi-plugin` =
    project
        .in(file("kyo-ffi/plugin"))
        .enablePlugins(SbtPlugin)
        // Scala 2.12 sbt plugin: kyo-doctest's Scala 3 CLI cannot run on this module
        // (same as kyo-compat-plugin and kyo-doctest-plugin).
        .disablePlugins(KyoDoctestPlugin)
        .settings(
            scalaVersion       := "2.12.20",
            crossScalaVersions := Seq("2.12.20"),
            name               := "kyo-ffi-plugin",
            sbtPlugin          := true,
            // Bake this plugin's version into a resource so it can resolve the matching
            // kyo-ffi-codegen (and its Scala 3 toolchain) from the user's resolvers at task time,
            // instead of bundling the ~33 MB toolchain into the published plugin JAR.
            Compile / resourceGenerators += Def.task {
                val outDir = (Compile / resourceManaged).value / "kyo-ffi-plugin"
                IO.createDirectory(outDir)
                val versionFile = outDir / "version.txt"
                IO.write(versionFile, version.value)
                Seq(versionFile)
            }.taskValue,
            scriptedLaunchOpts := {
                scriptedLaunchOpts.value ++
                    Seq(
                        "-Xmx1024M",
                        "-Dplugin.version=" + version.value,
                        "-Dkyo.version=" + version.value
                    )
            },
            scriptedBufferLog                      := false,
            libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.19" % Test,
            // Publish kyo-ffi + transitive deps locally across all three platforms before
            // scripted runs: scripted tests resolve `"io.getkyo" %% "kyo-ffi"` from Ivy.
            // kyo-ffi depends on kyo-core, so the full closure must be published or Ivy
            // resolution of kyo-ffi fails:
            //   kyo-config -> kyo-stats-registry -> kyo-data -> kyo-kernel -> kyo-prelude
            //   -> kyo-scheduler -> kyo-core -> kyo-ffi
            scriptedDependencies := {
                val a0 = (`kyo-config`.jvm / publishLocal).value
                val a1 = (`kyo-stats-registry`.jvm / publishLocal).value
                val a2 = (`kyo-data`.jvm / publishLocal).value
                val a3 = (`kyo-kernel`.jvm / publishLocal).value
                val a4 = (`kyo-prelude`.jvm / publishLocal).value
                val a5 = (`kyo-scheduler`.jvm / publishLocal).value
                val a6 = (`kyo-core`.jvm / publishLocal).value
                val a7 = (`kyo-ffi`.jvm / publishLocal).value
                val b0 = (`kyo-config`.native / publishLocal).value
                val b1 = (`kyo-stats-registry`.native / publishLocal).value
                val b2 = (`kyo-data`.native / publishLocal).value
                val b3 = (`kyo-kernel`.native / publishLocal).value
                val b4 = (`kyo-prelude`.native / publishLocal).value
                val b5 = (`kyo-scheduler`.native / publishLocal).value
                val b6 = (`kyo-core`.native / publishLocal).value
                val b7 = (`kyo-ffi`.native / publishLocal).value
                val c0 = (`kyo-config`.js / publishLocal).value
                val c1 = (`kyo-stats-registry`.js / publishLocal).value
                val c2 = (`kyo-data`.js / publishLocal).value
                val c3 = (`kyo-kernel`.js / publishLocal).value
                val c4 = (`kyo-prelude`.js / publishLocal).value
                val c5 = (`kyo-scheduler`.js / publishLocal).value
                val c6 = (`kyo-core`.js / publishLocal).value
                val c7 = (`kyo-ffi`.js / publishLocal).value
                // The plugin resolves kyo-ffi-codegen at task time, so publish it locally too:
                // scripted tests resolve it from Ivy the way a downstream user resolves it from Central.
                val d0 = (`kyo-ffi-codegen` / publishLocal).value
                scriptedDependencies.value
            },
            publish   := {},
            publishM2 := {}
        )

// JMH benchmarks for kyo-ffi. Separate from kyo-bench because Panama requires
// `--enable-native-access`. Not part of routine CI; see kyo-ffi-bench/README.md for recipes.
lazy val `kyo-ffi-bench` =
    project
        .in(file("kyo-ffi/bench"))
        .enablePlugins(JmhPlugin)
        .dependsOn(`kyo-ffi`.jvm)
        .disablePlugins(MimaPlugin)
        .settings(
            `kyo-settings`,
            publish / skip := true,
            Compile / javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED"),
            run / fork := true
        )

lazy val `kyo-direct` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-direct"))
        .dependsOn(`kyo-core`)
        .withKyoTest
        .settings(
            `kyo-settings`,
            libraryDependencies += "io.github.dotty-cps-async" %%% "dotty-cps-async" % "1.3.3",
            Test / sourceGenerators += TestVariant.generate.taskValue
        )
        .jvmSettings(mimaCheck(false))
        .jvmConfigure(_.settings(
            // dotty-cps-async macros register denotations into the compiler symbol table, which the warm
            // Driver invalidates on subsequent Runs ("denotation class SeqAsyncShift invalid in run N").
            // Rebuild the Compiler per fence to side-step the assertion.
            doctestFreshDriver := true
        ))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`)
        .wasmSettings(`wasm-settings`)

lazy val `kyo-stm` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-stm"))
        .dependsOn(`kyo-core`)
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`)
        .wasmSettings(`wasm-settings`)

lazy val `kyo-actor` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-actor"))
        .dependsOn(`kyo-core`)
        .dependsOn(`kyo-system`)
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`)
        .wasmSettings(`wasm-settings`)

lazy val `kyo-tasty` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-tasty"))
        .dependsOn(`kyo-core`, `kyo-schema`)
        .dependsOn(`kyo-system`)
        .dependsOn(`kyo-schema-json` % "test->compile")
        .withKyoTest
        .settings(
            `kyo-settings`,
            doctestPredef := Seq("import kyo.*", "import kyo.Tasty.*")
        )
        .jvmSettings(
            mimaCheck(false),
            // TypeKey.structuralEquals and computeHash are iterative (work-list) to prevent
            // StackOverflowError under scoverage instrumentation.
            coverageMinimumStmtTotal := 75.3,
            coverageFailOnMinimum    := true,
            // FROZEN: do not bump as part of routine dependency upgrades. The tasty-query oracle
            // and the real-world fixture jars below are a deliberate spread of versions chosen to
            // exercise TASTy decoding across compiler releases; changing them alters test-coverage
            // intent rather than upgrading a dependency.
            // Differential testing against tasty-query 1.7.0. JVM-only because
            // tasty-query's ClasspathLoaders requires java.nio.
            libraryDependencies += "ch.epfl.scala" %% "tasty-query" % "1.7.0" % Test,
            // Real-world classpath fidelity targets. Each jar is intransitive to avoid
            // downloading large transitive closures (Spark: ~5 GB; Play: ~500 MB). kyo-tasty
            // loads only .tasty files in the jar; missing transitive deps produce
            // Symbol.Unresolved stubs (not TastyError entries), so errors.isEmpty holds.
            libraryDependencies += "com.typesafe.akka"  % "akka-actor_3"    % "2.6.20" % Test intransitive (),
            libraryDependencies += "org.apache.pekko"  %% "pekko-actor"     % "1.1.3"  % Test intransitive (),
            libraryDependencies += "org.playframework" %% "play"            % "3.0.2"  % Test intransitive (),
            libraryDependencies += "org.apache.spark"   % "spark-core_2.13" % "3.5.1"  % Test intransitive (),
            libraryDependencies += "dev.zio"           %% "zio"             % "2.0.15" % Test intransitive ()
        )
        .nativeSettings(`native-settings`)
        .jsSettings(
            `js-settings`,
            scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
        )
        .wasmSettings(`wasm-settings`)
        .dependsOn(`kyo-tasty-fixtures-internal` % Test)

lazy val `kyo-tasty-fixtures-internal` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-tasty/fixtures"))
        .withKyoTest
        .settings(
            `kyo-settings`,
            publish / skip := true
        )
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`)
        .wasmSettings(`wasm-settings`)

lazy val `kyo-logging-jpl` =
    crossProject(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-logging-jpl"))
        .dependsOn(`kyo-core`)
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))

lazy val `kyo-logging-slf4j` =
    crossProject(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-logging-slf4j"))
        .dependsOn(`kyo-core`)
        .withKyoTest
        .settings(
            `kyo-settings`,
            libraryDependencies += "org.slf4j"      % "slf4j-api"       % "2.0.18",
            libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.5.35" % Test
        )
        .jvmSettings(mimaCheck(false))

lazy val `kyo-stats-registry` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-config`)
        .in(file("kyo-stats-registry"))
        .settings(
            `kyo-settings`,
            release17,
            libraryDependencies += "org.scalatest" %%% "scalatest" % scalaTestVersion % Test,
            scalacOptions ++= scalacOptionToken(ScalacOptions.source3).value,
            crossScalaVersions := List(scala3LTSVersion, scala213Version)
        )
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`)
        .wasmSettings(`wasm-settings`)

lazy val `kyo-config` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-config"))
        .settings(
            `kyo-settings`,
            release17,
            libraryDependencies += "org.scalatest" %%% "scalatest" % scalaTestVersion % Test,
            scalacOptions ++= scalacOptionToken(ScalacOptions.source3).value,
            crossScalaVersions := List(scala3LTSVersion, scala213Version)
        )
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(
            `js-settings`,
            // Rollout reads KYO_ROLLOUT_PATH once, when its object initializes, which can happen before any
            // test body runs. RolloutEnvTest asserts a StaticFlag rollout expression resolves against the
            // topology path Node reports, so the variable has to be in the test process environment from the
            // start rather than written by a test.
            Test / jsEnv := new NodeJSEnv(
                NodeJSEnv.Config()
                    .withArgs(List("--max_old_space_size=5120"))
                    .withEnv(Map("KYO_ROLLOUT_PATH" -> "prod/us-east-1"))
            )
        )
        .wasmSettings(
            `wasm-settings`,
            // Rollout reads KYO_ROLLOUT_PATH once, when its object initializes (see the .jsSettings note
            // above); RolloutEnvTest runs on wasm too (the js-wasm shared test root), so the same variable
            // must be in the wasm test process environment from the start. The wasm backend forces ESModule
            // and needs --experimental-wasm-exnref to load the WasmGC module, so this Test / jsEnv override
            // (which fully replaces wasm-settings' jsEnv) re-adds that flag alongside the env var.
            Test / jsEnv := new NodeJSEnv(
                NodeJSEnv.Config()
                    .withArgs(List("--max_old_space_size=5120", "--experimental-wasm-exnref"))
                    .withEnv(Map("KYO_ROLLOUT_PATH" -> "prod/us-east-1"))
            )
        )

lazy val `kyo-stats-machine` =
    crossProject(JVMPlatform, JSPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-stats-machine"))
        .enablePlugins(KyoFfiPlugin)
        .dependsOn(`kyo-ffi`)
        .dependsOn(`kyo-system`)
        .withKyoTest
        .settings(
            `kyo-settings`,
            ffiCodegenClasspath := (LocalProject("kyo-ffi-codegen") / Compile / fullClasspath).value.map(_.data),
            // MacosBindings declares library = "machine_macos", which is not a system library id
            // (ffiSystemLibraries), so it needs an explicit FfiLibrary entry naming its bundled C
            // source; LinuxBindings' library = "c" resolves through the system allowlist and needs
            // no entry here.
            ffiLibraries := Seq(
                FfiLibrary(
                    id = "machine_macos",
                    cSources = Seq((baseDirectory.value / ".." / "shared" / "src" / "main" / "c" / "machine_macos.c").getAbsoluteFile),
                    // Mach calls only: the shim is loaded on macOS and nowhere else. Its C is #ifdef-guarded
                    // to same-signature stubs off __APPLE__, so it compiles on a Linux or Windows host too,
                    // and without this the release built on Linux shipped a Linux artifact for a binding no
                    // Linux process ever loads, while shipping no darwin artifact at all. Scala Native still
                    // compiles the C into the binary on every OS (that is what keeps the stub symbols
                    // resolvable there); this governs the JVM/JS shared library only.
                    osTargets = Seq("darwin")
                )
            )
        )
        .jvmSettings(
            mimaCheck(false),
            Test / javaOptions += "--enable-native-access=ALL-UNNAMED",
            // The module auto-starts a background host sampler on first Stat touch. Disable it for the
            // module's OWN test runs so the once-per-second sampler does not race the suites' destructive
            // counter-drain assertions on the shared process-global machine.* handles; a test that needs a
            // sampler starts and stops its own explicitly (MachineStatFactoryTest, MachineHandlesTest).
            Test / javaOptions += "-Dkyo.machine.disabled=true"
        )
        .nativeSettings(
            `native-settings`,
            // Disable the auto-started sampler for the module's own Native test runs (see the JVM note).
            Test / envVars += "KYO_MACHINE_DISABLED" -> "true"
        )
        .jsSettings(
            `js-settings`,
            // koffi is loaded via CommonJS `require` at runtime, so align the linker.
            scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) },
            // Disable the auto-started sampler for the module's own JS test runs (see the JVM note); the
            // opt-out is read via System.Unsafe.env, which resolves process.env on Node. Also point the
            // runtime at the plugin-compiled machine_macos shim through KYO_FFI_MACHINE_MACOS_PATH: the
            // generated MacosBindings impl resolves the library through NativeLoader.jsResolve, whose first
            // step is this env var. Without it the shim (produced by ffiCompile under <axis>/target/ffi) is
            // unresolvable at Node runtime (@kyo/ffi-native is not installed), so koffi's load throws off
            // macOS instead of the reader degrading. The os/arch/ext tags mirror the plugin's own
            // CCompiler output name so the path matches the file it wrote, including the linux-musl split.
            Test / jsEnv := {
                val ffiOut = target.value / "ffi"
                val osName = sys.props.getOrElse("os.name", "").toLowerCase
                val osTag =
                    if (osName.contains("mac")) "darwin"
                    else if (osName.contains("win")) "windows"
                    else if (osName.contains("linux"))
                        if (
                            new java.io.File("/lib/ld-musl-x86_64.so.1").exists()
                            || new java.io.File("/lib/ld-musl-aarch64.so.1").exists()
                        ) "linux-musl"
                        else "linux"
                    else osName
                val ext    = if (osTag == "darwin") "dylib" else if (osTag == "windows") "dll" else "so"
                val prefix = if (osTag == "windows") "" else "lib"
                val arch = sys.props.getOrElse("os.arch", "") match {
                    case "x86_64" | "amd64"  => "x86_64"
                    case "aarch64" | "arm64" => "aarch64"
                    case other               => other
                }
                val shim = ffiOut / s"${prefix}machine_macos-$osTag-$arch.$ext"
                new NodeJSEnv(
                    NodeJSEnv.Config()
                        .withArgs(List("--max_old_space_size=5120"))
                        .withEnv(Map(
                            "KYO_MACHINE_DISABLED"       -> "true",
                            "KYO_FFI_MACHINE_MACOS_PATH" -> shim.getAbsolutePath
                        ))
                )
            },
            // koffi bootstrap (idempotent npm install, hooked on Test / compile) via the kyo-ffi plugin.
            // The CommonJS linker setting above stays in this .jsSettings block: the plugin is a Scala 2.12
            // sbt plugin with no sbt-scalajs dependency, so it cannot carry a scalaJSLinkerConfig setting.
            ffiKoffiJsBootstrap("kyo-stats-machine-js-test")
        )
        .wasmSettings(
            `wasm-settings`,
            // Disable the auto-started sampler for the module's own wasm test runs (see the JVM note); the
            // opt-out is read via System.Unsafe.env, which resolves process.env on Node, and point the
            // runtime at the plugin-compiled machine_macos shim (see the .jsSettings note). The wasm backend
            // forces ESModule, so the CommonJSModule linker line from .jsSettings is intentionally not
            // repeated here; the Test / jsEnv override fully replaces wasm-settings' jsEnv, so it re-adds
            // --experimental-wasm-exnref (the flag Node needs to load the WasmGC module).
            Test / jsEnv := {
                val ffiOut = target.value / "ffi"
                val osName = sys.props.getOrElse("os.name", "").toLowerCase
                val osTag =
                    if (osName.contains("mac")) "darwin"
                    else if (osName.contains("win")) "windows"
                    else if (osName.contains("linux"))
                        if (
                            new java.io.File("/lib/ld-musl-x86_64.so.1").exists()
                            || new java.io.File("/lib/ld-musl-aarch64.so.1").exists()
                        ) "linux-musl"
                        else "linux"
                    else osName
                val ext    = if (osTag == "darwin") "dylib" else if (osTag == "windows") "dll" else "so"
                val prefix = if (osTag == "windows") "" else "lib"
                val arch = sys.props.getOrElse("os.arch", "") match {
                    case "x86_64" | "amd64"  => "x86_64"
                    case "aarch64" | "arm64" => "aarch64"
                    case other               => other
                }
                val shim = ffiOut / s"${prefix}machine_macos-$osTag-$arch.$ext"
                new NodeJSEnv(
                    NodeJSEnv.Config()
                        .withArgs(List(
                            "--max_old_space_size=5120",
                            "--experimental-wasm-exnref"
                        ))
                        .withEnv(Map(
                            "KYO_MACHINE_DISABLED"       -> "true",
                            "KYO_FFI_MACHINE_MACOS_PATH" -> shim.getAbsolutePath,
                            // Wasm (ESModule) has no `require` global; KoffiFacade resolves koffi via
                            // node:module.createRequire, which searches NODE_PATH for the bootstrapped package.
                            "NODE_PATH" -> (target.value / "node_modules").getAbsolutePath
                        ))
                )
            },
            ffiKoffiJsBootstrap("kyo-stats-machine-wasm-test")
        )

lazy val `kyo-stats-otlp` =
    crossProject(JVMPlatform, JSPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-stats-otlp"))
        .dependsOn(`kyo-http`)
        .withKyoTest
        .settings(
            `kyo-settings`
        )
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`, `openssl-native-settings`)
        .jsSettings(
            `js-settings`,
            scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
        )
        .wasmSettings(`wasm-settings`)

lazy val `kyo-reactive-streams` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-reactive-streams"))
        .dependsOn(`kyo-core`)
        .withKyoTest
        .settings(
            `kyo-settings`
        )
        .jvmSettings(
            mimaCheck(false),
            libraryDependencies ++= Seq(
                "org.reactivestreams" % "reactive-streams"     % "1.0.4",
                "org.reactivestreams" % "reactive-streams-tck" % "1.0.4"    % Test,
                "org.scalatestplus"  %% "testng-7-5"           % "3.2.17.0" % Test
            )
        )
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`)
        .wasmSettings(`wasm-settings`)

// npm is `npm.cmd` on Windows, where CreateProcess resolves only .exe from a bare name;
// the koffi bootstraps below spawn it directly (not via a shell).
def npmCommand: String =
    if (System.getProperty("os.name", "").toLowerCase.contains("win")) "npm.cmd" else "npm"

// Host os-arch in the staged/<os-arch>/ naming used by build-boringssl.sh and
// kyo-aeron/scripts/build-aeron.sh (e.g. "darwin-aarch64"). Delegates to the kyo-ffi plugin's host
// resolver (KyoFfiPlugin.autoImport.ffiHostOsArch), the same one ffiCompile and the Packager resolve
// the target from, so this build and the packaged resource layout can never name the host
// differently. That matters on musl: this used to answer "linux-x86_64" on Alpine while the plugin
// packaged into META-INF/native/linux-musl-x86_64/, so a musl leg that staged BoringSSL under its
// runtime-correct name was not found here and the build silently fell back to the TLS stub.
def hostOsArch: String = ffiHostOsArch

// The staged BoringSSL tree for the host os-arch, present only after build-boringssl.sh ran.
def boringSslStagedDir(baseDir: File): File =
    baseDir / "build" / "boringssl" / "staged" / hostOsArch

// True when libssl.a + libcrypto.a + the openssl headers are staged for the host os-arch.
def boringSslStaged(baseDir: File): Boolean = {
    val d = boringSslStagedDir(baseDir)
    (d / "lib" / "libssl.a").exists() &&
    (d / "lib" / "libcrypto.a").exists() &&
    (d / "include" / "openssl" / "ssl.h").exists()
}

// BoringSSL is C++: link its runtime dynamically after the static archives (-lc++ on darwin, -lstdc++ on Linux).
def boringSslCxxRuntimeFlags: Seq[String] = {
    val osName = System.getProperty("os.name", "").toLowerCase
    if (osName.contains("mac")) Seq("-lc++")
    else Seq("-lstdc++")
}

// System-OpenSSL prefix: the brew openssl@3/openssl tree on macOS; None on Linux (default system path).
def systemOpensslPrefix: Option[File] = {
    val isMac = System.getProperty("os.name").toLowerCase.contains("mac")
    if (isMac) {
        val p3 = new java.io.File("/opt/homebrew/opt/openssl@3")
        val p1 = new java.io.File("/opt/homebrew/opt/openssl")
        val p0 = new java.io.File("/usr/local/opt/openssl")
        Some(if (p3.exists()) p3 else if (p1.exists()) p1 else p0)
    } else None
}

// -I dirs for the system-OpenSSL probe/compile: brew include/ on macOS, /usr/include on Linux. The Native
// codegen probe must find openssl/ssl.h here, or it emits a throwing stub instead of an @extern binding.
def systemOpensslIncludeDirs: Seq[File] =
    systemOpensslPrefix.map(p => Seq(p / "include")).getOrElse(Seq(new java.io.File("/usr/include")))

// The exact flags `openssl-native-settings` appends for system OpenSSL; factored out so
// `stripSystemOpensslForStagedBoringSsl` can undo them by exact subsequence match (a bare -lssl/-lcrypto
// token filter would also strip BoringSSL's identically-spelled Linux flags).
def systemOpensslNativeLinkOpts: Seq[String] =
    systemOpensslPrefix.map(p => Seq(s"-L${(p / "lib").getAbsolutePath}", "-lssl", "-lcrypto")).getOrElse(Seq("-lssl", "-lcrypto"))

def systemOpensslNativeCompileOpts: Seq[String] =
    systemOpensslPrefix.map(p => Seq(s"-I${(p / "include").getAbsolutePath}")).getOrElse(Nil)

// Removes every occurrence of `pattern` as a contiguous subsequence of `xs` (no-op if empty or absent).
// The system-OpenSSL flags can appear more than once and not as the trailing slice (a transitively-folded
// FFI manifest AND openssl-native-settings both append them), so removal must scan, not drop a tail.
def removeSubsequence[A](xs: Seq[A], pattern: Seq[A]): Seq[A] =
    if (pattern.isEmpty) xs
    else {
        @scala.annotation.tailrec
        def loop(acc: Seq[A]): Seq[A] =
            acc.indexOfSlice(pattern) match {
                case -1  => acc
                case idx => loop(acc.patch(idx, Nil, pattern.size))
            }
        loop(xs)
    }

// When BoringSSL is staged, strip `openssl-native-settings`'s system-OpenSSL flags and prepend the staged
// BoringSSL include, so a bundled TLS shim resolves BoringSSL headers instead of the system-OpenSSL macros
// (which segfault on a BoringSSL SSL* via ABI mismatch). `kyoNetBase` is kyo-net's own dir; no-op if unstaged.
def stripSystemOpensslForStagedBoringSsl(kyoNetBase: File)(base: NativeConfig): NativeConfig =
    if (!boringSslStaged(kyoNetBase)) base
    else {
        val stagedDir       = boringSslStagedDir(kyoNetBase)
        val strippedLinking = removeSubsequence(base.linkingOptions, systemOpensslNativeLinkOpts)
        val strippedCompile = removeSubsequence(base.compileOptions, systemOpensslNativeCompileOpts)
        val bsslInc         = s"-I${(stagedDir / "include").getAbsolutePath}"
        base.withLinkingOptions(strippedLinking).withCompileOptions(bsslInc +: strippedCompile)
    }

// kyo-net's staged-BoringSSL force-load link flags (whole-archive on Linux, -force_load on darwin) plus the
// dynamic C++ runtime. Reconstructed here (not reused) because downstream kyo-http lacks the
// `ffiNativeLinkingOptions` task yet also needs them. `kyoNetBase` is kyo-net's own dir; no-op if unstaged.
def stagedBoringSslForceLoadLinkOpts(kyoNetBase: File): Seq[String] =
    if (!boringSslStaged(kyoNetBase)) Nil
    else {
        val libDir = boringSslStagedDir(kyoNetBase) / "lib"
        val isMac  = System.getProperty("os.name", "").toLowerCase.contains("mac")
        val forceLoad =
            if (isMac)
                Seq("libssl.a", "libcrypto.a").map(a => s"-Wl,-force_load,${(libDir / a).getAbsolutePath}")
            else
                Seq(s"-L${libDir.getAbsolutePath}", "-Wl,--whole-archive", "-lssl", "-lcrypto", "-Wl,--no-whole-archive")
        forceLoad ++ boringSslCxxRuntimeFlags
    }

// Koffi bootstrap for the Node-run test platforms. Both JS and Wasm run the koffi posix transport on Node and resolve koffi dynamically at
// first native-load (the Wasm/ESModule leg via NODE_PATH; see kyoNetFfiEnvMap), so both need koffi installed in the target's node_modules
// before tests run. Hooked on Test / compile (not Test / test) so test, testOnly,
// and testQuick all trigger it, and it re-runs after a clean. Idempotent on the marker.
val kyoNetKoffiInstall: Def.Initialize[Task[Unit]] = Def.task {
    val log        = streams.value.log
    val targetBase = target.value
    val nodeMods   = targetBase / "node_modules"
    val marker     = nodeMods / "koffi" / "package.json"
    val koffiRange = "^2.7" // must match kyo.ffi.internal.FfiErrors.KoffiSupportedRange
    val pjContent  = s"""{"name":"kyo-net-node-test","private":true,"dependencies":{"koffi":"$koffiRange"}}"""
    val pj         = targetBase / "package.json"
    if (!pj.exists() || IO.read(pj) != pjContent) {
        IO.createDirectory(targetBase)
        IO.write(pj, pjContent)
    }
    if (!marker.exists()) {
        log.info(s"[kyo-net] installing koffi@$koffiRange into $targetBase ...")
        val rc = scala.sys.process.Process(
            Seq(npmCommand, "install", "--no-audit", "--no-fund", "--silent"),
            targetBase
        ).!
        if (rc != 0) sys.error(s"npm install koffi failed (exit $rc)")
    }
}

// The plugin-compiled native paths for the koffi posix transport and its BoringSSL TLS, exported via KYO_FFI_<LIBID>_PATH to the Node/Wasm
// test runtime. The plugin owns the artifact-naming convention and the host os/arch (musl probe included); re-deriving them here is how the
// path resolves `-linux-musl-x86_64.so` on Alpine where a naive `-linux-x86_64.so` would miss. ffiCompile always emits both artifacts (the
// real staged lib when BoringSSL is staged for this host, else the probe-unavailable stub), so pointing koffi at them is safe either way:
// on a staged host the `[posix / boringssl]` TLS cells run, on a non-staged host they cancel via the probe, matching JVM/Native.
def kyoNetFfiEnvMap(targetDir: File): Map[String, String] = {
    val ffiOut = targetDir / "ffi"
    Map(
        "KYO_FFI_KYONET_POSIX_URING_PATH" -> (ffiOut / ffiArtifactName("kyonet_posix_uring", ffiHostOsArch)).getAbsolutePath,
        "KYO_FFI_KYONET_BORINGSSL_PATH"   -> (ffiOut / ffiArtifactName("kyonet_boringssl", ffiHostOsArch)).getAbsolutePath,
        // The Wasm (ESModule) leg has no `require` global, so KoffiFacade resolves koffi via
        // node:module.createRequire, which searches NODE_PATH. Point it at the target's node_modules where the
        // koffi bootstrap installs the package. Harmless on the CommonJS (js) leg, which uses the `require` global.
        "NODE_PATH" -> (targetDir / "node_modules").getAbsolutePath
    )
}

// P2b completeness guard (DECISION-P2b-classifier.md Decision 5, layout-first per DECISION-P1 §6): a native
// classifier jar must carry a real native for every library id the P1 library-state manifest records as `native`
// or `prebuilt`, and must never ship a `stub`. Keys on the state manifest
// (META-INF/kyo-ffi/library-state/<module>.state, states native/stub/prebuilt/absent) plus the resource layout
// (META-INF/native/<os>-<arch>/lib<id>.<ext>). JVM only: JS/Wasm ship natives via the env/npm path and Native links
// them directly. When M lands it adds the manifest-equals-support-set check on top.
val kyoNetNativeClassifierGuard = taskKey[Unit](
    "Assert every library-state `native`/`prebuilt` id has a real native artifact in the resource layout, and no `stub` ships."
)

// P2b classifier slice: the per-os-arch native classifier jars + the all-natives aggregator, appended to
// `Compile / packagedArtifacts` (via `++=`, never a self-referential `:=`). Separate task so the extension is
// non-cyclic. DECISION-P2b-classifier.md Decisions 1 + 3.
val kyoNetClassifierArtifacts = taskKey[Map[Artifact, File]](
    "Per-os-arch native classifier jars (transport-native `<os-arch>`, vendored-BoringSSL `<os-arch>-boringssl`) + the `all-natives` aggregator."
)

lazy val `kyo-net` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .enablePlugins(KyoFfiPlugin)
        .dependsOn(`kyo-core`, `kyo-config`)
        .dependsOn(`kyo-system`)
        .dependsOn(`kyo-ffi`)
        .in(file("kyo-net"))
        .withKyoTest
        // FFI backs the posix transport and BoringSSL TLS on ALL FOUR platforms (Panama on JVM, Scala Native
        // @extern, koffi-on-Node on JS and Wasm), so KyoFfiPlugin, the kyo-ffi dependency, and the C-shim
        // ffiLibraries are uniform (mirrors kyo-aeron / kyo-ffi-it). The bindings + drivers live in `shared`.
        // ffiCodegenClasspath feeds the plugin the codegen classpath for in-build gen.
        .settings(
            `kyo-settings`,
            ffiCodegenClasspath := (LocalProject("kyo-ffi-codegen") / Compile / fullClasspath).value.map(_.data),
            // Only the io_uring shim needs a declared library (-luring, Linux only via linkLibsByOs; staticLink folds
            // liburing in, RI-003); the socket/epoll/kqueue bindings resolve to system libc. On Native, IoUringBindings
            // is nativeBundled: the plugin copies kyo_uring.c in; only -luring reaches the final link (Linux).
            ffiLibraries := {
                // baseDirectory is the per-platform dir (jvm/native/js); the C lives under ../shared/src/main.
                val sharedBase = baseDirectory.value / ".." / "shared"
                val kyoNetBase = baseDirectory.value / ".."
                val isNative   = ffiTargetPlatform.value == "Native"
                // BoringSSL (kyonet_boringssl): the kyo_net_boringssl.c shim insulates the raw SSL_* ABI (RI-006), linking
                // the staged static archives (JVM: loadable lib via Panama; Native: archive-linked). When not staged, compile
                // the stub instead (probe_available -> 0, so BoringSslProvider.isAvailable is false and TLS falls back).
                val staged    = boringSslStaged(kyoNetBase)
                val stagedDir = boringSslStagedDir(kyoNetBase)
                val boringSsl =
                    if (staged)
                        FfiLibrary(
                            id = "kyonet_boringssl",
                            cSources = (sharedBase / "src" / "main" / "c-boringssl" ** "*.c").get,
                            // Track the shared header as a compile input so a change to it invalidates the cached C compile.
                            cHeaders = (sharedBase / "src" / "main" / "c-boringssl" ** "*.h").get,
                            includeDirs = Seq(stagedDir / "include"),
                            libDirs = Seq(stagedDir / "lib"),
                            linkLibs = Seq("ssl", "crypto"),
                            linkFlags = boringSslCxxRuntimeFlags,
                            staticLink = true
                        )
                    else
                        FfiLibrary(
                            id = "kyonet_boringssl",
                            cSources = (sharedBase / "src" / "main" / "c-boringssl-stub" ** "*.c").get
                        )
                // System OpenSSL (kyonet_openssl): the kyo_net_openssl.c shim, registered only in the Native TLS registry
                // (SystemOpenSslProvider). On Native its C sources are declared UNCONDITIONALLY, because whether the system
                // OpenSSL headers exist is a question about the machine that LINKS the binary, not the one that publishes the
                // artifact, and the shim now answers it itself: it header-gates on `__has_include(<openssl/ssl.h>)` and
                // compiles to stubs where they are absent. Deciding it here froze the publisher's answer into the shipped C,
                // so a release built on a Linux runner left a macOS consumer's Scala Native link short 64 raw SSL_*, BIO_*,
                // EVP_* and ERR_* symbols, whether or not their program used TLS. `includeDirs` still tracks THIS host: it
                // only steers the local compile toward a non-default OpenSSL prefix, and is dropped when it holds no headers
                // so the shim gates to stubs rather than compiling against a prefix that has none.
                // On the JVM (where BoringSslProvider over the JDK SSLEngine floor covers TLS and no code path loads it) it
                // is still declared as a STUB with no C sources, so no static OpenSSL blob is bundled. The stub still declares
                // the library id, so the FFI codegen's library-id validation passes for the always-present OpenSslBindings
                // trait; the JVM jar simply no longer carries the ~6.5MB dead-weight archive.
                val openSsl =
                    if (isNative)
                        FfiLibrary(
                            id = "kyonet_openssl",
                            cSources = (sharedBase / "src" / "main" / "c-openssl" ** "*.c").get,
                            cHeaders = (sharedBase / "src" / "main" / "c-openssl" ** "*.h").get,
                            includeDirs = systemOpensslIncludeDirs.filter(d => (d / "openssl" / "ssl.h").exists())
                        )
                    else
                        FfiLibrary(id = "kyonet_openssl", cSources = Nil)
                Seq(
                    FfiLibrary(
                        id = "kyonet_posix_uring",
                        cSources = (sharedBase / "src" / "main" / "c" ** "*.c").get,
                        linkLibsByOs = Map("linux" -> Seq("uring")),
                        staticLink = true
                    ),
                    boringSsl,
                    openSsl
                )
            },
            // The BoringSSL stub declares (placeholder) C sources, so KyoFfiPlugin's library-state manifest cannot tell it from a
            // real build (it records any library with C sources as `native`). Declare it a stub on a non-staged host so the manifest
            // records `kyonet_boringssl=stub`, and the completeness guard rejects a stub jar; empty on a staged host, where the real
            // BoringSSL native is bundled. `boringSslStaged` reads this host's `build/boringssl/staged/<os-arch>`, the same gate the
            // `ffiLibraries` boringSsl branch above uses.
            ffiStubLibraries := {
                if (boringSslStaged(baseDirectory.value / "..")) Nil else Seq("kyonet_boringssl")
            }
        )
        .jvmSettings(
            mimaCheck(false),
            kyoNetNativeClassifierGuard := {
                val log        = streams.value.log
                val generated  = (Compile / managedResources).value // runs ffiPackagedNatives + the state-manifest generator
                val stateFiles = generated.filter(f => f.getName.endsWith(".state") && f.getParentFile.getName == "library-state")
                if (stateFiles.isEmpty)
                    sys.error("[kyo-net native-guard] no library-state manifest was generated; cannot verify native completeness.")
                val nativeFiles = generated.filter { f =>
                    val n = f.getName
                    (n.endsWith(".so") || n.endsWith(".dylib") || n.endsWith(".dll")) &&
                    f.getParentFile.getParentFile.getName == "native" // .../META-INF/native/<os-arch>/lib<id>.<ext>
                }
                def nativePresent(id: String): Boolean =
                    nativeFiles.exists(f => f.getName.startsWith(s"lib$id.") && f.length > 0)
                stateFiles.foreach { sf =>
                    IO.readLines(sf).foreach { line =>
                        val kv = line.split("=", 2)
                        if (kv.length == 2) {
                            val id    = kv(0).trim
                            val state = kv(1).trim
                            state match {
                                case "stub" =>
                                    sys.error(
                                        s"[kyo-net native-guard] library '$id' is a STUB in ${sf.getName}: a stub must not ship in a " +
                                            "native classifier jar. Stage the real native (build/boringssl) for this os-arch, or exclude the classifier."
                                    )
                                case "native" | "prebuilt" =>
                                    if (!nativePresent(id))
                                        sys.error(
                                            s"[kyo-net native-guard] library '$id' is declared '$state' but no non-empty " +
                                                s"META-INF/native/*/lib$id.* artifact was produced; the build did not compile or stage it."
                                        )
                                case "absent" => () // intentionally empty (e.g. kyonet_openssl on JVM); no native expected
                                case other =>
                                    sys.error(s"[kyo-net native-guard] library '$id' has unknown state '$other' in ${sf.getName}.")
                            }
                        }
                    }
                }
                log.info(s"[kyo-net native-guard] passed (${stateFiles.map(_.getName).mkString(", ")}).")
            },
            Compile / packageBin := (Compile / packageBin).dependsOn(kyoNetNativeClassifierGuard).value,
            // P2b (DECISION-P2b-classifier.md Decisions 1-3): the MAIN jar carries NO natives (pure-JVM NIO floor).
            // The natives ship in per-os-arch classifier jars sliced from the META-INF/native/<os-arch> resource tree:
            // the transport-native family (kyonet_posix_uring, classifier `<os-arch>`) and the vendored-BoringSSL family
            // (kyonet_boringssl, classifier `<os-arch>-boringssl`), plus an all-natives aggregator (classifier `all-natives`).
            Compile / packageBin / mappings := (Compile / packageBin / mappings).value.filterNot {
                case (_, path) => path.startsWith("META-INF/native/")
            },
            kyoNetClassifierArtifacts := {
                val log  = streams.value.log
                val res  = (Compile / managedResources).value
                val ver  = version.value
                val out  = crossTarget.value
                val base = "kyo-net"
                // Native files in the resource tree as (os-arch, library-id, file). Layout: META-INF/native/<os-arch>/lib<id>.<ext>.
                val nativeEntries = res.collect {
                    case f
                        if (f.getName.endsWith(".so") || f.getName.endsWith(".dylib") || f.getName.endsWith(".dll")) &&
                            f.getParentFile.getParentFile.getName == "native" =>
                        val osArch = f.getParentFile.getName
                        val libId  = f.getName.stripPrefix("lib").takeWhile(_ != '.')
                        (osArch, libId, f)
                }
                def familyClassifier(osArch: String, libId: String): Option[String] = libId match {
                    case "kyonet_posix_uring" => Some(osArch)
                    case "kyonet_boringssl"   => Some(s"$osArch-boringssl")
                    case _                    => None // kyonet_openssl etc. is not a JVM classifier family
                }
                def sliceJar(classifier: String, files: Seq[File]): (Artifact, File) = {
                    val jar     = out / s"$base-$ver-$classifier-natives.jar"
                    val entries = files.map(f => f -> s"META-INF/native/${f.getParentFile.getName}/${f.getName}")
                    IO.zip(entries, jar, None)
                    Artifact(base).withType("jar").withExtension("jar").withClassifier(Some(classifier)) -> jar
                }
                val perClassifier = nativeEntries
                    .groupBy { case (osArch, libId, _) => familyClassifier(osArch, libId) }
                    .collect { case (Some(classifier), entries) => sliceJar(classifier, entries.map(_._3)) }
                    .toMap
                val allNatives =
                    if (nativeEntries.nonEmpty) Map(sliceJar("all-natives", nativeEntries.map(_._3)))
                    else Map.empty[Artifact, File]
                val result = perClassifier ++ allNatives
                log.info(s"[kyo-net classifier] ${nativeEntries.size} native(s) -> ${result.keys.flatMap(_.classifier).mkString(", ")}")
                result
            },
            // Project-scoped `packagedArtifacts` is what `publish`/`ci-release` uploads (Compile / packagedArtifacts does not reach it).
            packagedArtifacts ++= kyoNetClassifierArtifacts.value
        )
        .nativeSettings(
            `native-settings`,
            `openssl-native-settings`,
            // KyoFfiPlugin bundles the C shims (kyo_uring.c, the TLS shims) into the Native binary and places their
            // objects before the link libs, so -luring and the staged BoringSSL archives resolve at nativeLink.
            // stripSystemOpensslForStagedBoringSsl (reused by kyo-http) swaps system OpenSSL for staged BoringSSL when
            // staged; the ffiLinking append is kyo-net-specific since it owns the FFI libraries.
            nativeConfig := {
                val kyoNetBase = baseDirectory.value / ".."
                val ffiLinking = ffiNativeLinkingOptions.value
                val stripped   = stripSystemOpensslForStagedBoringSsl(kyoNetBase)(nativeConfig.value)
                stripped.withLinkingOptions(stripped.linkingOptions ++ ffiLinking)
            },
            // The plugin's Native flat-copy stages only the .c, so the TLS shims' quoted #include of kyo_ssl_common.h
            // would not resolve; stage the co-located headers into the same flat dir. (JVM compiles .c in place.)
            Compile / resourceGenerators += Def.task {
                val sharedBase = baseDirectory.value / ".." / "shared" / "src" / "main"
                val destDir    = (Compile / resourceManaged).value / "scala-native"
                // The two co-located headers are byte-identical; on the flat Native dir they collapse to one.
                val headers = Seq(
                    sharedBase / "c-boringssl" / "kyo_ssl_common.h",
                    sharedBase / "c-openssl" / "kyo_ssl_common.h"
                ).filter(_.exists())
                IO.createDirectory(destDir)
                headers.map { src =>
                    val dest = destDir / src.getName
                    // Copy only when content differs, keeping the generated resource (and nativeLink's
                    // classpath hash) stable across no-change builds.
                    if (!dest.exists() || !IO.read(dest).equals(IO.read(src)))
                        IO.copyFile(src, dest, preserveLastModified = true)
                    dest
                }.distinct
            }.taskValue
        )
        .jsSettings(
            `js-settings`,
            scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) },
            // Point the Node runtime at the plugin-compiled koffi natives and bootstrap koffi into node_modules before tests run.
            Test / jsEnv := new NodeJSEnv(
                NodeJSEnv.Config()
                    .withArgs(List("--max_old_space_size=5120"))
                    .withEnv(kyoNetFfiEnvMap(target.value))
            ),
            Test / compile := (Test / compile).dependsOn(kyoNetKoffiInstall).value
        )
        // Wasm runs the same koffi posix transport on Node as JS (it `import`s koffi at module load), so it needs the identical koffi bootstrap
        // and native-path env; only the NodeJSEnv args differ (the WASM backend needs `--experimental-wasm-exnref`, Node 24+, matching
        // `wasm-settings`).
        .wasmSettings(
            `wasm-settings`,
            Test / jsEnv := new NodeJSEnv(
                NodeJSEnv.Config()
                    .withArgs(List("--max_old_space_size=5120", "--experimental-wasm-exnref"))
                    .withEnv(kyoNetFfiEnvMap(target.value))
            ),
            Test / compile := (Test / compile).dependsOn(kyoNetKoffiInstall).value
        )

lazy val `kyo-aeron` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .enablePlugins(KyoFfiPlugin)
        .in(file("kyo-aeron"))
        .dependsOn(`kyo-core`)
        .dependsOn(`kyo-system`)
        .dependsOn(`kyo-ffi`)
        .dependsOn(`kyo-schema`)
        // Schema types the public publish/stream surface; MsgPack encodes the Envelope on the wire and
        // lives in the per-format module since the schema family split, like kyo-test-snapshot.
        .dependsOn(`kyo-schema-msgpack`)
        .withKyoTest
        .settings(
            `kyo-settings`,
            // Hand the plugin the codegen project's own classpath (mirrors kyo-ffi-it) so a cold
            // build compiles the codegen first. Without it ffiGenerate falls back to the plugin's
            // bundled-resource path, absent on a clean checkout, and Ffi.load fails with ImplNotFound.
            ffiCodegenClasspath := (LocalProject("kyo-ffi-codegen") / Compile / fullClasspath).value.map(_.data),
            ffiLibraries := {
                // baseDirectory is the per-platform dir for a cross-project, so the shared C shim and
                // the staged aeron archives are one level up.
                val sharedBase  = baseDirectory.value / ".." / "shared"
                val aeronStaged = baseDirectory.value / ".." / "build" / "aeron" / "staged" / hostOsArch
                // Aeron's CMake records these as aeron_driver_static's link interface but does not bake
                // them into the .a, so linking the archive directly leaves uuid_generate, pthread, and
                // friends undefined unless named here. They belong in linkFlags rather than linkLibsByOs
                // because linkLibsByOs is static-folded (-Wl,-Bstatic) and these must stay dynamic: the
                // runners' libuuid.a is non-PIC and cannot go into the shim's shared object. -latomic is
                // aarch64-only, where 64-bit atomic_fetch_add lowers to an out-of-line libatomic call.
                // macOS supplies all of them via libSystem.
                val aeronArch = hostOsArch.split("-").lastOption.getOrElse("")
                val isWindows = hostOsArch.startsWith("windows")
                val linuxSystemLinkFlags =
                    if (hostOsArch.startsWith("linux"))
                        Seq("-lpthread", "-lm", "-ldl", "-luuid") ++ (if (aeronArch == "aarch64") Seq("-latomic") else Nil)
                    else Nil
                Seq(
                    FfiLibrary(
                        id = "kyo_aeron",
                        cSources = (sharedBase / "src" / "main" / "c" ** "*.c").get,
                        includeDirs = Seq(
                            aeronStaged / "include" / "aeron",
                            aeronStaged / "include" / "aeronmd"
                        ),
                        libDirs = Seq(aeronStaged / "lib"),
                        // aeron_driver_static already embeds the full client, so it alone provides the
                        // complete client + driver API. Adding aeron_static too duplicates every client
                        // symbol and fails the Darwin ld64 link.
                        linkLibs = Seq("aeron_driver_static"),
                        // Windows needs aeron's declared winsock stack plus shell32 (SHFileOperation,
                        // used by aeron's file utils), rendered as `.lib` by the plugin under MSVC.
                        linkLibsByOs = if (isWindows) Map("windows" -> Seq("ws2_32", "wsock32", "Iphlpapi", "shell32")) else Map.empty,
                        linkFlags = linuxSystemLinkFlags,
                        // Aeron supports Windows only under MSVC (its sources gate on _MSC_VER) and forces
                        // the dynamic CRT (/MD), so on Windows the shim compiles with cl and /MD.
                        // staticLink=true would add /MT (static CRT) and clash with aeron's /MD; the aeron
                        // .lib is embedded by the link regardless, so Windows uses staticLink=false.
                        cFlags = if (isWindows) Seq("/MD") else Nil,
                        compilerByOs = if (isWindows) Map("windows" -> "cl") else Map.empty,
                        staticLink = !isWindows
                    )
                )
            }
        )
        .jvmSettings(
            mimaCheck(false),
            fork := true,
            // The four --add-opens this used to need were io.aeron's: its embedded Java MediaDriver
            // reaches into jdk.internal.misc, java.lang, java.nio, and sun.nio.ch, and Topic.run failed
            // at driver launch without them. The JVM now drives the same C client and embedded C driver
            // as every other platform through Panama, which needs no add-opens; --enable-native-access
            // only silences the restricted-method warning.
            javaOptions += "--enable-native-access=ALL-UNNAMED"
        )
        .nativeSettings(
            `native-settings`,
            // The UDP round-trip and URI-validation suites bind fixed high ports, which collide if
            // suites run concurrently. `native-settings-base` now sets this too, for its own
            // reason; kept here so a change there cannot silently reintroduce the port collision.
            // (The JS and Wasm blocks need no equivalent: they inherit it from `js-settings`.)
            Test / parallelExecution := false,
            nativeConfig := {
                val base = nativeConfig.value
                // Scala Native compiles the C shim from a copy under scala-native/, so both the staged
                // Aeron headers and the shim's own directory (holding kyo_aeron.h) must be on the
                // include path. Without them kyo_aeron.c's #if __has_include(<aeronc.h>) guard is false
                // and every function compiles out, leaving an empty .c.o and undefined symbols at link.
                val aeronStaged = baseDirectory.value / ".." / "build" / "aeron" / "staged" / hostOsArch
                val cSrcDir     = baseDirectory.value / ".." / "shared" / "src" / "main" / "c"
                val aeronIncludes = Seq(
                    s"-I${cSrcDir.absolutePath}",
                    s"-I${(aeronStaged / "include" / "aeron").absolutePath}",
                    s"-I${(aeronStaged / "include" / "aeronmd").absolutePath}"
                )
                base
                    .withLinkingOptions(base.linkingOptions ++ ffiNativeLinkingOptions.value)
                    .withCompileOptions(base.compileOptions ++ aeronIncludes)
            }
        )
        .jsSettings(
            `js-settings`,
            scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) },
            Test / jsEnv := {
                val targetDir = target.value
                val ffiOut    = targetDir / "ffi"
                val os        = sys.props.getOrElse("os.name", "").toLowerCase
                val ext =
                    if (os.contains("mac")) "dylib"
                    else if (os.contains("win")) "dll"
                    else "so"
                val arch =
                    sys.props.getOrElse("os.arch", "") match {
                        case "x86_64" | "amd64"  => "x86_64"
                        case "aarch64" | "arm64" => "aarch64"
                        case other               => other
                    }
                val osDetect =
                    if (os.contains("mac")) "darwin"
                    else if (os.contains("win")) "windows"
                    else if (os.contains("linux")) "linux"
                    else os
                // Windows has no `lib` prefix on the shared library, matching the plugin's CCompiler output.
                val prefix = if (os.contains("win")) "" else "lib"
                val lib    = ffiOut / s"${prefix}kyo_aeron-$osDetect-$arch.$ext"
                new NodeJSEnv(
                    NodeJSEnv.Config()
                        .withArgs(List("--max_old_space_size=5120"))
                        .withEnv(Map("KYO_FFI_KYO_AERON_PATH" -> lib.getAbsolutePath))
                )
            },
            Test / compile := (Test / compile).dependsOn(Def.task {
                val log        = streams.value.log
                val targetBase = target.value
                val nodeMods   = targetBase / "node_modules"
                val marker     = nodeMods / "koffi" / "package.json"
                val koffiRange = "^2.7" // must match kyo.ffi.internal.FfiErrors.KoffiSupportedRange
                val pjContent =
                    s"""{"name":"kyo-aeron-js-test","private":true,"dependencies":{"koffi":"$koffiRange"}}"""
                val pj = targetBase / "package.json"
                if (!pj.exists() || IO.read(pj) != pjContent) {
                    IO.createDirectory(targetBase)
                    IO.write(pj, pjContent)
                }
                if (!marker.exists()) {
                    log.info(s"[kyo-aeron JS] installing koffi@$koffiRange into $targetBase ...")
                    val rc = scala.sys.process.Process(
                        Seq(npmCommand, "install", "--no-audit", "--no-fund", "--silent"),
                        targetBase
                    ).!
                    if (rc != 0) sys.error(s"npm install koffi failed (exit $rc)")
                }
            }).value
        )
        .wasmSettings(
            `wasm-settings`,
            // Wasm runs the same koffi-on-Node backend as JS, so the wiring mirrors .jsSettings with
            // two differences: the Wasm backend forces ESModule, so the CommonJSModule linker line is
            // not repeated; and this jsEnv fully replaces wasm-settings', so it re-adds
            // --experimental-wasm-exnref, which Node needs to load the WasmGC module.
            Test / jsEnv := {
                val targetDir = target.value
                val ffiOut    = targetDir / "ffi"
                val os        = sys.props.getOrElse("os.name", "").toLowerCase
                val ext =
                    if (os.contains("mac")) "dylib"
                    else if (os.contains("win")) "dll"
                    else "so"
                val arch =
                    sys.props.getOrElse("os.arch", "") match {
                        case "x86_64" | "amd64"  => "x86_64"
                        case "aarch64" | "arm64" => "aarch64"
                        case other               => other
                    }
                val osDetect =
                    if (os.contains("mac")) "darwin"
                    else if (os.contains("win")) "windows"
                    else if (os.contains("linux")) "linux"
                    else os
                // Windows has no `lib` prefix on the shared library, matching the plugin's CCompiler output.
                val prefix = if (os.contains("win")) "" else "lib"
                val lib    = ffiOut / s"${prefix}kyo_aeron-$osDetect-$arch.$ext"
                new NodeJSEnv(
                    NodeJSEnv.Config()
                        .withArgs(List("--max_old_space_size=5120", "--experimental-wasm-exnref"))
                        .withEnv(Map(
                            "KYO_FFI_KYO_AERON_PATH" -> lib.getAbsolutePath,
                            // Wasm (ESModule) has no `require` global; KoffiFacade resolves koffi via
                            // node:module.createRequire, which searches NODE_PATH for the bootstrapped package.
                            "NODE_PATH" -> (target.value / "node_modules").getAbsolutePath
                        ))
                )
            },
            Test / compile := (Test / compile).dependsOn(Def.task {
                val log        = streams.value.log
                val targetBase = target.value
                val nodeMods   = targetBase / "node_modules"
                val marker     = nodeMods / "koffi" / "package.json"
                val koffiRange = "^2.7" // must match kyo.ffi.internal.FfiErrors.KoffiSupportedRange
                val pjContent =
                    s"""{"name":"kyo-aeron-wasm-test","private":true,"dependencies":{"koffi":"$koffiRange"}}"""
                val pj = targetBase / "package.json"
                if (!pj.exists() || IO.read(pj) != pjContent) {
                    IO.createDirectory(targetBase)
                    IO.write(pj, pjContent)
                }
                if (!marker.exists()) {
                    log.info(s"[kyo-aeron Wasm] installing koffi@$koffiRange into $targetBase ...")
                    val rc = scala.sys.process.Process(
                        Seq(npmCommand, "install", "--no-audit", "--no-fund", "--silent"),
                        targetBase
                    ).!
                    if (rc != 0) sys.error(s"npm install koffi failed (exit $rc)")
                }
            }).value
        )

lazy val `kyo-compiler` =
    crossProject(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-compiler"))
        .dependsOn(`kyo-core`, `kyo-aeron`, `kyo-ai` % Test)
        .dependsOn(`kyo-system`)
        .withKyoTest
        .settings(
            `kyo-settings`,
            fork := true,
            // These were io.aeron's requirement, dropped with it: the pool's shared driver is now
            // kyo-aeron's AeronDriver, the same C driver every platform runs, reached through Panama.
            javaOptions += "--enable-native-access=ALL-UNNAMED",
            libraryDependencies ++= Seq(
                "org.scala-lang" %% "scala3-presentation-compiler" % scalaVersion.value
            )
        )
        .jvmSettings(mimaCheck(false))

lazy val `kyo-http` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-http"))
        .dependsOn(`kyo-core`, `kyo-config`, `kyo-schema-json`)
        .dependsOn(`kyo-system` % Test)
        .dependsOn(`kyo-net` % "compile->compile;test->test")
        .withKyoTest
        .settings(
            `kyo-settings`
        )
        .jvmSettings(
            mimaCheck(false)
        )
        .jsSettings(
            `js-settings`,
            scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
        )
        .nativeSettings(
            `native-settings`,
            `openssl-native-settings`,
            // kyo-http does not own the FFI libraries (only kyo-net enables KyoFfiPlugin); it inherits the bundled TLS
            // shim C transitively. When BoringSSL is staged, apply the same COMPILE strip/prepend as kyo-net
            // (stripSystemOpensslForStagedBoringSsl) and re-append kyo-net's force-load LINK window. Linux only, since
            // darwin force-loads by path (re-appending would duplicate symbols). Unstaged: both are no-ops.
            nativeConfig := {
                val kyoNetBase   = baseDirectory.value / ".." / ".." / "kyo-net"
                val stripped     = stripSystemOpensslForStagedBoringSsl(kyoNetBase)(nativeConfig.value)
                val isMac        = System.getProperty("os.name", "").toLowerCase.contains("mac")
                val bsslReappend = if (isMac) Nil else stagedBoringSslForceLoadLinkOpts(kyoNetBase)
                if (bsslReappend.isEmpty) stripped
                else stripped.withLinkingOptions(stripped.linkingOptions ++ bsslReappend)
            },
            // Scala Native resolves ServiceLoader.load at LINK time: a META-INF/services provider is linked
            // only when also enlisted here. Enlist the shared test factory so the auto-filter tests exercise
            // real discovery on Native. (The load site is a plain method, not a lazy val, to dodge a Scala
            // Native 0.5.12 codegen crash (see loadFactories); plain string literal so the "$" does not interpolate.)
            Test / nativeConfig ~= (_.withServiceProviders(Map("kyo.HttpFilter$Factory" -> Seq("kyo.HttpFilterTestFactory"))))
        )
        .wasmSettings(`wasm-settings`)

lazy val `kyo-ai` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-ai"))
        .dependsOn(`kyo-core`, `kyo-schema-json`, `kyo-http`, `kyo-actor`, `kyo-jsonrpc`, `kyo-jsonrpc-http`, `kyo-mcp`)
        .dependsOn(`kyo-system`)
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        .jsSettings(
            `js-settings`,
            scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
        )
        .nativeSettings(
            `native-settings`,
            `openssl-native-settings`
        )
        .wasmSettings(`wasm-settings`)

lazy val `kyo-flow` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-flow"))
        .dependsOn(`kyo-http`)
        .dependsOn(`kyo-direct` % Test)
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`, `openssl-native-settings`)
        .jsSettings(
            `js-settings`,
            scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
        )
        .wasmSettings(`wasm-settings`)

lazy val `kyo-jsonrpc` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-prelude`)
        .dependsOn(`kyo-core`)
        .dependsOn(`kyo-schema-json`)
        .dependsOn(`kyo-net`)
        .dependsOn(`kyo-system`)
        .in(file("kyo-jsonrpc"))
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        // kyo-net's Native FFI links the TLS shim unconditionally, so downstream Native modules need the SSL
        // link flags (-lssl -lcrypto); io_uring's -luring propagates through the kyo-ffi plugin on Linux.
        .nativeSettings(`native-settings`, `openssl-native-settings`)
        .wasmSettings(`wasm-settings`)
        // kyo-net's JS transports @JSImport Node built-ins, so the JS linker needs a module kind (default is
        // NoModule); CommonJS matches kyo-net and kyo-jsonrpc-http.
        .jsSettings(`js-settings`, scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) })

lazy val `kyo-jsonrpc-http` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Pure)
        .in(file("kyo-jsonrpc-http"))
        .withKyoTest
        .dependsOn(`kyo-jsonrpc`)
        .dependsOn(`kyo-http`)
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`, `openssl-native-settings`)
        .wasmSettings(`wasm-settings`)
        .jsSettings(
            `js-settings`,
            scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
        )

lazy val `kyo-mcp` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-mcp"))
        .withKyoTest
        .dependsOn(`kyo-jsonrpc`)
        .dependsOn(`kyo-system` % Test)
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        // Test-only dep so the JVM demo MCP servers (jvm/src/test/scala/demo) can drive
        // kyo-tasty's runtime reflection (RepoExplorer). kyo-tasty is a sibling, so no cycle.
        .jvmConfigure(_.dependsOn(`kyo-tasty`.jvm % Test))
        .nativeSettings(`native-settings`)
        .wasmSettings(`wasm-settings`)
        .jsSettings(`js-settings`)

lazy val `kyo-lsp` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-lsp"))
        .withKyoTest
        .dependsOn(`kyo-jsonrpc`)
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .wasmSettings(`wasm-settings`)
        .jsSettings(`js-settings`)

lazy val `kyo-caliban` =
    crossProject(JVMPlatform)
        .crossType(CrossType.Pure)
        .in(file("kyo-caliban"))
        .dependsOn(`kyo-core`)
        .dependsOn(`kyo-http`)
        .dependsOn(`kyo-zio`)
        .dependsOn(`kyo-zio-test`)
        .withKyoTest
        .settings(
            `kyo-settings`,
            libraryDependencies += "com.github.ghostdogpr"                 %% "caliban"               % "3.1.2",
            libraryDependencies += "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.38.16" % "provided"
        )
        .jvmSettings(mimaCheck(false))

lazy val `kyo-zio-test` =
    crossProject(JVMPlatform, JSPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-zio-test"))
        .dependsOn(`kyo-core`)
        .dependsOn(`kyo-zio`)
        .settings(
            `kyo-settings`,
            libraryDependencies += "dev.zio" %%% "zio"          % zioVersion,
            libraryDependencies += "dev.zio" %%% "zio-test"     % zioVersion,
            libraryDependencies += "dev.zio" %%% "zio-test-sbt" % zioVersion % Test
        )
        .jsSettings(
            `js-settings`
        )
        .nativeSettings(
            `native-settings`
        )
        .jvmSettings(mimaCheck(false))
        .wasmSettings(`wasm-settings`)

lazy val `kyo-zio` =
    crossProject(JVMPlatform, JSPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-zio"))
        .dependsOn(`kyo-core`)
        .withKyoTest
        .settings(
            `kyo-settings`,
            libraryDependencies += "dev.zio" %%% "zio"         % zioVersion,
            libraryDependencies += "dev.zio" %%% "zio-streams" % zioVersion
        )
        .jsSettings(
            `js-settings`
        )
        .nativeSettings(
            `native-settings`
        )
        .jvmSettings(mimaCheck(false))
        .wasmSettings(`wasm-settings`)

lazy val `kyo-compat-future` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-compat/bindings/future"))
        .settings(
            `kyo-settings`,
            release17,
            libraryDependencies += "org.scalatest" %%% "scalatest" % scalaTestVersion % Test,
            // Default compile under scala3Version so unidoc reads consistent TASTy with the rest of the build.
            // `+publish` still only emits LTS artifacts (crossScalaVersions + publish/skip guard).
            crossScalaVersions := List(scala3LTSVersion),
            publish / skip     := scalaVersion.value != scala3LTSVersion,
            scalacOptions += "-Xmax-inlines:1024",
            // Cross-platform: shared sources use atomics + ConcurrentLinkedQueue
            // (both polyfilled on JS and natively supported on Native).
            // Platform-specific source dirs hold the blocking-pool / scheduler
            // pieces that genuinely diverge per platform.
            Test / unmanagedSourceDirectories += {
                (ThisBuild / baseDirectory).value / "kyo-compat" / "test" / "shared" / "src" / "test" / "scala"
            },
            Test / unmanagedSourceDirectories += {
                (ThisBuild / baseDirectory).value / "kyo-compat" / "test-streams" / "shared" / "src" / "test" / "scala"
            }
        )
        .jvmSettings(
            mimaCheck(false),
            Test / unmanagedSourceDirectories += {
                (ThisBuild / baseDirectory).value / "kyo-compat" / "test" / "jvm" / "src" / "test" / "scala"
            },
            Test / unmanagedSourceDirectories += {
                (ThisBuild / baseDirectory).value / "kyo-compat" / "test-streams" / "jvm" / "src" / "test" / "scala"
            }
        )
        .jvmConfigure(_.disablePlugins(KyoDoctestPlugin))
        .jsSettings(`js-settings`, mimaCheck(false))
        .nativeSettings(`native-settings`, mimaCheck(false))
        .wasmSettings(`wasm-settings`)

lazy val `kyo-compat-kyo` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-compat/bindings/kyo"))
        .dependsOn(`kyo-core`, `kyo-data`)
        .settings(
            `kyo-settings`,
            libraryDependencies += "org.scalatest" %%% "scalatest" % scalaTestVersion % Test,
            Test / unmanagedSourceDirectories += {
                (ThisBuild / baseDirectory).value / "kyo-compat" / "test" / "shared" / "src" / "test" / "scala"
            },
            Test / unmanagedSourceDirectories += {
                (ThisBuild / baseDirectory).value / "kyo-compat" / "test-streams" / "shared" / "src" / "test" / "scala"
            }
        )
        .jsSettings(`js-settings`)
        .nativeSettings(`native-settings`)
        .jvmSettings(
            mimaCheck(false),
            Test / unmanagedSourceDirectories += {
                (ThisBuild / baseDirectory).value / "kyo-compat" / "test" / "jvm" / "src" / "test" / "scala"
            },
            Test / unmanagedSourceDirectories += {
                (ThisBuild / baseDirectory).value / "kyo-compat" / "test-streams" / "jvm" / "src" / "test" / "scala"
            }
        )
        .jvmConfigure(_.settings(
            // kyo-compat README lives at kyo-compat/ (three levels up from jvm/)
            doctestSources := Seq(baseDirectory.value / ".." / ".." / ".." / "README.md")
        ))
        .wasmSettings(`wasm-settings`)

lazy val `kyo-compat-zio` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-compat/bindings/zio"))
        .settings(
            `kyo-settings`,
            release17,
            libraryDependencies += "org.scalatest" %%% "scalatest" % scalaTestVersion % Test,
            crossScalaVersions                      := List(scala3LTSVersion),
            publish / skip                          := scalaVersion.value != scala3LTSVersion,
            scalacOptions += "-Xmax-inlines:1024",
            libraryDependencies += "dev.zio" %%% "zio"            % zioVersion,
            libraryDependencies += "dev.zio" %%% "zio-concurrent" % zioVersion,
            libraryDependencies += "dev.zio" %%% "zio-streams"    % zioVersion,
            Test / unmanagedSourceDirectories += {
                (ThisBuild / baseDirectory).value / "kyo-compat" / "test" / "shared" / "src" / "test" / "scala"
            },
            Test / unmanagedSourceDirectories += {
                (ThisBuild / baseDirectory).value / "kyo-compat" / "test-streams" / "shared" / "src" / "test" / "scala"
            }
        )
        .jsSettings(`js-settings`)
        .nativeSettings(`native-settings`)
        .jvmSettings(
            mimaCheck(false),
            Test / unmanagedSourceDirectories += {
                (ThisBuild / baseDirectory).value / "kyo-compat" / "test" / "jvm" / "src" / "test" / "scala"
            },
            Test / unmanagedSourceDirectories += {
                (ThisBuild / baseDirectory).value / "kyo-compat" / "test-streams" / "jvm" / "src" / "test" / "scala"
            }
        )
        .jvmConfigure(_.disablePlugins(KyoDoctestPlugin))
        .wasmSettings(`wasm-settings`)

lazy val `kyo-compat-ox` =
    crossProject(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-compat/bindings/ox"))
        .settings(
            `kyo-settings`,
            release17,
            libraryDependencies += "org.scalatest" %%% "scalatest" % scalaTestVersion % Test,
            crossScalaVersions                      := List(scala3LTSVersion),
            publish / skip                          := scalaVersion.value != scala3LTSVersion,
            scalacOptions += "-Xmax-inlines:1024",
            libraryDependencies += "com.softwaremill.ox" %% "core" % oxVersion,
            Test / unmanagedSourceDirectories += {
                (ThisBuild / baseDirectory).value / "kyo-compat" / "test" / "shared" / "src" / "test" / "scala"
            },
            Test / unmanagedSourceDirectories += {
                (ThisBuild / baseDirectory).value / "kyo-compat" / "test-streams" / "shared" / "src" / "test" / "scala"
            }
        )
        .jvmSettings(
            mimaCheck(false),
            Test / unmanagedSourceDirectories += {
                (ThisBuild / baseDirectory).value / "kyo-compat" / "test" / "jvm" / "src" / "test" / "scala"
            },
            Test / unmanagedSourceDirectories += {
                (ThisBuild / baseDirectory).value / "kyo-compat" / "test-streams" / "jvm" / "src" / "test" / "scala"
            }
        )
        .jvmConfigure(_.disablePlugins(KyoDoctestPlugin))

lazy val `kyo-compat-twitter-future` =
    crossProject(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-compat/bindings/twitter-future"))
        .settings(
            `kyo-settings`,
            release17,
            libraryDependencies += "org.scalatest" %%% "scalatest" % scalaTestVersion % Test,
            crossScalaVersions                      := List(scala3LTSVersion),
            publish / skip                          := scalaVersion.value != scala3LTSVersion,
            scalacOptions += "-Xmax-inlines:1024",
            libraryDependencies += ("com.twitter" %% "util-core" % "24.2.0")
                .exclude("org.scala-lang.modules", "scala-collection-compat_2.13"),
            Test / unmanagedSourceDirectories += {
                (ThisBuild / baseDirectory).value / "kyo-compat" / "test" / "shared" / "src" / "test" / "scala"
            },
            Test / unmanagedSourceDirectories += {
                (ThisBuild / baseDirectory).value / "kyo-compat" / "test-streams" / "shared" / "src" / "test" / "scala"
            }
        )
        .jvmSettings(
            mimaCheck(false),
            Test / unmanagedSourceDirectories += {
                (ThisBuild / baseDirectory).value / "kyo-compat" / "test" / "jvm" / "src" / "test" / "scala"
            },
            Test / unmanagedSourceDirectories += {
                (ThisBuild / baseDirectory).value / "kyo-compat" / "test-streams" / "jvm" / "src" / "test" / "scala"
            }
        )
        .jvmConfigure(_.disablePlugins(KyoDoctestPlugin))

// IDE/navigation anchor for the cross-binding test suite. The same shared+jvm
// test sources are picked up by all 6 bindings via `unmanagedSourceDirectories`;
// this project gives Metals/IntelliJ a single project to associate the folder
// with, compiled against the Future binding by default.
lazy val `kyo-compat-tests` =
    project
        .in(file("kyo-compat/test"))
        .dependsOn(`kyo-compat-future`.jvm)
        .disablePlugins(KyoDoctestPlugin)
        .settings(
            `kyo-settings`,
            release17,
            libraryDependencies += "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
            scalaVersion                           := scala3LTSVersion,
            crossScalaVersions                     := List(scala3LTSVersion),
            scalacOptions += "-Xmax-inlines:1024",
            publish / skip := true,
            mimaCheck(false),
            Test / unmanagedSourceDirectories := Seq(
                baseDirectory.value / "shared" / "src" / "test" / "scala",
                baseDirectory.value / "jvm" / "src" / "test" / "scala"
            )
        )

lazy val `kyo-combinators` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-combinators"))
        .dependsOn(`kyo-core`)
        .withKyoTest
        .settings(`kyo-settings`)
        .jsSettings(`js-settings`)
        .nativeSettings(`native-settings`)
        .jvmSettings(mimaCheck(false))
        .wasmSettings(`wasm-settings`)

lazy val `kyo-case-app` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-case-app"))
        .dependsOn(`kyo-core`)
        .withKyoTest
        .settings(
            `kyo-settings`,
            libraryDependencies += "com.github.alexarchambault" %%% "case-app" % "2.1.0"
        )
        .jsSettings(`js-settings`)
        .nativeSettings(`native-settings`)
        .jvmSettings(mimaCheck(false))
        .wasmSettings(`wasm-settings`)

lazy val `kyo-pod` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-pod"))
        .dependsOn(`kyo-core`, `kyo-http`)
        // Direct, not through kyo-http: `Container.init` opens a host-side TCP connection to prove a published
        // port is actually served before it hands the caller a handle.
        .dependsOn(`kyo-net`)
        .dependsOn(`kyo-system`)
        .withKyoTest
        .settings(
            `kyo-settings`
        )
        .jvmSettings(
            mimaCheck(false),
            // Each suite is forked once by default; suites that exercise a container runtime via
            // `runBackends` / `runBackendsLong` / `runRuntimes` are forked once per runtime instead
            // (KYO_POD_RUNTIME pinned in each fork) so each fork hits a single daemon and the two
            // daemons run concurrently up to the global ForkedTestGroup cap. We auto-detect which
            // suites need the per-runtime split by instantiating each suite at config time and
            // checking whether `Suite.testNames` contains the bracketed runtime markers `[podman]`
            // / `[docker]` registered by those test helpers — no marker trait or naming convention
            // for humans to forget. Brackets ensure no collision with unit-test descriptions that
            // happen to mention "podman" or "docker" as words (e.g. "docker auto-pull progress…").
            Test / testForkedParallel := true,
            Test / testGrouping := {
                val javaOptionsValue = javaOptions.value.toVector
                val envsVarsValue    = envVars.value
                val testSrcDirs      = (Test / unmanagedSourceDirectories).value
                val baseFork = (envOverrides: Map[String, String]) =>
                    ForkOptions(
                        javaHome = javaHome.value,
                        outputStrategy = outputStrategy.value,
                        bootJars = Vector.empty,
                        workingDirectory = Some(baseDirectory.value),
                        runJVMOptions = javaOptionsValue,
                        connectInput = connectInput.value,
                        envVars = envsVarsValue ++ envOverrides
                    )
                (Test / definedTests).value.flatMap { test =>
                    // kyo-test suites cannot be reflectively instantiated to call `testNames` (the runner owns
                    // instantiation via a thread-local). Instead, detect at config time whether the suite's source
                    // uses the marker-registering helpers `runBackends` / `runBackendsLong` / `runRuntimes` (which
                    // register the `[podman]` / `[docker]` runtime scopes). `runBackend` / `runBackendLong`
                    // (single-fork, no marker) are deliberately not matched (the trailing `s` distinguishes them).
                    val simpleName = test.name.split('.').last
                    val srcOpt     = testSrcDirs.flatMap(d => (d ** s"$simpleName.scala").get).headOption
                    // Match actual CALLS to the marker-registering helpers (helper name immediately followed by `{` or `(`),
                    // not mere textual mentions. A suite's scaladoc can reference `runBackends` (ContainerOrchestrationItTest
                    // points readers at ContainerItTest) while the suite itself only uses the single-fork `runBackend`; a plain
                    // `contains` check then forks that http-only suite per runtime and runs it twice against one daemon.
                    val runtimeHelperCall = """\b(runBackendsLong|runBackends|runRuntimes)\s*[{(]""".r
                    val usesRuntimeMarkers = srcOpt.exists { f =>
                        runtimeHelperCall.findFirstIn(IO.read(f)).isDefined
                    }
                    val targetRuntimes = if (usesRuntimeMarkers) Seq("podman", "docker") else Seq.empty
                    if (targetRuntimes.isEmpty)
                        Seq(Tests.Group(
                            name = test.name,
                            tests = Seq(test),
                            runPolicy = Tests.SubProcess(baseFork(Map.empty))
                        ))
                    else
                        targetRuntimes.map { runtime =>
                            Tests.Group(
                                name = s"${test.name}#$runtime",
                                tests = Seq(test),
                                runPolicy = Tests.SubProcess(baseFork(Map("KYO_POD_RUNTIME" -> runtime)))
                            )
                        }
                }
            }
        )
        .nativeSettings(
            `native-settings`,
            nativeConfig ~= { c =>
                val opensslOpts =
                    if (System.getProperty("os.name").toLowerCase.contains("mac")) {
                        val prefix = {
                            val p3 = new java.io.File("/opt/homebrew/opt/openssl@3")
                            val p1 = new java.io.File("/opt/homebrew/opt/openssl")
                            val p0 = new java.io.File("/usr/local/opt/openssl")
                            if (p3.exists()) p3.getAbsolutePath
                            else if (p1.exists()) p1.getAbsolutePath
                            else p0.getAbsolutePath
                        }
                        Seq(s"-L$prefix/lib", s"-I$prefix/include", "-lssl", "-lcrypto")
                    } else Seq("-lssl", "-lcrypto")
                c.withLinkingOptions(c.linkingOptions ++ opensslOpts)
                    .withCompileOptions(c.compileOptions ++ {
                        if (System.getProperty("os.name").toLowerCase.contains("mac")) {
                            val prefix = {
                                val p3 = new java.io.File("/opt/homebrew/opt/openssl@3")
                                val p1 = new java.io.File("/opt/homebrew/opt/openssl")
                                val p0 = new java.io.File("/usr/local/opt/openssl")
                                if (p3.exists()) p3.getAbsolutePath
                                else if (p1.exists()) p1.getAbsolutePath
                                else p0.getAbsolutePath
                            }
                            Seq(s"-I$prefix/include")
                        } else Nil
                    })
            }
        )
        .jsSettings(
            `js-settings`,
            scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
        )
        .wasmSettings(`wasm-settings`)

lazy val `kyo-browser` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-browser"))
        .dependsOn(`kyo-http`, `kyo-jsonrpc`, `kyo-jsonrpc-http`)
        .dependsOn(`kyo-system`)
        .withKyoTest
        .settings(
            `kyo-settings`
        )
        .jvmSettings(
            mimaCheck(false),
            // Per-suite JVM forking: each test suite gets its own JVM (and its own SharedChrome).
            // Cross-suite Chrome state degradation makes a single shared Chrome unstable over 700+ tests
            // in a 10-minute run; isolating each suite eliminates that contamination at the cost of ~3
            // minutes of additional Chrome startup. parallelExecution = false serializes the per-suite
            // groups so Chrome processes don't compete for resources; testForkedParallel = false keeps
            // within-fork tests sequential as a belt-and-braces safeguard. (Running the per-suite forks
            // concurrently was tried and reverted: cores/2 simultaneous Chrome processes starve each other,
            // a Chrome dies, and the dead-Chrome failures cascade -- the very thing the serial mode prevents.)
            Test / parallelExecution  := false,
            Test / testForkedParallel := false,
            Test / testGrouping := {
                val javaOptionsValue = (Test / javaOptions).value.toVector
                val envsVarsValue    = envVars.value
                (Test / definedTests).value map { test =>
                    Tests.Group(
                        name = test.name,
                        tests = Seq(test),
                        runPolicy = Tests.SubProcess(
                            ForkOptions(
                                javaHome = javaHome.value,
                                outputStrategy = outputStrategy.value,
                                bootJars = Vector.empty,
                                workingDirectory = Some(baseDirectory.value),
                                runJVMOptions = javaOptionsValue,
                                connectInput = connectInput.value,
                                envVars = envsVarsValue
                            )
                        )
                    )
                }
            }
        )
        .nativeSettings(
            `native-settings`,
            `openssl-native-settings`,
            // Chrome resource contention makes parallel test-suite execution flaky on Native — serialize
            // suites so each owns the shared Chrome WebSocket channel in turn.
            Test / parallelExecution := false,
            // kyo-browser runs N=10 parallel Async.zip + Scope.ensure chains in its uniqueness tests.
            // The default 8 MB main-thread stack (macOS system default) is insufficient for 10 concurrent
            // fibers each running deep Abort.recover / Scope / CDP send continuations. Set the main-thread
            // stack to 64 MB via the macOS linker's -stack_size flag. On Linux the kernel grows the stack
            // on demand so no linker flag is needed.
            nativeConfig ~= { c =>
                if (System.getProperty("os.name").toLowerCase.contains("mac"))
                    c.withLinkingOptions(
                        c.linkingOptions ++ Seq("-Xlinker", "-stack_size", "-Xlinker", "0x4000000")
                    )
                else c
            }
        )
        .jsSettings(
            `js-settings`,
            scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
        )
        .wasmSettings(`wasm-settings`)

lazy val `kyo-slack` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-slack"))
        .dependsOn(`kyo-http`, `kyo-schema-json`)
        .dependsOn(`kyo-system` % Test)
        .withKyoTest
        .settings(
            `kyo-settings`
        )
        .jvmSettings(
            mimaCheck(false)
        )
        .jsSettings(
            `js-settings`,
            scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
        )
        .nativeSettings(
            `native-settings`,
            `openssl-native-settings`
        )
        .wasmSettings(`wasm-settings`)

lazy val `kyo-markdown` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-markdown"))
        .dependsOn(`kyo-ui`)
        .dependsOn(`kyo-parse`)
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(
            `js-settings`,
            // kyo-ui links as a CommonJS module (its js-wasm sources import scalajs-dom); a
            // downstream test link must match its module kind.
            scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
        )
        .wasmSettings(`wasm-settings`)

lazy val `kyo-i18n` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-i18n"))
        .dependsOn(`kyo-core`)
        .dependsOn(`kyo-system`)
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`)
        .wasmSettings(`wasm-settings`)

lazy val `kyo-ui` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-ui"))
        .dependsOn(`kyo-core`, `kyo-http`)
        .dependsOn(`kyo-browser` % Test)
        .dependsOn(`kyo-system` % Test)
        .withKyoTest
        .settings(
            `kyo-settings`
        )
        .jvmSettings(
            mimaCheck(false),
            // kyo-ui tests drive real Chrome via kyo-browser's SharedChrome. Per-suite JVM forking gives
            // each test class its own JVM and SharedChrome; parallelExecution = false serializes the
            // per-suite groups so the Chrome processes don't compete. Mirrors kyo-browser's jvmSettings.
            Test / parallelExecution  := false,
            Test / testForkedParallel := false,
            Test / testGrouping := {
                val javaOptionsValue = (Test / javaOptions).value.toVector
                val envsVarsValue    = envVars.value
                (Test / definedTests).value map { test =>
                    Tests.Group(
                        name = test.name,
                        tests = Seq(test),
                        runPolicy = Tests.SubProcess(
                            ForkOptions(
                                javaHome = javaHome.value,
                                outputStrategy = outputStrategy.value,
                                bootJars = Vector.empty,
                                workingDirectory = Some(baseDirectory.value),
                                runJVMOptions = javaOptionsValue,
                                connectInput = connectInput.value,
                                envVars = envsVarsValue
                            )
                        )
                    )
                }
            }
        )
        .nativeSettings(
            `native-settings`,
            `openssl-native-settings`,
            // Chrome resource contention makes parallel test-suite execution flaky on Native. Serialize
            // suites so each owns the shared Chrome WebSocket channel in turn.
            Test / parallelExecution := false
        )
        .jsSettings(
            `js-settings`,
            libraryDependencies += "org.scala-js" %%% "scalajs-dom" % "2.8.1",
            scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
        )
        .wasmSettings(
            `wasm-settings`,
            libraryDependencies += "org.scala-js" %%% "scalajs-dom" % "2.8.1"
        )

// The website: shared apps + page wrapper + content model + cross-platform kyo-parse Markdown
// transpiler (DocsMarkdown in shared/, no third-party Markdown dependency). JVM side carries the
// SSG generator; JS side is the browser-mounted chrome. Native is not a target: the generator needs
// one host and the deploy runs on JVM.
lazy val `kyo-website` =
    crossProject(JSPlatform, JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-website"))
        .dependsOn(`kyo-ui`)
        .dependsOn(`kyo-parse`)
        .dependsOn(`kyo-system`)
        .withKyoTest
        .settings(`kyo-settings`)
        .settings(publish / skip := true)
        .disablePlugins(MimaPlugin)
        .jvmSettings(
            // scalameta tokenizers: JVM-only build-time Scala highlighter; must not reach the JS
            // link classpath. WebsiteBuildGraphTest enforces this placement.
            // The exclude on sourcecode resolves the _2.13 vs _3 cross-version conflict that arises
            // because scalameta_3 transitively pulls in trees_2.13 -> common_2.13 -> sourcecode_2.13
            // while the rest of the project uses sourcecode_3.
            libraryDependencies += ("org.scalameta" %% "scalameta" % "4.17.0")
                .exclude("com.lihaoyi", "sourcecode_2.13")
        )
        .jsSettings(
            `js-settings`,
            // The content model shares WebsiteContent with the JVM generator, whose path.read pulls in
            // node:path. Enable module support so the JS test link resolves it, matching kyo-ui. The
            // browser bundle (kyo-website-bundle) re-links as ESModule for Chrome.
            scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
        )

// The single browser-loadable ESModule bundle (chrome only). Its Compile classpath holds
// kyo-website.js + kyo-ui.js so the linked bundle has no Node-only require calls and loads in
// Chrome as `<script type="module">`. fullLinkJS in deploy.
lazy val `kyo-website-bundle` =
    crossProject(JSPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-website-bundle"))
        .dependsOn(`kyo-website`)
        .withKyoTest
        .settings(`kyo-settings`)
        .settings(publish / skip := true)
        .disablePlugins(MimaPlugin)
        .jsSettings(
            `js-settings`,
            scalaJSUseMainModuleInitializer := true,
            Compile / mainClass             := Some("kyo.website.WebsiteBundleMain"),
            scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) }
        )

lazy val `kyo-examples` =
    crossProject(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-examples"))
        .dependsOn(`kyo-http`)
        .dependsOn(`kyo-system`)
        .dependsOn(`kyo-schema-json`)
        .dependsOn(`kyo-direct`)
        .dependsOn(`kyo-core`)
        .dependsOn(`kyo-actor`)
        .disablePlugins(MimaPlugin)
        .settings(
            `kyo-settings`,
            fork := true,
            javaOptions ++= Seq(
                "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.nio=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"
            ),
            Compile / doc / sources := Seq.empty,
            publish / skip          := true
        )

lazy val `kyo-bench` =
    crossProject(JVMPlatform)
        .crossType(CrossType.Pure)
        .in(file("kyo-bench"))
        .enablePlugins(JmhPlugin)
        .dependsOn(`kyo-core`)
        .dependsOn(`kyo-parse`)
        .dependsOn(`kyo-http`)
        .dependsOn(`kyo-schema-json`)
        .dependsOn(`kyo-schema-yaml`)
        .dependsOn(`kyo-stm`)
        .dependsOn(`kyo-direct`)
        .dependsOn(`kyo-scheduler-zio`)
        .disablePlugins(MimaPlugin)
        .jvmConfigure(_.disablePlugins(KyoDoctestPlugin))
        .settings(
            `kyo-settings`,
            publish / skip                          := true,
            libraryDependencies += "org.scalatest" %%% "scalatest" % scalaTestVersion % Test,
            Test / testForkedParallel               := true,
            // Forks each test suite individually
            Test / testGrouping := {
                val javaOptionsValue = javaOptions.value.toVector
                val envsVarsValue    = envVars.value
                (Test / definedTests).value map { test =>
                    Tests.Group(
                        name = test.name,
                        tests = Seq(test),
                        runPolicy = Tests.SubProcess(
                            ForkOptions(
                                javaHome = javaHome.value,
                                outputStrategy = outputStrategy.value,
                                bootJars = Vector.empty,
                                workingDirectory = Some(baseDirectory.value),
                                runJVMOptions = javaOptionsValue,
                                connectInput = connectInput.value,
                                envVars = envsVarsValue
                            )
                        )
                    )
                }
            },
            libraryDependencies += "dev.zio"              %% "izumi-reflect"       % "3.0.9",
            libraryDependencies += "org.typelevel"        %% "cats-effect"         % catsVersion,
            libraryDependencies += "org.typelevel"        %% "log4cats-core"       % "2.8.0",
            libraryDependencies += "org.typelevel"        %% "log4cats-slf4j"      % "2.8.0",
            libraryDependencies += "org.typelevel"        %% "cats-mtl"            % "1.7.0",
            libraryDependencies += "io.github.timwspence" %% "cats-stm"            % "0.13.5",
            libraryDependencies += "com.47deg"            %% "fetch"               % "3.2.1",
            libraryDependencies += "dev.zio"              %% "zio-logging"         % "2.5.3",
            libraryDependencies += "dev.zio"              %% "zio-logging-slf4j2"  % "2.5.3",
            libraryDependencies += "dev.zio"              %% "zio"                 % zioVersion,
            libraryDependencies += "dev.zio"              %% "zio-concurrent"      % zioVersion,
            libraryDependencies += "dev.zio"              %% "zio-query"           % "0.7.8",
            libraryDependencies += "dev.zio"              %% "zio-parser"          % "0.1.11",
            libraryDependencies += "dev.zio"              %% "zio-prelude"         % "1.0.0-RC47",
            libraryDependencies += "co.fs2"               %% "fs2-core"            % "3.13.0",
            libraryDependencies += "org.http4s"           %% "http4s-ember-client" % "1.0.0-M46",
            libraryDependencies += "org.http4s"           %% "http4s-ember-server" % "1.0.0-M46",
            libraryDependencies += "org.http4s"           %% "http4s-dsl"          % "1.0.0-M46",
            libraryDependencies += "dev.zio"              %% "zio-http"            % "3.11.2",
            libraryDependencies += "io.vertx"              % "vertx-core"          % "5.1.3",
            libraryDependencies += "io.vertx"              % "vertx-web"           % "5.1.3",
            // JSON serialization benchmarks
            libraryDependencies += "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core"   % "2.38.16",
            libraryDependencies += "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.38.16" % "provided",
            libraryDependencies += "dev.zio"                               %% "zio-json"              % "0.9.2",
            libraryDependencies += "io.circe"                              %% "circe-core"            % "0.14.15",
            libraryDependencies += "io.circe"                              %% "circe-generic"         % "0.14.15",
            libraryDependencies += "io.circe"                              %% "circe-parser"          % "0.14.15",
            libraryDependencies += "dev.zio"                               %% "zio-blocks-schema"     % "0.017"
        )

lazy val `kyo-doctest` =
    crossProject(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-doctest"))
        .dependsOn(`kyo-core`)
        .dependsOn(`kyo-system`)
        .dependsOn(`kyo-schema-json`)
        .dependsOn(`kyo-parse`)
        .dependsOn(`kyo-direct` % Test)
        .withKyoTest
        .disablePlugins(MimaPlugin)
        .jvmConfigure(_.disablePlugins(KyoDoctestPlugin))
        .settings(
            `kyo-settings`,
            libraryDependencies += "org.scala-lang" %% "scala3-compiler" % scala3Version
        )

// Validates the root README.md (repo-level, outside any module directory).
// The smart default does not reach the repo root from target/root-readme/,
// so doctestSources is overridden to point there explicitly.
lazy val `root-readme` =
    project
        .in(file("target/root-readme"))
        .disablePlugins(MimaPlugin)
        .dependsOn(
            `kyo-core`.jvm,
            `kyo-direct`.jvm,
            `kyo-bench`.jvm,
            `kyo-zio`.jvm,
            `kyo-caliban`.jvm,
            `kyo-combinators`.jvm
        )
        .settings(
            `kyo-settings`,
            publish / skip := true,
            doctestSources := Seq((ThisBuild / baseDirectory).value / "README.md")
        )

// Validates kyo-doctest's own README. kyo-doctest disables KyoDoctestPlugin on itself (a module
// cannot doctest the very library that implements doctest), so a separate project, like root-readme,
// validates that README against the kyo-doctest classpath.
lazy val `kyo-doctest-readme` =
    project
        .in(file("target/kyo-doctest-readme"))
        .disablePlugins(MimaPlugin)
        .dependsOn(`kyo-doctest`.jvm)
        .settings(
            `kyo-settings`,
            publish / skip := true,
            doctestSources := Seq((ThisBuild / baseDirectory).value / "kyo-doctest" / "README.md")
        )

// Validates kyo-test's own README. kyo-test is split into api/runner/prop/snapshot subprojects, none of
// which individually carries the README's combined surface (it uses api assertions, runner reporters/config,
// prop generators, and snapshot helpers), so the per-subproject doctestSources smart-default never reaches
// kyo-test/README.md. A separate project, like root-readme / kyo-doctest-readme, validates that README
// against all four classpaths.
lazy val `kyo-test-readme` =
    project
        .in(file("target/kyo-test-readme"))
        .disablePlugins(MimaPlugin)
        .dependsOn(
            `kyo-test-api`.jvm,
            `kyo-test-runner`.jvm,
            `kyo-test-prop`.jvm,
            `kyo-test-snapshot`.jvm
        )
        .settings(
            `kyo-settings`,
            publish / skip := true,
            doctestSources := Seq((ThisBuild / baseDirectory).value / "kyo-test" / "README.md")
        )

lazy val `openssl-native-settings` = Seq(
    nativeConfig ~= { c =>
        c.withLinkingOptions(c.linkingOptions ++ systemOpensslNativeLinkOpts)
            .withCompileOptions(c.compileOptions ++ systemOpensslNativeCompileOpts)
    }
)

// Reads the FFI native-flag manifests KyoFfiPlugin writes per FFI dependency (one `<module>-<os>.flags` file
// per module under `relDir`), one flag per line, deduped first-seen so a BoringSSL `-I` precedes a later system
// include. A downstream Native module folds a dependency's flags in so the dep's bundled C compiles and links
// the way it does in the owning module (see `native-settings`).
//
// Only THIS target's OS is read. The manifests ride a classpath that also carries published artifacts, and a
// flag set produced for another OS does not merely fail to help: `-luring` and the GNU-ld options ld64 rejects
// break a Darwin link outright.
// Delegates to KyoFfiPlugin, which owns the manifest layout and the in-build / packaged precedence. This build
// reads them here because `native-settings` applies to every Native module, including the many that do not enable
// KyoFfiPlugin and so have no `ffiNativeDependencyLinkingOptions` of their own.
def readFfiNativeManifest(cp: Seq[Attributed[File]], relDir: Seq[String], inBuildRelDir: Seq[String]): Seq[String] =
    KyoFfiPlugin.readNativeFlagManifests(cp.map(_.data), relDir, inBuildRelDir, KyoFfiPlugin.ffiManifestTargetOs)

// Everything a Native row needs that does not assume the project is itself a Scala Native module, so
// the kyoNative aggregate (which has no native sources, hence no Test / nativeLink to transform) can
// take these without the per-module link hook below.
lazy val `native-settings-base` = Seq(
    fork       := false,
    bspEnabled := false,
    // One test task per module, not one per suite. The scala-native TestAdapter keys its runner
    // processes by sbt task thread id, and sbt's cached task pool reaps a thread after 60s idle, so
    // one task per suite gives one FRESH runner process per suite whenever consecutive suites are
    // more than a minute apart. Every kyo-sql suite is, which turned the per-process container
    // singleton into per-suite provisioning: 24 worker processes and ~2 container starts each in one
    // module. Serial tasks keep the module on one thread and therefore one worker. Nothing is lost in
    // concurrency: Native leaf parallelism is already capped at 1 by kyo-test's LeafPool. Matches what
    // `js-settings` does globally and what kyo-aeron already does for Native.
    Test / parallelExecution                          := false,
    Test / testForkedParallel                         := false,
    Test / envVars += "SCALANATIVE_THREAD_STACK_SIZE" -> "33554432",
    libraryDependencies += "io.github.cquiroz"       %%% "scala-java-time" % "2.7.0",
    // Off-JVM these java.time/java.util types exist but carry no data (named zones, locales, currencies), so
    // resolving one throws at run time, invisible to compile and link. These data artifacts supply the data.
    libraryDependencies += "io.github.cquiroz" %%% "scala-java-time-tzdb"       % "2.7.0",
    libraryDependencies += "io.github.cquiroz" %%% "scala-java-locales"         % "1.5.4",
    libraryDependencies += "io.github.cquiroz" %%% "locales-full-currencies-db" % "1.5.4",
    // A dependency's bundled FFI C (kyo-net's kyo_uring.c and TLS shims) compiles into THIS Native binary, but
    // nativeConfig does not propagate across a project dependency, so fold each dep's FFI compile/link flags in
    // here or the link fails (SSL_CTX_ctrl macro / undefined io_uring_*).
    nativeConfig := {
        val base         = nativeConfig.value
        val cp           = (Compile / dependencyClasspath).value
        val linkExtra    = readFfiNativeManifest(cp, KyoFfiPlugin.ffiNativeLinkFlagsDir, KyoFfiPlugin.ffiNativeInBuildLinkFlagsDir)
        val compileExtra = readFfiNativeManifest(cp, KyoFfiPlugin.ffiNativeCompileFlagsDir, KyoFfiPlugin.ffiNativeInBuildCompileFlagsDir)
        val withLink     = if (linkExtra.isEmpty) base else base.withLinkingOptions(base.linkingOptions ++ linkExtra)
        if (compileExtra.isEmpty) withLink else withLink.withCompileOptions(withLink.compileOptions ++ compileExtra)
    }
)

lazy val `native-settings` = `native-settings-base` ++ Seq(
    // Drop this module's Scala Native work directory the moment its test binary links. Scala Native
    // keeps <target>/native-test (IR, objects, unpacked native libraries: 526MB of kyo-core's 665MB
    // workspace) so a LATER invocation can relink incrementally, and the ~13GB the ~40 modules of a
    // Native row accumulate is what carries the row's peak past the free disk of a runner image that
    // started small, killing the runner mid-link with no log at all.
    //
    // Everything except `build-checksum` goes. That file and the linked binary (which sits one level
    // up, outside the work directory) are the entire input to Scala Native's "Build skipped" check, so
    // a repeat link still short-circuits; dropping the rest wholesale is what keeps the directory
    // self-consistent. Deleting only `generated/` did not: it left `package2hash`, the incremental
    // codegen state naming those IR files, behind. A later link that missed the checksum (CI relinks a
    // module whenever the test phase recompiles one of its dependencies) then trusted that state,
    // skipped regenerating every unit whose NIR was unchanged, and handed clang object paths for files
    // nobody had written. Codegen and compilation both reported success and the link died on missing
    // .ll.o with no diagnostic. See issue #1821.
    //
    // Reads the environment rather than insideCI because a value transform sees no other settings; a
    // local build keeps its intermediates for the next incremental link.
    Test / nativeLink ~= { binary =>
        if (sys.env.contains("CI")) {
            val workDir = binary.getParentFile / "native-test"
            IO.listFiles(workDir).filterNot(_.getName == "build-checksum").foreach(IO.delete)
        }
        binary
    }
)

lazy val `js-settings` = Seq(
    Compile / doc / sources                     := Seq.empty,
    fork                                        := false,
    bspEnabled                                  := false,
    Test / parallelExecution                    := false,
    jsEnv                                       := new NodeJSEnv(NodeJSEnv.Config().withArgs(List("--max_old_space_size=5120"))),
    libraryDependencies += "io.github.cquiroz" %%% "scala-java-time" % "2.7.0",
    // Off-JVM these java.time/java.util types exist but carry no data (named zones, locales, currencies), so
    // resolving one throws at run time, invisible to compile and link. These data artifacts supply the data.
    libraryDependencies += "io.github.cquiroz" %%% "scala-java-time-tzdb"       % "2.7.0",
    libraryDependencies += "io.github.cquiroz" %%% "scala-java-locales"         % "1.5.4",
    libraryDependencies += "io.github.cquiroz" %%% "locales-full-currencies-db" % "1.5.4",
    // CI links every module's test binary in one sbt process; retaining each module's incremental
    // linker state overflows the 12G sbt heap now that the schema family links per-format
    // binaries. Batch mode drops that state after each link: incremental relink speed is
    // irrelevant in CI, footprint is what matters.
    scalaJSLinkerConfig := {
        val c = scalaJSLinkerConfig.value
        if (insideCI.value) c.withBatchMode(true) else c
    }
)

// WASM rows are Scala.js compilations: same scala-java-time stand-in for the JDK time APIs,
// emitted as an ESModule (set by WasmPlatform). They require Node 24+: it defaults to V8's
// Turboshaft Wasm pipeline, under which the generated WasmGC code compiles correctly. The legacy
// TurboFan pipeline on Node 22/23 miscompiled it; Node 23 is EOL, and Node 24 made Turboshaft the
// default and removed the --turboshaft-wasm opt-in flag (passing it there is a startup error).
lazy val `wasm-settings` = Seq(
    Compile / doc / sources  := Seq.empty,
    fork                     := false,
    bspEnabled               := false,
    Test / parallelExecution := false,
    jsEnv := new NodeJSEnv(
        NodeJSEnv.Config().withArgs(List(
            "--max_old_space_size=5120",
            // exnref: the WASM backend emits exnref exception-handling opcodes Node needs to load it.
            "--experimental-wasm-exnref"
        ))
    ),
    libraryDependencies += "io.github.cquiroz" %%% "scala-java-time" % "2.7.0",
    // Off-JVM these java.time/java.util types exist but carry no data (named zones, locales, currencies), so
    // resolving one throws at run time, invisible to compile and link. These data artifacts supply the data.
    libraryDependencies += "io.github.cquiroz" %%% "scala-java-time-tzdb"       % "2.7.0",
    libraryDependencies += "io.github.cquiroz" %%% "scala-java-locales"         % "1.5.4",
    libraryDependencies += "io.github.cquiroz" %%% "locales-full-currencies-db" % "1.5.4",
    // Same CI heap rationale as `js-settings`: the WASM rows are Scala.js links too.
    scalaJSLinkerConfig := {
        val c = scalaJSLinkerConfig.value
        if (insideCI.value) c.withBatchMode(true) else c
    }
)

def scalacOptionToken(proposedScalacOption: ScalacOption) =
    scalacOptionTokens(Set(proposedScalacOption))

def scalacOptionTokens(proposedScalacOptions: Set[ScalacOption]) = Def.setting {
    val version = ScalaVersion.fromString(scalaVersion.value).right.get
    ScalacOptions.tokensForVersion(version, proposedScalacOptions)
}

def mimaCheck(failOnProblem: Boolean) =
    Seq(
        mimaPreviousArtifacts ++= previousStableVersion.value.map(organization.value %% name.value % _).toSet,
        mimaBinaryIssueFilters ++= Seq(),
        mimaFailOnProblem := failOnProblem
    )

// --- kyo-doctest-plugin (sbt plugin; pairs with kyo-doctest library)
//
// Scala 2.12 sbt plugin that forks the kyo-doctest library CLI to validate Markdown fences.
// In-tree at kyo-doctest/plugin (same layout as kyo-compat/plugin). Aggregated into kyoJVM only.
// Behavioral tests run via `kyo-doctest-plugin/scripted`.
lazy val `kyo-doctest-plugin` = (project in file("kyo-doctest/plugin"))
    .enablePlugins(SbtPlugin)
    .disablePlugins(KyoDoctestPlugin)
    .settings(
        moduleName         := "kyo-doctest-plugin",
        scalaVersion       := "2.12.20",
        crossScalaVersions := Seq("2.12.20"),
        sbtPlugin          := true,
        // scalafmt-dynamic powers the `doctestFormat` task (rewrite-in-place of README scala
        // blocks using the repo's .scalafmt.conf). Pinned to the .scalafmt.conf version.
        libraryDependencies += "org.scalameta" %% "scalafmt-dynamic" % "3.9.6",
        scriptedLaunchOpts := Seq(
            "-Xmx1024M",
            "-Dplugin.version=" + version.value,
            // Path to the runner-classpath file written by scriptedDependencies below.
            "-Dkyo.doctest.runnerCpFile=" + (target.value / "doctest-runner-cp.txt").getAbsolutePath,
            // The sub-builds compile against the same Scala the runner classpath was built with.
            // Pinning it here rather than in each build.sbt keeps the two from drifting apart, which
            // breaks with a NoSuchMethodError once the two versions disagree on the standard library.
            "-Dkyo.doctest.scalaVersion=" + scala3Version
        ),
        scriptedBufferLog := false,
        // Provide the kyo-doctest runner's built classpath to the scripted forks without ivy
        // resolution (mirrors how kyo-settings injects it into the main build's doctest fork). The
        // path is handed to each scripted sub-build, which reads it into doctestExtraClasspath.
        scriptedDependencies := {
            val compiled  = (Test / compile).value
            val published = publishLocal.value
            val cp        = (`kyo-doctest`.jvm / Compile / fullClasspath).value.files.map(_.getAbsolutePath)
            val cpFile    = target.value / "doctest-runner-cp.txt"
            IO.write(cpFile, cp.mkString(System.lineSeparator))
            (compiled, published)
            ()
        },
        // Run the scripted suite as part of the plugin's regular test task so CI gates it via
        // `kyo-doctest-plugin/test` rather than a bespoke scripted invocation.
        Test / test := (Test / test).dependsOn(Def.taskDyn {
            // Skipped on Windows: sbt's scripted framework boots a nested sbt whose Win32 named-pipe
            // boot-server lock flakily fails to create (error 1336), failing the batch reload before
            // any test runs (sbt/sbt#6777). This plugin's behavior is platform-independent and is
            // fully exercised on Linux/macOS.
            if (sys.props.getOrElse("os.name", "").toLowerCase.contains("win"))
                Def.task(streams.value.log.info("scripted skipped on Windows (sbt#6777 boot-server named-pipe flake)"))
            else
                Def.task((scripted.toTask("")).value)
        }).value
    )

// --- kyo-compat-plugin (in-tree sbt plugin; published as artifact `kyo-compat-plugin`)
//
// First SbtPlugin module in kyo. Scala 2.12 only (sbt 1.x runtime).
// Aggregated into kyoJVM only (not kyoJS/kyoNative, since an sbt plugin
// is a single JVM artifact) so the JVM `ci-release` pass publishes it.
// Its behavioral tests are scripted tests, bound into `test` (below) so the
// regular testKyo 2.12 pass runs them, no bespoke CI step needed.
lazy val `kyo-compat-plugin` = (project in file("kyo-compat/plugin"))
    .enablePlugins(SbtPlugin)
    .disablePlugins(KyoDoctestPlugin)
    .settings(
        moduleName         := "kyo-compat-plugin",
        scalaVersion       := "2.12.20",
        crossScalaVersions := Seq("2.12.20"),
        sbtPlugin          := true,
        // Plugin code adds rows to a `ProjectMatrix` programmatically, so
        // it compiles against sbt-projectmatrix; it also references the
        // %%% macro from sbt-scalajs-crossproject / sbt-scala-native-crossproject's
        // platform-deps shim. Pinned to the same versions as kyo's own
        // project/plugins.sbt so the runtime sbt classloader resolves
        // exactly one copy of each.
        //
        // sbt-scalajs and sbt-scala-native are pinned here even though this project
        // never calls them: the two crossproject plugins each declare a compile-scope
        // dependency on an ancient default (0.6.23 and 0.3.7) that was published only
        // to the sbt and Typesafe ivy repos, never to Maven Central. Without these
        // pins winning conflict resolution, resolving this project reaches those two
        // hosts, and any runner that cannot reach them fails the build.
        addSbtPlugin("com.eed3si9n"       % "sbt-projectmatrix"             % "0.11.0"),
        addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject"      % "1.3.2"),
        addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.3.2"),
        addSbtPlugin("org.scala-js"       % "sbt-scalajs"                   % "1.22.0"),
        addSbtPlugin("org.scala-native"   % "sbt-scala-native"              % "0.5.12"),
        scriptedLaunchOpts := Seq(
            "-Xmx1024M",
            "-Dplugin.version=" + version.value
        ),
        scriptedBufferLog := false,
        // Run the scripted suite as part of the plugin's regular test task (matches
        // kyo-doctest-plugin) so the testKyo 2.12 pass gates it; no bespoke CI step.
        Test / test := (Test / test).dependsOn(Def.taskDyn {
            // Skipped on Windows: sbt's scripted framework boots a nested sbt whose Win32 named-pipe
            // boot-server lock flakily fails to create (error 1336), failing the batch reload before
            // any test runs (sbt/sbt#6777). This plugin's behavior is platform-independent and is
            // fully exercised on Linux/macOS.
            if (sys.props.getOrElse("os.name", "").toLowerCase.contains("win"))
                Def.task(streams.value.log.info("scripted skipped on Windows (sbt#6777 boot-server named-pipe flake)"))
            else
                Def.task((scripted.toTask("")).value)
        }).value,
        // Bundle the cross-binding conformance suite (kyo-compat/test + test-streams)
        // into the plugin jar as resources, plus an INDEX, so an external binding can
        // pull it in via `.compatConformance`. Copied verbatim from the canonical suite
        // the in-repo bindings compile against, so the bundle stays byte-identical.
        Compile / resourceGenerators += Def.task {
            val outDir  = (Compile / resourceManaged).value / "kyo-compat-testkit"
            val suites  = Seq("test" -> "test", "test-streams" -> "streams")
            val buckets = Seq("shared", "jvm", "js", "native")
            val base    = (ThisBuild / baseDirectory).value / "kyo-compat"
            IO.delete(outDir)
            val index     = scala.collection.mutable.ArrayBuffer.empty[String]
            val generated = scala.collection.mutable.ArrayBuffer.empty[File]
            for {
                (suiteDir, suiteTag) <- suites
                bucket               <- buckets
                root = base / suiteDir / bucket / "src" / "test" / "scala"
                if root.exists
                src <- (root ** "*.scala").get
            } {
                val rel   = root.toPath.relativize(src.toPath).toString.replace('\\', '/')
                val scope = s"$suiteTag-$bucket"
                val dest  = outDir / scope / rel
                IO.copyFile(src, dest)
                index += s"$scope\t$rel"
                generated += dest
            }
            val indexFile = outDir / "INDEX"
            IO.write(indexFile, index.sorted.mkString("\n") + "\n")
            generated += indexFile
            generated.toSeq
        }.taskValue
    )

// ===========================================================================
// kyo-test framework modules (additive; consumer modules opt in via .withKyoTest
// as they are migrated). Defined at end of file; sbt lazy vals are order-independent.
// ===========================================================================

lazy val `kyo-test-api` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-data`)
        .dependsOn(`kyo-core`)
        .in(file("kyo-test/api"))
        .settings(
            `kyo-settings`,
            libraryDependencies += "org.scalatest" %%% "scalatest" % scalaTestVersion % Test
        )
        .jvmSettings(
            mimaCheck(false)
        )
        .nativeSettings(
            `native-settings`
        )
        .jsSettings(
            `js-settings`
        )
        .wasmSettings(
            `wasm-settings`
        )

lazy val `kyo-test-runner` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-test-api`)
        .dependsOn(`kyo-scheduler`)
        .enablePlugins(kyo.test.sbt.KyoTestPlugin)
        .in(file("kyo-test/runner"))
        .settings(
            `kyo-settings`,
            libraryDependencies += "org.scalatest" %%% "scalatest" % scalaTestVersion % Test
        )
        .jvmSettings(
            mimaCheck(false),
            Compile / mainClass                   := Some("kyo.test.runner.Cli"),
            libraryDependencies += "org.scala-sbt" % "test-interface" % "1.0" % Provided
        )
        .nativeSettings(
            `native-settings`,
            libraryDependencies += "org.scala-sbt" % "test-interface" % "1.0" % Provided
        )
        .jsSettings(
            `js-settings`,
            libraryDependencies += "org.scala-sbt" % "test-interface" % "1.0" % Provided
        )
        .wasmSettings(
            `wasm-settings`,
            libraryDependencies += "org.scala-sbt" % "test-interface" % "1.0" % Provided
        )

lazy val `kyo-test-prop` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-test-api`)
        .dependsOn(`kyo-data`)
        .dependsOn(`kyo-test-runner` % Test)
        .in(file("kyo-test/prop"))
        .settings(
            `kyo-settings`,
            libraryDependencies += "org.scalatest" %%% "scalatest" % scalaTestVersion % Test
        )
        .jvmSettings(
            mimaCheck(false)
        )
        .nativeSettings(
            `native-settings`
        )
        .jsSettings(
            `js-settings`
        )
        .wasmSettings(
            `wasm-settings`
        )

lazy val `kyo-test-snapshot` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-test-api`)
        .dependsOn(`kyo-data`)
        .dependsOn(`kyo-schema`)
        // SnapshotCodec's presets cover every codec kyo-schema ships, so this module needs
        // all six per-format modules of the split schema family, like kyo-schema-tests.
        .dependsOn(`kyo-schema-json`)
        .dependsOn(`kyo-schema-protobuf`)
        .dependsOn(`kyo-schema-msgpack`)
        .dependsOn(`kyo-schema-bson`)
        .dependsOn(`kyo-schema-ion`)
        .dependsOn(`kyo-schema-yaml`)
        .dependsOn(`kyo-test-prop`)
        .dependsOn(`kyo-test-runner` % Test)
        .in(file("kyo-test/snapshot"))
        .settings(
            `kyo-settings`,
            libraryDependencies += "org.scalatest" %%% "scalatest" % scalaTestVersion % Test
        )
        .jvmSettings(
            mimaCheck(false),
            Compile / unmanagedSourceDirectories +=
                baseDirectory.value.getParentFile / "jvm-native" / "src" / "main" / "scala",
            Test / unmanagedSourceDirectories +=
                baseDirectory.value.getParentFile / "jvm-native" / "src" / "test" / "scala"
        )
        .nativeSettings(
            `native-settings`,
            Compile / unmanagedSourceDirectories +=
                baseDirectory.value.getParentFile / "jvm-native" / "src" / "main" / "scala",
            Test / unmanagedSourceDirectories +=
                baseDirectory.value.getParentFile / "jvm-native" / "src" / "test" / "scala"
        )
        .jsSettings(
            `js-settings`,
            scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
        )
        // WASM keeps WasmPlatform's ESModule linker kind (no CommonJSModule override): the
        // @JSImport("node:fs") snapshot facade resolves as an ESM import under Node.
        .wasmSettings(
            `wasm-settings`
        )

lazy val `kyo-test-sbt` =
    project
        .in(file("kyo-test/sbt"))
        .enablePlugins(SbtPlugin)
        // sbt plugin (Scala 2.12), no README: the doctest plugin has nothing to validate here and otherwise
        // runs scalafmt against unrelated blocks and fails. Disable it as the other plugin modules do.
        .disablePlugins(KyoDoctestPlugin)
        .settings(
            name               := "sbt-kyo-test",
            sbtPlugin          := true,
            scalaVersion       := "2.12.20",
            crossScalaVersions := Seq("2.12.20"),
            // Must never lag project/plugins.sbt: a consumer who takes ScalaJSPlugin through this
            // plugin links kyo's published artifacts with these versions, and Scala.js IR is
            // forward-incompatible. Scala Native NIR has the same directional constraint.
            addSbtPlugin("org.scala-js"     % "sbt-scalajs"      % "1.22.0"),
            addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.12"),
            // Supplies platformDepsCrossVersion, which SbtKyoTestPlugin re-crosses through.
            addSbtPlugin("org.portable-scala" % "sbt-platform-deps" % "1.0.2")
        )

lazy val `kyo-test-sbt-publish` =
    project
        .in(file("kyo-test/sbt-publish"))
        .enablePlugins(SbtPlugin, BuildInfoPlugin)
        .disablePlugins(KyoDoctestPlugin)
        .dependsOn(`kyo-test-sbt`)
        .settings(
            name                                   := "sbt-kyo-test-publish",
            sbtPlugin                              := true,
            scalaVersion                           := "2.12.20",
            crossScalaVersions                     := Seq("2.12.20"),
            buildInfoKeys                          := Seq[BuildInfoKey](BuildInfoKey.map(version) { case (_, v) => ("kyoVersion", v) }),
            buildInfoPackage                       := "kyo.test.sbt",
            buildInfoObject                        := "BuildInfo",
            libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.19" % Test,
            scriptedLaunchOpts := Seq(
                // The native sub-build links a real binary in this JVM; 1G (enough for the other
                // three) OOMs inside nativeLink.
                "-Xmx4G",
                "-Dplugin.version=" + version.value,
                "-Dkyo.scalaVersion=" + scala3Version
            ),
            scriptedBufferLog := false,
            // The sub-builds resolve kyo-test-runner from ivy-local, and publishLocal is not
            // transitive, so the whole classpath closure has to be published first. Derived from the
            // build graph rather than listed: the closure reaches kyo-config through
            // kyo-scheduler -> kyo-stats-registry, which a hand-maintained list silently misses.
            scriptedDependencies := Def.taskDyn {
                val build = thisProjectRef.value.build
                val deps  = buildDependencies.value.classpathTransitive
                val roots = Seq("kyo-test-runnerJVM", "kyo-test-runnerJS", "kyo-test-runnerNative")
                    .map(id => ProjectRef(build, id))
                val closure = roots.flatMap(r => r +: deps.getOrElse(r, Nil)).distinct
                Def.task {
                    publishLocal.all(ScopeFilter(inProjects(closure *))).value
                    (`kyo-test-sbt` / publishLocal).value
                    publishLocal.value
                    ()
                }
            }.value,
            // Gate the suite through the normal test task so CI picks it up with no bespoke step.
            // Skipped on Windows: scripted's nested sbt flakily fails to create its named-pipe boot
            // server there (sbt/sbt#6777), failing the batch reload before any test runs.
            Test / test := (Test / test).dependsOn(Def.taskDyn {
                if (sys.props.getOrElse("os.name", "").toLowerCase.contains("win"))
                    Def.task(streams.value.log.info("scripted skipped on Windows (sbt#6777)"))
                else
                    Def.task((scripted.toTask("")).value)
            }).value
        )
