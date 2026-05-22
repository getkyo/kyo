// Scripted test - depends-on.
//
// Exercises three dependency-wiring scenarios in one scripted test:
//
//   - compat-to-compat (backend-aware) -- myHttp.dependsOn(myFetcher)
//     where both are compatLibrary matrices. After publishLocal,
//     my-http-future_3.pom must list my-fetcher-future_3 (NOT
//     my-fetcher-kyo_3) as a dependency, and vice-versa for kyo.
//     WeakAxis matching on CompatBackendAxis is the relied-upon
//     projectMatrix feature.
//
//   - compat-to-plain-project -- myThing.dependsOn(plainCommon)
//     where plainCommon is a plain sbt Project (not a matrix).
//     Every cell of myThing should depend on plainCommon, so every
//     backend's POM (my-thing-future_3, my-thing-kyo_3) lists
//     plain-common_3 in its dependencies.
//
//   - transitive A -> B -> C -- three matrices.
//     myA.dependsOn(myB); myB.dependsOn(myC).
//     After publishLocal of all three across Future + Kyo,
//     my-a-future_3.pom directly lists my-b-future_3 (not my-c).
//     my-b-future_3.pom directly lists my-c-future_3.
//     Same for kyo. Verifies projectMatrix's transitive-dep wiring
//     composes correctly with the backend axis (no leak across
//     backends, no flattening of B->C into A->C).
//
// JVM-only to keep scripted-test runtime down. Fake-stub
// kyo-compat-{future,kyo} jars are published to ivy-cache so the
// auto-injected `libraryDependencies +=` in every cell resolves at
// publishLocal time.

ThisBuild / scalaVersion     := "3.3.4"
ThisBuild / compatKyoVersion := "STUB-FOR-SCRIPTED-TEST"

// Pin ivy paths to a known location inside the test dir, mirroring
// the publish/ scripted test. ThisBuild / ivyPaths is shadowed by
// sbt's per-project Defaults so we apply the setting on every project.
val rootBase: File = file(".").getCanonicalFile
val pinnedIvyPaths: Setting[IvyPaths] =
    ivyPaths := IvyPaths(rootBase, Some(rootBase / "ivy-cache"))

// --------------------------------------------------------------------
// fake kyo-compat-{future,kyo} stubs (JVM only).
// Mirrors publish/build.sbt's fake-compat pattern; documented there.
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
// backend-aware compat-to-compat.
// Two matrices, each with Future + Kyo on JVM. myHttp dependsOn myFetcher.
// projectMatrix's MatrixClasspathDependency overload wires each row of
// myHttp to the same-axes row of myFetcher (WeakAxis matching).
// --------------------------------------------------------------------

lazy val myFetcher = (projectMatrix in file("my-fetcher"))
    .settings(
        pinnedIvyPaths,
        organization := "com.example",
        name         := "my-fetcher",
        version      := "0.1.0-SNAPSHOT"
    )
    .compatLibrary(KyoLib)(VirtualAxis.jvm)(Seq("3.3.4"))

lazy val myHttp = (projectMatrix in file("my-http"))
    .settings(
        pinnedIvyPaths,
        organization := "com.example",
        name         := "my-http",
        version      := "0.1.0-SNAPSHOT"
    )
    .compatLibrary(KyoLib)(VirtualAxis.jvm)(Seq("3.3.4"))
    .dependsOn(myFetcher)

// --------------------------------------------------------------------
// compat-to-plain.
// plainCommon is a plain Project; myThing.dependsOn(plainCommon) goes
// through projectMatrix's nonMatrixDependencies path -- every cell
// inherits the dep.
// --------------------------------------------------------------------

lazy val plainCommon = project
    .in(file("plain-common"))
    .settings(
        pinnedIvyPaths,
        organization := "com.example",
        name         := "plain-common",
        version      := "0.1.0-SNAPSHOT",
        scalaVersion := "3.3.4"
    )

lazy val myThing = (projectMatrix in file("my-thing"))
    .settings(
        pinnedIvyPaths,
        organization := "com.example",
        name         := "my-thing",
        version      := "0.1.0-SNAPSHOT"
    )
    .compatLibrary(KyoLib)(VirtualAxis.jvm)(Seq("3.3.4"))
    .dependsOn(plainCommon)

// --------------------------------------------------------------------
// transitive A -> B -> C, all backend-aware.
// --------------------------------------------------------------------

lazy val myC = (projectMatrix in file("my-c"))
    .settings(
        pinnedIvyPaths,
        organization := "com.example",
        name         := "my-c",
        version      := "0.1.0-SNAPSHOT"
    )
    .compatLibrary(KyoLib)(VirtualAxis.jvm)(Seq("3.3.4"))

lazy val myB = (projectMatrix in file("my-b"))
    .settings(
        pinnedIvyPaths,
        organization := "com.example",
        name         := "my-b",
        version      := "0.1.0-SNAPSHOT"
    )
    .compatLibrary(KyoLib)(VirtualAxis.jvm)(Seq("3.3.4"))
    .dependsOn(myC)

lazy val myA = (projectMatrix in file("my-a"))
    .settings(
        pinnedIvyPaths,
        organization := "com.example",
        name         := "my-a",
        version      := "0.1.0-SNAPSHOT"
    )
    .compatLibrary(KyoLib)(VirtualAxis.jvm)(Seq("3.3.4"))
    .dependsOn(myB)

// --------------------------------------------------------------------
// POM helpers + check tasks
// --------------------------------------------------------------------

def localPomFile(base: File, artifactBase: String, backend: String): File =
    base / "ivy-cache" / "local" / "com.example" /
        s"$artifactBase-${backend}_3" / "0.1.0-SNAPSHOT" / "poms" /
        s"$artifactBase-${backend}_3.pom"

// For plain (non-matrix) projects there is no `-<backend>` suffix.
def localPlainPomFile(base: File, artifactBase: String): File =
    base / "ivy-cache" / "local" / "com.example" /
        s"${artifactBase}_3" / "0.1.0-SNAPSHOT" / "poms" /
        s"${artifactBase}_3.pom"

def pomDeps(pom: File): Seq[(String, String)] = {
    val xml = scala.xml.XML.loadFile(pom)
    (xml \\ "dependency").map { d =>
        ((d \ "groupId").text.trim, (d \ "artifactId").text.trim)
    }
}

val checkBackendAwareDeps = taskKey[Unit](
    "my-http POM lists same-backend my-fetcher only"
)
val checkPlainProjectDep = taskKey[Unit](
    "my-thing POM (every backend) lists plain-common_3"
)
val checkTransitiveDeps = taskKey[Unit](
    "A->B and B->C both wire same-backend, no flattening"
)

checkBackendAwareDeps := {
    val base = baseDirectory.value
    val backends = Seq("future", "kyo")
    backends.foreach { backend =>
        val pom = localPomFile(base, "my-http", backend)
        if (!pom.isFile)
            sys.error(s"checkBackendAwareDeps FAIL: missing POM at $pom")
        val deps = pomDeps(pom)
        // Same-backend dep MUST be present.
        val expected = ("com.example", s"my-fetcher-${backend}_3")
        if (!deps.contains(expected))
            sys.error(
                s"checkBackendAwareDeps FAIL: my-http-${backend}_3 POM is missing " +
                    s"$expected. Actual deps: $deps"
            )
        // Other-backend leak MUST NOT be present.
        val others = backends.filterNot(_ == backend)
        others.foreach { other =>
            val foreign = ("com.example", s"my-fetcher-${other}_3")
            if (deps.contains(foreign))
                sys.error(
                    s"checkBackendAwareDeps FAIL: my-http-${backend}_3 POM leaks " +
                        s"foreign dep $foreign (only same-backend my-fetcher allowed). " +
                        s"Actual deps: $deps"
                )
        }
        println(
            s"checkBackendAwareDeps: my-http-${backend}_3 -> my-fetcher-${backend}_3 OK " +
                s"(no foreign-backend leak)."
        )
    }
    println("checkBackendAwareDeps OK; both backends route to matching fetcher row.")
}

checkPlainProjectDep := {
    val base = baseDirectory.value
    val backends = Seq("future", "kyo")
    backends.foreach { backend =>
        val pom = localPomFile(base, "my-thing", backend)
        if (!pom.isFile)
            sys.error(s"checkPlainProjectDep FAIL: missing POM at $pom")
        val deps = pomDeps(pom)
        val expected = ("com.example", "plain-common_3")
        if (!deps.contains(expected))
            sys.error(
                s"checkPlainProjectDep FAIL: my-thing-${backend}_3 POM is missing " +
                    s"$expected. Actual deps: $deps"
            )
        println(
            s"checkPlainProjectDep: my-thing-${backend}_3 -> plain-common_3 OK."
        )
    }
    println("checkPlainProjectDep OK; every backend cell depends on the plain project.")
}

checkTransitiveDeps := {
    val base = baseDirectory.value
    val backends = Seq("future", "kyo")
    backends.foreach { backend =>
        val pomA = localPomFile(base, "my-a", backend)
        val pomB = localPomFile(base, "my-b", backend)
        val pomC = localPomFile(base, "my-c", backend)
        Seq("my-a" -> pomA, "my-b" -> pomB, "my-c" -> pomC).foreach { case (n, p) =>
            if (!p.isFile)
                sys.error(s"checkTransitiveDeps FAIL: missing POM for $n-${backend}_3 at $p")
        }
        val depsA = pomDeps(pomA)
        val depsB = pomDeps(pomB)
        val depsC = pomDeps(pomC)

        // A -> B (same backend) MUST be direct.
        val expectAtoB = ("com.example", s"my-b-${backend}_3")
        if (!depsA.contains(expectAtoB))
            sys.error(
                s"checkTransitiveDeps FAIL: my-a-${backend}_3 POM is missing direct dep " +
                    s"$expectAtoB. Actual deps: $depsA"
            )
        // A must NOT directly list C (transitive deps don't flatten in POMs).
        val flatAtoC = ("com.example", s"my-c-${backend}_3")
        if (depsA.contains(flatAtoC))
            sys.error(
                s"checkTransitiveDeps FAIL: my-a-${backend}_3 POM directly lists " +
                    s"$flatAtoC (transitive dep should be reached via my-b, not flattened). " +
                    s"Actual deps: $depsA"
            )

        // B -> C (same backend) MUST be direct.
        val expectBtoC = ("com.example", s"my-c-${backend}_3")
        if (!depsB.contains(expectBtoC))
            sys.error(
                s"checkTransitiveDeps FAIL: my-b-${backend}_3 POM is missing direct dep " +
                    s"$expectBtoC. Actual deps: $depsB"
            )

        // C must NOT depend on A or B (no upstream leakage).
        val others = backends.filterNot(_ == backend)
        Seq("my-a", "my-b").foreach { upstream =>
            val leak = ("com.example", s"$upstream-${backend}_3")
            if (depsC.contains(leak))
                sys.error(
                    s"checkTransitiveDeps FAIL: my-c-${backend}_3 POM has upstream leak " +
                        s"$leak. Actual deps: $depsC"
                )
        }

        // No cross-backend leak in any of A/B/C.
        Seq(("my-a", depsA), ("my-b", depsB), ("my-c", depsC)).foreach { case (n, d) =>
            others.foreach { other =>
                val foreign = d.filter { case (g, a) =>
                    g == "com.example" && a.endsWith(s"-${other}_3") &&
                        Seq("my-a", "my-b", "my-c").exists(p => a.startsWith(s"$p-"))
                }
                if (foreign.nonEmpty)
                    sys.error(
                        s"checkTransitiveDeps FAIL: $n-${backend}_3 POM leaks cross-backend " +
                            s"deps $foreign. Actual deps: $d"
                    )
            }
        }

        println(
            s"checkTransitiveDeps: chain my-a-${backend}_3 -> my-b-${backend}_3 -> " +
                s"my-c-${backend}_3 verified (no flattening, no cross-backend leak)."
        )
    }
    println("checkTransitiveDeps OK; A->B->C wires same-backend across the full chain.")
}
