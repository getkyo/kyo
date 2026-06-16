import WasmCrossProject.*
import WithKyoTest._
import com.github.sbt.git.SbtGit.GitKeys.useConsoleForROGit
import org.scalajs.jsenv.nodejs.*
import org.typelevel.scalacoptions.ScalacOption
import org.typelevel.scalacoptions.ScalacOptions
import org.typelevel.scalacoptions.ScalaVersion
import sbtdynver.DynVerPlugin.autoImport.*

val scala3Version    = "3.8.3"
val scala3LTSVersion = "3.3.7"
val scala213Version  = "2.13.18"

val zioVersion       = "2.1.24"
val catsVersion      = "3.7.0"
val oxVersion        = "1.0.4"
val scalaTestVersion = "3.2.19"

val compilerOptionFailDiscard = "-Wconf:msg=(unused.*value|discarded.*value|pure.*statement):error"

val compilerOptions = Set(
    ScalacOptions.encoding("utf8"),
    ScalacOptions.feature,
    ScalacOptions.unchecked,
    ScalacOptions.deprecation,
    ScalacOptions.warnValueDiscard,
    ScalacOptions.warnNonUnitStatement,
    ScalacOptions.languageStrictEquality,
    ScalacOptions.release("17"),
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

// Serialize scaladoc generation. Scala 3 dottydoc runs in-process (the `doc` task is not
// forked) and is not safe to run concurrently within one sbt JVM: parallel per-module `doc`
// runs intermittently corrupt shared compiler state and crash with a null
// SignatureBuilder.content() NPE while rendering method signatures. That surfaced as flaky
// `ci-release` failures on main (the per-module Native javadoc step). Every project tags its
// `Compile / doc` with DocTag (see kyo-settings) and concurrentRestrictions caps it at 1, so
// docs build one module at a time while compilation and tests stay parallel.
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
    // Forked-test cap: how many forked test JVMs run concurrently. On CI we hard-cap at 2 so kyo-pod
    // (which splits each suite into a podman fork and a docker fork via KYO_POD_RUNTIME pinning) ends
    // up with at most one fork per daemon — strictly bounding container-daemon contention. Locally
    // we allow up to half the cores for fast iteration; some same-daemon overlap is tolerable.
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
        // Serialize scaladoc: dottydoc shares mutable compiler state across concurrent in-JVM
        // `doc` runs and NPEs intermittently under parallelism. See DocTag above.
        Tags.limit(DocTag, 1)
    )
}

lazy val `kyo-settings` = Seq(
    fork               := true,
    scalaVersion       := scala3Version,
    crossScalaVersions := List(scala3Version),
    scalacOptions ++= scalacOptionTokens(compilerOptions).value,
    Test / scalacOptions --= scalacOptionTokens(Set(ScalacOptions.warnNonUnitStatement)).value,
    scalafmtOnCompile := true,
    // Tag the doc task so concurrentRestrictions can serialize scaladoc across modules; dottydoc
    // is not concurrency-safe in a single sbt JVM. See DocTag and Tags.limit(DocTag, 1) above.
    Compile / doc := (Compile / doc).tag(DocTag).value,
    scalacOptions += compilerOptionFailDiscard,
    // Treat compiler warnings as errors on the Scala 3 series. The Scala 2.13 cross-builds (the kyo-scheduler
    // family) carry a different, noisier warning set that is out of scope, so the flag is gated on Scala 3.
    scalacOptions ++= (if (scalaVersion.value.startsWith("3")) Seq("-Werror") else Nil),
    Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oDG"),
    ThisBuild / versionScheme := Some("early-semver"),
    Test / javaOptions += "--add-opens=java.base/java.lang=ALL-UNNAMED",
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

Global / onLoad := {

    val javaVersion  = System.getProperty("java.version")
    val majorVersion = javaVersion.split("\\.")(0).toInt
    if (majorVersion < 21) {
        throw new IllegalStateException(
            s"Java version $javaVersion is not supported. Please use Java 21 or higher."
        )
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
        `kyo-scheduler-cats`.jvm,
        `kyo-scheduler-finagle`.jvm,
        `kyo-scheduler-pekko`.jvm,
        `kyo-data`.jvm,
        `kyo-kernel`.jvm,
        `kyo-prelude`.jvm,
        `kyo-parse`.jvm,
        `kyo-core`.jvm,
        `kyo-offheap`.jvm,
        `kyo-ffi`.jvm,
        `kyo-ffi-codegen`,
        `kyo-ffi-plugin`,
        `kyo-ffi-bench`,
        `kyo-ffi-it`.jvm,
        `kyo-direct`.jvm,
        `kyo-stm`.jvm,
        `kyo-stats-registry`.jvm,
        `kyo-config`.jvm,
        `kyo-stats-otlp`.jvm,
        `kyo-logging-jpl`.jvm,
        `kyo-logging-slf4j`.jvm,
        `kyo-reactive-streams`.jvm,
        `kyo-aeron`.jvm,
        `kyo-schema`.jvm,
        `kyo-http`.jvm,
        `kyo-flow`.jvm,
        `kyo-caliban`.jvm,
        `kyo-bench`.jvm,
        `kyo-zio-test`.jvm,
        `kyo-zio`.jvm,
        `kyo-cats`.jvm,
        `kyo-combinators`.jvm,
        `kyo-browser`.jvm,
        `kyo-ui`.jvm,
        `kyo-case-app`.jvm,
        `kyo-pod`.jvm,
        `kyo-examples`.jvm,
        `kyo-actor`.jvm,
        `kyo-tasty`.jvm,
        `kyo-tasty-fixtures-internal`.jvm,
        `kyo-compat-future`.jvm,
        `kyo-compat-kyo`.jvm,
        `kyo-compat-zio`.jvm,
        `kyo-compat-ce`.jvm,
        `kyo-compat-ox`.jvm,
        `kyo-compat-twitter-future`.jvm,
        `kyo-compat-plugin`,
        `kyo-doctest`.jvm,
        `kyo-doctest-plugin`,
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
        `kyo-settings`
    )
    .disablePlugins(MimaPlugin, KyoDoctestPlugin)
    .aggregate(
        `kyo-scheduler`.js,
        `kyo-data`.js,
        `kyo-kernel`.js,
        `kyo-prelude`.js,
        `kyo-parse`.js,
        `kyo-core`.js,
        `kyo-ffi`.js,
        `kyo-ffi-it`.js,
        `kyo-direct`.js,
        `kyo-stm`.js,
        `kyo-stats-registry`.js,
        `kyo-config`.js,
        `kyo-reactive-streams`.js,
        `kyo-stats-otlp`.js,
        `kyo-zio-test`.js,
        `kyo-zio`.js,
        `kyo-cats`.js,
        `kyo-combinators`.js,
        `kyo-case-app`.js,
        `kyo-actor`.js,
        `kyo-tasty`.js,
        `kyo-tasty-fixtures-internal`.js,
        `kyo-schema`.js,
        `kyo-http`.js,
        `kyo-flow`.js,
        `kyo-browser`.js,
        `kyo-ui`.js,
        `kyo-website`.js,
        `kyo-website-bundle`.js,
        `kyo-pod`.js,
        `kyo-compat-future`.js,
        `kyo-compat-kyo`.js,
        `kyo-compat-zio`.js,
        `kyo-compat-ce`.js,
        `kyo-test-api`.js,
        `kyo-test-runner`.js,
        `kyo-test-prop`.js,
        `kyo-test-snapshot`.js
    )

lazy val kyoNative = project
    .in(file("native"))
    .settings(
        name := "kyoNative",
        `native-settings`
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
        `kyo-offheap`.native,
        `kyo-ffi`.native,
        `kyo-ffi-it`.native,
        `kyo-direct`.native,
        `kyo-combinators`.native,
        `kyo-case-app`.native,
        `kyo-reactive-streams`.native,
        `kyo-actor`.native,
        `kyo-tasty`.native,
        `kyo-tasty-fixtures-internal`.native,
        `kyo-schema`.native,
        `kyo-http`.native,
        `kyo-flow`.native,
        `kyo-scheduler-zio`.native,
        `kyo-zio`.native,
        `kyo-zio-test`.native,
        `kyo-stm`.native,
        `kyo-stats-otlp`.native,
        `kyo-browser`.native,
        `kyo-ui`.native,
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
        `kyo-settings`
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
        `kyo-scheduler`.wasm,
        `kyo-core`.wasm,
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
        `kyo-stats-otlp`.wasm,
        `kyo-flow`.wasm,
        `kyo-pod`.wasm,
        `kyo-browser`.wasm,
        `kyo-ui`.wasm,
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

lazy val `kyo-scheduler-cats` =
    crossProject(JVMPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-scheduler`)
        .in(file("kyo-scheduler-cats"))
        .settings(
            `kyo-settings`,
            libraryDependencies += "org.typelevel" %%% "cats-effect" % catsVersion,
            libraryDependencies += "org.scalatest" %%% "scalatest"   % scalaTestVersion % Test
        )
        .jvmSettings(mimaCheck(false))
        .settings(
            scalacOptions ++= scalacOptionToken(ScalacOptions.source3).value,
            crossScalaVersions := List(scala3LTSVersion, scala213Version)
        )

lazy val `kyo-scheduler-pekko` =
    crossProject(JVMPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-scheduler`)
        .in(file("kyo-scheduler-pekko"))
        .settings(
            `kyo-settings`,
            libraryDependencies += "org.apache.pekko" %%% "pekko-actor"   % "1.4.0",
            libraryDependencies += "org.apache.pekko" %%% "pekko-testkit" % "1.4.0"          % Test,
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
            libraryDependencies += "com.lihaoyi" %%% "pprint"        % "0.9.6",
            libraryDependencies += "dev.zio"     %%% "izumi-reflect" % "3.0.9" % Test
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
            libraryDependencies += "org.javassist" % "javassist" % "3.30.2-GA" % Test,
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
            libraryDependencies += "dev.zio" %%% "zio-laws-laws" % "1.0.0-RC46" % Test,
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
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`, Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)))
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

lazy val `kyo-offheap` =
    crossProject(JVMPlatform, NativePlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-offheap"))
        .dependsOn(`kyo-core`)
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        .jvmConfigure(_.settings(
            doctestScalacOptions := Seq("-release", "22")
        ))
        .nativeSettings(
            `native-settings`,
            Compile / doc / sources := Seq.empty
        )

lazy val `kyo-ffi` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-ffi"))
        .dependsOn(`kyo-core`)
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(
            mimaCheck(false),
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
        .jsSettings(`js-settings`)

// Declared at top level so the key resolves in the crossProject's native sub-project scope.
lazy val buildKyoItBundled =
    taskKey[File]("Compile kyo-ffi-it bundled C sources into libkyo_it_bundled.{so,dylib,dll} and return its directory.")

lazy val `kyo-ffi-it` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-ffi/it"))
        .enablePlugins(KyoFfiPlugin)
        .dependsOn(`kyo-ffi`)
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
            // Point the JS runtime at the plugin-compiled library via KYO_FFI_<LIBID>_PATH.
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
                val bundled = ffiOut / s"libkyo_it_bundled-$osDetect-$arch.$ext"
                new NodeJSEnv(
                    NodeJSEnv.Config()
                        .withArgs(List("--max_old_space_size=5120"))
                        .withEnv(Map("KYO_FFI_KYO_IT_BUNDLED_PATH" -> bundled.getAbsolutePath))
                )
            },
            // Bootstrap koffi into Node's resolver before tests run. Hooked on Test / compile (not
            // Test / test) so test, testOnly, and testQuick all trigger it, and it re-runs after a
            // clean wipes node_modules. Idempotent on the marker file.
            Test / compile := (Test / compile).dependsOn(Def.task {
                val log        = streams.value.log
                val targetBase = target.value
                val nodeMods   = targetBase / "node_modules"
                val marker     = nodeMods / "koffi" / "package.json"
                // Must match `kyo.ffi.internal.FfiErrors.KoffiSupportedRange`.
                val koffiRange = "^2.7"
                val pjContent =
                    s"""{"name":"kyo-ffi-it-js-test","private":true,"dependencies":{"koffi":"$koffiRange"}}"""
                val pj = targetBase / "package.json"
                if (!pj.exists() || IO.read(pj) != pjContent) {
                    IO.createDirectory(targetBase)
                    IO.write(pj, pjContent)
                }
                if (!marker.exists()) {
                    log.info(s"[kyo-ffi-it JS] installing koffi@$koffiRange into $targetBase ...")
                    val rc = scala.sys.process.Process(
                        Seq("npm", "install", "--no-audit", "--no-fund", "--silent"),
                        targetBase
                    ).!
                    if (rc != 0) sys.error(s"npm install koffi failed (exit $rc)")
                }
            }).value
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
            // Bundle the Scala 3 codegen JAR + its runtime deps as plugin resources so the
            // 2.12 plugin can load them reflectively at task time.
            Compile / resourceGenerators += Def.task {
                val codegenJar = (LocalProject("kyo-ffi-codegen") / Compile / packageBin).value
                val codegenCp = (LocalProject("kyo-ffi-codegen") / Compile / fullClasspath).value
                    .map(_.data)
                val outDir = (Compile / resourceManaged).value / "kyo-ffi-plugin"
                IO.createDirectory(outDir)
                // Names of JARs we must bundle to make the codegen self-sufficient at runtime.
                val names = Set(
                    "scala3-tasty-inspector_3",
                    "tasty-core_3",
                    "scala3-compiler_3",
                    "scala3-interfaces",
                    "scala-asm",
                    "compiler-interface",
                    "util-interface",
                    s"scala-library" // the REAL Scala 3 stdlib is published as `scala-library` at scala3 version
                )
                val bundledJars = codegenCp.filter { f =>
                    val n = f.getName
                    names.exists(prefix => n.startsWith(prefix + "-"))
                }
                val results = Seq(codegenJar -> "kyo-ffi-codegen.jar") ++
                    bundledJars.map(j => j -> j.getName)
                val copied = results.map { case (src, name) =>
                    val dest = outDir / name
                    IO.copyFile(src, dest)
                    dest
                }
                // Write a manifest of bundled JAR names so the plugin can enumerate at runtime.
                val manifest = outDir / "bundled.txt"
                IO.write(manifest, results.map(_._2).mkString("\n"))
                copied :+ manifest
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
            Test / javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED"),
            run / javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED"),
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
            libraryDependencies += "io.github.dotty-cps-async" %%% "dotty-cps-async" % "1.3.2",
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
            // Differential testing against tasty-query 1.7.0. JVM-only because
            // tasty-query's ClasspathLoaders requires java.nio.
            libraryDependencies += "ch.epfl.scala" %% "tasty-query" % "1.7.0" % Test,
            // Real-world classpath fidelity targets. Each jar is intransitive to avoid
            // downloading large transitive closures (Spark: ~5 GB; Play: ~500 MB). kyo-tasty
            // loads only .tasty files in the jar; missing transitive deps produce
            // Symbol.Unresolved stubs (not TastyError entries), so errors.isEmpty holds.
            libraryDependencies += "com.typesafe.akka"  % "akka-actor_3"    % "2.6.20"  % Test intransitive (),
            libraryDependencies += "org.typelevel"     %% "cats-effect"     % "3.7.0"   % Test intransitive (),
            libraryDependencies += "org.http4s"        %% "http4s-core"     % "0.23.28" % Test intransitive (),
            libraryDependencies += "org.apache.pekko"  %% "pekko-actor"     % "1.1.3"   % Test intransitive (),
            libraryDependencies += "org.playframework" %% "play"            % "3.0.2"   % Test intransitive (),
            libraryDependencies += "org.apache.spark"   % "spark-core_2.13" % "3.5.1"   % Test intransitive (),
            libraryDependencies += "org.typelevel"     %% "spire"           % "0.18.0"  % Test intransitive (),
            libraryDependencies += "dev.zio"           %% "zio"             % "2.0.15"  % Test intransitive ()
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
        .settings(`kyo-settings`)
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
            libraryDependencies += "org.slf4j"      % "slf4j-api"       % "2.0.17",
            libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.5.32" % Test
        )
        .jvmSettings(mimaCheck(false))

lazy val `kyo-stats-registry` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-config`)
        .in(file("kyo-stats-registry"))
        .settings(
            `kyo-settings`,
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
            libraryDependencies += "org.scalatest" %%% "scalatest" % scalaTestVersion % Test,
            scalacOptions ++= scalacOptionToken(ScalacOptions.source3).value,
            crossScalaVersions := List(scala3LTSVersion, scala213Version)
        )
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`)
        .wasmSettings(`wasm-settings`)

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

lazy val `kyo-aeron` =
    crossProject(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-aeron"))
        .dependsOn(`kyo-core`)
        .withKyoTest
        .settings(
            `kyo-settings`,
            fork := true,
            javaOptions ++= Seq(
                "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.nio=ALL-UNNAMED",
                "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
            ),
            libraryDependencies ++= Seq(
                "io.aeron"     % "aeron-driver" % "1.50.2",
                "io.aeron"     % "aeron-client" % "1.50.2",
                "com.lihaoyi" %% "upickle"      % "4.4.3"
            )
        )
        .jvmSettings(mimaCheck(false))

lazy val `kyo-http` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-http"))
        .dependsOn(`kyo-core`, `kyo-config`, `kyo-schema`)
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
            libraryDependencies += "com.github.ghostdogpr"                 %% "caliban"               % "3.1.0",
            libraryDependencies += "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.28.2" % "provided"
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

// TODO(wasm): re-enable once cats-effect supports WASM (typelevel/cats-effect#4608).
lazy val `kyo-cats` =
    crossProject(JSPlatform, JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-cats"))
        .dependsOn(`kyo-core`)
        .withKyoTest
        .settings(
            `kyo-settings`,
            libraryDependencies += "org.typelevel" %%% "cats-effect" % catsVersion
        )
        .jsSettings(
            `js-settings`
        )
        .jvmSettings(mimaCheck(false))

lazy val `kyo-compat-future` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-compat/bindings/future"))
        .settings(
            `kyo-settings`,
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

// TODO(wasm): re-enable with cats-effect WASM support; depends on cats-effect (see kyo-cats).
lazy val `kyo-compat-ce` =
    crossProject(JSPlatform, JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-compat/bindings/ce"))
        .settings(
            `kyo-settings`,
            libraryDependencies += "org.scalatest" %%% "scalatest" % scalaTestVersion % Test,
            crossScalaVersions                      := List(scala3LTSVersion),
            publish / skip                          := scalaVersion.value != scala3LTSVersion,
            scalacOptions += "-Xmax-inlines:1024",
            libraryDependencies += "org.typelevel" %%% "cats-effect" % catsVersion,
            libraryDependencies += "co.fs2"        %%% "fs2-core"    % "3.12.2",
            Test / unmanagedSourceDirectories += {
                (ThisBuild / baseDirectory).value / "kyo-compat" / "test" / "shared" / "src" / "test" / "scala"
            },
            Test / unmanagedSourceDirectories += {
                (ThisBuild / baseDirectory).value / "kyo-compat" / "test-streams" / "shared" / "src" / "test" / "scala"
            }
        )
        .jsSettings(`js-settings`)
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

lazy val `kyo-compat-ox` =
    crossProject(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-compat/bindings/ox"))
        .settings(
            `kyo-settings`,
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
                    val usesRuntimeMarkers = srcOpt.exists { f =>
                        val src = IO.read(f)
                        src.contains("runBackends") || src.contains("runRuntimes")
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
        .dependsOn(`kyo-http`)
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
            Test / parallelExecution := false
        )
        .jsSettings(
            `js-settings`,
            scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
        )
        .wasmSettings(`wasm-settings`)

lazy val `kyo-ui` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-ui"))
        .dependsOn(`kyo-core`, `kyo-http`)
        .dependsOn(`kyo-browser` % Test)
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
            libraryDependencies += "org.scala-js" %%% "scalajs-dom" % "2.8.0",
            scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
        )
        .wasmSettings(
            `wasm-settings`,
            libraryDependencies += "org.scala-js" %%% "scalajs-dom" % "2.8.0"
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
            libraryDependencies += ("org.scalameta" %% "scalameta" % "4.13.4")
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
        .dependsOn(`kyo-schema`)
        .dependsOn(`kyo-direct`)
        .dependsOn(`kyo-core`)
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
            Compile / doc / sources := Seq.empty
        )

lazy val `kyo-bench` =
    crossProject(JVMPlatform)
        .crossType(CrossType.Pure)
        .in(file("kyo-bench"))
        .enablePlugins(JmhPlugin)
        .dependsOn(`kyo-core`)
        .dependsOn(`kyo-parse`)
        .dependsOn(`kyo-http`)
        .dependsOn(`kyo-schema`)
        .dependsOn(`kyo-stm`)
        .dependsOn(`kyo-direct`)
        .dependsOn(`kyo-scheduler-zio`)
        .dependsOn(`kyo-scheduler-cats`)
        .disablePlugins(MimaPlugin)
        .jvmConfigure(_.disablePlugins(KyoDoctestPlugin))
        .settings(
            `kyo-settings`,
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
            libraryDependencies += "org.typelevel"        %% "cats-mtl"            % "1.6.0",
            libraryDependencies += "io.github.timwspence" %% "cats-stm"            % "0.13.5",
            libraryDependencies += "com.47deg"            %% "fetch"               % "3.2.1",
            libraryDependencies += "dev.zio"              %% "zio-logging"         % "2.5.3",
            libraryDependencies += "dev.zio"              %% "zio-logging-slf4j2"  % "2.5.3",
            libraryDependencies += "dev.zio"              %% "zio"                 % zioVersion,
            libraryDependencies += "dev.zio"              %% "zio-concurrent"      % zioVersion,
            libraryDependencies += "dev.zio"              %% "zio-query"           % "0.7.7",
            libraryDependencies += "dev.zio"              %% "zio-parser"          % "0.1.11",
            libraryDependencies += "dev.zio"              %% "zio-prelude"         % "1.0.0-RC45",
            libraryDependencies += "co.fs2"               %% "fs2-core"            % "3.12.2",
            libraryDependencies += "org.http4s"           %% "http4s-ember-client" % "1.0.0-M44",
            libraryDependencies += "org.http4s"           %% "http4s-ember-server" % "1.0.0-M44",
            libraryDependencies += "org.http4s"           %% "http4s-dsl"          % "1.0.0-M44",
            libraryDependencies += "dev.zio"              %% "zio-http"            % "3.8.0",
            libraryDependencies += "io.vertx"              % "vertx-core"          % "5.0.7",
            libraryDependencies += "io.vertx"              % "vertx-web"           % "5.0.7",
            // JSON serialization benchmarks
            libraryDependencies += "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core"   % "2.28.2",
            libraryDependencies += "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.28.2" % "provided",
            libraryDependencies += "dev.zio"                               %% "zio-json"              % "0.7.45",
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
        .dependsOn(`kyo-schema`)
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
            `kyo-cats`.jvm,
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
        val isMac = System.getProperty("os.name").toLowerCase.contains("mac")
        val opensslPrefix =
            if (isMac) {
                val p3 = new java.io.File("/opt/homebrew/opt/openssl@3")
                val p1 = new java.io.File("/opt/homebrew/opt/openssl")
                val p0 = new java.io.File("/usr/local/opt/openssl")
                Some(if (p3.exists()) p3.getAbsolutePath else if (p1.exists()) p1.getAbsolutePath else p0.getAbsolutePath)
            } else None
        val linkOpts    = opensslPrefix.map(p => Seq(s"-L$p/lib", "-lssl", "-lcrypto")).getOrElse(Seq("-lssl", "-lcrypto"))
        val compileOpts = opensslPrefix.map(p => Seq(s"-I$p/include")).getOrElse(Nil)
        c.withLinkingOptions(c.linkingOptions ++ linkOpts)
            .withCompileOptions(c.compileOptions ++ compileOpts)
    }
)

lazy val `native-settings` = Seq(
    fork                                              := false,
    bspEnabled                                        := false,
    Test / testForkedParallel                         := false,
    Test / envVars += "SCALANATIVE_THREAD_STACK_SIZE" -> "33554432",
    libraryDependencies += "io.github.cquiroz"       %%% "scala-java-time" % "2.6.0"
)

lazy val `js-settings` = Seq(
    Compile / doc / sources                     := Seq.empty,
    fork                                        := false,
    bspEnabled                                  := false,
    Test / parallelExecution                    := false,
    jsEnv                                       := new NodeJSEnv(NodeJSEnv.Config().withArgs(List("--max_old_space_size=5120"))),
    libraryDependencies += "io.github.cquiroz" %%% "scala-java-time" % "2.6.0"
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
    libraryDependencies += "io.github.cquiroz" %%% "scala-java-time" % "2.6.0"
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
            "-Dkyo.doctest.runnerCpFile=" + (target.value / "doctest-runner-cp.txt").getAbsolutePath
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
        Test / test := (Test / test).dependsOn(scripted.toTask("")).value
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
        addSbtPlugin("com.eed3si9n"       % "sbt-projectmatrix"             % "0.10.1"),
        addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject"      % "1.3.2"),
        addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.3.2"),
        scriptedLaunchOpts := Seq(
            "-Xmx1024M",
            "-Dplugin.version=" + version.value
        ),
        scriptedBufferLog := false,
        // Run the scripted suite as part of the plugin's regular test task (matches
        // kyo-doctest-plugin) so the testKyo 2.12 pass gates it; no bespoke CI step.
        Test / test := (Test / test).dependsOn(scripted.toTask("")).value
    )

// ===========================================================================
// kyo-test framework modules (additive; consumer modules opt in via .withKyoTest
// as they are migrated). Defined at end of file; sbt lazy vals are order-independent.
// ===========================================================================

lazy val `kyo-test-api` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-data`)
        .in(file("kyo-test/api"))
        .settings(
            `kyo-settings`,
            libraryDependencies += "org.scalatest" %%% "scalatest" % scalaTestVersion % Test
        )
        .jvmSettings(
            mimaCheck(false),
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-preludeJVM") / Compile / fullClasspath).value,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-coreJVM") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-preludeJVM") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-coreJVM") / Compile / fullClasspath).value
        )
        .nativeSettings(
            `native-settings`,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-preludeNative") / Compile / fullClasspath).value,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-coreNative") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-preludeNative") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-coreNative") / Compile / fullClasspath).value
        )
        .jsSettings(
            `js-settings`,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-preludeJS") / Compile / fullClasspath).value,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-coreJS") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-preludeJS") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-coreJS") / Compile / fullClasspath).value
        )
        .wasmSettings(
            `wasm-settings`,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-preludeWasm") / Compile / fullClasspath).value,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-coreWasm") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-preludeWasm") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-coreWasm") / Compile / fullClasspath).value
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
            mainClass                             := Some("kyo.test.runner.Cli"),
            libraryDependencies += "org.scala-sbt" % "test-interface" % "1.0" % Provided,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-preludeJVM") / Compile / fullClasspath).value,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-coreJVM") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-preludeJVM") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-coreJVM") / Compile / fullClasspath).value
        )
        .nativeSettings(
            `native-settings`,
            libraryDependencies += "org.scala-sbt" % "test-interface" % "1.0" % Provided,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-preludeNative") / Compile / fullClasspath).value,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-coreNative") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-preludeNative") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-coreNative") / Compile / fullClasspath).value
        )
        .jsSettings(
            `js-settings`,
            libraryDependencies += "org.scala-sbt" % "test-interface" % "1.0" % Provided,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-preludeJS") / Compile / fullClasspath).value,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-coreJS") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-preludeJS") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-coreJS") / Compile / fullClasspath).value
        )
        .wasmSettings(
            `wasm-settings`,
            libraryDependencies += "org.scala-sbt" % "test-interface" % "1.0" % Provided,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-preludeWasm") / Compile / fullClasspath).value,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-coreWasm") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-preludeWasm") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-coreWasm") / Compile / fullClasspath).value
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
            mimaCheck(false),
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-preludeJVM") / Compile / fullClasspath).value,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-coreJVM") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-preludeJVM") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-coreJVM") / Compile / fullClasspath).value
        )
        .nativeSettings(
            `native-settings`,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-preludeNative") / Compile / fullClasspath).value,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-coreNative") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-preludeNative") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-coreNative") / Compile / fullClasspath).value
        )
        .jsSettings(
            `js-settings`,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-preludeJS") / Compile / fullClasspath).value,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-coreJS") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-preludeJS") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-coreJS") / Compile / fullClasspath).value
        )
        .wasmSettings(
            `wasm-settings`,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-preludeWasm") / Compile / fullClasspath).value,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-coreWasm") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-preludeWasm") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-coreWasm") / Compile / fullClasspath).value
        )

lazy val `kyo-test-snapshot` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-test-api`)
        .dependsOn(`kyo-data`)
        .dependsOn(`kyo-test-runner` % Test)
        .in(file("kyo-test/snapshot"))
        .settings(
            `kyo-settings`,
            libraryDependencies += "org.scalatest" %%% "scalatest" % scalaTestVersion % Test
        )
        .jvmSettings(
            mimaCheck(false),
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-preludeJVM") / Compile / fullClasspath).value,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-coreJVM") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-preludeJVM") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-coreJVM") / Compile / fullClasspath).value,
            Compile / unmanagedSourceDirectories +=
                baseDirectory.value.getParentFile / "jvm-native" / "src" / "main" / "scala",
            Test / unmanagedSourceDirectories +=
                baseDirectory.value.getParentFile / "jvm-native" / "src" / "test" / "scala"
        )
        .nativeSettings(
            `native-settings`,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-preludeNative") / Compile / fullClasspath).value,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-coreNative") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-preludeNative") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-coreNative") / Compile / fullClasspath).value,
            Compile / unmanagedSourceDirectories +=
                baseDirectory.value.getParentFile / "jvm-native" / "src" / "main" / "scala",
            Test / unmanagedSourceDirectories +=
                baseDirectory.value.getParentFile / "jvm-native" / "src" / "test" / "scala"
        )
        .jsSettings(
            `js-settings`,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-preludeJS") / Compile / fullClasspath).value,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-coreJS") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-preludeJS") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-coreJS") / Compile / fullClasspath).value,
            scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
        )
        // WASM keeps WasmPlatform's ESModule linker kind (no CommonJSModule override): the
        // @JSImport("node:fs") snapshot facade resolves as an ESM import under Node.
        .wasmSettings(
            `wasm-settings`,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-preludeWasm") / Compile / fullClasspath).value,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-coreWasm") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-preludeWasm") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-coreWasm") / Compile / fullClasspath).value
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
            addSbtPlugin("org.scala-js"     % "sbt-scalajs"      % "1.21.0"),
            addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.10")
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
            libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.19" % Test
        )
