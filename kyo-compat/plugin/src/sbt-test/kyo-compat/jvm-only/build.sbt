// Scripted test — JVM-only, all 5 backends.
//
// `compatLibrary` adds one row per (backend × jvm × scalaVersion). All
// five backends support JVM, so we expect 5 rows. kyo-compat-X is pinned
// to a stub version so the test does not have to resolve real artifacts;
// we only assert on project graph shape, not on `compile`.

ThisBuild / scalaVersion     := "3.3.4"
ThisBuild / compatKyoVersion := "STUB-FOR-SCRIPTED-TEST"

lazy val myLib = (projectMatrix in file("my-lib"))
    .settings(
        organization := "com.example",
        version      := "0.1.0-TEST"
    )
    .compatLibrary(KyoLib, ZioLib, CeLib, OxLib)(VirtualAxis.jvm)(Seq("3.3.4"))

// Cross-backend aggregator. Should fan to all 5 backends' JVM cells.
lazy val myLibAll = myLib.aggregate("my-lib-all")

// --------------------------------------------------------------------
// Assertion task keys
// --------------------------------------------------------------------

val checkProjects        = taskKey[Unit]("verify exact project set")
val checkDeps            = taskKey[Unit]("verify each backend has its kyo-compat-X dep")
val checkSharedSources   = taskKey[Unit]("verify each backend sees my-lib/shared/src/main/scala")
val checkBackendBaseDirs = taskKey[Unit]("verify each backend at my-lib/<id>/jvm/")
val checkArtifactNames   = taskKey[Unit]("verify moduleName := myLib-<id>")

checkProjects := {
    val s        = Keys.state.value
    val ext      = sbt.Project.extract(s)
    // compatLibrary pins defaultAxes to (jvm, scalaABIVersion(scalaVersions.head))
    // so the JVM and Scala suffixes are suppressed in project ids — leaving
    // just the backend suffix (matching sbt-crossproject's
    // `.withoutSuffixFor(JVMPlatform)` convention from the previous design).
    val expected = Set("myLibFuture", "myLibKyo", "myLibZio", "myLibCe", "myLibOx")
    val actual   = ext.structure.allProjectRefs.map(_.project).toSet
    val missing  = expected -- actual
    if (missing.nonEmpty)
        sys.error(s"Missing expected subprojects: $missing (have: $actual)")
    // No JS or Native cells should exist for a JVM-only build.
    val unwanted = actual.filter(id => id.endsWith("JS") || id.endsWith("Native"))
    if (unwanted.nonEmpty)
        sys.error(s"Unexpected JS/Native subprojects in JVM-only build: $unwanted")
    println(s"checkProjects OK; have ${expected.intersect(actual).toSeq.sorted}")
}

checkDeps := {
    val s   = Keys.state.value
    val ext = sbt.Project.extract(s)
    val pairs = Seq(
        myLib.future.jvm -> "kyo-compat-future",
        myLib.kyo.jvm    -> "kyo-compat-kyo",
        myLib.zio.jvm    -> "kyo-compat-zio",
        myLib.ce.jvm     -> "kyo-compat-ce",
        myLib.ox.jvm     -> "kyo-compat-ox"
    )
    pairs.foreach { case (proj, expectedArtifact) =>
        val deps = ext.get(proj / Keys.libraryDependencies)
        val hit = deps.exists { mid =>
            mid.organization == "io.getkyo" &&
            mid.name == expectedArtifact &&
            mid.revision == "STUB-FOR-SCRIPTED-TEST"
        }
        if (!hit)
            sys.error(
                s"Project ${proj.id} missing expected dependency " +
                    s"io.getkyo:$expectedArtifact:STUB-FOR-SCRIPTED-TEST. " +
                    s"Actual deps: ${deps.map(_.toString).mkString(", ")}"
            )
    }
    println("checkDeps OK; every backend has its kyo-compat-X dependency.")
}

checkSharedSources := {
    val s            = Keys.state.value
    val ext          = sbt.Project.extract(s)
    val sharedScala  = (file("my-lib") / "shared" / "src" / "main" / "scala").getCanonicalFile
    val sharedTest   = (file("my-lib") / "shared" / "src" / "test" / "scala").getCanonicalFile
    val backendProjs = Seq(myLib.future.jvm, myLib.kyo.jvm, myLib.zio.jvm, myLib.ce.jvm, myLib.ox.jvm)
    backendProjs.foreach { proj =>
        val mainDirs = ext.get(proj / Compile / Keys.unmanagedSourceDirectories)
            .map(_.getCanonicalFile)
        val testDirs = ext.get(proj / Test / Keys.unmanagedSourceDirectories)
            .map(_.getCanonicalFile)
        if (!mainDirs.contains(sharedScala))
            sys.error(
                s"Project ${proj.id} missing shared main scala dir $sharedScala " +
                    s"(actual: $mainDirs)"
            )
        if (!testDirs.contains(sharedTest))
            sys.error(
                s"Project ${proj.id} missing shared test scala dir $sharedTest " +
                    s"(actual: $testDirs)"
            )
    }
    println("checkSharedSources OK; every backend sees the shared src dirs.")
}

checkBackendBaseDirs := {
    val s   = Keys.state.value
    val ext = sbt.Project.extract(s)
    // compatLibrary pins baseDirectory to <matrixBase>/<backend.name>/<platform>/.
    val pairs = Seq(
        myLib.future.jvm -> (file("my-lib") / "future" / "jvm"),
        myLib.kyo.jvm    -> (file("my-lib") / "kyo"    / "jvm"),
        myLib.zio.jvm    -> (file("my-lib") / "zio"    / "jvm"),
        myLib.ce.jvm     -> (file("my-lib") / "ce"     / "jvm"),
        myLib.ox.jvm     -> (file("my-lib") / "ox"     / "jvm")
    )
    pairs.foreach { case (proj, expectedDir) =>
        val actual = ext.get(proj / Keys.baseDirectory).getCanonicalFile
        val want   = expectedDir.getCanonicalFile
        if (actual != want)
            sys.error(s"Project ${proj.id} baseDirectory $actual != expected $want")
    }
    println("checkBackendBaseDirs OK; every backend lives at my-lib/<id>/jvm/.")
}

checkArtifactNames := {
    val s   = Keys.state.value
    val ext = sbt.Project.extract(s)
    // moduleName = name.value + backend.directorySuffix; name is `myLib`
    // (matrix's id) by default — projectMatrix sets `name := self.id`.
    val pairs = Seq(
        myLib.future.jvm -> "myLib-future",
        myLib.kyo.jvm    -> "myLib-kyo",
        myLib.zio.jvm    -> "myLib-zio",
        myLib.ce.jvm     -> "myLib-ce",
        myLib.ox.jvm     -> "myLib-ox"
    )
    pairs.foreach { case (proj, expectedName) =>
        val actual = ext.get(proj / Keys.moduleName)
        if (actual != expectedName)
            sys.error(s"Project ${proj.id} moduleName [$actual] != expected [$expectedName]")
    }
    println("checkArtifactNames OK; every backend publishes as myLib-<id>.")
}
