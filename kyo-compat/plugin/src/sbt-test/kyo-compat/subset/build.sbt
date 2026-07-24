// Scripted test — subset of backends + subset of platforms.
//
// Backends opted in (extras): Zio, Kyo. (Future is always implicit.)
// Platforms requested: JVM, JS.
//
// Expected per-backend supportedPlatforms intersection:
//   Future (JVM)           ∩ {JVM, JS} = {JVM}
//   Zio    (JVM/JS/Native) ∩ {JVM, JS} = {JVM, JS}
//   Kyo    (JVM/JS/Native) ∩ {JVM, JS} = {JVM, JS}
//
// Ox / TwitterFuture were NOT opted in via compatLibrary(...). Accessing
// myLib.ox / myLib.twitterFuture throws NoSuchBackendException at evaluation time.

ThisBuild / scalaVersion     := "3.3.4"
ThisBuild / compatKyoVersion := "STUB-FOR-SCRIPTED-TEST"

lazy val myLib = (projectMatrix in file("my-lib"))
    .settings(
        organization := "com.example",
        version      := "0.1.0-TEST"
    )
    .compatLibrary(ZioLib, KyoLib)(VirtualAxis.jvm, VirtualAxis.js)(Seq("3.3.4"))

// --------------------------------------------------------------------
// Assertion task keys
// --------------------------------------------------------------------

val checkProjects       = taskKey[Unit]("verify exact project set is Future + Zio + Kyo")
val checkNamedAccessors = taskKey[Unit]("verify .ox / .twitterFuture throw NoSuchBackendException")

checkProjects := {
    val s        = Keys.state.value
    val ext      = sbt.Project.extract(s)
    val expected = Set(
        "myLibFuture",
        "myLibZio",    "myLibZioJS",
        "myLibKyo",    "myLibKyoJS"
    )
    val actual   = ext.structure.allProjectRefs.map(_.project).toSet
    val missing  = expected -- actual
    if (missing.nonEmpty)
        sys.error(s"Missing expected subprojects: $missing (have: $actual)")
    val unwanted = actual.filter { id =>
        id.startsWith("myLib") &&
        id != "myLib" &&
        !expected.contains(id)
    }
    if (unwanted.nonEmpty)
        sys.error(s"Unexpected myLib* subprojects (should only have Future/Zio/Kyo JVM+JS): $unwanted")
    println(s"checkProjects OK; have ${expected.intersect(actual).toSeq.sorted}")
}

checkNamedAccessors := {
    // myLib.future, myLib.zio, myLib.kyo already resolved at build evaluation
    // (above). Verify the opted-out accessors throw NoSuchBackendException.
    val tries = Seq[(String, () => Any)](
        "ox"            -> (() => myLib.ox),
        "twitterFuture" -> (() => myLib.twitterFuture)
    )
    tries.foreach { case (name, thunk) =>
        try {
            val _ = thunk()
            sys.error(s"myLib.$name should have thrown NoSuchBackendException but returned a value")
        } catch {
            case _: NoSuchBackendException =>
                () // expected
            case other: Throwable =>
                sys.error(
                    s"myLib.$name threw ${other.getClass.getName} (${other.getMessage}); " +
                        s"expected NoSuchBackendException"
                )
        }
    }
    println("checkNamedAccessors OK; opted-out backends throw NoSuchBackendException.")
}
