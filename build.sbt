import sys.process.*

val scala3Version = "3.4.1"

val compilerOptions = Seq(
    "-encoding",
    "utf8",
    "-feature",
    "-unchecked",
    "-deprecation",
    "-language:implicitConversions",
    "-Wvalue-discard",
    "-Wunused:all"
    // "-Xfatal-warnings"
    // "-explain"
    // "-Vprofile",
)

scalaVersion                       := scala3Version
ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"
sonatypeRepository                 := "https://s01.oss.sonatype.org/service/local"
sonatypeProfileName                := "io.getkyo"
publish / skip                     := true

lazy val `kyo-settings` = Seq(
    fork               := true,
    scalaVersion       := scala3Version,
    crossScalaVersions := List(scala3Version),
    scalacOptions ++= compilerOptions,
    scalafmtOnCompile := true,
    organization      := "io.getkyo",
    homepage          := Some(url("https://getkyo.io")),
    licenses          := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers := List(
        Developer(
            "fwbrasil",
            "Flavio Brasil",
            "fwbrasil@gmail.com",
            url("https://github.com/fwbrasil/")
        )
    ),
    ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org",
    sonatypeRepository                 := "https://s01.oss.sonatype.org/service/local",
    sonatypeProfileName                := "io.getkyo",
    Test / testOptions += Tests.Argument("-oDG"),
    ThisBuild / versionScheme := Some("early-semver"),
    scalacOptions ++= Seq("-release:11"),
    Test / javaOptions += "--add-opens=java.base/java.lang=ALL-UNNAMED"
)

lazy val kyo =
    crossProject(JVMPlatform)
        .in(file("."))
        .settings(
            name                                   := "kyo",
            organization                           := "io.getkyo",
            publishArtifact                        := false,
            publish / skip                         := true,
            Compile / packageBin / publishArtifact := false,
            Compile / packageDoc / publishArtifact := false,
            Compile / packageSrc / publishArtifact := false,
            scalaVersion                           := scala3Version,
            `kyo-settings`
        ).aggregate(
            `kyo-core`,
            `kyo-direct`,
            `kyo-stats-otel`,
            `kyo-cache`,
            `kyo-sttp`,
            `kyo-tapir`,
            `kyo-bench`,
            `kyo-examples`
        )

val zioVersion = "2.0.21"

lazy val `kyo-core` =
    crossProject(JSPlatform, JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-core"))
        .settings(
            `kyo-settings`,
            libraryDependencies += "dev.zio"       %%% "izumi-reflect"   % "2.3.8",
            libraryDependencies += "org.slf4j"       % "slf4j-api"       % "2.0.11",
            libraryDependencies += "org.jctools"     % "jctools-core"    % "4.0.3",
            libraryDependencies += "com.lihaoyi"   %%% "sourcecode"      % "0.3.1",
            libraryDependencies += "com.lihaoyi"   %%% "pprint"          % "0.8.1",
            libraryDependencies += "dev.zio"       %%% "zio-laws-laws"   % "1.0.0-RC23" % Test,
            libraryDependencies += "org.scalatest" %%% "scalatest"       % "3.2.16"     % Test,
            libraryDependencies += "ch.qos.logback"  % "logback-classic" % "1.5.3"      % Test,
            libraryDependencies += "javassist"       % "javassist"       % "3.12.1.GA"  % Test,
            testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
        )
        .jsSettings(`js-settings`)

lazy val `kyo-direct` =
    crossProject(JSPlatform, JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Pure)
        .in(file("kyo-direct"))
        .dependsOn(`kyo-core` % "test->test;compile->compile")
        .settings(
            `kyo-settings`,
            libraryDependencies += "com.github.rssh" %%% "dotty-cps-async" % "0.9.21"
        )
        .jsSettings(`js-settings`)

lazy val `kyo-stats-otel` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Pure)
        .in(file("kyo-stats-otel"))
        .dependsOn(`kyo-core` % "test->test;compile->compile")
        .settings(
            `kyo-settings`,
            libraryDependencies += "io.opentelemetry" % "opentelemetry-api" % "1.36.0",
            libraryDependencies += "io.opentelemetry" % "opentelemetry-sdk" % "1.36.0" % Test,
            libraryDependencies += "io.opentelemetry" % "opentelemetry-exporters-inmemory" % "0.9.1" % Test
        )

lazy val `kyo-cache` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-cache"))
        .dependsOn(`kyo-core` % "test->test;compile->compile")
        .settings(
            `kyo-settings`,
            libraryDependencies += "com.github.ben-manes.caffeine" % "caffeine" % "3.1.8"
        )

lazy val `kyo-os-lib` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-os-lib"))
        .dependsOn(`kyo-core` % "test->test;compile->compile")
        .settings(
            `kyo-settings`,
            libraryDependencies += "com.lihaoyi" %% "os-lib" % "0.9.3"
        )

lazy val `kyo-sttp` =
    crossProject(JSPlatform, JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-sttp"))
        .dependsOn(`kyo-core` % "test->test;compile->compile")
        .settings(
            `kyo-settings`,
            libraryDependencies += "com.softwaremill.sttp.client3" %%% "core" % "3.9.5"
        )
        .jsSettings(`js-settings`)

lazy val `kyo-tapir` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Pure)
        .in(file("kyo-tapir"))
        .dependsOn(`kyo-core` % "test->test;compile->compile")
        .dependsOn(`kyo-sttp`)
        .settings(
            `kyo-settings`,
            libraryDependencies += "com.softwaremill.sttp.tapir" %% "tapir-core"         % "1.10.0",
            libraryDependencies += "com.softwaremill.sttp.tapir" %% "tapir-netty-server" % "1.10.0"
        )

lazy val `kyo-examples` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-examples"))
        .dependsOn(`kyo-tapir`)
        .dependsOn(`kyo-direct`)
        .dependsOn(`kyo-os-lib`)
        .dependsOn(`kyo-core` % "test->test;compile->compile")
        .settings(
            `kyo-settings`,
            Compile / doc / sources                              := Seq.empty,
            libraryDependencies += "com.softwaremill.sttp.tapir" %% "tapir-json-zio" % "1.10.0"
        )

lazy val `kyo-bench` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Pure)
        .in(file("kyo-bench"))
        .enablePlugins(JmhPlugin)
        .dependsOn(`kyo-core`)
        .settings(
            `kyo-settings`,
            libraryDependencies += "org.typelevel"       %% "cats-effect"        % "3.5.4",
            libraryDependencies += "org.typelevel"       %% "log4cats-core"      % "2.6.0",
            libraryDependencies += "org.typelevel"       %% "log4cats-slf4j"     % "2.6.0",
            libraryDependencies += "dev.zio"             %% "zio-logging"        % "2.2.2",
            libraryDependencies += "dev.zio"             %% "zio-logging-slf4j2" % "2.2.2",
            libraryDependencies += "dev.zio"             %% "zio"                % zioVersion,
            libraryDependencies += "dev.zio"             %% "zio-concurrent"     % zioVersion,
            libraryDependencies += "dev.zio"             %% "zio-prelude"        % "1.0.0-RC23",
            libraryDependencies += "com.softwaremill.ox" %% "core"               % "0.0.25",
            libraryDependencies += "co.fs2"              %% "fs2-core"           % "3.10.2",
            libraryDependencies += "org.scalatest"       %% "scalatest"          % "3.2.16" % Test
        )

lazy val rewriteReadmeFile = taskKey[Unit]("Rewrite README file")

addCommandAlias("checkReadme", ";readme/rewriteReadmeFile; readme/mdoc")

lazy val readme =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Pure)
        .in(file("target/readme"))
        .enablePlugins(MdocPlugin)
        .settings(
            `kyo-settings`,
            mdocIn  := new File("./../../README-in.md"),
            mdocOut := new File("./../../README-out.md"),
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
            `kyo-bench`
        )

import org.scalajs.jsenv.nodejs.*

lazy val `js-settings` = Seq(
    Compile / doc / sources := Seq.empty,
    fork                    := false,
    jsEnv := new NodeJSEnv(NodeJSEnv.Config().withArgs(List("--max_old_space_size=5120")))
)
