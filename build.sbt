val scala3Version = "3.3.0"
val scala2Version = "2.13.11"

val compilerOptions = Seq(
    "-encoding",
    "utf8",
    "-feature",
    "-unchecked",
    "-deprecation",
    "-language:implicitConversions"
    // "-explain",
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
          IO.copyDirectory(origin, dest)
          transformFiles(dest) {
            _.replaceAllLiterally(s"/*inline*/", "inline")
          }
        }
    ).aggregate(
        `kyo-core`,
        `kyo-core-opt`,
        `kyo-zio`,
        `kyo-direct`,
        `kyo-sttp`,
        `kyo-graal`,
        `kyo-chatgpt`,
        `kyo-bench`
    )

val zioVersion = "2.0.16"

lazy val `kyo-core-settings` = `kyo-settings` ++ Seq(
    libraryDependencies += "dev.zio"       %%% "izumi-reflect"     % "2.3.8",
    libraryDependencies += "org.slf4j"       % "slf4j-api"         % "2.0.9",
    libraryDependencies += "org.jctools"     % "jctools-core"      % "4.0.1",
    libraryDependencies += "dev.zio"       %%% "zio-test"          % zioVersion   % Test,
    libraryDependencies += "dev.zio"       %%% "zio-test-magnolia" % zioVersion   % Test,
    libraryDependencies += "dev.zio"       %%% "zio-test-sbt"      % zioVersion   % Test,
    libraryDependencies += "dev.zio"       %%% "zio-prelude"       % "1.0.0-RC20" % Test,
    libraryDependencies += "dev.zio"       %%% "zio-laws-laws"     % "1.0.0-RC20" % Test,
    libraryDependencies += "org.scalatest" %%% "scalatest"         % "3.2.16"     % Test,
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
        `with-cross-scala`
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
            "org.scala-lang"   % "scala-library"  % scalaVersion.value,
            "org.scala-lang"   % "scala-compiler" % scalaVersion.value,
            "org.scala-lang"   % "scala-reflect"  % scalaVersion.value,
            "org.scalamacros" %% "resetallattrs"  % "1.0.0"
        ).filter(_ => scalaVersion.value.startsWith("2"))
    )
    .jsSettings(`js-settings`)

lazy val `kyo-zio` =
  crossProject(JSPlatform, JVMPlatform)
    .withoutSuffixFor(JVMPlatform)
    .crossType(CrossType.Pure)
    .in(file("kyo-zio"))
    .dependsOn(`kyo-core` % "test->test;compile->compile")
    .settings(
        `kyo-settings`,
        `with-cross-scala`,
        libraryDependencies += "dev.zio" %%% "zio" % zioVersion
    )
    .jsSettings(`js-settings`)

lazy val `kyo-sttp` =
  crossProject(JSPlatform, JVMPlatform)
    .withoutSuffixFor(JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("kyo-sttp"))
    .dependsOn(`kyo-core` % "test->test;compile->compile")
    .settings(
        `kyo-settings`,
        `with-cross-scala`,
        libraryDependencies += "com.softwaremill.sttp.client3" %%% "core" % "3.9.0"
    )
    .jsSettings(`js-settings`)

lazy val `kyo-graal` =
  crossProject(JVMPlatform)
    .withoutSuffixFor(JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("kyo-graal"))
    .dependsOn(`kyo-core` % "test->test;compile->compile")
    .settings(
        `kyo-settings`,
        `with-cross-scala`,
        libraryDependencies += "org.graalvm.sdk" % "graal-sdk" % "23.0.1"
    )

lazy val `kyo-chatgpt` =
  crossProject(JSPlatform, JVMPlatform)
    .withoutSuffixFor(JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("kyo-chatgpt"))
    .dependsOn(`kyo-sttp`)
    .dependsOn(`kyo-direct`)
    .dependsOn(`kyo-core` % "test->test;compile->compile")
    .jvmSettings(
        libraryDependencies += "org.apache.lucene"    % "lucene-core"        % "9.7.0",
        libraryDependencies += "org.apache.lucene"    % "lucene-queryparser" % "9.7.0",
        libraryDependencies += "com.formdev"          % "flatlaf"            % "3.2",
        libraryDependencies += "com.vladsch.flexmark" % "flexmark-all"       % "0.64.8",
        libraryDependencies += "com.vladsch.flexmark" % "flexmark-java"      % "0.64.8",
        libraryDependencies += "com.knuddels"         % "jtokkit"            % "0.6.1"
    )
    .settings(
        `kyo-settings`,
        `without-cross-scala`,
        libraryDependencies += "com.softwaremill.sttp.client3" %% "zio-json"            % "3.9.0",
        libraryDependencies += "dev.zio"                       %% "zio-schema"          % "0.4.13",
        libraryDependencies += "dev.zio"                       %% "zio-schema-json"     % "0.4.13",
        libraryDependencies += "dev.zio"                       %% "zio-schema-protobuf" % "0.4.13",
        libraryDependencies += "dev.zio" %% "zio-schema-derivation" % "0.4.13"
    )
    .jsSettings(`js-settings`)

lazy val `kyo-bench` =
  crossProject(JVMPlatform)
    .withoutSuffixFor(JVMPlatform)
    .crossType(CrossType.Pure)
    .in(file("kyo-bench"))
    .enablePlugins(JmhPlugin)
    .dependsOn(`kyo-core-opt` % "test->test;compile->compile")
    .settings(
        `kyo-settings`,
        `without-cross-scala`,
        libraryDependencies += "org.typelevel"       %% "cats-effect"    % "3.5.1",
        libraryDependencies += "dev.zio"             %% "zio"            % zioVersion,
        libraryDependencies += "dev.zio"             %% "zio-concurrent" % zioVersion,
        libraryDependencies += "com.softwaremill.ox" %% "core"           % "0.0.11"
    )

lazy val `js-settings` = Seq(
    Compile / doc / sources := Seq.empty,
    fork                    := false
)
