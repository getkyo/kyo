val scala3Version = "3.2.2"

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

ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"
sonatypeRepository                 := "https://s01.oss.sonatype.org/service/local"
sonatypeProfileName                := "io.getkyo"
publish / skip                     := true

lazy val `kyo-settings` = Seq(
    scalaVersion := scala3Version,
    fork         := true,
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

lazy val genState: State => State = { s: State =>
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
        `kyo-settings`,
        gen := {
          def genOpt(i: Int) = {
            val origin = new File("kyo-core/")
            val dest   = new File(s"kyo-core-opt$i/")
            IO.copyDirectory(origin, dest)
            transformFiles(dest) { s =>
              var content = s
              for (i <- 1 to i)
                content = content.replaceAllLiterally(s"/*inline(${4 - i})*/", "inline")
              content
            }
          }
          genOpt(1)
          genOpt(2)
          genOpt(3)
        }
    ).aggregate(
        `kyo-core`,
        `kyo-core-opt1`,
        `kyo-core-opt2`,
        `kyo-core-opt3`,
        `kyo-zio`,
        `kyo-direct`,
        `kyo-sttp`,
        `kyo-chatgpt`,
        `kyo-bench`
    )

val zioVersion = "2.0.10"

lazy val `kyo-core-settings` = `kyo-settings` ++ Seq(
    libraryDependencies += "dev.zio"       %%% "izumi-reflect"     % "2.3.7",
    libraryDependencies += "org.slf4j"       % "slf4j-api"         % "2.0.7",
    libraryDependencies += "org.jctools"     % "jctools-core"      % "4.0.1",
    libraryDependencies += "dev.zio"       %%% "zio-test"          % zioVersion   % Test,
    libraryDependencies += "dev.zio"       %%% "zio-test-magnolia" % zioVersion   % Test,
    libraryDependencies += "dev.zio"       %%% "zio-test-sbt"      % zioVersion   % Test,
    libraryDependencies += "dev.zio"       %%% "zio-prelude"       % "1.0.0-RC19" % Test,
    libraryDependencies += "dev.zio"       %%% "zio-laws-laws"     % "1.0.0-RC19" % Test,
    libraryDependencies += "org.scalatest" %%% "scalatest"         % "3.2.15"     % Test,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    Global / concurrentRestrictions := Seq(
        Tags.limit(Tags.CPU, 1)
    )
)

lazy val `kyo-core` =
  crossProject(JSPlatform, JVMPlatform)
    .withoutSuffixFor(JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("kyo-core"))
    .settings(
        `kyo-core-settings`
    )
    .jsSettings(`js-settings`)

lazy val `kyo-scala2` =
  crossProject(JVMPlatform)
    .withoutSuffixFor(JVMPlatform)
    .crossType(CrossType.Pure)
    .dependsOn(`kyo-core`)
    .settings(
        `kyo-settings`,
        scalaVersion := "2.13.10",
        scalacOptions += "-Ytasty-reader",
        libraryDependencies += "org.scalatest" %%% "scalatest" % "3.2.15" % Test
    )
    .in(file("kyo-scala2"))

lazy val `kyo-core-opt1` =
  crossProject(JVMPlatform)
    .withoutSuffixFor(JVMPlatform)
    .crossType(CrossType.Full)
    .in(file(s"kyo-core-opt1"))
    .settings(
        `kyo-core-settings`,
        scalafmtOnCompile := false
    )

lazy val `kyo-core-opt2` =
  crossProject(JVMPlatform)
    .withoutSuffixFor(JVMPlatform)
    .crossType(CrossType.Full)
    .in(file(s"kyo-core-opt2"))
    .settings(
        `kyo-core-settings`,
        scalafmtOnCompile := false
    )

lazy val `kyo-core-opt3` =
  crossProject(JVMPlatform)
    .withoutSuffixFor(JVMPlatform)
    .crossType(CrossType.Full)
    .in(file(s"kyo-core-opt3"))
    .settings(
        `kyo-core-settings`,
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
        libraryDependencies += "com.github.rssh" %%% "dotty-cps-async" % "0.9.16"
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
        libraryDependencies += "com.softwaremill.sttp.client3" %%% "core" % "3.8.15"
    )
    .jsSettings(`js-settings`)

lazy val `kyo-chatgpt` =
  crossProject(JSPlatform, JVMPlatform)
    .withoutSuffixFor(JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("kyo-chatgpt"))
    .dependsOn(`kyo-sttp`)
    .dependsOn(`kyo-direct`)
    .dependsOn(`kyo-core` % "test->test;compile->compile")
    .jvmSettings(
        libraryDependencies += "org.apache.lucene"    % "lucene-core"        % "9.5.0",
        libraryDependencies += "org.apache.lucene"    % "lucene-queryparser" % "9.5.0",
        libraryDependencies += "com.formdev"          % "flatlaf"            % "3.1.1",
        libraryDependencies += "com.vladsch.flexmark" % "flexmark-all"       % "0.64.4",
        libraryDependencies += "com.vladsch.flexmark" % "flexmark-java"      % "0.64.4",
        libraryDependencies += "com.knuddels"         % "jtokkit"            % "0.4.0"
    )
    .settings(
        `kyo-settings`,
        libraryDependencies += "com.softwaremill.sttp.client3" %% "zio-json"            % "3.8.15",
        libraryDependencies += "dev.zio"                       %% "zio-schema"          % "0.4.10",
        libraryDependencies += "dev.zio"                       %% "zio-schema-json"     % "0.4.10",
        libraryDependencies += "dev.zio"                       %% "zio-schema-protobuf" % "0.4.11",
        libraryDependencies += "dev.zio" %% "zio-schema-derivation" % "0.4.10"
    )
    .jsSettings(`js-settings`)

lazy val `kyo-bench` =
  crossProject(JVMPlatform)
    .withoutSuffixFor(JVMPlatform)
    .crossType(CrossType.Pure)
    .in(file("kyo-bench"))
    .enablePlugins(JmhPlugin)
    .dependsOn(`kyo-core-opt3` % "test->test;compile->compile")
    .settings(
        `kyo-settings`,
        libraryDependencies += "org.typelevel"       %% "cats-effect"    % "3.4.10",
        libraryDependencies += "dev.zio"             %% "zio"            % zioVersion,
        libraryDependencies += "dev.zio"             %% "zio-concurrent" % zioVersion,
        libraryDependencies += "com.softwaremill.ox" %% "core"           % "0.0.6"
    )

lazy val `js-settings` = Seq(
    Compile / doc / sources := Seq.empty,
    fork                    := false
)
