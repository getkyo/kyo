import com.github.sbt.git.SbtGit.GitKeys.useConsoleForROGit
import org.scalajs.jsenv.nodejs.*
import org.typelevel.scalacoptions.ScalacOption
import org.typelevel.scalacoptions.ScalacOptions
import org.typelevel.scalacoptions.ScalaVersion
import protocbridge.Target
import sbtdynver.DynVerPlugin.autoImport.*

val scala3Version   = "3.7.0"
val scala213Version = "2.13.16"
val scala212Version = "2.12.20"

val zioVersion       = "2.1.17"
val catsVersion      = "3.6.1"
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
        `kyo-playwright`.jvm,
        `kyo-examples`.jvm,
        `kyo-actor`.jvm,
        `kyo-grpc`.jvm
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
        `kyo-actor`.js,
	`kyo-grpc`.js
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
            crossScalaVersions                     := List(scala3Version, scala213Version),
            libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.5.18" % Test
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
        crossScalaVersions := List(scala3Version, scala213Version)
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
            crossScalaVersions := List(scala3Version, scala213Version)
        )

lazy val `kyo-scheduler-pekko` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-scheduler`)
        .in(file("kyo-scheduler-pekko"))
        .settings(
            `kyo-settings`,
            libraryDependencies += "org.apache.pekko" %%% "pekko-actor"   % "1.1.4",
            libraryDependencies += "org.apache.pekko" %%% "pekko-testkit" % "1.1.4" % Test
        )
        .jvmSettings(mimaCheck(false))
        .settings(
            scalacOptions ++= scalacOptionToken(ScalacOptions.source3).value,
            crossScalaVersions := List(scala3Version, scala213Version)
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
            libraryDependencies += "dev.zio"     %%% "izumi-reflect" % "3.0.3" % Test
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
            libraryDependencies += "dev.zio" %%% "zio-laws-laws" % "1.0.0-RC41" % Test,
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
            libraryDependencies += "org.slf4j"      % "slf4j-api"       % "2.0.17",
            libraryDependencies += "dev.dirs"       % "directories"     % "26",
            libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.5.18" % Test
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
            libraryDependencies += "io.github.dotty-cps-async" %%% "dotty-cps-async" % "1.1.2"
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
            crossScalaVersions                       := List(scala3Version, scala213Version)
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
            libraryDependencies += "io.opentelemetry" % "opentelemetry-api"                % "1.51.0",
            libraryDependencies += "io.opentelemetry" % "opentelemetry-sdk"                % "1.51.0" % Test,
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
            libraryDependencies += "com.github.ben-manes.caffeine" % "caffeine" % "3.2.1"
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
    crossProject(JVMPlatform)
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

lazy val `kyo-sttp` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-sttp"))
        .dependsOn(`kyo-core`)
        .settings(
            `kyo-settings`,
            libraryDependencies += "com.softwaremill.sttp.client3" %%% "core" % "3.11.0"
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
            libraryDependencies += "com.softwaremill.sttp.tapir" %% "tapir-core"         % "1.11.34",
            libraryDependencies += "com.softwaremill.sttp.tapir" %% "tapir-netty-server" % "1.11.34"
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
            libraryDependencies += "com.github.ghostdogpr" %% "caliban"       % "2.10.0",
            libraryDependencies += "com.github.ghostdogpr" %% "caliban-tapir" % "2.10.0"
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
        ).jvmSettings(mimaCheck(false))

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
        .aggregate(`kyo-grpc-protoc-gen`.componentProjects.map(p => p: ProjectReference) *)

lazy val `kyo-grpc-core` =
    crossProject(JVMPlatform, JSPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-grpc") / "core")
        .dependsOn(`kyo-core`)
        .settings(`kyo-settings`)
        .settings(
            libraryDependencies += "org.scalamock" %% "scalamock" % "7.4.0" % Test
        )
        .jvmSettings(
            libraryDependencies ++= Seq(
                "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
                "io.grpc"               % "grpc-api"             % "1.72.0",
                // It is a little unusual to include this here but it greatly reduces the amount of generated code.
                "io.grpc" % "grpc-stub" % "1.72.0",
                "ch.qos.logback" % "logback-classic" % "1.5.18" % Test
            )
        ).jsSettings(
            `js-settings`,
            libraryDependencies ++= Seq( //
                "com.thesamet.scalapb.grpcweb" %%% "scalapb-grpcweb" % "0.7.0")
        )

lazy val `kyo-grpc-code-gen` =
    crossProject(JVMPlatform, JSPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-grpc") / "code-gen")
        .enablePlugins(BuildInfoPlugin)
        .settings(
            `kyo-settings`,
            buildInfoKeys := Seq[BuildInfoKey](name, organization, version, scalaVersion, sbtVersion),
            buildInfoPackage := "kyo.grpc.compiler",
            crossScalaVersions := List(scala213Version, scala3Version),
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

lazy val `kyo-grpc-protoc-gen` =
    protocGenProject("kyo-grpc-protoc-gen", `kyo-grpc-code-gen_2.12`)
        .settings(
            `kyo-settings`,
            scalaVersion       := scala212Version,
            crossScalaVersions := Seq(scala212Version),
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
        .dependsOn(`kyo-grpc-core` % "compile->compile;test->test")
        .settings(
            `kyo-settings`,
            publish / skip := true,
            Compile / PB.protoSources += sharedSourceDir("main").value / "protobuf",
            Compile / PB.targets := Seq(
                scalapb.gen() -> (Compile / sourceManaged).value / "scalapb",
                // Users of the plugin can use: kyo.grpc.gen() -> (Compile / sourceManaged).value / "scalapb"
                genModule("kyo.grpc.compiler.CodeGenerator$") -> (Compile / sourceManaged).value / "scalapb"
            ),
            Compile / scalacOptions ++= scalacOptionToken(ScalacOptions.warnOption("conf:src=.*/src_managed/main/scalapb/kgrpc/.*:silent")).value
        ).jvmSettings(
            codeGenClasspath := (`kyo-grpc-code-gen_2.12` / Compile / fullClasspath).value,
            libraryDependencies ++= Seq(
                "io.grpc" % "grpc-netty-shaded" % "1.72.0",
                "ch.qos.logback" % "logback-classic" % "1.5.18" % Test
            )
        ).jsSettings(
            `js-settings`,
            codeGenClasspath := (`kyo-grpc-code-genJS_2.12` / Compile / fullClasspath).value,
            libraryDependencies += "com.thesamet.scalapb.grpcweb" %%% "scalapb-grpcweb" % "0.7.0"
        )

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

lazy val `kyo-playwright` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-playwright"))
        .dependsOn(`kyo-core`)
        .settings(
            `kyo-settings`,
            libraryDependencies += "com.microsoft.playwright" % "playwright" % "1.52.0"
        )
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
            libraryDependencies += "com.softwaremill.sttp.tapir" %% "tapir-json-zio" % "1.11.34"
        )
        .jvmSettings(mimaCheck(false))

lazy val `kyo-bench` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Pure)
        .in(file("kyo-bench"))
        .enablePlugins(Fs2Grpc, JmhPlugin, LocalCodeGenPlugin)
        .disablePlugins(MimaPlugin)
        .dependsOn(
            `kyo-core`,
	    `kyo-direct`,
            `kyo-grpc-core`,
            `kyo-scheduler-cats`,
            `kyo-scheduler-zio`,
            `kyo-stm`,
            `kyo-sttp`
        )
        .settings(
            `kyo-settings`,
            publish / skip := true,
            Compile / PB.protoSources += baseDirectory.value.getParentFile / "src" / "main" / "protobuf",
            Compile / PB.targets := {
                val scalapbDir = (Compile / sourceManaged).value / "scalapb"
                // This includes the base scalapb.gen.
                val catsGen = Fs2GrpcPlugin.autoImport.scalapbCodeGenerators.value
                catsGen ++ Seq[Target](
                    scalapb.gen(scala3Sources = true)             -> scalapbDir / "vanilla",
                    scalapb.zio_grpc.ZioCodeGenerator             -> scalapbDir,
                    genModule("kyo.grpc.compiler.CodeGenerator$") -> scalapbDir
                )
            },
            Compile / PB.generate ~= { files =>
                files.filter(_.isFile).filter(_.getPath.contains("/vanilla/")).foreach { file =>
                    val fileContent = IO.read(file)
                    val updatedContent = fileContent
                        // Workaround for https://github.com/scalapb/ScalaPB/issues/1816.
                        .replace(
                            "_unknownFields__.parseField(tag, _input__)",
                            "_unknownFields__.parseField(tag, _input__): Unit"
                        )
                        // Hacky workaround to not get a collision with the one generated by Kyo and ZIO.
                        .replace(
                            "kgrpc.bench",
                            "vanilla.kgrpc.bench",
                        )
                    IO.write(file, updatedContent)
                }
                files
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
            libraryDependencies += "dev.zio"              %% "izumi-reflect"        % "3.0.3",
            libraryDependencies += "org.typelevel"        %% "cats-effect"          % catsVersion,
            libraryDependencies += "org.typelevel"        %% "log4cats-core"        % "2.7.1",
            libraryDependencies += "org.typelevel"        %% "log4cats-slf4j"       % "2.7.1",
            libraryDependencies += "org.typelevel"        %% "cats-mtl"             % "1.5.0",
            libraryDependencies += "io.github.timwspence" %% "cats-stm"             % "0.13.5",
            libraryDependencies += "com.47deg"            %% "fetch"                % "3.2.0",
            libraryDependencies += "dev.zio"              %% "zio-logging"          % "2.5.0",
            libraryDependencies += "dev.zio"              %% "zio-logging-slf4j2"   % "2.5.0",
            libraryDependencies += "dev.zio"              %% "zio"                  % zioVersion,
            libraryDependencies += "dev.zio"              %% "zio-concurrent"       % zioVersion,
            libraryDependencies += "dev.zio"              %% "zio-query"            % "0.7.7",
            libraryDependencies += "dev.zio"              %% "zio-prelude"          % "1.0.0-RC41",
            libraryDependencies += "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
            libraryDependencies += "co.fs2"               %% "fs2-core"             % "3.12.0",
            libraryDependencies += "org.http4s"           %% "http4s-ember-client"  % "1.0.0-M44",
            libraryDependencies += "org.http4s"           %% "http4s-dsl"           % "1.0.0-M44",
            libraryDependencies += "dev.zio"              %% "zio-http"             % "3.3.3",
            libraryDependencies += "io.grpc"               % "grpc-netty-shaded"    % "1.72.0",
            libraryDependencies += "io.vertx"              % "vertx-core"           % "5.0.0",
            libraryDependencies += "io.vertx"              % "vertx-web"            % "5.0.0"
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

def sharedSourceDir(conf: String) = Def.setting {
    CrossType.Full.sharedSrcDir(baseDirectory.value, conf).get.getParentFile
}
