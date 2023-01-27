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
    scalafmtOnCompile := true
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

lazy val prepareOpt = TaskKey[Unit]("prepareOpt", "prepareOpt")

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
      `kyo-core-settings`,
      prepareOpt := {
        import sys.process._
        "./opt-gen.sh".!!
      },
      compile := ((Compile / compile) dependsOn (prepareOpt)).value
  )

lazy val `kyo-core-opt1` = project
  .in(file(s"kyo-core-opt1"))
  .settings(
      name := s"kyo-core-opt1",
      `kyo-core-settings`
  )

lazy val `kyo-core-opt2` = project
  .in(file(s"kyo-core-opt2"))
  .settings(
      name := s"kyo-core-opt2",
      `kyo-core-settings`
  )

lazy val `kyo-core-opt3` = project
  .in(file(s"kyo-core-opt3"))
  .settings(
      name := s"kyo-core-opt3",
      `kyo-core-settings`
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

// # Set up some configuration for publishing to GitHub

// GitHub repo info
val githubOwner: String      = "fwbrasil"
val githubRepository: String = "kyo"

// Settings to set the version based on a tagged release in GitHub,
// or use a default value if not present
val defaultVersion: String = "0.1.0-SNAPSHOT"
val tagWithQualifier: String => String => String =
  qualifier =>
    tagVersion => s"%s.%s.%s-${qualifier}%s".format(tagVersion.split("\\.")*)

val tagAlpha: String => String     = tagWithQualifier("a")
val tagBeta: String => String      = tagWithQualifier("b")
val tagMilestone: String => String = tagWithQualifier("m")
val tagRC: String => String        = tagWithQualifier("rc")
val tagSnapshot: String => String = tagVersion =>
  s"%s.%s.%s-SNAPSHOT".format(tagVersion.split("\\.")*)

val versionFromTag: String = sys.env
  .get("GITHUB_REF_TYPE")
  .filter(_ == "tag")
  .flatMap(_ => sys.env.get("GITHUB_REF_NAME"))
  .flatMap { t =>
    t.headOption.map {
      case 'a' => tagAlpha(t.tail)     // Alpha build, a1.2.3.4 => 1.2.3-a4
      case 'b' => tagBeta(t.tail)      // Beta build, b1.2.3.4 => 1.2.3-b4
      case 'm' => tagMilestone(t.tail) // Milestone build, m1.2.3.4 => 1.2.3-m4
      case 'r' => tagRC(t.tail)        // RC build, r1.2.3.4 => 1.2.3-rc4
      case 's' => tagSnapshot(t.tail)  // SNAPSHOT build, s1.2.3 => 1.2.3-SNAPSHOT
      case 'v' => t.tail               // Production build, should be v1.2.3 => 1.2.3
      case _   => defaultVersion
    }
  }
  .getOrElse(defaultVersion)

ThisBuild / version := versionFromTag

ThisBuild / publishMavenStyle := true // GitHub resolves maven style
ThisBuild / versionScheme     := Some("early-semver")
ThisBuild / publishTo := Some(
    "GitHub Package Registry " at s"https://maven.pkg.github.com/$githubOwner/$githubRepository"
)
ThisBuild / credentials += Credentials(
    "GitHub Package Registry",            // realm
    "maven.pkg.github.com",               // host
    githubOwner,                          // user
    sys.env.getOrElse("GITHUB_TOKEN", "") // password
)
