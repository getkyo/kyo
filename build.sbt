import com.github.sbt.git.SbtGit.GitKeys.useConsoleForROGit
import org.scalajs.jsenv.nodejs.*
import org.typelevel.scalacoptions.ScalacOption
import org.typelevel.scalacoptions.ScalacOptions
import org.typelevel.scalacoptions.ScalaVersion
import sbtdynver.DynVerPlugin.autoImport.*

val scala3Version   = "3.6.4"
val scala212Version = "2.12.20"
val scala213Version = "2.13.15"

val zioVersion       = "2.1.14"
val catsVersion      = "3.5.7"
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
    ScalacOptions.release("11"),
    ScalacOptions.advancedKindProjector
)

ThisBuild / scalaVersion := scala3Version
publish / skip           := true

ThisBuild / organization := "io.getkyo"
ThisBuild / homepage     := Some(url("https://getkyo.io"))
ThisBuild / licenses     := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / developers := List(
    Developer(
        "fwbrasil",
        "Flavio Brasil",
        "fwbrasil@gmail.com",
        url("https://github.com/fwbrasil/")
    )
)

ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"
ThisBuild / sonatypeRepository     := "https://s01.oss.sonatype.org/service/local"
ThisBuild / sonatypeProfileName    := "io.getkyo"

ThisBuild / useConsoleForROGit := (baseDirectory.value / ".git").isFile

Global / commands += Repeat.command

lazy val `kyo-settings` = Seq(
    fork               := true,
    scalaVersion       := scala3Version,
    crossScalaVersions := List(scala3Version),
    scalacOptions ++= scalacOptionTokens(compilerOptions).value,
    Test / scalacOptions --= scalacOptionTokens(Set(ScalacOptions.warnNonUnitStatement)).value,
    scalafmtOnCompile := true,
    scalacOptions += compilerOptionFailDiscard,
    Test / testOptions += Tests.Argument("-oDG"),
    ThisBuild / versionScheme               := Some("early-semver"),
    libraryDependencies += "org.scalatest" %%% "scalatest" % scalaTestVersion % Test,
    Test / javaOptions += "--add-opens=java.base/java.lang=ALL-UNNAMED"
)

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

lazy val kyoJVM = project
    .in(file("."))
    .settings(
        name := "kyoJVM",
        `kyo-settings`
    )
    .disablePlugins(MimaPlugin)
    .aggregate(
        `kyo-scheduler`.jvm,
        `kyo-scheduler-zio`.jvm,
        `kyo-scheduler-cats`.jvm,
        `kyo-scheduler-finagle`.jvm,
        `kyo-scheduler-pekko`.jvm,
        `kyo-data`.jvm,
        `kyo-kernel`.jvm,
        `kyo-prelude`.jvm,
        `kyo-core`.jvm,
        `kyo-offheap`.jvm,
        `kyo-direct`.jvm,
        `kyo-stm`.jvm,
        `kyo-stats-registry`.jvm,
        `kyo-stats-otel`.jvm,
        `kyo-cache`.jvm,
        `kyo-reactive-streams`.jvm,
        `kyo-aeron`.jvm,
        `kyo-sttp`.jvm,
        `kyo-tapir`.jvm,
        `kyo-caliban`.jvm,
        `kyo-bench`.jvm,
        `kyo-zio-test`.jvm,
        `kyo-zio`.jvm,
        `kyo-cats`.jvm,
        `kyo-combinators`.jvm,
        `kyo-examples`.jvm,
        `kyo-monix`.jvm,
        `kyo-actor`.jvm
    )

lazy val kyoJS = project
    .in(file("js"))
    .settings(
        name := "kyoJS",
        `kyo-settings`
    )
    .disablePlugins(MimaPlugin)
    .aggregate(
        `kyo-scheduler`.js,
        `kyo-data`.js,
        `kyo-kernel`.js,
        `kyo-prelude`.js,
        `kyo-core`.js,
        `kyo-direct`.js,
        `kyo-stm`.js,
        `kyo-stats-registry`.js,
        `kyo-sttp`.js,
        `kyo-zio-test`.js,
        `kyo-zio`.js,
        `kyo-cats`.js,
        `kyo-combinators`.js,
        `kyo-actor`.js
    )

lazy val kyoNative = project
    .in(file("native"))
    .settings(
        name := "kyoNative",
        `native-settings`
    )
    .disablePlugins(MimaPlugin)
    .aggregate(
        `kyo-data`.native,
        `kyo-prelude`.native,
        `kyo-kernel`.native,
        `kyo-stats-registry`.native,
        `kyo-scheduler`.native,
        `kyo-core`.native,
        `kyo-offheap`.native,
        `kyo-direct`.native,
        `kyo-combinators`.native,
        `kyo-sttp`.native,
        `kyo-actor`.native
    )

lazy val `kyo-scheduler` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-stats-registry`)
        .in(file("kyo-scheduler"))
        .settings(
            `kyo-settings`,
            scalacOptions ++= scalacOptionToken(ScalacOptions.source3).value,
            crossScalaVersions                     := List(scala3Version, scala212Version, scala213Version),
            libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.5.16" % Test
        )
        .jvmSettings(mimaCheck(false))
        .nativeSettings(
            `native-settings`,
            crossScalaVersions                         := List(scala3Version),
            libraryDependencies += "org.scala-native" %%% "scala-native-java-logging" % "1.0.0"
        )
        .jsSettings(
            `js-settings`,
            libraryDependencies += "org.scala-js" %%% "scala-js-macrotask-executor" % "1.1.1"
        )

lazy val `kyo-scheduler-zio` = sbtcrossproject.CrossProject("kyo-scheduler-zio", file("kyo-scheduler-zio"))(JVMPlatform)
    .withoutSuffixFor(JVMPlatform)
    .crossType(CrossType.Full)
    .dependsOn(`kyo-scheduler`)
    .settings(
        `kyo-settings`,
        libraryDependencies += "dev.zio" %%% "zio" % zioVersion
    )
    .jvmSettings(mimaCheck(false))
    .settings(
        scalacOptions ++= scalacOptionToken(ScalacOptions.source3).value,
        crossScalaVersions := List(scala3Version, scala212Version, scala213Version)
    )

lazy val `kyo-scheduler-cats` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-scheduler`)
        .in(file("kyo-scheduler-cats"))
        .settings(
            `kyo-settings`,
            libraryDependencies += "org.typelevel" %%% "cats-effect" % catsVersion
        )
        .jvmSettings(mimaCheck(false))
        .settings(
            scalacOptions ++= scalacOptionToken(ScalacOptions.source3).value,
            crossScalaVersions := List(scala3Version, scala212Version, scala213Version)
        )

lazy val `kyo-scheduler-pekko` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-scheduler`)
        .in(file("kyo-scheduler-pekko"))
        .settings(
            `kyo-settings`,
            libraryDependencies += "org.apache.pekko" %%% "pekko-actor"   % "1.1.3",
            libraryDependencies += "org.apache.pekko" %%% "pekko-testkit" % "1.1.3" % Test
        )
        .jvmSettings(mimaCheck(false))
        .settings(
            scalacOptions ++= scalacOptionToken(ScalacOptions.source3).value,
            crossScalaVersions := List(scala3Version, scala212Version, scala213Version)
        )

lazy val `kyo-scheduler-finagle` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-scheduler-finagle"))
        .settings(
            `kyo-settings`,
            libraryDependencies ++= {
                if (scalaVersion.value == scala213Version)
                    Seq("com.twitter" %% "finagle-core" % "24.2.0")
                else
                    Seq.empty
            },
            scalacOptions ++= scalacOptionToken(ScalacOptions.source3).value,
            crossScalaVersions := Seq(scala213Version, scala3Version),
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
        .dependsOn(`kyo-scheduler`)

lazy val `kyo-data` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-data"))
        .settings(
            `kyo-settings`,
            libraryDependencies += "com.lihaoyi" %%% "pprint"        % "0.9.0",
            libraryDependencies += "dev.zio"     %%% "izumi-reflect" % "2.3.10" % Test
        )
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`)

lazy val `kyo-kernel` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-data`)
        .in(file("kyo-kernel"))
        .settings(
            `kyo-settings`,
            libraryDependencies += "org.jctools"   % "jctools-core" % "4.0.5",
            libraryDependencies += "org.javassist" % "javassist"    % "3.30.2-GA" % Test
        )
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`)

lazy val `kyo-prelude` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-kernel`)
        .in(file("kyo-prelude"))
        .settings(
            `kyo-settings`,
            libraryDependencies += "dev.zio" %%% "zio-laws-laws" % "1.0.0-RC36" % Test,
            libraryDependencies += "dev.zio" %%% "zio-test-sbt"  % zioVersion   % Test
        )
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
        .settings(
            `kyo-settings`,
            libraryDependencies += "org.jctools"    % "jctools-core"    % "4.0.5",
            libraryDependencies += "org.slf4j"      % "slf4j-api"       % "2.0.16",
            libraryDependencies += "dev.dirs"       % "directories"     % "26",
            libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.5.16" % Test
        )
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(
            `js-settings`,
            libraryDependencies += ("org.scala-js" %%% "scalajs-java-logging" % "1.0.0").cross(CrossVersion.for3Use2_13)
        )

lazy val `kyo-offheap` =
    crossProject(JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-offheap"))
        .dependsOn(`kyo-core`)
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
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
        .settings(
            `kyo-settings`,
            libraryDependencies += "com.github.rssh" %%% "dotty-cps-async" % "0.9.23"
        )
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`)

lazy val `kyo-stm` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-stm"))
        .dependsOn(`kyo-core`)
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
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`)

lazy val `kyo-stats-registry` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-stats-registry"))
        .settings(
            `kyo-settings`,
            scalacOptions ++= scalacOptionToken(ScalacOptions.source3).value,
            libraryDependencies += "org.hdrhistogram" % "HdrHistogram" % "2.2.2",
            crossScalaVersions                       := List(scala3Version, scala212Version, scala213Version)
        )
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`)

lazy val `kyo-stats-otel` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-stats-otel"))
        .dependsOn(`kyo-core`)
        .settings(
            `kyo-settings`,
            libraryDependencies += "io.opentelemetry" % "opentelemetry-api"                % "1.46.0",
            libraryDependencies += "io.opentelemetry" % "opentelemetry-sdk"                % "1.46.0" % Test,
            libraryDependencies += "io.opentelemetry" % "opentelemetry-exporters-inmemory" % "0.9.1"  % Test
        )
        .jvmSettings(mimaCheck(false))

lazy val `kyo-cache` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-cache"))
        .dependsOn(`kyo-core`)
        .settings(
            `kyo-settings`,
            libraryDependencies += "com.github.ben-manes.caffeine" % "caffeine" % "3.1.8"
        )
        .jvmSettings(mimaCheck(false))

lazy val `kyo-reactive-streams` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-reactive-streams"))
        .dependsOn(`kyo-core`)
        .settings(
            `kyo-settings`,
            libraryDependencies ++= Seq(
                "org.reactivestreams" % "reactive-streams"     % "1.0.4",
                "org.reactivestreams" % "reactive-streams-tck" % "1.0.4"    % Test,
                "org.scalatestplus"  %% "testng-7-5"           % "3.2.17.0" % Test
            )
        )
        .jvmSettings(mimaCheck(false))

lazy val `kyo-aeron` =
    crossProject(JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-aeron"))
        .dependsOn(`kyo-core`)
        .settings(
            `kyo-settings`,
            libraryDependencies ++= Seq(
                "io.aeron"     % "aeron-driver" % "1.46.7",
                "io.aeron"     % "aeron-client" % "1.46.7",
                "com.lihaoyi" %% "upickle"      % "4.1.0"
            )
        )
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)

lazy val `kyo-sttp` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-sttp"))
        .dependsOn(`kyo-core`)
        .settings(
            `kyo-settings`,
            libraryDependencies += "com.softwaremill.sttp.client3" %%% "core" % "3.10.2"
        )
        .jsSettings(`js-settings`)
        .nativeSettings(`native-settings`)
        .jvmSettings(mimaCheck(false))

lazy val `kyo-tapir` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-tapir"))
        .dependsOn(`kyo-core`)
        .dependsOn(`kyo-sttp`)
        .settings(
            `kyo-settings`,
            libraryDependencies += "com.softwaremill.sttp.tapir" %% "tapir-core"         % "1.11.12",
            libraryDependencies += "com.softwaremill.sttp.tapir" %% "tapir-netty-server" % "1.11.12"
        )
        .jvmSettings(mimaCheck(false))

lazy val `kyo-caliban` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Pure)
        .in(file("kyo-caliban"))
        .dependsOn(`kyo-core`)
        .dependsOn(`kyo-tapir`)
        .dependsOn(`kyo-zio`)
        .dependsOn(`kyo-zio-test`)
        .dependsOn(`kyo-sttp`)
        .settings(
            `kyo-settings`,
            libraryDependencies += "com.github.ghostdogpr" %% "caliban"       % "2.9.1",
            libraryDependencies += "com.github.ghostdogpr" %% "caliban-tapir" % "2.9.1"
        )
        .jvmSettings(mimaCheck(false))

lazy val `kyo-zio-test` =
    crossProject(JVMPlatform, JSPlatform)
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
        .jvmSettings(mimaCheck(false))

lazy val `kyo-zio` =
    crossProject(JVMPlatform, JSPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-zio"))
        .dependsOn(`kyo-core`)
        .settings(
            `kyo-settings`,
            libraryDependencies += "dev.zio" %%% "zio" % zioVersion
        )
        .jsSettings(
            `js-settings`
        )
        .jvmSettings(mimaCheck(false))

lazy val `kyo-cats` =
    crossProject(JSPlatform, JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-cats"))
        .dependsOn(`kyo-core`)
        .settings(
            `kyo-settings`,
            libraryDependencies += "org.typelevel" %%% "cats-effect" % catsVersion
        )
        .jsSettings(
            `js-settings`
        )
        .jvmSettings(mimaCheck(false))

lazy val `kyo-monix` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-monix"))
        .dependsOn(`kyo-core`)
        .settings(
            `kyo-settings`,
            libraryDependencies += "io.monix" %% "monix" % "3.4.1"
        )
        .jvmSettings(mimaCheck(false))

lazy val `kyo-combinators` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-combinators"))
        .dependsOn(`kyo-core`)
        .settings(`kyo-settings`)
        .jsSettings(`js-settings`)
        .nativeSettings(`native-settings`)
        .jvmSettings(mimaCheck(false))

lazy val `kyo-examples` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-examples"))
        .dependsOn(`kyo-tapir`)
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
            Compile / doc / sources                              := Seq.empty,
            libraryDependencies += "com.softwaremill.sttp.tapir" %% "tapir-json-zio" % "1.11.12"
        )
        .jvmSettings(mimaCheck(false))

lazy val `kyo-bench` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Pure)
        .in(file("kyo-bench"))
        .enablePlugins(JmhPlugin)
        .dependsOn(`kyo-core`)
        .dependsOn(`kyo-sttp`)
        .dependsOn(`kyo-stm`)
        .dependsOn(`kyo-scheduler-zio`)
        .dependsOn(`kyo-scheduler-cats`)
        .disablePlugins(MimaPlugin)
        .settings(
            `kyo-settings`,
            Test / testForkedParallel := true,
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
            libraryDependencies += "dev.zio"              %% "izumi-reflect"       % "2.3.10",
            libraryDependencies += "org.typelevel"        %% "cats-effect"         % catsVersion,
            libraryDependencies += "org.typelevel"        %% "log4cats-core"       % "2.7.0",
            libraryDependencies += "org.typelevel"        %% "log4cats-slf4j"      % "2.7.0",
            libraryDependencies += "org.typelevel"        %% "cats-mtl"            % "1.5.0",
            libraryDependencies += "org.typelevel"        %% "cats-mtl"            % "1.5.0",
            libraryDependencies += "io.github.timwspence" %% "cats-stm"            % "0.13.5",
            libraryDependencies += "com.47deg"            %% "fetch"               % "3.1.2",
            libraryDependencies += "dev.zio"              %% "zio-logging"         % "2.4.0",
            libraryDependencies += "dev.zio"              %% "zio-logging-slf4j2"  % "2.4.0",
            libraryDependencies += "dev.zio"              %% "zio"                 % zioVersion,
            libraryDependencies += "dev.zio"              %% "zio-concurrent"      % zioVersion,
            libraryDependencies += "dev.zio"              %% "zio-query"           % "0.7.6",
            libraryDependencies += "dev.zio"              %% "zio-prelude"         % "1.0.0-RC36",
            libraryDependencies += "co.fs2"               %% "fs2-core"            % "3.11.0",
            libraryDependencies += "org.http4s"           %% "http4s-ember-client" % "0.23.30",
            libraryDependencies += "org.http4s"           %% "http4s-dsl"          % "0.23.30",
            libraryDependencies += "dev.zio"              %% "zio-http"            % "3.0.1",
            libraryDependencies += "io.vertx"              % "vertx-core"          % "5.0.0.CR3",
            libraryDependencies += "io.vertx"              % "vertx-web"           % "5.0.0.CR3"
        )

lazy val rewriteReadmeFile = taskKey[Unit]("Rewrite README file")

addCommandAlias("checkReadme", ";readme/rewriteReadmeFile; readme/mdoc")

lazy val readme =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("target/readme"))
        .enablePlugins(MdocPlugin)
        .settings(
            `kyo-settings`,
            mdocIn  := new File("./../../README-in.md"),
            mdocOut := new File("./../../README-out.md"),
            scalacOptions --= compilerOptionFailDiscard +: scalacOptionTokens(Set(ScalacOptions.warnNonUnitStatement)).value,
            rewriteReadmeFile := {
                val readmeFile       = new File("README.md")
                val targetReadmeFile = new File("target/README-in.md")
                val contents         = IO.read(readmeFile)
                val newContents      = contents.replaceAll("```scala\n", "```scala mdoc:reset\n")
                IO.write(targetReadmeFile, newContents)
            }
        )
        .dependsOn(
            `kyo-core`,
            `kyo-direct`,
            `kyo-cache`,
            `kyo-sttp`,
            `kyo-tapir`,
            `kyo-bench`,
            `kyo-zio`,
            `kyo-cats`,
            `kyo-caliban`,
            `kyo-combinators`
        )
        .settings(
            libraryDependencies += "com.softwaremill.sttp.tapir" %% "tapir-json-zio" % "1.10.7"
        )

lazy val `native-settings` = Seq(
    fork                                        := false,
    bspEnabled                                  := false,
    Test / testForkedParallel                   := false,
    libraryDependencies += "io.github.cquiroz" %%% "scala-java-time" % "2.6.0"
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
