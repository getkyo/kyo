// Scripted test: .compatConformance wires the plugin-bundled conformance suite.
//
// The plugin bundles the cross-binding conformance suite (kyo-compat/test +
// test-streams) in its jar. `.compatConformance()` makes each generated row
// extract those sources into Test/sourceManaged and adds scalatest +
// -Xmax-inlines. This test checks the wiring on a Future/JVM row: the expected
// source files are extracted (shared bucket + the jvm-only bucket), scalatest is
// on the Test classpath, -Xmax-inlines is set, and an extracted file is
// byte-identical to its bundled resource. The scripted sandbox has no real
// binding, so the suite is not compiled here; that is covered in the main build.
//
// A fake io.getkyo:kyo-compat-future stub is publishLocal'd first (same pattern
// as publish/ and source-overrides/) so the auto-injected backend dependency
// resolves when the checks read the row's settings.

ThisBuild / scalaVersion     := "3.3.4"
ThisBuild / compatKyoVersion := "STUB-COMPAT-VERSION"

// Pin ivy paths inside the test dir (ThisBuild / ivyPaths is shadowed by sbt's
// per-project Defaults, so it is applied on every participating project).
val rootBase: File = file(".").getCanonicalFile
val pinnedIvyPaths: Setting[IvyPaths] =
    ivyPaths := IvyPaths(rootBase, Some(rootBase / "ivy-cache"))

// Fake empty io.getkyo:kyo-compat-future stub so the auto-injected dependency resolves.
lazy val fakeFutureJVM = Project("fakeFutureJVM", file("fake-compat/future/jvm")).settings(
    pinnedIvyPaths,
    organization := "io.getkyo",
    moduleName   := "kyo-compat-future",
    version      := "STUB-COMPAT-VERSION",
    scalaVersion := "3.3.4"
)

lazy val publishFakes = taskKey[Unit]("publishLocal the fake kyo-compat-future stub")
publishFakes := (fakeFutureJVM / publishLocal).value

lazy val myLib = (projectMatrix in file("my-lib"))
    .settings(
        pinnedIvyPaths,
        organization := "com.example",
        version      := "0.1.0-TEST"
    )
    .compatLibrary()(VirtualAxis.jvm)(Seq("3.3.4")) // Future backend (implicit), JVM only
    .compatConformance()

// --------------------------------------------------------------------
// Assertion task keys
// --------------------------------------------------------------------

val checkConformanceSources = taskKey[Unit](
    "verify the bundled conformance sources (shared + jvm buckets) are extracted into Test/sourceManaged"
)
val checkConformanceWiring = taskKey[Unit](
    "verify scalatest and -Xmax-inlines are added to the row's Test scope"
)
val checkByteIdentity = taskKey[Unit](
    "verify an extracted source is byte-identical to its bundled plugin resource"
)

// The 18 shared conformance files + the 1 streams-shared file all reach every
// backend/platform; FromCompletionStageTest is jvm-only (test-jvm bucket).
val expectedShared = Set(
    "AsyncRegisterTest.scala", "AtomicNumTest.scala", "AtomicRefTest.scala",
    "BlockingCedeTest.scala", "BracketEnsureTest.scala", "CChunkTest.scala",
    "ChannelTest.scala", "CompatTest.scala", "ErrorsTest.scala", "FiberTest.scala",
    "ForeachTest.scala", "LatchTest.scala", "LiftingTest.scala", "LocalTest.scala",
    "MeterTest.scala", "PromiseTest.scala", "RaceZipTest.scala", "TimeTest.scala",
    "CStreamTest.scala"
)
val expectedJvmOnly = Set("FromCompletionStageTest.scala")

checkConformanceSources := {
    val generated = (myLib.future.jvm / Test / managedSources).value
    val names     = generated.map(_.getName).toSet
    val missing   = (expectedShared ++ expectedJvmOnly) -- names
    if (missing.nonEmpty)
        sys.error(s"conformance sources not extracted: $missing (got: ${names.toSeq.sorted.mkString(", ")})")
    if (!names.contains("FromCompletionStageTest.scala"))
        sys.error("jvm-only bucket not extracted onto the JVM row (FromCompletionStageTest.scala missing)")
    println(s"checkConformanceSources OK; extracted ${names.size} sources incl. jvm-only FromCompletionStageTest.scala")
}

checkConformanceWiring := {
    val deps = (myLib.future.jvm / Keys.libraryDependencies).value
    if (!deps.exists(m => m.organization == "org.scalatest" && m.name == "scalatest" && m.revision == "3.2.20"))
        sys.error(s"scalatest 3.2.20 not added to the row. libraryDependencies: $deps")
    val opts = (myLib.future.jvm / Test / scalacOptions).value
    if (!opts.contains("-Xmax-inlines:1024"))
        sys.error(s"-Xmax-inlines:1024 not in Test scalacOptions: $opts")
    println("checkConformanceWiring OK; scalatest 3.2.20 + -Xmax-inlines:1024 present")
}

checkByteIdentity := {
    val generated  = (myLib.future.jvm / Test / managedSources).value
    val compatTest = generated.find(_.getName == "CompatTest.scala").getOrElse(
        sys.error("CompatTest.scala was not extracted")
    )
    val extracted = IO.read(compatTest)
    val resStream = getClass.getClassLoader.getResourceAsStream(
        "kyo-compat-testkit/test-shared/kyo/compat/CompatTest.scala"
    )
    if (resStream == null)
        sys.error("bundled CompatTest.scala resource not found on the plugin classpath")
    val bundled =
        try scala.io.Source.fromInputStream(resStream, "UTF-8").mkString
        finally resStream.close()
    if (extracted != bundled)
        sys.error("extracted CompatTest.scala differs from the bundled plugin resource")
    println("checkByteIdentity OK; extracted CompatTest.scala == bundled resource")
}
