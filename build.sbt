import sys.process.*

val scala3Version   = "3.4.1"
val scala212Version = "2.12.19"
val scala213Version = "2.13.14"

val compilerOptions = Seq(
    "-encoding",
    "utf8",
    "-feature",
    "-unchecked",
    "-deprecation",
    "-language:implicitConversions",
    "-Wvalue-discard",
    "-Wunused:all",
    "-language:strictEquality"
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
            `kyo-scheduler`,
            `kyo-tag`,
            `kyo-core`,
            `kyo-direct`,
            `kyo-stats-otel`,
            `kyo-cache`,
            `kyo-sttp`,
            `kyo-tapir`,
            `kyo-bench`,
            `kyo-test`,
            `kyo-zio`,
            `kyo-examples`
        )

lazy val `kyo-scheduler` =
    crossProject(JSPlatform, JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-scheduler"))
        .settings(
            `kyo-settings`,
            scalacOptions --= Seq(
                "-Wvalue-discard",
                "-Wunused:all",
                "-language:strictEquality"
            ),
            scalacOptions += "-Xsource:3",
            crossScalaVersions                      := List(scala3Version, scala212Version, scala213Version),
            libraryDependencies += "org.scalatest" %%% "scalatest"       % "3.2.16" % Test,
            libraryDependencies += "ch.qos.logback"  % "logback-classic" % "1.5.5"  % Test
        )
        .jsSettings(`js-settings`)

lazy val `kyo-tag` =
    crossProject(JSPlatform, JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-tag"))
        .settings(
            `kyo-settings`,
            libraryDependencies += "org.scalatest" %%% "scalatest"     % "3.2.16" % Test,
            libraryDependencies += "dev.zio"       %%% "izumi-reflect" % "2.3.9"  % Test
        )
        .jsSettings(`js-settings`)

lazy val `kyo-core` =
    crossProject(JSPlatform, JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-scheduler`)
        .dependsOn(`kyo-tag`)
        .in(file("kyo-core"))
        .settings(
            `kyo-settings`,
            libraryDependencies += "com.lihaoyi"   %%% "pprint"          % "0.9.0",
            libraryDependencies += "org.jctools"     % "jctools-core"    % "4.0.3",
            libraryDependencies += "org.slf4j"       % "slf4j-api"       % "2.0.13",
            libraryDependencies += "dev.zio"       %%% "zio-laws-laws"   % "1.0.0-RC25" % Test,
            libraryDependencies += "dev.zio"       %%% "zio-test-sbt"    % "2.1.1"      % Test,
            libraryDependencies += "org.scalatest" %%% "scalatest"       % "3.2.16"     % Test,
            libraryDependencies += "ch.qos.logback"  % "logback-classic" % "1.5.6"      % Test,
            libraryDependencies += "javassist"       % "javassist"       % "3.12.1.GA"  % Test
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
            libraryDependencies += "io.opentelemetry" % "opentelemetry-api"                % "1.38.0",
            libraryDependencies += "io.opentelemetry" % "opentelemetry-sdk"                % "1.38.0" % Test,
            libraryDependencies += "io.opentelemetry" % "opentelemetry-exporters-inmemory" % "0.9.1"  % Test
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
            libraryDependencies += "com.lihaoyi" %% "os-lib" % "0.10.0"
        )

lazy val `kyo-sttp` =
    crossProject(JSPlatform, JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-sttp"))
        .dependsOn(`kyo-core` % "test->test;compile->compile")
        .settings(
            `kyo-settings`,
            libraryDependencies += "com.softwaremill.sttp.client3" %%% "core" % "3.9.6"
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

lazy val `kyo-test` =
    crossProject(JVMPlatform, JSPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-test"))
        .dependsOn(`kyo-core`)
        .settings(
            `kyo-settings`,
            libraryDependencies += "dev.zio" %% "zio"          % "2.1.1",
            libraryDependencies += "dev.zio" %% "zio-test"     % "2.1.1",
            libraryDependencies += "dev.zio" %% "zio-test-sbt" % "2.1.1" % Test
        ).jsSettings(
            `js-settings`
        )

lazy val `kyo-zio` =
    crossProject(JVMPlatform, JSPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-zio"))
        .dependsOn(`kyo-core` % "test->test;compile->compile")
        .settings(
            `kyo-settings`,
            libraryDependencies += "dev.zio" %% "zio"          % "2.1.0-RC3",
            libraryDependencies += "dev.zio" %% "zio-test"     % "2.1.0-RC3",
            libraryDependencies += "dev.zio" %% "zio-test-sbt" % "2.1.0-RC3" % Test
        ).jsSettings(
            `js-settings`
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
        .dependsOn(`kyo-sttp`)
        .settings(
            `kyo-settings`,
            // Forks each test suite individually
            Test / testGrouping := {
                val javaOptionsValue = javaOptions.value.toVector
                val envsVarsValue    = envVars.value
                (Test / definedTests).value map { test =>
                    import sbt.dsl.LinterLevel.Ignore
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
            libraryDependencies += "dev.zio"             %% "izumi-reflect"       % "2.3.9",
            libraryDependencies += "org.typelevel"       %% "cats-effect"         % "3.5.4",
            libraryDependencies += "org.typelevel"       %% "log4cats-core"       % "2.7.0",
            libraryDependencies += "org.typelevel"       %% "log4cats-slf4j"      % "2.7.0",
            libraryDependencies += "dev.zio"             %% "zio-logging"         % "2.2.3",
            libraryDependencies += "dev.zio"             %% "zio-logging-slf4j2"  % "2.2.3",
            libraryDependencies += "dev.zio"             %% "zio"                 % "2.1.1",
            libraryDependencies += "dev.zio"             %% "zio-concurrent"      % "2.1.1",
            libraryDependencies += "dev.zio"             %% "zio-prelude"         % "1.0.0-RC25",
            libraryDependencies += "com.softwaremill.ox" %% "core"                % "0.0.25",
            libraryDependencies += "co.fs2"              %% "fs2-core"            % "3.10.2",
            libraryDependencies += "org.http4s"          %% "http4s-ember-client" % "0.23.27",
            libraryDependencies += "org.http4s"          %% "http4s-dsl"          % "0.23.27",
            libraryDependencies += "dev.zio"             %% "zio-http"            % "3.0.0-RC6",
            libraryDependencies += "org.scalatest"       %% "scalatest"           % "3.2.16" % Test
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
    jsEnv                   := new NodeJSEnv(NodeJSEnv.Config().withArgs(List("--max_old_space_size=5120")))
)
