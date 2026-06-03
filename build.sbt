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
        Tags.limit(DoctestTag, 2)
    )
}

lazy val `kyo-settings` = Seq(
    fork               := true,
    scalaVersion       := scala3Version,
    crossScalaVersions := List(scala3Version),
    scalacOptions ++= scalacOptionTokens(compilerOptions).value,
    Test / scalacOptions --= scalacOptionTokens(Set(ScalacOptions.warnNonUnitStatement)).value,
    scalafmtOnCompile := true,
    scalacOptions += compilerOptionFailDiscard,
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
            (LocalProject("kyo-doctest") / Compile / fullClasspath).value
        else
            Seq.empty[Attributed[File]]
    },
    doctestExtraClasspath := {
        if (scalaVersion.value == scala3Version)
            Seq.empty[File]
        else
            (LocalProject("kyo-doctest") / Compile / fullClasspath).value.files
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
        `root-readme`
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
        `kyo-schema`.js,
        `kyo-http`.js,
        `kyo-flow`.js,
        `kyo-browser`.js,
        `kyo-ui`.js,
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
        `kyo-direct`.native,
        `kyo-combinators`.native,
        `kyo-case-app`.native,
        `kyo-reactive-streams`.native,
        `kyo-actor`.native,
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

lazy val `kyo-scheduler` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
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

lazy val `kyo-scheduler-zio` = sbtcrossproject.CrossProject("kyo-scheduler-zio", file("kyo-scheduler-zio"))(JVMPlatform, NativePlatform)
    .withoutSuffixFor(JVMPlatform)
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
        .withoutSuffixFor(JVMPlatform)
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
        .withoutSuffixFor(JVMPlatform)
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
        .withoutSuffixFor(JVMPlatform)
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
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
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

lazy val `kyo-kernel` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
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

lazy val `kyo-prelude` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
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

lazy val `kyo-parse` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-prelude`)
        .withKyoTest
        .in(file("kyo-parse"))
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`)

lazy val `kyo-schema` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-data` % "test->test;compile->compile")
        .in(file("kyo-schema"))
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`)

lazy val `kyo-core` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
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

lazy val `kyo-offheap` =
    crossProject(JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
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

lazy val `kyo-direct` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
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

lazy val `kyo-stm` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-stm"))
        .dependsOn(`kyo-core`)
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`)

lazy val `kyo-actor` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-actor"))
        .dependsOn(`kyo-core`)
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`)

lazy val `kyo-logging-jpl` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-logging-jpl"))
        .dependsOn(`kyo-core`)
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))

lazy val `kyo-logging-slf4j` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
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
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
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

lazy val `kyo-config` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
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

lazy val `kyo-stats-otlp` =
    crossProject(JVMPlatform, JSPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
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

lazy val `kyo-reactive-streams` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
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

lazy val `kyo-aeron` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
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
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
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

lazy val `kyo-flow` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
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

lazy val `kyo-caliban` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
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
    crossProject(JVMPlatform, JSPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
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

lazy val `kyo-zio` =
    crossProject(JVMPlatform, JSPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
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

lazy val `kyo-cats` =
    crossProject(JSPlatform, JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
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
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
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

lazy val `kyo-compat-kyo` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
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

lazy val `kyo-compat-zio` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
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

lazy val `kyo-compat-ce` =
    crossProject(JSPlatform, JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
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
        .withoutSuffixFor(JVMPlatform)
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
        .withoutSuffixFor(JVMPlatform)
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
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-combinators"))
        .dependsOn(`kyo-core`)
        .withKyoTest
        .settings(`kyo-settings`)
        .jsSettings(`js-settings`)
        .nativeSettings(`native-settings`)
        .jvmSettings(mimaCheck(false))

lazy val `kyo-case-app` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
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

lazy val `kyo-pod` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
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

lazy val `kyo-browser` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
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

lazy val `kyo-ui` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
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

lazy val `kyo-examples` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
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
        .withoutSuffixFor(JVMPlatform)
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
        .withoutSuffixFor(JVMPlatform)
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
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
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
                (LocalProject("kyo-prelude") / Compile / fullClasspath).value,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-core") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-prelude") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-core") / Compile / fullClasspath).value
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

lazy val `kyo-test-runner` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
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
                (LocalProject("kyo-prelude") / Compile / fullClasspath).value,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-core") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-prelude") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-core") / Compile / fullClasspath).value
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

lazy val `kyo-test-prop` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
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
                (LocalProject("kyo-prelude") / Compile / fullClasspath).value,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-core") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-prelude") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-core") / Compile / fullClasspath).value
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

lazy val `kyo-test-snapshot` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
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
                (LocalProject("kyo-prelude") / Compile / fullClasspath).value,
            Compile / unmanagedClasspath ++=
                (LocalProject("kyo-core") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-prelude") / Compile / fullClasspath).value,
            Test / unmanagedClasspath ++=
                (LocalProject("kyo-core") / Compile / fullClasspath).value,
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
