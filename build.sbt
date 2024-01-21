val scala3Version = "3.3.1"
val scala2Version = "2.13.12"

val compilerOptions = Seq(
    "-encoding",
    "utf8",
    "-feature",
    "-unchecked",
    "-deprecation",
    "-language:implicitConversions"
    // "-explain"
    // "-Wvalue-discard",
    // "-Vprofile",
)

scalaVersion                       := scala3Version
ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"
sonatypeRepository                 := "https://s01.oss.sonatype.org/service/local"
sonatypeProfileName                := "io.getkyo"
publish / skip                     := true

lazy val `kyo-settings` = Seq(
    fork := true,
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
    gen                                := {},
    Test / testOptions += Tests.Argument("-oDG"),
    ThisBuild / versionScheme := Some("early-semver")
)

lazy val gen = TaskKey[Unit]("gen", "")

lazy val genState: State => State = {
  s: State =>
    "gen" :: s
}

Global / onLoad := {
  val old = (Global / onLoad).value
  genState compose old
}

def transformFiles(path: File)(f: String => String): Unit =
  if (path.isDirectory) path.listFiles.foreach(transformFiles(_)(f))
  else {
    var original = IO.read(path)
    IO.write(path, f(original))
  }

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
        `kyo-settings`,
        gen := {
          val origin = new File("kyo-core/")
          val dest   = new File(s"kyo-core-opt/")
          IO.delete(dest)
          IO.copyDirectory(origin, dest)
          transformFiles(dest) {
            _.replaceAllLiterally(s"/*inline*/", "inline")
          }
        }
    ).aggregate(
        `kyo-core`,
        `kyo-core-opt`,
        `kyo-direct`,
        `kyo-stats-otel`,
        `kyo-cache`,
        `kyo-sttp`,
        `kyo-tapir`,
        `kyo-llm-macros`,
        `kyo-llm`,
        `kyo-bench`
    )

val zioVersion = "2.0.21"

lazy val `kyo-core-settings` = `kyo-settings` ++ Seq(
    libraryDependencies += "dev.zio"       %%% "izumi-reflect"     % "2.3.8",
    libraryDependencies += "org.slf4j"       % "slf4j-api"         % "2.0.11",
    libraryDependencies += "org.jctools"     % "jctools-core"      % "4.0.2",
    libraryDependencies += "com.lihaoyi"   %%% "sourcecode"        % "0.3.1",
    libraryDependencies += "com.lihaoyi"   %%% "pprint"            % "0.8.1",
    libraryDependencies += "dev.zio"       %%% "zio-test"          % zioVersion   % Test,
    libraryDependencies += "dev.zio"       %%% "zio-test-magnolia" % zioVersion   % Test,
    libraryDependencies += "dev.zio"       %%% "zio-test-sbt"      % zioVersion   % Test,
    libraryDependencies += "dev.zio"       %%% "zio-prelude"       % "1.0.0-RC22" % Test,
    libraryDependencies += "dev.zio"       %%% "zio-laws-laws"     % "1.0.0-RC22" % Test,
    libraryDependencies += "org.scalatest" %%% "scalatest"         % "3.2.16"     % Test,
    libraryDependencies += "ch.qos.logback"  % "logback-classic"   % "1.4.14"     % Test,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    Global / concurrentRestrictions := Seq(
        Tags.limit(Tags.CPU, java.lang.Runtime.getRuntime().availableProcessors() - 1)
    )
)

lazy val `without-cross-scala` = Seq(
    scalaVersion       := scala3Version,
    crossScalaVersions := List(scala3Version)
)

lazy val `with-cross-scala` = Seq(
    scalaVersion       := scala3Version,
    crossScalaVersions := List(scala3Version, scala2Version)
)

lazy val `kyo-core` =
  crossProject(JSPlatform, JVMPlatform)
    .withoutSuffixFor(JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("kyo-core"))
    .settings(
        `kyo-core-settings`,
        `with-cross-scala`,
        libraryDependencies ++= (CrossVersion.partialVersion(scalaVersion.value) match {
          case Some((2, _)) => Seq("org.scala-lang" % "scala-reflect" % scalaVersion.value)
          case _            => Seq.empty
        })
    )
    .jsSettings(`js-settings`)

lazy val `kyo-core-opt` =
  crossProject(JVMPlatform)
    .withoutSuffixFor(JVMPlatform)
    .crossType(CrossType.Full)
    .in(file(s"kyo-core-opt"))
    .settings(
        `kyo-core-settings`,
        `without-cross-scala`,
        scalafmtOnCompile := false
    )

lazy val `kyo-direct` =
  crossProject(JSPlatform, JVMPlatform)
    .withoutSuffixFor(JVMPlatform)
    .crossType(CrossType.Pure)
    .in(file("kyo-direct"))
    .dependsOn(`kyo-core` % "test->test;compile->compile")
    .settings(
        `kyo-settings`,
        `with-cross-scala`,
        libraryDependencies ++= Seq(
            "com.github.rssh" %%% "dotty-cps-async" % "0.9.19"
        ).filter(_ => scalaVersion.value.startsWith("3")),
        libraryDependencies ++= Seq(
            "org.scala-lang"   % "scala-library"  % scalaVersion.value,
            "org.scala-lang"   % "scala-compiler" % scalaVersion.value,
            "org.scala-lang"   % "scala-reflect"  % scalaVersion.value,
            "org.scalamacros" %% "resetallattrs"  % "1.0.0"
        ).filter(_ => scalaVersion.value.startsWith("2"))
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
        `with-cross-scala`,
        libraryDependencies += "io.opentelemetry" % "opentelemetry-api" % "1.34.1",
        libraryDependencies += "io.opentelemetry" % "opentelemetry-sdk" % "1.34.1",
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
        `with-cross-scala`,
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
        `without-cross-scala`,
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
        `with-cross-scala`,
        libraryDependencies += "com.softwaremill.sttp.client3" %%% "core" % "3.9.2"
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
        `with-cross-scala`,
        libraryDependencies += "com.softwaremill.sttp.tapir" %% "tapir-core"         % "1.8.4",
        libraryDependencies += "com.softwaremill.sttp.tapir" %% "tapir-netty-server" % "1.8.4"
    )

lazy val `kyo-llm-macros` =
  crossProject(JSPlatform, JVMPlatform)
    .withoutSuffixFor(JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("kyo-llm-macros"))
    .dependsOn(`kyo-core` % "test->test;compile->compile")
    .settings(
        `kyo-settings`,
        scalaVersion       := scala3Version,
        crossScalaVersions := List(scala2Version, scala3Version),
        libraryDependencies += "com.softwaremill.sttp.client3" %% "zio-json"            % "3.9.2",
        libraryDependencies += "dev.zio"                       %% "zio-schema"          % "0.4.17",
        libraryDependencies += "dev.zio"                       %% "zio-schema"          % "0.4.17",
        libraryDependencies += "dev.zio"                       %% "zio-schema-json"     % "0.4.17",
        libraryDependencies += "dev.zio"                       %% "zio-schema-protobuf" % "0.4.17",
        libraryDependencies += "dev.zio" %% "zio-schema-derivation" % "0.4.17",
        libraryDependencies ++= (CrossVersion.partialVersion(scalaVersion.value) match {
          case Some((2, _)) => Seq("org.scala-lang" % "scala-reflect" % scalaVersion.value)
          case _            => Seq.empty
        })
    )
    .jsSettings(`js-settings`)

lazy val `kyo-llm` =
  crossProject(JSPlatform, JVMPlatform)
    .withoutSuffixFor(JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("kyo-llm"))
    .dependsOn(`kyo-sttp`)
    .dependsOn(`kyo-direct`)
    .dependsOn(`kyo-core` % "test->test;compile->compile")
    .dependsOn(`kyo-llm-macros`)
    .settings(
        `kyo-settings`,
        `without-cross-scala`,
        libraryDependencies += "com.knuddels" % "jtokkit" % "0.6.1"
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
        `without-cross-scala`,
        libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.4.14"
    )

lazy val `kyo-bench` =
  crossProject(JVMPlatform)
    .withoutSuffixFor(JVMPlatform)
    .crossType(CrossType.Pure)
    .in(file("kyo-bench"))
    .enablePlugins(JmhPlugin)
    .dependsOn(`kyo-core-opt`)
    .settings(
        `kyo-settings`,
        `without-cross-scala`,
        libraryDependencies += "org.typelevel"       %% "cats-effect"        % "3.5.3",
        libraryDependencies += "org.typelevel"       %% "log4cats-core"      % "2.6.0",
        libraryDependencies += "org.typelevel"       %% "log4cats-slf4j"     % "2.6.0",
        libraryDependencies += "dev.zio"             %% "zio-logging"        % "2.1.16",
        libraryDependencies += "dev.zio"             %% "zio-logging-slf4j2" % "2.1.16",
        libraryDependencies += "dev.zio"             %% "zio"                % zioVersion,
        libraryDependencies += "dev.zio"             %% "zio-concurrent"     % zioVersion,
        libraryDependencies += "com.softwaremill.ox" %% "core"               % "0.0.16",
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
        `without-cross-scala`,
        mdocIn  := new File("./../../README-in.md"),
        mdocOut := new File("./../../README-out.md"),
        rewriteReadmeFile := {
          val readmeFile       = new File("README.md")
          val targetReadmeFile = new File("target/README-in.md")
          val contents         = IO.read(readmeFile)
          val newContents      = contents.replaceAll("```scala\n", "```scala mdoc:nest\n")
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

import org.scalajs.jsenv.nodejs._

lazy val `js-settings` = Seq(
    Compile / doc / sources := Seq.empty,
    fork                    := false,
    jsEnv := new NodeJSEnv(NodeJSEnv.Config().withArgs(List("--max_old_space_size=5120")))
)
