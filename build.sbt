
val scala3Version = "3.2.0"

val compilerOptions = Seq(
    "-encoding",
    "utf8",
    "-feature",
    "-unchecked",
    "-deprecation",
    "-language:implicitConversions"
    // "-explain",
    // "-Wvalue-discard",
    //"-Vprofile",
)

lazy val `kyo-settings` = Seq(
    scalaVersion := scala3Version,
    fork         := true,
    scalacOptions ++= compilerOptions,
    scalafmtOnCompile := true,
    
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
    ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org",
    sonatypeRepository := "https://s01.oss.sonatype.org/service/local"
)

lazy val kyo = (project in file("."))
  .aggregate(
      `kyo-core`,
      `kyo-core-opt1`,
      `kyo-core-opt2`,
      `kyo-core-opt3`,
      `kyo-bench`,
      `kyo-zio`,
      `kyo-direct`
  )
  .settings(
      name := "kyo",
      `kyo-settings`,
      publishArtifact := false
  )

lazy val `kyo-core-settings` = `kyo-settings` ++ Seq(
    libraryDependencies += "com.lihaoyi"   %% "sourcecode"        % "0.3.0",
    libraryDependencies += "dev.zio"       %% "izumi-reflect"     % "2.2.2",
    libraryDependencies += "dev.zio"       %% "zio-test"          % "2.0.5"      % Test,
    libraryDependencies += "dev.zio"       %% "zio-test-magnolia" % "2.0.5"      % Test,
    libraryDependencies += "dev.zio"       %% "zio-test-sbt"      % "2.0.5"      % Test,
    libraryDependencies += "dev.zio"       %% "zio-prelude"       % "1.0.0-RC16" % Test,
    libraryDependencies += "dev.zio"       %% "zio-laws-laws"     % "1.0.0-RC16" % Test,
    libraryDependencies += "org.scalatest" %% "scalatest"         % "3.2.11"     % Test
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
      libraryDependencies += "com.github.rssh" %% "dotty-cps-async" % "0.9.14"
  )

lazy val `kyo-zio` = project
  .in(file("kyo-zio"))
  .dependsOn(`kyo-core` % "test->test;compile->compile")
  .settings(
      name := "kyo-zio",
      `kyo-settings`,
      libraryDependencies += "dev.zio" %% "zio" % "2.0.3"
  )

lazy val `kyo-bench` = project
  .in(file("kyo-bench"))
  .enablePlugins(JmhPlugin)
  .dependsOn(`kyo-core-opt1`)
  .settings(
      name := "kyo-bench",
      `kyo-settings`,
      libraryDependencies += "org.typelevel" %% "cats-effect" % "3.3.12",
      libraryDependencies += "dev.zio"       %% "zio"         % "2.0.5"
  )

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

