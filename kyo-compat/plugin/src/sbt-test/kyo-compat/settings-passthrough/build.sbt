// Scripted test - settings-passthrough.
//
// Exercises:
//
//   - .settings(commonSettingMarker := ...)         -> every cell
//   - .jvmSettings(jvmSettingMarker := ...)         -> JVM cells only
//   - .jsSettings(jsSettingMarker := ...)           -> JS  cells only
//   - .nativeSettings(nativeSettingMarker := ...)   -> Native cells only
//   - Test/fork := true + Test/javaOptions += "-Dfoo=bar" on the receiver
//     flows to JVM cells; ForkTest reads System.getProperty("foo") == "bar"
//     from inside a forked test JVM.
//
// Matrix shape: Future + Kyo (both support all 3 platforms) across
// JVM + JS + Native = 6 cells. Single Scala 3.3.4. Per-platform setting
// discrimination is observed by enumerating each cell's Project ref
// (myLib.future.jvm, myLib.future.js, ...) directly, matching the
// pattern used by the cross-platform scripted test.
//
// Why fake-stub kyo-compat-X jars: the plugin auto-injects
// `libraryDependencies += "io.getkyo" %%% "kyo-compat-<backend>" % compatKyoVersion.value`
// on every generated row. The check tasks themselves don't compile cells,
// but checkForkTest invokes Test/test, which forces update on the JVM
// cells. We publishLocal empty stubs so resolution succeeds without hitting
// any real backend artifacts. Same pattern as publish/.

ThisBuild / scalaVersion     := "3.3.4"
ThisBuild / compatKyoVersion := "STUB-FOR-SCRIPTED-TEST"

// Custom marker keys default to "" on ThisBuild. Per-cell .settings /
// .jvmSettings / .jsSettings / .nativeSettings calls below override the
// default on the matching cells; the check tasks read each cell's value
// and assert the expected JVM/JS/Native partitioning.
ThisBuild / commonSettingMarker := ""
ThisBuild / jvmSettingMarker    := ""
ThisBuild / jsSettingMarker     := ""
ThisBuild / nativeSettingMarker := ""
ThisBuild / forkSettingMarker   := ""

lazy val commonSettingMarker = settingKey[String](".settings(...) reaches every cell")
lazy val jvmSettingMarker    = settingKey[String](".jvmSettings(...) reaches JVM cells only")
lazy val jsSettingMarker     = settingKey[String](".jsSettings(...) reaches JS cells only")
lazy val nativeSettingMarker = settingKey[String](".nativeSettings(...) reaches Native cells only")
lazy val forkSettingMarker   = settingKey[String]("sanity marker for fork settings")

// Pin ivy paths inside the test dir, mirroring the publish/ scripted test.
// ThisBuild / ivyPaths is shadowed by sbt's per-project Defaults, so the
// setting must be applied on every project.
val rootBase: File = file(".").getCanonicalFile
val pinnedIvyPaths: Setting[IvyPaths] =
    ivyPaths := IvyPaths(rootBase, Some(rootBase / "ivy-cache"))

// --------------------------------------------------------------------
// fake kyo-compat-{future,kyo} stubs (JVM + JS + Native).
//
// Six stubs total: (future, kyo) x (jvm, js, native), matching the
// receiver matrix shape.
// --------------------------------------------------------------------

def fakeCompat(backend: String, platform: String): Project = {
    val id   = s"fake_${backend}_${platform}"
    val dir  = file(s"fake-compat/$backend/$platform")
    val base = Project(id, dir).settings(
        pinnedIvyPaths,
        organization := "io.getkyo",
        moduleName   := s"kyo-compat-$backend",
        version      := "STUB-FOR-SCRIPTED-TEST",
        scalaVersion := "3.3.4"
    )
    platform match {
        case "jvm"    => base
        case "js"     => base.enablePlugins(ScalaJSPlugin)
        case "native" => base.enablePlugins(ScalaNativePlugin)
    }
}

lazy val fakeFutureJVM    = fakeCompat("future", "jvm")
lazy val fakeKyoJVM       = fakeCompat("kyo",    "jvm")
lazy val fakeKyoJS        = fakeCompat("kyo",    "js")
lazy val fakeKyoNative    = fakeCompat("kyo",    "native")

lazy val publishFakes = taskKey[Unit]("publishLocal every fake-compat stub")
publishFakes := Def.sequential(
    fakeFutureJVM    / publishLocal,
    fakeKyoJVM       / publishLocal,
    fakeKyoJS        / publishLocal,
    fakeKyoNative    / publishLocal
).value

// --------------------------------------------------------------------
// my-lib - receiver matrix that exercises every settings entry point.
// --------------------------------------------------------------------

lazy val myLib = (projectMatrix in file("my-lib"))
    .settings(
        pinnedIvyPaths,
        organization := "com.example",
        name         := "my-lib",
        version      := "0.1.0-TEST",
        // .settings(...) reaches every cell.
        commonSettingMarker := "common-value",
        // Test/fork + Test/javaOptions on the receiver.
        // ForkTest asserts System.getProperty("foo") == "bar" inside a
        // forked test JVM. checkForkTest below invokes Test/test on the
        // JVM cells; if fork or javaOptions did not flow, the assertion
        // and the task fail.
        Test / fork           := true,
        Test / javaOptions    += "-Dfoo=bar",
        forkSettingMarker     := "fork-on",
        // scalatest is the test runtime for ForkTest. %%% picks up the
        // per-platform crossVersion automatically. The shared/src/test
        // sources are compiled across every cell, so the dep must be
        // available on JVM/JS/Native; checkForkTest only RUNS Test/test
        // on the JVM cells.
        libraryDependencies   += "org.scalatest" %%% "scalatest" % "3.2.19" % Test
    )
    // Scenarios 5/6/7: per-platform settings via the kyo-compat plugin's
    // `.jvmSettings` / `.jsSettings` / `.nativeSettings` extension methods on
    // `ProjectMatrix`. These mirror sbt-crossproject's per-platform setters
    // and apply AFTER the receiver's `.settings(...)` so per-platform values
    // win on conflicts.
    .compatLibrary(KyoLib)(VirtualAxis.jvm, VirtualAxis.js, VirtualAxis.native)(Seq("3.3.4"))
    .jvmSettings(jvmSettingMarker       := "jvm-value")
    .jsSettings(jsSettingMarker         := "js-value")
    .nativeSettings(nativeSettingMarker := "native-value")

// --------------------------------------------------------------------
// Per-platform Project handles. Same pattern as cross-platform/build.sbt.
// --------------------------------------------------------------------

lazy val jvmCells: Seq[Project] = Seq(
    myLib.future.jvm,
    myLib.kyo.jvm
)

lazy val jsCells: Seq[Project] = Seq(
    myLib.kyo.js
)

lazy val nativeCells: Seq[Project] = Seq(
    myLib.kyo.native
)

lazy val allCells: Seq[Project] = jvmCells ++ jsCells ++ nativeCells

// --------------------------------------------------------------------
// Check tasks. Each enumerates the relevant cells, prints what it
// observed, then asserts the JVM/JS/Native partitioning. sys.error on
// failure carries enough detail to diagnose without re-running.
// --------------------------------------------------------------------

val checkCommonSettings = taskKey[Unit](".settings(commonSettingMarker) on every cell")
val checkJvmSettings    = taskKey[Unit](".jvmSettings on JVM cells only")
val checkJsSettings     = taskKey[Unit](".jsSettings on JS cells only")
val checkNativeSettings = taskKey[Unit](".nativeSettings on Native cells only")
val checkForkTest       = taskKey[Unit]("Test/fork + Test/javaOptions flow to JVM cells")

checkCommonSettings := {
    val s   = Keys.state.value
    val ext = sbt.Project.extract(s)
    allCells.foreach { proj =>
        val v = ext.get(proj / commonSettingMarker)
        if (v != "common-value") {
            val msg = "checkCommonSettings FAIL: " + proj.id +
                " expected commonSettingMarker = 'common-value', got '" + v + "'"
            sys.error(msg)
        }
    }
    val ids = allCells.map(_.id).sorted.mkString(", ")
    println(
        "checkCommonSettings OK; " + allCells.size + " cells [" + ids +
            "] all carry commonSettingMarker = 'common-value'."
    )
}

// Scenarios 5/6/7: each check task asserts the per-platform marker resolved
// to the expected value on the matching cells AND remained empty (the
// ThisBuild default) on the other cells, proving the `.jvmSettings` /
// `.jsSettings` / `.nativeSettings` calls on the receiver landed only on
// the targeted platform's cells.

checkJvmSettings := {
    val s   = Keys.state.value
    val ext = sbt.Project.extract(s)
    val nonJvmCells = jsCells ++ nativeCells
    jvmCells.foreach { proj =>
        val v = ext.get(proj / jvmSettingMarker)
        if (v != "jvm-value") {
            val msg = "checkJvmSettings FAIL: JVM cell " + proj.id +
                " expected jvmSettingMarker = 'jvm-value', got '" + v + "'"
            sys.error(msg)
        }
    }
    nonJvmCells.foreach { proj =>
        val v = ext.get(proj / jvmSettingMarker)
        if (v != "") {
            val msg = "checkJvmSettings FAIL: non-JVM cell " + proj.id +
                " unexpectedly has jvmSettingMarker = '" + v + "' (expected '')"
            sys.error(msg)
        }
    }
    val jvmIds    = jvmCells.map(_.id).sorted.mkString(", ")
    val nonJvmIds = nonJvmCells.map(_.id).sorted.mkString(", ")
    println(
        "checkJvmSettings OK; JVM cells [" + jvmIds + "] carry jvmSettingMarker = 'jvm-value', " +
            "non-JVM cells [" + nonJvmIds + "] carry the empty default."
    )
}

checkJsSettings := {
    val s   = Keys.state.value
    val ext = sbt.Project.extract(s)
    val nonJsCells = jvmCells ++ nativeCells
    jsCells.foreach { proj =>
        val v = ext.get(proj / jsSettingMarker)
        if (v != "js-value") {
            val msg = "checkJsSettings FAIL: JS cell " + proj.id +
                " expected jsSettingMarker = 'js-value', got '" + v + "'"
            sys.error(msg)
        }
    }
    nonJsCells.foreach { proj =>
        val v = ext.get(proj / jsSettingMarker)
        if (v != "") {
            val msg = "checkJsSettings FAIL: non-JS cell " + proj.id +
                " unexpectedly has jsSettingMarker = '" + v + "' (expected '')"
            sys.error(msg)
        }
    }
    val jsIds    = jsCells.map(_.id).sorted.mkString(", ")
    val nonJsIds = nonJsCells.map(_.id).sorted.mkString(", ")
    println(
        "checkJsSettings OK; JS cells [" + jsIds + "] carry jsSettingMarker = 'js-value', " +
            "non-JS cells [" + nonJsIds + "] carry the empty default."
    )
}

checkNativeSettings := {
    val s   = Keys.state.value
    val ext = sbt.Project.extract(s)
    val nonNativeCells = jvmCells ++ jsCells
    nativeCells.foreach { proj =>
        val v = ext.get(proj / nativeSettingMarker)
        if (v != "native-value") {
            val msg = "checkNativeSettings FAIL: Native cell " + proj.id +
                " expected nativeSettingMarker = 'native-value', got '" + v + "'"
            sys.error(msg)
        }
    }
    nonNativeCells.foreach { proj =>
        val v = ext.get(proj / nativeSettingMarker)
        if (v != "") {
            val msg = "checkNativeSettings FAIL: non-Native cell " + proj.id +
                " unexpectedly has nativeSettingMarker = '" + v + "' (expected '')"
            sys.error(msg)
        }
    }
    val nativeIds    = nativeCells.map(_.id).sorted.mkString(", ")
    val nonNativeIds = nonNativeCells.map(_.id).sorted.mkString(", ")
    println(
        "checkNativeSettings OK; Native cells [" + nativeIds + "] carry nativeSettingMarker = 'native-value', " +
            "non-Native cells [" + nonNativeIds + "] carry the empty default."
    )
}

// Fan out Test/test to every JVM cell. ForkTest's
// `assert(System.getProperty("foo") == "bar")` only passes if both
// `Test/fork := true` and `Test/javaOptions += "-Dfoo=bar"` propagated to
// the cell's task scope. A failed assertion fails the test runner, which
// fails this task.
checkForkTest := Def.sequential(
    myLib.future.jvm / Test / test,
    myLib.kyo.jvm    / Test / test,
    Def.task(println(
        s"checkForkTest OK; ${jvmCells.size} JVM cells (myLibFuture, myLibKyo) ran " +
            "ForkTest under fork with -Dfoo=bar."
    ))
).value
