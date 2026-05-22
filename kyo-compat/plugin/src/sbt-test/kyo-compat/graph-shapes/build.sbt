// Scripted test - graph-shapes.
//
// Exercises three matrix-shape scenarios in one scripted test:
//
//   1. multi-scala  -- compatLibrary(KyoLib)(JVM)(Seq("3.3.4", "3.4.0")) ->
//                      4 cells: myMultiFuture3_3_4, myMultiFuture3_4_0,
//                      myMultiKyo3_3_4, myMultiKyo3_4_0.
//   2. future-only  -- compatLibrary()(JVM)(Seq("3.3.4")) ->
//                      1 cell: myFutureOnlyFuture (Future is the implicit
//                      anchor; empty extras = Future-only).
//   3. single-cell  -- compatLibrary(KyoLib)(JVM)(Seq("3.3.4")) ->
//                      2 cells: mySingleFuture, mySingleKyo.
//
// JVM-only to keep scripted-test runtime down. Fake-stub kyo-compat-{future,kyo}
// jars are published to ivy-cache so resolution succeeds even though no
// real compile or update will happen during the check tasks.

ThisBuild / scalaVersion     := "3.3.4"
ThisBuild / compatKyoVersion := "STUB-FOR-SCRIPTED-TEST"

// Pin ivy paths to a known location inside the test dir, mirroring the
// publish/ scripted test. ThisBuild / ivyPaths is shadowed by sbt's
// per-project Defaults, so we must apply the setting on every project.
val rootBase: File = file(".").getCanonicalFile
val pinnedIvyPaths: Setting[IvyPaths] =
    ivyPaths := IvyPaths(rootBase, Some(rootBase / "ivy-cache"))

// --------------------------------------------------------------------
// fake kyo-compat-{future,kyo} stubs (JVM only).
//
// The plugin auto-injects libraryDependencies +=
// "io.getkyo" %%% s"kyo-compat-<backend>" % compatKyoVersion.value on every
// generated row. Scripted tests run in an isolated env without real backend
// artifacts, so we publish empty stubs here. The check tasks below only
// inspect projectRefs (no compile, no update) but settings access still
// touches libraryDependencies, so the stubs are cheap insurance.
// --------------------------------------------------------------------

def fakeCompat(backend: String): Project = {
    val id  = s"fake_${backend}_jvm"
    val dir = file(s"fake-compat/$backend/jvm")
    Project(id, dir).settings(
        pinnedIvyPaths,
        organization := "io.getkyo",
        moduleName   := s"kyo-compat-$backend",
        version      := "STUB-FOR-SCRIPTED-TEST",
        scalaVersion := "3.3.4"
    )
}

lazy val fakeFutureJVM = fakeCompat("future")
lazy val fakeKyoJVM    = fakeCompat("kyo")

lazy val publishFakes = taskKey[Unit]("publishLocal every fake-compat stub")
publishFakes := Def.sequential(
    fakeFutureJVM / publishLocal,
    fakeKyoJVM    / publishLocal
).value

// --------------------------------------------------------------------
// Three matrices covering the three scenarios.
// --------------------------------------------------------------------

// multi-scala. Two scala versions => 2 backends * 1 platform * 2 scala = 4 cells.
lazy val myMulti = (projectMatrix in file("my-multi"))
    .settings(
        pinnedIvyPaths,
        organization := "com.example",
        name         := "my-multi",
        version      := "0.1.0-TEST"
    )
    .compatLibrary(KyoLib)(VirtualAxis.jvm)(Seq("3.3.4", "3.4.0"))

// future-only. compatLibrary() with empty extras => only the
// implicit Future anchor. 1 backend * 1 platform * 1 scala = 1 cell.
lazy val myFutureOnly = (projectMatrix in file("my-future-only"))
    .settings(
        pinnedIvyPaths,
        organization := "com.example",
        name         := "my-future-only",
        version      := "0.1.0-TEST"
    )
    .compatLibrary()(VirtualAxis.jvm)(Seq("3.3.4"))

// single-cell. Future + Kyo, JVM only, single scala => 2 cells.
lazy val mySingle = (projectMatrix in file("my-single"))
    .settings(
        pinnedIvyPaths,
        organization := "com.example",
        name         := "my-single",
        version      := "0.1.0-TEST"
    )
    .compatLibrary(KyoLib)(VirtualAxis.jvm)(Seq("3.3.4"))

// --------------------------------------------------------------------
// Check tasks. Each enumerates the matrix's projectRefs, prints the
// actual project ids, then asserts the set matches the spec's prediction.
// --------------------------------------------------------------------

val checkMultiScala  = taskKey[Unit]("verify myMulti's 4 cells exist with expected ids")
val checkFutureOnly  = taskKey[Unit]("verify myFutureOnly has exactly 1 future cell")
val checkSingleCell  = taskKey[Unit]("verify mySingle has exactly 2 cells (Future + Kyo)")

// myMulti.projectRefs returns Seq[ProjectReference] (sealed) which can't be
// converted to a Seq[String] of project ids directly. We instead enumerate
// ext.structure.allProjectRefs (a Seq[ProjectRef] where _.project: String)
// and filter to ids whose baseDirectory falls under the given matrix dir.
def projectIdsUnder(s: State, matrixDir: File): Set[String] = {
    val ext   = sbt.Project.extract(s)
    val canon = matrixDir.getCanonicalFile
    ext.structure.allProjectRefs.flatMap { ref =>
        val base = ext.get(ref / Keys.baseDirectory).getCanonicalFile
        if (base.toPath.startsWith(canon.toPath)) Some(ref.project) else None
    }.toSet
}

checkMultiScala := {
    val actual   = projectIdsUnder(Keys.state.value, file("my-multi"))
    val expected = Set(
        "myMultiFuture3_3_4",
        "myMultiFuture3_4_0",
        "myMultiKyo3_3_4",
        "myMultiKyo3_4_0"
    )
    println(s"checkMultiScala: actual project ids = ${actual.toSeq.sorted.mkString(", ")}")
    if (actual != expected)
        sys.error(
            s"checkMultiScala FAIL: expected $expected, got $actual " +
                s"(missing: ${expected -- actual}; extra: ${actual -- expected})"
        )
    println(s"checkMultiScala OK; ${actual.size} cells matched.")
}

checkFutureOnly := {
    val actual   = projectIdsUnder(Keys.state.value, file("my-future-only"))
    val expected = Set("myFutureOnlyFuture")
    println(s"checkFutureOnly: actual project ids = ${actual.toSeq.sorted.mkString(", ")}")
    if (actual != expected)
        sys.error(
            s"checkFutureOnly FAIL: expected $expected, got $actual " +
                s"(missing: ${expected -- actual}; extra: ${actual -- expected})"
        )
    println(s"checkFutureOnly OK; 1 future-only cell matched.")
}

checkSingleCell := {
    val actual   = projectIdsUnder(Keys.state.value, file("my-single"))
    val expected = Set("mySingleFuture", "mySingleKyo")
    println(s"checkSingleCell: actual project ids = ${actual.toSeq.sorted.mkString(", ")}")
    if (actual != expected)
        sys.error(
            s"checkSingleCell FAIL: expected $expected, got $actual " +
                s"(missing: ${expected -- actual}; extra: ${actual -- expected})"
        )
    println(s"checkSingleCell OK; ${actual.size} cells matched.")
}
