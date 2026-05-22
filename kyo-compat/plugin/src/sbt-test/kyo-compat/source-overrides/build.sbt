// Scripted test - source-overrides.
//
// Exercises:
//
//   - Per-backend source overrides:
//     <base>/<backend.name>/src/main/scala/  reaches every platform cell of
//     THAT backend and no cell of other backends. Files placed at
//     my-lib/future/src/main/scala/...  should appear on
//     `Compile/unmanagedSourceDirectories` for myLibFuture (Future is
//     JVM-only) AND must be absent from the three Kyo cells.
//   - Per-(backend, platform) cell overrides:
//     <base>/<backend.name>/<platform>/src/main/scala/  reaches ONLY the
//     matching cell. Files at my-lib/future/jvm/src/main/scala/  should
//     appear on `Compile/unmanagedSourceDirectories` for myLibFuture only.
//   - Shared test sources:
//     <base>/shared/src/test/scala/  reaches every cell's
//     `Test/unmanagedSourceDirectories` (the universal default).
//
// Plugin source-dir wiring (CompatLibrary.scala customRow process closure):
//
//   sharedMain     = <base>/shared/src/main/scala
//   sharedTest     = <base>/shared/src/test/scala
//   perBackendMain = <base>/<backend.name>/src/main/scala
//   perBackendTest = <base>/<backend.name>/src/test/scala
//   backendMain    = <base>/<backend.name>/<platform>/src/main/scala
//   backendTest    = <base>/<backend.name>/<platform>/src/test/scala
//   Compile / unmanagedSourceDirectories ++= Seq(sharedMain, perBackendMain, backendMain)
//   Test    / unmanagedSourceDirectories ++= Seq(sharedTest, perBackendTest, backendTest)
//
// The directory segment uses `backend.name` ("future", "kyo") -- not
// `backend.directorySuffix` ("-future", "-kyo") -- matching the in-tree
// `kyo-compat-example` convention.
//
// Matrix shape: Future + Kyo across JVM + JS + Native = 6 cells. Single
// Scala 3.3.4. Same fake-stub publishLocal pattern as publish/ and
// settings-passthrough/ so the auto-injected `kyo-compat-X` deps resolve
// when checkAllCompile drives Compile/compile.

ThisBuild / scalaVersion     := "3.3.4"
ThisBuild / compatKyoVersion := "STUB-FOR-SCRIPTED-TEST"

// Pin ivy paths inside the test dir, mirroring publish/ and settings-passthrough/.
// `ThisBuild / ivyPaths` is shadowed by sbt's per-project Defaults, so the setting
// must be applied on every project that participates in resolution.
val rootBase: File = file(".").getCanonicalFile
val pinnedIvyPaths: Setting[IvyPaths] =
    ivyPaths := IvyPaths(rootBase, Some(rootBase / "ivy-cache"))

// --------------------------------------------------------------------
// fake kyo-compat-{future,kyo} stubs (JVM + JS + Native).
//
// Six stubs total: (future, kyo) x (jvm, js, native), matching the
// receiver matrix shape. Their only purpose is to satisfy the
// auto-injected `libraryDependencies += "io.getkyo" %%% "kyo-compat-<X>"`
// when checkAllCompile triggers update on each cell.
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
// my-lib - receiver matrix. Future + Kyo x JVM + JS + Native = 6 cells.
// --------------------------------------------------------------------

lazy val myLib = (projectMatrix in file("my-lib"))
    .settings(
        pinnedIvyPaths,
        organization := "com.example",
        name         := "my-lib",
        version      := "0.1.0-TEST"
    )
    .compatLibrary(KyoLib)(VirtualAxis.jvm, VirtualAxis.js, VirtualAxis.native)(Seq("3.3.4"))

// --------------------------------------------------------------------
// Per-cell Project handles. Same enumeration pattern as
// settings-passthrough and cross-platform.
// --------------------------------------------------------------------

lazy val futureCells: Seq[Project] = Seq(
    myLib.future.jvm
)

lazy val kyoCells: Seq[Project] = Seq(
    myLib.kyo.jvm,
    myLib.kyo.js,
    myLib.kyo.native
)

lazy val allCells: Seq[Project] = futureCells ++ kyoCells

// --------------------------------------------------------------------
// Override directories. Computed from the my-lib base ONCE so the
// expected-path comparisons below are exact.
// --------------------------------------------------------------------

val myLibBase: File = (file("my-lib")).getCanonicalFile

// Per-backend and per-cell overrides use
// `backend.name` ("future" / "kyo") for the directory segment, matching the
// plugin's customRow source-dir wiring (no leading dash).
val sharedMain          : File = (myLibBase / "shared" / "src" / "main" / "scala").getCanonicalFile
val sharedTest          : File = (myLibBase / "shared" / "src" / "test" / "scala").getCanonicalFile
val futureBackendMain   : File = (myLibBase / "future" / "src" / "main" / "scala").getCanonicalFile
val kyoBackendMain      : File = (myLibBase / "kyo"    / "src" / "main" / "scala").getCanonicalFile
val futureJvmCellMain   : File = (myLibBase / "future" / "jvm" / "src" / "main" / "scala").getCanonicalFile
val kyoNativeCellMain   : File = (myLibBase / "kyo"    / "native" / "src" / "main" / "scala").getCanonicalFile

// --------------------------------------------------------------------
// Check tasks.
// --------------------------------------------------------------------

val checkSharedReachesAllCells          = taskKey[Unit]("Compile - shared/src/main/scala on every cell")
val checkPerBackendOverride             = taskKey[Unit]("<directorySuffix>/src/main/scala on every cell of THAT backend")
val checkPerCellOverride                = taskKey[Unit]("<directorySuffix>/<platform>/src/main/scala on the matching cell only")
val checkSharedTestSourcesReachAllCells = taskKey[Unit]("Test - shared/src/test/scala on every cell")
val checkAllCompile                     = taskKey[Unit]("Compile/compile every cell to confirm classpath wiring")

// compile-side: every cell's Compile/unmanagedSourceDirectories
// must contain my-lib/shared/src/main/scala. The plugin adds it explicitly in
// the customRow process closure.
checkSharedReachesAllCells := {
    val s   = Keys.state.value
    val ext = sbt.Project.extract(s)
    allCells.foreach { proj =>
        val dirs = ext.get(proj / Compile / unmanagedSourceDirectories)
            .map(_.getCanonicalFile)
        if (!dirs.contains(sharedMain))
            sys.error(
                "checkSharedReachesAllCells FAIL: cell " + proj.id +
                    " is missing shared main dir " + sharedMain +
                    " (actual: " + dirs.mkString(", ") + ")"
            )
    }
    val ids = allCells.map(_.id).sorted.mkString(", ")
    println(
        "checkSharedReachesAllCells OK; " + allCells.size + " cells [" + ids +
            "] all see " + sharedMain
    )
}

// per-backend: future/src/main/scala lands on every Future cell and on no
// Kyo cell. Symmetric for kyo/src/main/scala.
checkPerBackendOverride := {
    val s   = Keys.state.value
    val ext = sbt.Project.extract(s)

    futureCells.foreach { proj =>
        val dirs = ext.get(proj / Compile / unmanagedSourceDirectories)
            .map(_.getCanonicalFile)
        if (!dirs.contains(futureBackendMain))
            sys.error(
                "checkPerBackendOverride FAIL: Future cell " + proj.id +
                    " is missing the per-backend dir " + futureBackendMain +
                    " (actual: " + dirs.mkString(", ") + ")"
            )
    }
    kyoCells.foreach { proj =>
        val dirs = ext.get(proj / Compile / unmanagedSourceDirectories)
            .map(_.getCanonicalFile)
        if (dirs.contains(futureBackendMain))
            sys.error(
                "checkPerBackendOverride FAIL: Kyo cell " + proj.id +
                    " unexpectedly carries the Future-backend dir " + futureBackendMain
            )
    }

    kyoCells.foreach { proj =>
        val dirs = ext.get(proj / Compile / unmanagedSourceDirectories)
            .map(_.getCanonicalFile)
        if (!dirs.contains(kyoBackendMain))
            sys.error(
                "checkPerBackendOverride FAIL: Kyo cell " + proj.id +
                    " is missing the per-backend dir " + kyoBackendMain +
                    " (actual: " + dirs.mkString(", ") + ")"
            )
    }
    futureCells.foreach { proj =>
        val dirs = ext.get(proj / Compile / unmanagedSourceDirectories)
            .map(_.getCanonicalFile)
        if (dirs.contains(kyoBackendMain))
            sys.error(
                "checkPerBackendOverride FAIL: Future cell " + proj.id +
                    " unexpectedly carries the Kyo-backend dir " + kyoBackendMain
            )
    }
    println(
        "checkPerBackendOverride OK; future reaches " + futureCells.size +
            " Future cells only; kyo reaches " + kyoCells.size + " Kyo cells only."
    )
}

// per-cell: future/jvm/src/main/scala lands ONLY on myLibFuture (the
// Future/JVM cell). kyo/native/src/main/scala lands ONLY on myLibKyoNative.
checkPerCellOverride := {
    val s   = Keys.state.value
    val ext = sbt.Project.extract(s)

    val futureJvm = myLib.future.jvm
    val kyoNative = myLib.kyo.native

    val futureJvmDirs = ext.get(futureJvm / Compile / unmanagedSourceDirectories)
        .map(_.getCanonicalFile)
    if (!futureJvmDirs.contains(futureJvmCellMain))
        sys.error(
            "checkPerCellOverride FAIL: " + futureJvm.id +
                " is missing the per-cell dir " + futureJvmCellMain +
                " (actual: " + futureJvmDirs.mkString(", ") + ")"
        )

    allCells.filter(_ != futureJvm).foreach { proj =>
        val dirs = ext.get(proj / Compile / unmanagedSourceDirectories)
            .map(_.getCanonicalFile)
        if (dirs.contains(futureJvmCellMain))
            sys.error(
                "checkPerCellOverride FAIL: cell " + proj.id +
                    " unexpectedly carries the Future/JVM-only dir " + futureJvmCellMain
            )
    }

    val kyoNativeDirs = ext.get(kyoNative / Compile / unmanagedSourceDirectories)
        .map(_.getCanonicalFile)
    if (!kyoNativeDirs.contains(kyoNativeCellMain))
        sys.error(
            "checkPerCellOverride FAIL: " + kyoNative.id +
                " is missing the per-cell dir " + kyoNativeCellMain +
                " (actual: " + kyoNativeDirs.mkString(", ") + ")"
        )

    allCells.filter(_ != kyoNative).foreach { proj =>
        val dirs = ext.get(proj / Compile / unmanagedSourceDirectories)
            .map(_.getCanonicalFile)
        if (dirs.contains(kyoNativeCellMain))
            sys.error(
                "checkPerCellOverride FAIL: cell " + proj.id +
                    " unexpectedly carries the Kyo/Native-only dir " + kyoNativeCellMain
            )
    }
    println(
        "checkPerCellOverride OK; future/jvm reaches myLibFuture only; " +
            "kyo/native reaches myLibKyoNative only."
    )
}

// test-side: every cell's Test/unmanagedSourceDirectories
// must contain my-lib/shared/src/test/scala. The plugin adds it explicitly
// in the customRow process closure.
checkSharedTestSourcesReachAllCells := {
    val s   = Keys.state.value
    val ext = sbt.Project.extract(s)
    allCells.foreach { proj =>
        val dirs = ext.get(proj / Test / unmanagedSourceDirectories)
            .map(_.getCanonicalFile)
        if (!dirs.contains(sharedTest))
            sys.error(
                "checkSharedTestSourcesReachAllCells FAIL: cell " + proj.id +
                    " is missing shared test dir " + sharedTest +
                    " (actual: " + dirs.mkString(", ") + ")"
            )
    }
    val ids = allCells.map(_.id).sorted.mkString(", ")
    println(
        "checkSharedTestSourcesReachAllCells OK; " + allCells.size + " cells [" +
            ids + "] all see " + sharedTest
    )
}

// Drive Compile/compile across every cell to confirm the classpath wiring
// matches the unmanagedSourceDirectories assertions above. Each marker
// object is independent (no cross-references), so a mis-scoped marker
// can only break compilation if it appears as a duplicate definition on
// the same classpath -- a state none of these checks tolerate.
checkAllCompile := Def.sequential(
    myLib.future.jvm    / Compile / compile,
    myLib.kyo.jvm       / Compile / compile,
    myLib.kyo.js        / Compile / compile,
    myLib.kyo.native    / Compile / compile,
    Def.task(println(
        "checkAllCompile OK; " + allCells.size + " cells [" +
            allCells.map(_.id).sorted.mkString(", ") + "] compiled."
    ))
).value
