// Scripted test — end-to-end publishLocal flow.
//
// Verifies the user-facing publishing contract per backend:
//   - my-lib-<backend>_<platform-suffix>_3.jar lands in the local ivy cache
//   - the published POM lists exactly one io.getkyo:kyo-compat-<backend>
//     dependency (NEVER another backend's compat jar)
//   - the per-platform crossVersion marker on that dep is correct
//     (Binary on JVM, sjs1_ on JS, native0.5_ on Native)
//   - the cross-backend aggregator (publish/skip := true) does NOT publish
//
// Why fake-compat stubs: the auto-injected
// `libraryDependencies += "io.getkyo" %%% s"kyo-compat-<backend>" % compatKyoVersion.value`
// must resolve at update-time for `myLibX/publishLocal` to succeed. The real
// kyo-compat-<backend> backends are not in the scripted-test environment, so
// this build defines 9 stub projects (Future/Kyo/Zio × JVM/JS/Native) that
// publish empty `io.getkyo:kyo-compat-<id>` jars to the test-local ivy
// before myLib's publishLocal runs. The jars only exist to satisfy
// resolution; the test deliberately inspects POM metadata, not bytecode.

ThisBuild / scalaVersion     := "3.3.4"
ThisBuild / compatKyoVersion := "STUB-FOR-SCRIPTED-TEST"

// Pin ivy paths inside the test dir so publishLocal lands in a known
// location and the same dir is also used for resolution.
//
// sbt's `Defaults` sets `ivyPaths := IvyPaths(baseDirectory.value, bootIvyHome(...))`
// at the *project* scope, so `ThisBuild / ivyPaths := ...` (and even
// `Global / ivyPaths`) are shadowed by that per-project default. The only
// way to actually override `ivyHome` is to set `ivyPaths` on every project
// explicitly — `pinnedIvyPaths` is plumbed into the fake-compat stubs and
// into the projectMatrix below.
val rootBase: File = file(".").getCanonicalFile
val pinnedIvyPaths: Setting[IvyPaths] =
    ivyPaths := IvyPaths(rootBase, Some(rootBase / "ivy-cache"))

// --------------------------------------------------------------------
// fake kyo-compat-<backend> stubs (Future/Kyo/Zio × JVM/JS/Native)
//
// Each stub publishes an empty `io.getkyo:kyo-compat-<id>_<suffix>_3:STUB-FOR-SCRIPTED-TEST`
// to the test-local ivy. crossPaths/crossVersion picks up `_sjs1_` /
// `_native0.5_` automatically from the platform plugin.
// --------------------------------------------------------------------

def fakeCompat(backend: String, platform: String, ver: String = "STUB-FOR-SCRIPTED-TEST"): Project = {
    val verSlug = ver.replaceAll("[^A-Za-z0-9]", "_")
    val id  = s"fake_${backend}_${platform}_${verSlug}"
    val dir = file(s"fake-compat/$verSlug/$backend/$platform")
    val base = Project(id, dir).settings(
        pinnedIvyPaths,
        organization := "io.getkyo",
        moduleName   := s"kyo-compat-$backend",
        version      := ver,
        scalaVersion := "3.3.4"
    )
    platform match {
        case "jvm"    => base
        case "js"     => base.enablePlugins(ScalaJSPlugin)
        case "native" => base.enablePlugins(ScalaNativePlugin)
    }
}

lazy val fakeFutureJVM    = fakeCompat("future", "jvm")
lazy val fakeKyoJVM       = fakeCompat("kyo", "jvm")
lazy val fakeKyoJS        = fakeCompat("kyo", "js")
lazy val fakeKyoNative    = fakeCompat("kyo", "native")
lazy val fakeZioJVM       = fakeCompat("zio", "jvm")
lazy val fakeZioJS        = fakeCompat("zio", "js")
lazy val fakeZioNative    = fakeCompat("zio", "native")

// Fake stubs at the override version 9.9.9 (JVM-only since
// myOverride only opts in JVM). Without these the per-cell update-time
// resolution of `kyo-compat-<backend>:9.9.9` would fail.
lazy val fakeFutureJVMOverride = fakeCompat("future", "jvm", "9.9.9")
lazy val fakeKyoJVMOverride    = fakeCompat("kyo", "jvm", "9.9.9")

lazy val publishFakes = taskKey[Unit]("publishLocal every fake-compat stub")
publishFakes := Def.sequential(
    fakeFutureJVM    / publishLocal,
    fakeKyoJVM       / publishLocal,
    fakeKyoJS        / publishLocal,
    fakeKyoNative    / publishLocal,
    fakeZioJVM       / publishLocal,
    fakeZioJS        / publishLocal,
    fakeZioNative    / publishLocal,
    fakeFutureJVMOverride / publishLocal,
    fakeKyoJVMOverride    / publishLocal
).value

// --------------------------------------------------------------------
// my-lib — Future + Kyo + Zio across JVM/JS/Native (3 backends × 3 platforms = 9 cells)
// --------------------------------------------------------------------

lazy val myLib = (projectMatrix in file("my-lib"))
    .settings(
        pinnedIvyPaths,
        organization := "com.example",
        // Override `name` so the published artifactId is `my-lib-<backend>`
        // (the plugin sets `moduleName := name.value + backend.directorySuffix`).
        // Without this, sbt would derive `name` from the project id (`myLib`)
        // and publish as `myLib-<backend>`.
        name         := "my-lib",
        version      := "0.1.0-SNAPSHOT"
    )
    .compatLibrary(KyoLib, ZioLib)(
        VirtualAxis.jvm, VirtualAxis.js, VirtualAxis.native
    )(Seq("3.3.4"))

lazy val `my-lib-all` = myLib.aggregate("my-lib-all")

// --------------------------------------------------------------------
// compatKyoVersion override flows into per-cell POM dep version.
//
// `compatKyoVersion := "9.9.9"` placed in the matrix's `.settings(...)`
// lands on every cell as a project-scoped override. The plugin's per-cell
// `libraryDependencies` setting reads `CompatPlugin.autoImport.compatKyoVersion.value`
// so each cell's update + POM should resolve and list the override version.
// JVM-only + Future/Kyo to keep the override-fake matrix tiny.
// --------------------------------------------------------------------

lazy val myOverride = (projectMatrix in file("my-override"))
    .settings(
        pinnedIvyPaths,
        organization     := "com.example",
        name             := "my-override",
        version          := "0.1.0-SNAPSHOT",
        compatKyoVersion := "9.9.9"
    )
    .compatLibrary(KyoLib)(VirtualAxis.jvm)(Seq("3.3.4"))

// --------------------------------------------------------------------
// Assertions on the published artifacts
// --------------------------------------------------------------------

val checkPublishedJars            = taskKey[Unit]("verify each cell's jar landed in ivy-cache/local")
val checkPomDeps                  = taskKey[Unit]("verify POM lists exactly the matching kyo-compat-<backend> dep")
val checkPomCrossVersionMarkers   = taskKey[Unit]("verify JS/Native POMs carry sjs1_/native0.5_ markers")
val checkAggregatorNotPublished   = taskKey[Unit]("verify the my-lib-all aggregator did not publish")

// (backend, suffix-on-artifactId): "" for JVM, "_sjs1" for JS, "_native0.5" for Native.
// Future is JVM-only; Kyo and Zio cross-compile.
val cells: Seq[(String, String)] = Seq(
    ("future", ""),
    ("kyo",    ""),
    ("kyo",    "_sjs1"),
    ("kyo",    "_native0.5"),
    ("zio",    ""),
    ("zio",    "_sjs1"),
    ("zio",    "_native0.5")
)

def localDir(base: File, backend: String, suffix: String): File =
    base / "ivy-cache" / "local" / "com.example" /
        s"my-lib-${backend}${suffix}_3" / "0.1.0-SNAPSHOT"

checkPublishedJars := {
    val base = baseDirectory.value
    cells.foreach { case (backend, suffix) =>
        val artifact = s"my-lib-${backend}${suffix}_3"
        val jar = localDir(base, backend, suffix) / "jars" / s"$artifact.jar"
        if (!jar.isFile)
            sys.error(s"checkPublishedJars FAIL: missing jar at $jar")
    }
    println(s"checkPublishedJars OK; ${cells.size} jars present in ivy-cache/local.")
}

def pomFile(base: File, backend: String, suffix: String): File =
    localDir(base, backend, suffix) / "poms" / s"my-lib-${backend}${suffix}_3.pom"

def pomDeps(pom: File): Seq[(String, String)] = {
    val xml = scala.xml.XML.loadFile(pom)
    (xml \\ "dependency").map { d =>
        ((d \ "groupId").text.trim, (d \ "artifactId").text.trim)
    }
}

checkPomDeps := {
    val base = baseDirectory.value
    cells.foreach { case (backend, suffix) =>
        val pom = pomFile(base, backend, suffix)
        if (!pom.isFile)
            sys.error(s"checkPomDeps FAIL: missing POM at $pom")
        val deps = pomDeps(pom)
        val compat = deps.filter { case (g, a) =>
            g == "io.getkyo" && a.startsWith("kyo-compat-")
        }
        // Self-backend dep MUST be present (with platform suffix).
        val expectedArtifactPrefix = s"kyo-compat-${backend}${suffix}_"
        val matching = compat.filter { case (_, a) => a.startsWith(expectedArtifactPrefix) }
        if (matching.isEmpty)
            sys.error(
                s"checkPomDeps FAIL: my-lib-${backend}${suffix}_3 POM is missing the " +
                    s"matching $expectedArtifactPrefix* dep. Actual compat deps: $compat"
            )
        // No OTHER backend's compat jar may appear.
        val foreign = compat.filterNot { case (_, a) =>
            a.startsWith(expectedArtifactPrefix)
        }
        if (foreign.nonEmpty)
            sys.error(
                s"checkPomDeps FAIL: my-lib-${backend}${suffix}_3 POM leaks foreign " +
                    s"compat deps: $foreign (only $expectedArtifactPrefix* allowed)"
            )
        println(
            s"checkPomDeps: my-lib-${backend}${suffix}_3 POM lists ${matching.head._2}, " +
                "no other compat deps."
        )
    }
    println(s"checkPomDeps OK; ${cells.size} POMs verified.")
}

checkPomCrossVersionMarkers := {
    val base = baseDirectory.value
    // For each (backend, JS-platform): expect _sjs1_3 suffix on the
    // kyo-compat-<backend> dep.
    val jsCases     = cells.filter(_._2 == "_sjs1")
    val nativeCases = cells.filter(_._2 == "_native0.5")

    jsCases.foreach { case (backend, suffix) =>
        val deps = pomDeps(pomFile(base, backend, suffix))
        val artifactId = deps.collectFirst {
            case (g, a) if g == "io.getkyo" && a.startsWith(s"kyo-compat-${backend}_") => a
        }.getOrElse(
            sys.error(
                s"checkPomCrossVersionMarkers FAIL: my-lib-${backend}${suffix}_3 POM has no " +
                    s"kyo-compat-${backend} dep. Deps: $deps"
            )
        )
        if (!artifactId.endsWith("_sjs1_3"))
            sys.error(
                s"checkPomCrossVersionMarkers FAIL: expected $artifactId to end with " +
                    "'_sjs1_3' (JS cross-version marker)"
            )
    }
    nativeCases.foreach { case (backend, suffix) =>
        val deps = pomDeps(pomFile(base, backend, suffix))
        val artifactId = deps.collectFirst {
            case (g, a) if g == "io.getkyo" && a.startsWith(s"kyo-compat-${backend}_") => a
        }.getOrElse(
            sys.error(
                s"checkPomCrossVersionMarkers FAIL: my-lib-${backend}${suffix}_3 POM has no " +
                    s"kyo-compat-${backend} dep. Deps: $deps"
            )
        )
        if (!artifactId.endsWith("_native0.5_3"))
            sys.error(
                s"checkPomCrossVersionMarkers FAIL: expected $artifactId to end with " +
                    "'_native0.5_3' (Native cross-version marker)"
            )
    }
    println(
        s"checkPomCrossVersionMarkers OK; ${jsCases.size} JS + ${nativeCases.size} Native " +
            "deps carry the per-platform marker."
    )
}

checkAggregatorNotPublished := {
    val aggDir = baseDirectory.value / "ivy-cache" / "local" / "com.example" /
        "my-lib-all" / "0.1.0-SNAPSHOT"
    if (aggDir.exists)
        sys.error(
            s"checkAggregatorNotPublished FAIL: aggregator should be publish-skipped " +
                s"but $aggDir exists"
        )
    println("checkAggregatorNotPublished OK; aggregator is publish-skipped.")
}

// --------------------------------------------------------------------
// Version-override assertion — every myOverride cell's POM lists the
// kyo-compat-<backend> dep at version 9.9.9 (override flowed).
// --------------------------------------------------------------------

val checkVersionOverride = taskKey[Unit](
    "verify compatKyoVersion override flows into every myOverride cell's POM dep version"
)

def pomDepsWithVersion(pom: File): Seq[(String, String, String)] = {
    val xml = scala.xml.XML.loadFile(pom)
    (xml \\ "dependency").map { d =>
        (
            (d \ "groupId").text.trim,
            (d \ "artifactId").text.trim,
            (d \ "version").text.trim
        )
    }
}

checkVersionOverride := {
    val base = baseDirectory.value
    // myOverride opted in Future + Kyo (Future is implicit), JVM-only.
    val overrideCells = Seq("future", "kyo")
    overrideCells.foreach { backend =>
        val pom = base / "ivy-cache" / "local" / "com.example" /
            s"my-override-${backend}_3" / "0.1.0-SNAPSHOT" / "poms" /
            s"my-override-${backend}_3.pom"
        if (!pom.isFile)
            sys.error(s"checkVersionOverride FAIL: missing POM at $pom")
        val deps = pomDepsWithVersion(pom)
        val matching = deps.filter { case (g, a, _) =>
            g == "io.getkyo" && a.startsWith(s"kyo-compat-${backend}_")
        }
        if (matching.isEmpty)
            sys.error(
                s"checkVersionOverride FAIL: my-override-${backend}_3 POM is missing " +
                    s"kyo-compat-${backend}_* dep. Actual deps: $deps"
            )
        matching.foreach { case (g, a, v) =>
            if (v != "9.9.9")
                sys.error(
                    s"checkVersionOverride FAIL: my-override-${backend}_3 POM dep $g:$a " +
                        s"has version '$v', expected '9.9.9' (compatKyoVersion override)"
                )
        }
        println(
            s"checkVersionOverride: my-override-${backend}_3 POM lists " +
                s"${matching.head._2} at version ${matching.head._3}."
        )
    }
    println(s"checkVersionOverride OK; ${overrideCells.size} override-cell POMs verified at 9.9.9.")
}
