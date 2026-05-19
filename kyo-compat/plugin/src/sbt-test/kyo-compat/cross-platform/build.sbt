// Scripted test — all 5 backends, all 3 platforms.
//
// Per-backend supportedPlatforms intersection means:
//   Kyo / Zio       : JVM + JS + Native
//   Ce              : JVM + JS         (Native skipped)
//   Future / Ox     : JVM              (JS + Native skipped)

ThisBuild / scalaVersion     := "3.3.4"
ThisBuild / compatKyoVersion := "STUB-FOR-SCRIPTED-TEST"

lazy val myLib = (projectMatrix in file("my-lib"))
    .settings(
        organization := "com.example",
        version      := "0.1.0-TEST"
    )
    .compatLibrary(KyoLib, ZioLib, CeLib, OxLib)(
        VirtualAxis.jvm, VirtualAxis.js, VirtualAxis.native
    )(Seq("3.3.4"))

// --------------------------------------------------------------------
// Assertion task keys
// --------------------------------------------------------------------

val checkProjects                     = taskKey[Unit]("verify exact project set")
val checkSkippedCells                 = taskKey[Unit]("verify Ce/Native, Ox/JS, Ox/Native are not generated")
val checkDepsPerPlatform              = taskKey[Unit]("verify per-platform cross-version on the kyo-compat-X dep")
val checkSharedSourcesAcrossPlatforms = taskKey[Unit]("verify every per-platform project sees the shared dir")

checkProjects := {
    val s   = Keys.state.value
    val ext = sbt.Project.extract(s)
    val expected = Set(
        "myLibFuture",
        "myLibKyo",    "myLibKyoJS",    "myLibKyoNative",
        "myLibZio",    "myLibZioJS",    "myLibZioNative",
        "myLibCe",     "myLibCeJS",
        "myLibOx"
    )
    val actual  = ext.structure.allProjectRefs.map(_.project).toSet
    val missing = expected -- actual
    if (missing.nonEmpty)
        sys.error(s"Missing expected subprojects: $missing (have: $actual)")
    println(s"checkProjects OK; have ${expected.intersect(actual).toSeq.sorted}")
}

checkSkippedCells := {
    val s   = Keys.state.value
    val ext = sbt.Project.extract(s)
    val mustNotExist = Set("myLibFutureJS", "myLibFutureNative", "myLibCeNative", "myLibOxJS", "myLibOxNative")
    val actual       = ext.structure.allProjectRefs.map(_.project).toSet
    val leaked       = mustNotExist intersect actual
    if (leaked.nonEmpty)
        sys.error(
            s"Cells the backend cannot support leaked into the project graph: $leaked"
        )
    println("checkSkippedCells OK; Future/JS, Future/Native, Ce/Native, Ox/JS, Ox/Native are absent.")
}

checkDepsPerPlatform := {
    val s   = Keys.state.value
    val ext = sbt.Project.extract(s)

    // Each per-platform Project must carry the platform-appropriate
    // cross-version on the auto-injected kyo-compat-X dep:
    //   - JVM: CrossVersion.binary (no sjs/native marker)
    //   - JS:  sjs1_-prefixed Binary
    //   - Native: native0.5_-prefixed Binary
    case class Case(proj: sbt.Project, base: String, platformTag: String)
    val cases = Seq(
        Case(myLib.future.jvm,    "kyo-compat-future", "jvm"),
        Case(myLib.kyo.js,        "kyo-compat-kyo",    "sjs1"),
        Case(myLib.zio.native,    "kyo-compat-zio",    "native"),
        Case(myLib.ce.js,         "kyo-compat-ce",     "sjs1"),
        Case(myLib.ox.jvm,        "kyo-compat-ox",     "jvm")
    )

    cases.foreach { c =>
        val deps = ext.get(c.proj / Keys.libraryDependencies)
        val matching = deps.find { mid =>
            mid.organization == "io.getkyo" &&
            mid.name == c.base &&
            mid.revision == "STUB-FOR-SCRIPTED-TEST"
        }
        matching match {
            case None =>
                sys.error(
                    s"${c.proj.id}: missing io.getkyo:${c.base}:STUB-FOR-SCRIPTED-TEST. " +
                        s"Actual deps: ${deps.map(_.toString).mkString(", ")}"
                )
            case Some(mid) =>
                val cvStr = mid.crossVersion.toString
                c.platformTag match {
                    case "jvm" =>
                        if (cvStr.contains("sjs") || cvStr.contains("native"))
                            sys.error(
                                s"${c.proj.id}: expected JVM-style crossVersion (Binary) on " +
                                    s"${c.base}, got: $cvStr"
                            )
                    case "sjs1" =>
                        if (!cvStr.contains("sjs"))
                            sys.error(
                                s"${c.proj.id}: expected JS-style crossVersion (sjs1_) on " +
                                    s"${c.base}, got: $cvStr"
                            )
                    case "native" =>
                        if (!cvStr.contains("native"))
                            sys.error(
                                s"${c.proj.id}: expected Native-style crossVersion (native0.5_) on " +
                                    s"${c.base}, got: $cvStr"
                            )
                }
        }
    }
    println("checkDepsPerPlatform OK; per-platform cross-version markers present.")
}

checkSharedSourcesAcrossPlatforms := {
    val s            = Keys.state.value
    val ext          = sbt.Project.extract(s)
    val sharedScala  = (file("my-lib") / "shared" / "src" / "main" / "scala").getCanonicalFile

    // Every per-platform Project across every backend should see the shared dir.
    val perPlatformProjects: Seq[sbt.Project] = Seq(
        myLib.future.jvm,
        myLib.kyo.jvm,    myLib.kyo.js,    myLib.kyo.native,
        myLib.zio.jvm,    myLib.zio.js,    myLib.zio.native,
        myLib.ce.jvm,     myLib.ce.js,
        myLib.ox.jvm
    )

    perPlatformProjects.foreach { proj =>
        val mainDirs = ext.get(proj / Compile / Keys.unmanagedSourceDirectories)
            .map(_.getCanonicalFile)
        if (!mainDirs.contains(sharedScala))
            sys.error(
                s"Project ${proj.id} missing shared main scala dir $sharedScala " +
                    s"(actual: $mainDirs)"
            )
    }
    println(
        s"checkSharedSourcesAcrossPlatforms OK; ${perPlatformProjects.size} " +
            "per-platform projects see the shared dir."
    )
}
