import sys.process.*

val scala3Version = "3.3.3"

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
    scalacOptions ++= Seq("-release:21"),
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
            `kyo-llm`,
            `kyo-bench`
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
            libraryDependencies += "ch.qos.logback"  % "logback-classic" % "1.5.1"      % Test,
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
            libraryDependencies += "com.github.rssh" %%% "dotty-cps-async" % "0.9.20"
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
            libraryDependencies += "io.opentelemetry" % "opentelemetry-api" % "1.35.0",
            libraryDependencies += "io.opentelemetry" % "opentelemetry-sdk" % "1.35.0" % Test,
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
            libraryDependencies += "com.softwaremill.sttp.client3" %%% "core" % "3.9.3"
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
            libraryDependencies += "com.softwaremill.sttp.tapir" %% "tapir-core"         % "1.9.11",
            libraryDependencies += "com.softwaremill.sttp.tapir" %% "tapir-netty-server" % "1.9.11"
        )

lazy val `kyo-llm` =
    crossProject(JSPlatform, JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-llm"))
        .dependsOn(`kyo-sttp`)
        .dependsOn(`kyo-direct`)
        .dependsOn(`kyo-core` % "test->test;compile->compile")
        .settings(
            `kyo-settings`,
            libraryDependencies += "com.knuddels"                   % "jtokkit"         % "1.0.0",
            libraryDependencies += "com.softwaremill.sttp.client3" %% "zio-json"        % "3.9.3",
            libraryDependencies += "dev.zio"                       %% "zio-schema"      % "1.0.1",
            libraryDependencies += "dev.zio"                       %% "zio-schema-json" % "1.0.1",
            libraryDependencies += "dev.zio" %% "zio-schema-derivation" % "1.0.1"
        )
        .jsSettings(`js-settings`)

lazy val `kyo-llm-bench` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-llm-bench"))
        .dependsOn(`kyo-llm`)
        .dependsOn(`kyo-os-lib`)
        .dependsOn(`kyo-core` % "test->test;compile->compile")
        .settings(
            `kyo-settings`,
            libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.5.1"
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
            libraryDependencies += "org.typelevel"       %% "cats-effect"        % "3.5.3",
            libraryDependencies += "org.typelevel"       %% "log4cats-core"      % "2.6.0",
            libraryDependencies += "org.typelevel"       %% "log4cats-slf4j"     % "2.6.0",
            libraryDependencies += "dev.zio"             %% "zio-logging"        % "2.2.2",
            libraryDependencies += "dev.zio"             %% "zio-logging-slf4j2" % "2.2.2",
            libraryDependencies += "dev.zio"             %% "zio"                % zioVersion,
            libraryDependencies += "dev.zio"             %% "zio-concurrent"     % zioVersion,
            libraryDependencies += "dev.zio"             %% "zio-prelude"        % "1.0.0-RC23",
            libraryDependencies += "com.softwaremill.ox" %% "core"               % "0.0.21",
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
            `kyo-llm`,
            `kyo-bench`
        )

import org.scalajs.jsenv.nodejs.*

lazy val `js-settings` = Seq(
    Compile / doc / sources := Seq.empty,
    fork                    := false,
    jsEnv := new NodeJSEnv(NodeJSEnv.Config().withArgs(List("--max_old_space_size=5120")))
)

Global / onLoad := {
  val old = (Global / onLoad).value
  val javaVersion = System.getProperty("java.version")
  if (!javaVersion.startsWith("21")) {
    throw new Exception("This project requires Java 21")
  }
  old
}