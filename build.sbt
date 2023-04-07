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

lazy val `kyo-settings` = Seq(
    scalaVersion := scala3Version,
    fork         := true,
    scalacOptions ++= compilerOptions,
    scalafmtOnCompile := true,
    // organization      := "io.getkyo",
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
    ThisBuild / organization := "giuliohome.com",
    ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org",
    sonatypeRepository                 := "https://s01.oss.sonatype.org/service/local",
    gen                                := {},
    Test / testOptions += Tests.Argument("-oDG")
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

lazy val kyo = (project in file("."))
  .aggregate(
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
  .settings(
      name := "kyo",
      `kyo-settings`,
      publishArtifact := false,
      gen := {
        def genOpt(i: Int) = {
          val origin = new File("kyo-core/src/")
          val dest   = new File(s"kyo-core-opt$i/src/")
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
  )

val zioVersion = "2.0.10"

lazy val `kyo-core-settings` = `kyo-settings` ++ Seq(
    libraryDependencies += "com.lihaoyi"   %% "sourcecode"        % "0.3.0",
    libraryDependencies += "dev.zio"       %% "izumi-reflect"     % "2.2.5",
    libraryDependencies += "org.slf4j"      % "slf4j-api"         % "2.0.7",
    libraryDependencies += "org.jctools"    % "jctools-core"      % "4.0.1",
    libraryDependencies += "dev.zio"       %% "zio-test"          % zioVersion   % Test,
    libraryDependencies += "dev.zio"       %% "zio-test-magnolia" % zioVersion   % Test,
    libraryDependencies += "dev.zio"       %% "zio-test-sbt"      % zioVersion   % Test,
    libraryDependencies += "dev.zio"       %% "zio-prelude"       % "1.0.0-RC18" % Test,
    libraryDependencies += "dev.zio"       %% "zio-laws-laws"     % "1.0.0-RC18" % Test,
    libraryDependencies += "org.scalatest" %% "scalatest"         % "3.2.15"     % Test,
    Global / concurrentRestrictions := Seq(
        Tags.limit(Tags.CPU, 1)
    )
)

lazy val `kyo-core` = project
  .in(file("kyo-core"))
  .settings(
      name := "kyo-core",
      `kyo-core-settings`
  )

lazy val `kyo-core-opt1` = project
  .in(file(s"kyo-core-opt1"))
  .settings(
      name := s"kyo-core-opt1",
      `kyo-core-settings`,
      scalafmtOnCompile := false
  )

lazy val `kyo-core-opt2` = project
  .in(file(s"kyo-core-opt2"))
  .settings(
      name := s"kyo-core-opt2",
      `kyo-core-settings`,
      scalafmtOnCompile := false
  )

lazy val `kyo-core-opt3` = project
  .in(file(s"kyo-core-opt3"))
  .settings(
      name := s"kyo-core-opt3",
      `kyo-core-settings`,
      scalafmtOnCompile := false
  )

lazy val `kyo-direct` = project
  .in(file("kyo-direct"))
  .dependsOn(`kyo-core` % "test->test;compile->compile")
  .settings(
      name := "kyo-direct",
      `kyo-settings`,
      libraryDependencies += "com.github.rssh" %% "dotty-cps-async" % "0.9.16"
  )

lazy val `kyo-zio` = project
  .in(file("kyo-zio"))
  .dependsOn(`kyo-core` % "test->test;compile->compile")
  .settings(
      name := "kyo-zio",
      `kyo-settings`,
      libraryDependencies += "dev.zio" %% "zio" % zioVersion
  )

lazy val `kyo-sttp` = project
  .in(file("kyo-sttp"))
  .dependsOn(`kyo-core` % "test->test;compile->compile")
  .settings(
      name := "kyo-sttp",
      `kyo-settings`,
      libraryDependencies += "com.softwaremill.sttp.client3" %% "core" % "3.8.13"
  )

lazy val `kyo-chatgpt` = project
  .in(file("kyo-chatgpt"))
  .dependsOn(`kyo-sttp`)
  .dependsOn(`kyo-core` % "test->test;compile->compile")
  .settings(
      name := "kyo-chatgpt",
      `kyo-settings`,
      libraryDependencies += "com.softwaremill.sttp.client3" %% "jsoniter" % "3.8.14",
      libraryDependencies += "com.softwaremill.sttp.client3" %% "zio-json" % "3.8.14",
      libraryDependencies += "dev.zio" %% "zio-schema"          % "0.4.10",
      libraryDependencies += "dev.zio" %% "zio-schema-json"     % "0.4.10",
      libraryDependencies += "dev.zio" %% "zio-schema-protobuf" % "0.4.9",
      libraryDependencies += "dev.zio" %% "zio-schema-derivation" % "0.4.10",
      // https://mvnrepository.com/artifact/org.scala-sbt/io
      libraryDependencies += "org.scala-sbt" %% "io" % "1.8.0",
      resourceGenerators in Compile += Def.task {
         val file = (sourceDirectory in Compile).value / "scala" / "kyo" / "quests-original.txt"
         val content = IO.read(file)
         val target = (resourceManaged in Compile).value / "scala" / "kyo" / "quests.scala"
         IO.write(target, content)
         Seq(target)
       }.taskValue,
       mappings in (Compile, packageBin) ++= {
         val resourceDir = (resourceManaged in Compile).value / "kyo-chatgpt"
         val files = (resourceDir ** "*").get.map { file =>
           file -> ("kyo-chatgpt/" + file.getName)
         }
         files
       }
  )

lazy val `kyo-bench` = project
  .in(file("kyo-bench"))
  .enablePlugins(JmhPlugin)
  .dependsOn(`kyo-core` % "test->test;compile->compile")
  .settings(
      name := "kyo-bench",
      `kyo-settings`,
      libraryDependencies += "org.typelevel" %% "cats-effect"    % "3.4.8",
      libraryDependencies += "dev.zio"       %% "zio"            % zioVersion,
      libraryDependencies += "dev.zio"       %% "zio-concurrent" % zioVersion
  )

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
