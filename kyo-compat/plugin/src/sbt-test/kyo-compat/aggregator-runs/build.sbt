// Scripted test — aggregator-runs-everything.
//
// Verifies that `myLib.aggregate("my-lib-all")` returns a Project whose
// `Test/test` fans out to EVERY (backend × platform) cell — not just
// compiles, not selectively skips. Each cell's test writes a unique
// sentinel file into a shared dir keyed by its `name.value`. After
// `my-lib-all/Test/test`, `checkAllCellsRan` enumerates the sentinels
// dir and asserts the exact expected set is present.
//
// Matrix: 2 backends (Future + Kyo) × 1 platform (JVM only to keep
// scripted-test runtime down) × 1 Scala (3.3.4). Result: 2 cells.
//
// Why fake-compat stubs: the auto-injected
// `libraryDependencies += "io.getkyo" %%% s"kyo-compat-<backend>" % compatKyoVersion.value`
// must resolve at update-time. The real backends are not in the scripted
// environment, so this build publishes empty stubs to the test-local ivy
// before myLib's tests run. The stubs only exist to satisfy resolution.

ThisBuild / scalaVersion     := "3.3.4"
ThisBuild / compatKyoVersion := "STUB-FOR-SCRIPTED-TEST"

// Pin ivy paths to a known location inside the test dir, mirroring
// publish/ and depends-on/ scripted tests. ThisBuild / ivyPaths is
// shadowed by sbt's per-project Defaults so we apply the setting on
// every project explicitly.
val rootBase: File = file(".").getCanonicalFile
val pinnedIvyPaths: Setting[IvyPaths] =
    ivyPaths := IvyPaths(rootBase, Some(rootBase / "ivy-cache"))

val sentinelsDir: File = rootBase / "sentinels"

// --------------------------------------------------------------------
// fake kyo-compat-{future,kyo} stubs (JVM only).
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
// my-lib — Future + Kyo on JVM. Each cell's Test/test forks a JVM that
// inherits cell.name + sentinel.dir, so the SentinelTest knows which
// sentinel filename to write into the shared sentinels dir.
// --------------------------------------------------------------------

lazy val myLib = (projectMatrix in file("my-lib"))
    .settings(
        pinnedIvyPaths,
        organization := "com.example",
        name         := "my-lib",
        version      := "0.1.0-SNAPSHOT",
        Test / fork           := true,
        // The plugin pins baseDirectory to <matrixBase>/<backend>/<platform>/,
        // a path that doesn't materially exist until something writes there.
        // Forking inherits baseDirectory as cwd, which then fails with ENOENT.
        // Anchor the fork's cwd to the test root (which always exists).
        Test / baseDirectory  := rootBase,
        // The plugin sets `moduleName := name.value + backend.directorySuffix`
        // per cell — that's the per-cell-distinct identity we want as the
        // sentinel filename. `name` itself is `"my-lib"` for every cell.
        Test / javaOptions    += s"-Dcell.name=${moduleName.value}",
        Test / javaOptions    += s"-Dsentinel.dir=${sentinelsDir.getAbsolutePath}",
        libraryDependencies   += "org.scalatest" %% "scalatest" % "3.2.19" % Test
    )
    .compatLibrary(KyoLib)(VirtualAxis.jvm)(Seq("3.3.4"))

// Cross-backend aggregator. Project id = "my-lib-all".
lazy val myLibAll = myLib.aggregate("my-lib-all")

// --------------------------------------------------------------------
// Sentinel-management + assertion tasks.
// --------------------------------------------------------------------

lazy val clearSentinels = taskKey[Unit](
    "Delete the sentinels dir before the aggregator run for determinism"
)
clearSentinels := {
    IO.delete(sentinelsDir)
}

lazy val checkAllCellsRan = taskKey[Unit](
    "Assert every cell's Test/test wrote its sentinel under <test-base>/sentinels/"
)
checkAllCellsRan := {
    // moduleName = name.value + backend.directorySuffix; name is "my-lib";
    // backend.directorySuffix is "-future" / "-kyo" (the same suffix used
    // by SentinelTest via -Dcell.name=${name.value}).
    val expected = Set("my-lib-future", "my-lib-kyo")
    if (!sentinelsDir.isDirectory)
        sys.error(
            s"checkAllCellsRan FAIL: sentinels dir $sentinelsDir does not exist; " +
                "no cell wrote a sentinel — aggregator did not run any test."
        )
    val actual = Option(sentinelsDir.listFiles()).map(_.toSeq).getOrElse(Nil)
        .filter(_.isFile)
        .map(_.getName)
        .toSet
    val missing = expected -- actual
    if (missing.nonEmpty)
        sys.error(
            s"checkAllCellsRan FAIL: missing sentinels $missing in $sentinelsDir " +
                s"(found: $actual). Aggregator did NOT fan Test/test to every cell."
        )
    val extra = actual -- expected
    if (extra.nonEmpty)
        sys.error(
            s"checkAllCellsRan FAIL: unexpected extra sentinels $extra in $sentinelsDir " +
                s"(expected exactly $expected)."
        )
    println(s"checkAllCellsRan OK; sentinels found: ${actual.toSeq.sorted.mkString(", ")}")
}
