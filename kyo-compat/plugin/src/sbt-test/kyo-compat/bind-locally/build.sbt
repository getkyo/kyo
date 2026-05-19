// Scripted test — .bindLocally on a compatLibrary matrix.
//
// `.bindLocally(b, local)` swaps the auto-injected
// `libraryDependencies += "io.getkyo" %%% "kyo-compat-<id>"` for a
// project-level `dependsOn` on a local Project — used by in-tree
// consumers (see the kyo-compat-example block in the kyo root build.sbt).

ThisBuild / scalaVersion     := "3.3.4"
ThisBuild / compatKyoVersion := "STUB-FOR-SCRIPTED-TEST"

// A fake "local kyo-compat-future" stand-in. Project (not projectMatrix)
// — bindLocally accepts any ProjectReference.
lazy val fakeCompatFuture = (project in file("fake-compat-future"))
    .settings(
        organization := "io.getkyo",
        name         := "kyo-compat-future"
    )

// Build a Future-only compat matrix bound to the fake local Project.
// Future is implicit; passing no extras keeps it Future-only.
lazy val myLib = (projectMatrix in file("my-lib"))
    .settings(
        organization := "com.example",
        version      := "0.1.0-TEST"
    )
    .compatLibrary()(VirtualAxis.jvm)(Seq("3.3.4"))
    .bindLocally(FutureLib, fakeCompatFuture)

// --------------------------------------------------------------------
// Assertion task keys
// --------------------------------------------------------------------

val checkLocalDep   = taskKey[Unit]("verify myLibFutureJVM depends on fakeCompatFuture (project-level)")
val checkNoMavenDep = taskKey[Unit]("verify myLibFutureJVM's libraryDependencies does NOT contain kyo-compat-future")

checkLocalDep := {
    val s   = Keys.state.value
    val ext = sbt.Project.extract(s)
    val ref = ext.structure.allProjectRefs.find(_.project == "myLibFuture").getOrElse(
        sys.error(s"myLibFuture project not found in build (have: ${ext.structure.allProjectRefs.map(_.project)})")
    )
    val proj = ext.structure.allProjects(ref.build).find(_.id == ref.project).getOrElse(
        sys.error(s"Project def for ${ref.project} not resolvable from structure")
    )
    val depIds = proj.dependencies.map(_.project.project).toSet
    if (!depIds.contains("fakeCompatFuture"))
        sys.error(
            s"myLibFuture should depend on fakeCompatFuture (project-level dependsOn). " +
                s"Actual dependencies: $depIds"
        )
    println(s"checkLocalDep OK; myLibFuture dependsOn $depIds (includes fakeCompatFuture).")
}

checkNoMavenDep := {
    val s    = Keys.state.value
    val ext  = sbt.Project.extract(s)
    val deps = ext.get(myLib.future.jvm / Keys.libraryDependencies)
    val leaked = deps.filter { mid =>
        mid.organization == "io.getkyo" &&
        mid.name == "kyo-compat-future"
    }
    if (leaked.nonEmpty)
        sys.error(
            s"myLibFuture must NOT have a maven libraryDependencies entry for " +
                s"io.getkyo:kyo-compat-future when locally bound. Leaked entries: $leaked"
        )
    println("checkNoMavenDep OK; locally-bound backend has no maven libraryDependencies entry.")
}

// --------------------------------------------------------------------
// Partial bindLocally.
//
// One backend (Future) is locally bound, another (Kyo) is NOT. The
// unbound backend must continue to pull from the auto-injected
// `io.getkyo:kyo-compat-kyo` maven libraryDependencies entry, while the
// bound backend gets the project-level dependsOn instead.
// --------------------------------------------------------------------

lazy val myPartial = (projectMatrix in file("my-partial"))
    .settings(
        organization := "com.example",
        version      := "0.1.0-TEST"
    )
    .compatLibrary(KyoLib)(VirtualAxis.jvm)(Seq("3.3.4"))
    .bindLocally(FutureLib, fakeCompatFuture)

val checkPartialFutureLocal = taskKey[Unit](
    "verify myPartialFuture has the local dependsOn (Future is bound)"
)
val checkPartialFutureNoMaven = taskKey[Unit](
    "verify myPartialFuture has NO maven kyo-compat-future libraryDependencies entry"
)
val checkPartialKyoMaven = taskKey[Unit](
    "verify myPartialKyo retained the maven kyo-compat-kyo libraryDependencies entry (Kyo unbound)"
)
val checkPartialKyoNoLocal = taskKey[Unit](
    "verify myPartialKyo did NOT acquire any local dependsOn (Kyo unbound)"
)

checkPartialFutureLocal := {
    val s   = Keys.state.value
    val ext = sbt.Project.extract(s)
    val ref = ext.structure.allProjectRefs.find(_.project == "myPartialFuture").getOrElse(
        sys.error(
            s"myPartialFuture project not found. Have: " +
                ext.structure.allProjectRefs.map(_.project)
        )
    )
    val proj = ext.structure.allProjects(ref.build).find(_.id == ref.project).getOrElse(
        sys.error(s"Project def for ${ref.project} not resolvable from structure")
    )
    val depIds = proj.dependencies.map(_.project.project).toSet
    if (!depIds.contains("fakeCompatFuture"))
        sys.error(
            s"myPartialFuture should depend on fakeCompatFuture (project-level dependsOn). " +
                s"Actual dependencies: $depIds"
        )
    println(s"checkPartialFutureLocal OK; myPartialFuture dependsOn $depIds (includes fakeCompatFuture).")
}

checkPartialFutureNoMaven := {
    val s    = Keys.state.value
    val ext  = sbt.Project.extract(s)
    val deps = ext.get(myPartial.future.jvm / Keys.libraryDependencies)
    val leaked = deps.filter { mid =>
        mid.organization == "io.getkyo" && mid.name == "kyo-compat-future"
    }
    if (leaked.nonEmpty)
        sys.error(
            s"myPartialFuture must NOT have a maven libraryDependencies entry for " +
                s"io.getkyo:kyo-compat-future when locally bound. Leaked: $leaked"
        )
    println(
        "checkPartialFutureNoMaven OK; locally-bound Future has no maven kyo-compat-future entry."
    )
}

checkPartialKyoMaven := {
    val s    = Keys.state.value
    val ext  = sbt.Project.extract(s)
    val deps = ext.get(myPartial.kyo.jvm / Keys.libraryDependencies)
    val matching = deps.filter { mid =>
        mid.organization == "io.getkyo" && mid.name == "kyo-compat-kyo"
    }
    if (matching.isEmpty)
        sys.error(
            s"myPartialKyo MUST retain the maven libraryDependencies entry for " +
                s"io.getkyo:kyo-compat-kyo when Kyo is NOT locally bound. " +
                s"Actual io.getkyo deps: ${deps.filter(_.organization == "io.getkyo")}"
        )
    println(
        s"checkPartialKyoMaven OK; unbound Kyo retained ${matching.size} maven kyo-compat-kyo entry " +
            s"(${matching.map(_.name).mkString(", ")})."
    )
}

checkPartialKyoNoLocal := {
    val s   = Keys.state.value
    val ext = sbt.Project.extract(s)
    val ref = ext.structure.allProjectRefs.find(_.project == "myPartialKyo").getOrElse(
        sys.error(
            s"myPartialKyo project not found. Have: " +
                ext.structure.allProjectRefs.map(_.project)
        )
    )
    val proj = ext.structure.allProjects(ref.build).find(_.id == ref.project).getOrElse(
        sys.error(s"Project def for ${ref.project} not resolvable from structure")
    )
    val depIds = proj.dependencies.map(_.project.project).toSet
    if (depIds.contains("fakeCompatFuture"))
        sys.error(
            s"myPartialKyo should NOT have any local dependsOn since Kyo is unbound. " +
                s"Actual dependencies: $depIds"
        )
    println(s"checkPartialKyoNoLocal OK; myPartialKyo has no local compat dependsOn (deps: $depIds).")
}
