// Scripted test: every doctest task in one sbt server formats with the same scalafmt, so scalafmt is loaded once.
//
// Each scalafmt load defines scalafmt-core and scalameta's parser in a class loader of its own, thousands of classes. A build
// that loaded it once per module ran out of Metaspace partway through a whole-repository `doctest`. The JVM's cumulative
// loaded-class count measures it: a run that reuses the loaded scalafmt defines almost no new classes.

import java.lang.management.ManagementFactory

ThisBuild / scalaVersion := sys.props("kyo.scalaVersion")

def module(id: String) =
    Project(id, file(id))
        .enablePlugins(KyoDoctestPlugin)
        .settings(
            doctestSources := Seq(baseDirectory.value / "README.md"),
            doctestScalacOptions := Seq("-release", "17"),
            doctestExtraClasspath := IO.readLines(file(sys.props("kyo.doctest.runnerCpFile"))).map(file)
        )

lazy val a = module("a")
lazy val b = module("b")
lazy val c = module("c")

lazy val root = (project in file(".")).aggregate(a, b, c).settings(name := "format-reuse-test")

val loadedMark = settingKey[File]("Where recordLoaded writes the loaded-class count")
loadedMark := target.value / "loaded-classes.txt"

val recordLoaded = taskKey[Unit]("Records the JVM's cumulative loaded-class count")
recordLoaded := IO.write(loadedMark.value, ManagementFactory.getClassLoadingMXBean.getTotalLoadedClassCount.toString)

// A scalafmt load is over 5000 classes; reusing the loaded one defines a few hundred at most.
val checkFewLoaded = taskKey[Unit]("Fails when the runs since recordLoaded loaded scalafmt again")
checkFewLoaded := {
    val before = IO.read(loadedMark.value).trim.toLong
    val loaded = ManagementFactory.getClassLoadingMXBean.getTotalLoadedClassCount - before
    if (loaded > 2000) sys.error(s"two doctest runs loaded $loaded classes; scalafmt was loaded again")
}
