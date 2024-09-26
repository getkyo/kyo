import com.github.sbt.git.SbtGit.GitKeys.useConsoleForROGit
import org.scalajs.jsenv.nodejs.*
import org.typelevel.scalacoptions.ScalacOption
import org.typelevel.scalacoptions.ScalacOptions
import org.typelevel.scalacoptions.ScalaVersion

val scala3Version   = "3.5.1"
val scala212Version = "2.12.20"
val scala213Version = "2.13.14"

val zioVersion       = "2.1.9"
val catsVersion      = "3.5.4"
val scalaTestVersion = "3.2.19"

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

lazy val `kyo-settings` = Seq(
    fork               := true,
    scalaVersion       := scala3Version,
    crossScalaVersions := List(scala3Version),
    scalacOptions ++= scalacOptionTokens(compilerOptions).value,
    Test / scalacOptions --= scalacOptionTokens(Set(ScalacOptions.warnNonUnitStatement)).value,
    scalafmtOnCompile := true,
    Test / testOptions += Tests.Argument("-oDG"),
    ThisBuild / versionScheme               := Some("early-semver"),
    libraryDependencies += "org.scalatest" %%% "scalatest" % scalaTestVersion % Test,
    Test / javaOptions += "--add-opens=java.base/java.lang=ALL-UNNAMED"
)

Global / onLoad := {
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
        `kyo-settings`,
        crossScalaVersions := Seq.empty
    )
    .aggregate(
        `kyo-scheduler`.jvm,
        `kyo-scheduler-zio`.jvm,
        `kyo-tag`.jvm,
        `kyo-data`.jvm,
        `kyo-prelude`.jvm,
        `kyo-core`.jvm,
        `kyo-direct`.jvm,
        `kyo-stats-registry`.jvm,
        `kyo-stats-otel`.jvm,
        `kyo-cache`.jvm,
        `kyo-sttp`.jvm,
        `kyo-tapir`.jvm,
        `kyo-caliban`.jvm,
        `kyo-bench`.jvm,
        `kyo-test`.jvm,
        `kyo-zio`.jvm,
        `kyo-cats`.jvm,
        `kyo-combinators`.jvm,
        `kyo-grpc`.jvm,
        `kyo-examples`.jvm
    )

lazy val kyoJS = project
    .in(file("js"))
    .settings(
        name := "kyoJS",
        `kyo-settings`,
        crossScalaVersions := Seq.empty
    )
    .aggregate(
        `kyo-scheduler`.js,
        `kyo-tag`.js,
        `kyo-data`.js,
        `kyo-prelude`.js,
        `kyo-core`.js,
        `kyo-direct`.js,
        `kyo-stats-registry`.js,
        `kyo-sttp`.js,
        `kyo-test`.js,
        `kyo-zio`.js,
        `kyo-cats`.js,
        `kyo-combinators`.js,
	`kyo-grpc`.js
    )

lazy val kyoNative = project
    .in(file("native"))
    .settings(
        name := "kyoNative",
        `kyo-settings`
    )
    .aggregate(
        `kyo-tag`.native,
        `kyo-data`.native,
        `kyo-prelude`.native
    )

lazy val `kyo-scheduler` =
    crossProject(JSPlatform, JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-stats-registry`)
        .in(file("kyo-scheduler"))
        .settings(
            `kyo-settings`,
            scalacOptions ++= scalacOptionToken(ScalacOptions.source3).value,
            Test / scalacOptions --= scalacOptionToken(ScalacOptions.languageStrictEquality).value,
            crossScalaVersions                      := List(scala3Version, scala212Version, scala213Version),
            libraryDependencies += "org.scalatest" %%% "scalatest"       % scalaTestVersion % Test,
            libraryDependencies += "ch.qos.logback"  % "logback-classic" % "1.5.8"          % Test
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
        libraryDependencies += "dev.zio"       %%% "zio"       % zioVersion,
        libraryDependencies += "org.scalatest" %%% "scalatest" % scalaTestVersion % Test
    )
    .settings(
        scalacOptions ++= scalacOptionToken(ScalacOptions.source3).value,
        crossScalaVersions := List(scala3Version, scala212Version, scala213Version)
    )

lazy val `kyo-tag` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-tag"))
        .settings(
            `kyo-settings`,
            libraryDependencies += "org.scalatest" %%% "scalatest"     % scalaTestVersion % Test,
            libraryDependencies += "dev.zio"       %%% "izumi-reflect" % "2.3.10"         % Test
        )
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`)

lazy val `kyo-data` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-tag`)
        .in(file("kyo-data"))
        .settings(
            `kyo-settings`,
            libraryDependencies += "dev.zio" %%% "zio-test-sbt" % zioVersion % Test
        )
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`)

lazy val `kyo-prelude` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-data`)
        .in(file("kyo-prelude"))
        .settings(
            `kyo-settings`,
            libraryDependencies += "com.lihaoyi" %%% "pprint"        % "0.9.0",
            libraryDependencies += "org.jctools"   % "jctools-core"  % "4.0.5",
            libraryDependencies += "dev.zio"     %%% "zio-laws-laws" % "1.0.0-RC31" % Test,
            libraryDependencies += "dev.zio"     %%% "zio-test-sbt"  % zioVersion   % Test,
            libraryDependencies += "org.javassist" % "javassist"     % "3.30.2-GA"  % Test
        )
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`)

lazy val `kyo-core` =
    crossProject(JSPlatform, JVMPlatform)
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
            libraryDependencies += "dev.zio"      %%% "zio-laws-laws"   % "1.0.0-RC31" % Test,
            libraryDependencies += "dev.zio"      %%% "zio-test-sbt"    % "2.1.9"      % Test,
            libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.5.8"      % Test,
            libraryDependencies += "org.javassist"  % "javassist"       % "3.30.2-GA"  % Test
        )
        .jsSettings(`js-settings`)

lazy val `kyo-direct` =
    crossProject(JSPlatform, JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-direct"))
        .dependsOn(`kyo-core`)
        .settings(
            `kyo-settings`,
            libraryDependencies += "com.github.rssh" %%% "dotty-cps-async" % "0.9.22"
        )
        .jsSettings(`js-settings`)

lazy val `kyo-stats-registry` =
    crossProject(JSPlatform, JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-stats-registry"))
        .settings(
            `kyo-settings`,
            scalacOptions ++= scalacOptionToken(ScalacOptions.source3).value,
            scalacOptions --= scalacOptionToken(ScalacOptions.languageStrictEquality).value,
            libraryDependencies += "org.hdrhistogram" % "HdrHistogram" % "2.2.2",
            libraryDependencies += "org.scalatest"  %%% "scalatest"    % scalaTestVersion % Test,
            crossScalaVersions                       := List(scala3Version, scala212Version, scala213Version)
        )
        .jsSettings(`js-settings`)

lazy val `kyo-stats-otel` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-stats-otel"))
        .dependsOn(`kyo-core`)
        .settings(
            `kyo-settings`,
            libraryDependencies += "io.opentelemetry" % "opentelemetry-api"                % "1.42.1",
            libraryDependencies += "io.opentelemetry" % "opentelemetry-sdk"                % "1.42.1" % Test,
            libraryDependencies += "io.opentelemetry" % "opentelemetry-exporters-inmemory" % "0.9.1"  % Test
        )

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

lazy val `kyo-sttp` =
    crossProject(JSPlatform, JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-sttp"))
        .dependsOn(`kyo-core`)
        .settings(
            `kyo-settings`,
            libraryDependencies += "com.softwaremill.sttp.client3" %%% "core" % "3.9.8"
        )
        .jsSettings(`js-settings`)

lazy val `kyo-tapir` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-tapir"))
        .dependsOn(`kyo-core`)
        .dependsOn(`kyo-sttp`)
        .settings(
            `kyo-settings`,
            libraryDependencies += "com.softwaremill.sttp.tapir" %% "tapir-core"         % "1.11.5",
            libraryDependencies += "com.softwaremill.sttp.tapir" %% "tapir-netty-server" % "1.11.5"
        )

lazy val `kyo-caliban` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Pure)
        .in(file("kyo-caliban"))
        .dependsOn(`kyo-core`)
        .dependsOn(`kyo-tapir`)
        .dependsOn(`kyo-zio`)
        .dependsOn(`kyo-sttp`)
        .settings(
            `kyo-settings`,
            libraryDependencies += "com.github.ghostdogpr"       %% "caliban"        % "2.8.1",
            libraryDependencies += "com.github.ghostdogpr"       %% "caliban-tapir"  % "2.8.1",
            libraryDependencies += "com.softwaremill.sttp.tapir" %% "tapir-json-zio" % "1.11.5" % Test
        )

lazy val `kyo-test` =
    crossProject(JVMPlatform, JSPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-test"))
        .dependsOn(`kyo-core`)
        .dependsOn(`kyo-zio`)
        .settings(
            `kyo-settings`,
            libraryDependencies += "dev.zio" %%% "zio"          % zioVersion,
            libraryDependencies += "dev.zio" %%% "zio-test"     % zioVersion,
            libraryDependencies += "dev.zio" %%% "zio-test-sbt" % zioVersion % Test
        ).jsSettings(
            `js-settings`
        )

lazy val `kyo-zio` =
    crossProject(JVMPlatform, JSPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-zio"))
        .dependsOn(`kyo-core`)
        .settings(
            `kyo-settings`,
            libraryDependencies += "dev.zio" %%% "zio"          % zioVersion,
            libraryDependencies += "dev.zio" %%% "zio-test"     % zioVersion,
            libraryDependencies += "dev.zio" %%% "zio-test-sbt" % zioVersion % Test
        ).jsSettings(
            `js-settings`
        )

lazy val `kyo-cats` =
    crossProject(JSPlatform, JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-cats"))
        .dependsOn(`kyo-core`)
        .settings(
            `kyo-settings`,
            libraryDependencies += "org.typelevel" %%% "cats-effect" % catsVersion
        ).jsSettings(
            `js-settings`
        ).jvmSettings()

lazy val `kyo-grpc` =
    crossProject(JVMPlatform, JSPlatform)
        .withoutSuffixFor(JVMPlatform)
        .settings(
            crossScalaVersions := Seq.empty,
            publishArtifact    := false,
            publish            := {},
            publishLocal       := {}
        )
        .aggregate(
            `kyo-grpc-core`,
            `kyo-grpc-code-gen`,
            `kyo-grpc-e2e`
        )

lazy val `kyo-grpc-jvm` =
    `kyo-grpc`
        .jvm
        .aggregate(`protoc-gen-kyo-grpc`.componentProjects.map(p => p: ProjectReference) *)

// TODO: Split this into client and server
lazy val `kyo-grpc-core` =
    crossProject(JVMPlatform, JSPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-grpc") / "core")
        .dependsOn(`kyo-core`)
        .settings(`kyo-settings`)
        .jvmSettings(
            libraryDependencies ++= Seq(
                "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
                "io.grpc"               % "grpc-api"             % "1.64.0",
                // It is a little unusual to include this here but it greatly reduces the amount of generated code.
                "io.grpc" % "grpc-stub" % "1.64.0"
            )
        ).jsSettings(
            `js-settings`,
            libraryDependencies ++= Seq( //
                "com.thesamet.scalapb.grpcweb" %%% "scalapb-grpcweb" % "0.7.0")
        )

// TODO: Split this into shared, client, and server
// TODO: Do we need code gen for JS?
lazy val `kyo-grpc-code-gen` =
    crossProject(JVMPlatform, JSPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-grpc") / "code-gen")
        .enablePlugins(BuildInfoPlugin)
        .settings(
            `kyo-settings`,
            buildInfoKeys := Seq[BuildInfoKey](name, organization, version, scalaVersion, sbtVersion),
            // TODO: What package to use here?
            buildInfoPackage := "kyo.grpc.compiler",
            // TODO: Which versions should this be for?
            crossScalaVersions := List(scala212Version, scala213Version, scala3Version),
            scalacOptions ++= scalacOptionToken(ScalacOptions.source3).value,
            libraryDependencies ++= Seq(
                "com.thesamet.scalapb"    %% "compilerplugin"          % scalapb.compiler.Version.scalapbVersion,
                "org.scala-lang.modules" %%% "scala-collection-compat" % "2.12.0",
                "org.typelevel"          %%% "paiges-core"             % "0.4.3"
            )
        ).jsSettings(
            `js-settings`
        )

lazy val `kyo-grpc-code-gen_2.12` =
    `kyo-grpc-code-gen`
        .jvm
        .settings(scalaVersion := scala212Version)

lazy val `kyo-grpc-code-genJS_2.12` =
    `kyo-grpc-code-gen`
        .js
        .settings(scalaVersion := scala212Version)

// TODO: Why this name?
// TODO: Can these meta projects be in the sub directory?
lazy val `protoc-gen-kyo-grpc` =
    protocGenProject("protoc-gen-kyo-grpc", `kyo-grpc-code-gen_2.12`)
        .settings(
            `kyo-settings`,
            scalaVersion       := scala212Version,
            crossScalaVersions := Seq(scala212Version),
            // TODO: Does it not auto-discover it?
            Compile / mainClass := Some("kyo.grpc.compiler.CodeGenerator")
        )
        .aggregateProjectSettings(
            scalaVersion       := scala212Version,
            crossScalaVersions := Seq(scala212Version)
        )

lazy val `kyo-grpc-e2e` =
    crossProject(JVMPlatform, JSPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-grpc") / "e2e")
        .enablePlugins(LocalCodeGenPlugin)
        .dependsOn(`kyo-grpc-core`)
        .settings(
            `kyo-settings`,
            publish / skip := true,
            Compile / PB.protoSources += sharedSourceDir("main").value / "protobuf",
            Compile / PB.targets := Seq(
                scalapb.gen() -> (Compile / sourceManaged).value / "scalapb",
                // TODO: Make this nicer. Like scalapb.zio_grpc.ZioCodeGenerator.
                genModule("kyo.grpc.compiler.CodeGenerator$") -> (Compile / sourceManaged).value / "scalapb"
            ),
            Compile / scalacOptions ++= scalacOptionToken(ScalacOptions.warnOption("conf:src=.*/src_managed/main/scalapb/kgrpc/.*:silent")).value
        ).jvmSettings(
            codeGenClasspath := (`kyo-grpc-code-gen_2.12` / Compile / fullClasspath).value,
            libraryDependencies ++= Seq(
                "io.grpc" % "grpc-netty" % "1.65.1"
            )
        ).jsSettings(
            `js-settings`,
            codeGenClasspath := (`kyo-grpc-code-genJS_2.12` / Compile / fullClasspath).value,
            libraryDependencies ++= Seq(
                "com.thesamet.scalapb.grpcweb" %%% "scalapb-grpcweb" % "0.7.0"
            )
        )

lazy val `kyo-combinators` =
    crossProject(JSPlatform, JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-combinators"))
        .dependsOn(`kyo-core`)
        .settings(
            `kyo-settings`
        )
        .jsSettings(`js-settings`)

lazy val `kyo-examples` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-examples"))
        .dependsOn(`kyo-tapir`)
        .dependsOn(`kyo-direct`)
        .dependsOn(`kyo-core`)
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
            libraryDependencies += "com.softwaremill.sttp.tapir" %% "tapir-json-zio" % "1.10.15"
        )

lazy val `kyo-bench` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Pure)
        .in(file("kyo-bench"))
        .enablePlugins(JmhPlugin, LocalCodeGenPlugin)
        .dependsOn(
            `kyo-core`,
            `kyo-grpc-core`,
            `kyo-sttp`,
            `kyo-scheduler-zio`
        )
        .settings(
            `kyo-settings`,
            publish / skip := true,
            Compile / PB.protoSources += baseDirectory.value.getParentFile / "src" / "main" / "protobuf",
            Compile / PB.targets := {
                val scalapbDir = (Compile / sourceManaged).value / "scalapb"
                Seq(
                    scalapb.gen()                                 -> scalapbDir,
                    scalapb.zio_grpc.ZioCodeGenerator             -> scalapbDir,
                    genModule("kyo.grpc.compiler.CodeGenerator$") -> scalapbDir
                )
            },
            codeGenClasspath          := (`kyo-grpc-code-gen_2.12` / Compile / fullClasspath).value,
            Compile / scalacOptions ++= scalacOptionToken(ScalacOptions.warnOption("conf:src=.*/src_managed/main/scalapb/kgrpc/.*:silent")).value,
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
            libraryDependencies += "dev.zio"             %% "izumi-reflect"       % "2.3.10",
            libraryDependencies += "org.typelevel"       %% "cats-effect"         % catsVersion,
            libraryDependencies += "org.typelevel"       %% "log4cats-core"       % "2.7.0",
            libraryDependencies += "org.typelevel"       %% "log4cats-slf4j"      % "2.7.0",
            libraryDependencies += "org.typelevel"       %% "cats-mtl"            % "1.5.0",
            libraryDependencies += "dev.zio"             %% "zio-logging"         % "2.3.1",
            libraryDependencies += "dev.zio"             %% "zio-logging-slf4j2"  % "2.3.1",
            libraryDependencies += "dev.zio"             %% "zio"                 % zioVersion,
            libraryDependencies += "dev.zio"             %% "zio-concurrent"      % zioVersion,
            libraryDependencies += "dev.zio"             %% "zio-prelude"         % "1.0.0-RC31",
            libraryDependencies += "com.softwaremill.ox" %% "core"                % "0.0.25",
            libraryDependencies += "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
            libraryDependencies += "co.fs2"              %% "fs2-core"            % "3.11.0",
            libraryDependencies += "org.http4s"          %% "http4s-ember-client" % "0.23.28",
            libraryDependencies += "org.http4s"          %% "http4s-dsl"          % "0.23.28",
            libraryDependencies += "dev.zio"             %% "zio-http"            % "3.0.1",
            libraryDependencies += "io.grpc"              % "grpc-netty"          % "1.65.1",
            libraryDependencies += "io.vertx"             % "vertx-core"          % "4.5.10",
            libraryDependencies += "io.vertx"             % "vertx-web"           % "4.5.10",
            libraryDependencies += "org.scalatest"       %% "scalatest"           % scalaTestVersion % Test,
        )

lazy val rewriteReadmeFile = taskKey[Unit]("Rewrite README file")

addCommandAlias("checkReadme", ";readme/rewriteReadmeFile; readme/mdoc")

lazy val readme =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("target/readme"))
        .enablePlugins(MdocPlugin)
        .disablePlugins(ProtocPlugin)
        .settings(
            `kyo-settings`,
            mdocIn  := new File("./../../README-in.md"),
            mdocOut := new File("./../../README-out.md"),
            scalacOptions --= scalacOptionTokens(Set(ScalacOptions.warnNonUnitStatement)).value,
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
    fork := false
)

lazy val `js-settings` = Seq(
    Compile / doc / sources                     := Seq.empty,
    fork                                        := false,
    jsEnv                                       := new NodeJSEnv(NodeJSEnv.Config().withArgs(List("--max_old_space_size=5120"))),
    libraryDependencies += "io.github.cquiroz" %%% "scala-java-time" % "2.5.0" % "provided"
)

def scalacOptionToken(proposedScalacOption: ScalacOption) =
    scalacOptionTokens(Set(proposedScalacOption))

def scalacOptionTokens(proposedScalacOptions: Set[ScalacOption]) = Def.setting {
    val version = ScalaVersion.fromString(scalaVersion.value).right.get
    ScalacOptions.tokensForVersion(version, proposedScalacOptions)
}

def sharedSourceDir(conf: String) = Def.setting {
    CrossType.Full.sharedSrcDir(baseDirectory.value, conf).get.getParentFile
}
