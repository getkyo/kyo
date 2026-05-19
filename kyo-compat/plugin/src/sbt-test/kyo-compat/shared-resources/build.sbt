// Scripted test - shared-resources.
//
// Exercises shared resources:
//   <base>/shared/src/main/resources/  reaches every backend cell's
//   published jar. Files placed at my-lib/shared/src/main/resources/...
//   must appear inside both myLibFuture/myLibKyo published jars.
//
// Plugin resource-dir wiring (CompatLibrary.scala customRow process closure,
// at the time of writing):
//
//   sharedMainR = <base>/shared/src/main/resources
//   sharedTestR = <base>/shared/src/test/resources
//   Compile / unmanagedResourceDirectories += sharedMainR
//   Test    / unmanagedResourceDirectories += sharedTestR
//
// NOTE: The plugin DOES NOT currently wire per-backend
// (<base>/<backend>/src/main/resources) or per-cell
// (<base>/<backend>/<platform>/src/main/resources) resource dirs - only the
// shared one. This scripted test deliberately checks per-backend and per-cell
// resources too; if the plugin doesn't wire them, the corresponding check
// fails and surfaces the gap.
//
// Matrix shape: Future + Kyo on JVM only (single platform, single Scala) =
// 2 cells. JVM-only and a single Scala version keep this test under the
// 2-minute budget.

ThisBuild / scalaVersion     := "3.3.4"
ThisBuild / compatKyoVersion := "STUB-FOR-SCRIPTED-TEST"

// Pin ivy paths inside the test dir, mirroring publish/ and source-overrides/.
// `ThisBuild / ivyPaths` is shadowed by sbt's per-project Defaults, so the
// setting must be applied on every project that participates in resolution.
val rootBase: File = file(".").getCanonicalFile
val pinnedIvyPaths: Setting[IvyPaths] =
    ivyPaths := IvyPaths(rootBase, Some(rootBase / "ivy-cache"))

// --------------------------------------------------------------------
// fake kyo-compat-{future,kyo} stubs (JVM only).
//
// Two stubs total: (future, kyo) x (jvm). Their only purpose is to satisfy
// the auto-injected `libraryDependencies += "io.getkyo" %%% "kyo-compat-<X>"`
// when myLib<Backend>/publishLocal triggers update.
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
// my-lib - receiver matrix. Future + Kyo on JVM = 2 cells.
//
// `name := "my-lib"` is required so the published artifactId is
// `my-lib-<backend>` (the plugin sets `moduleName := name.value +
// backend.directorySuffix`). Without this, sbt would derive `name` from the
// project id (`myLib`) and publish as `myLib-<backend>`.
// --------------------------------------------------------------------

lazy val myLib = (projectMatrix in file("my-lib"))
    .settings(
        pinnedIvyPaths,
        organization := "com.example",
        name         := "my-lib",
        version      := "0.1.0-TEST"
    )
    .compatLibrary(KyoLib)(VirtualAxis.jvm)(Seq("3.3.4"))

// --------------------------------------------------------------------
// Helpers + check tasks.
// --------------------------------------------------------------------

// Each cell publishes as my-lib-<backend>_3 (no platform suffix on JVM).
def cellLocalDir(base: File, backend: String): File =
    base / "ivy-cache" / "local" / "com.example" /
        s"my-lib-${backend}_3" / "0.1.0-TEST"

def cellJar(base: File, backend: String): File =
    cellLocalDir(base, backend) / "jars" / s"my-lib-${backend}_3.jar"

// Read a single jar entry into a String. Returns None if entry is absent.
def jarEntry(jar: File, entry: String): Option[String] = {
    val jf = new java.util.jar.JarFile(jar)
    try {
        Option(jf.getJarEntry(entry)).map { je =>
            val is = jf.getInputStream(je)
            try {
                val bytes = is.readAllBytes()
                new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim
            } finally is.close()
        }
    } finally jf.close()
}

val checkSharedResourceInAllJars = taskKey[Unit](
    "shared/src/main/resources/shared-marker.txt in every cell's published jar")
val checkPerBackendResource = taskKey[Unit](
    "<backend>/src/main/resources/<file> reaches that backend's cell jar; absent from other backends")
val checkPerCellResource = taskKey[Unit](
    "<backend>/<platform>/src/main/resources/<file> reaches the matching cell's jar")

// shared-marker.txt must appear in every cell's published jar
// with the expected content.
checkSharedResourceInAllJars := {
    val base = baseDirectory.value
    val cells = Seq("future", "kyo")
    cells.foreach { backend =>
        val jar = cellJar(base, backend)
        if (!jar.isFile)
            sys.error(
                s"checkSharedResourceInAllJars FAIL: missing jar at $jar"
            )
        jarEntry(jar, "shared-marker.txt") match {
            case None =>
                sys.error(
                    s"checkSharedResourceInAllJars FAIL: my-lib-${backend}_3.jar " +
                        "is missing entry 'shared-marker.txt'. The plugin must add " +
                        "<base>/shared/src/main/resources to Compile/unmanagedResourceDirectories."
                )
            case Some(actual) =>
                if (actual != "shared-resource-content")
                    sys.error(
                        s"checkSharedResourceInAllJars FAIL: my-lib-${backend}_3.jar " +
                            s"shared-marker.txt content mismatch. Expected " +
                            s"'shared-resource-content', got '$actual'"
                    )
        }
    }
    println(
        s"checkSharedResourceInAllJars OK; ${cells.size} cells " +
            s"[${cells.map(b => s"my-lib-${b}_3").mkString(", ")}] all carry shared-marker.txt."
    )
}

// per-backend: future/src/main/resources/future-marker.txt
// must appear in myLibFuture's jar and must NOT appear in myLibKyo's jar.
checkPerBackendResource := {
    val base       = baseDirectory.value
    val futureJar  = cellJar(base, "future")
    val kyoJar     = cellJar(base, "kyo")

    if (!futureJar.isFile)
        sys.error(s"checkPerBackendResource FAIL: missing jar at $futureJar")
    if (!kyoJar.isFile)
        sys.error(s"checkPerBackendResource FAIL: missing jar at $kyoJar")

    jarEntry(futureJar, "future-marker.txt") match {
        case None =>
            sys.error(
                "checkPerBackendResource FAIL: my-lib-future_3.jar is missing " +
                    "entry 'future-marker.txt'. The plugin must add " +
                    "<base>/<backend>/src/main/resources to " +
                    "Compile/unmanagedResourceDirectories (analogous to perBackendMain " +
                    "for sources)."
            )
        case Some(actual) =>
            if (actual != "future-only-resource")
                sys.error(
                    "checkPerBackendResource FAIL: my-lib-future_3.jar " +
                        s"future-marker.txt content mismatch. Expected " +
                        s"'future-only-resource', got '$actual'"
                )
    }

    jarEntry(kyoJar, "future-marker.txt") match {
        case Some(_) =>
            sys.error(
                "checkPerBackendResource FAIL: my-lib-kyo_3.jar unexpectedly " +
                    "contains entry 'future-marker.txt'. The Future-backend resource " +
                    "must NOT leak into other backends' jars."
            )
        case None => ()
    }

    println(
        "checkPerBackendResource OK; future-marker.txt present in my-lib-future_3.jar, " +
            "absent from my-lib-kyo_3.jar."
    )
}

// per-cell: future/jvm/src/main/resources/future-jvm-marker.txt
// must appear in myLibFuture's jar (the only Future-JVM cell). With this
// matrix shape (2 backends x 1 platform) there is no other Future cell that
// could falsely include it.
checkPerCellResource := {
    val base      = baseDirectory.value
    val futureJar = cellJar(base, "future")

    if (!futureJar.isFile)
        sys.error(s"checkPerCellResource FAIL: missing jar at $futureJar")

    jarEntry(futureJar, "future-jvm-marker.txt") match {
        case None =>
            sys.error(
                "checkPerCellResource FAIL: my-lib-future_3.jar is missing " +
                    "entry 'future-jvm-marker.txt'. The plugin must add " +
                    "<base>/<backend>/<platform>/src/main/resources to " +
                    "Compile/unmanagedResourceDirectories (analogous to backendMain " +
                    "for sources)."
            )
        case Some(actual) =>
            if (actual != "future-jvm-only-resource")
                sys.error(
                    "checkPerCellResource FAIL: my-lib-future_3.jar " +
                        s"future-jvm-marker.txt content mismatch. Expected " +
                        s"'future-jvm-only-resource', got '$actual'"
                )
    }

    println(
        "checkPerCellResource OK; future-jvm-marker.txt present in my-lib-future_3.jar."
    )
}
