// Scripted test — error case (empty backend × platform intersection).
//
// Ox supports JVM only; the build requests JS only. Empty intersection
// MUST fail at compatLibrary(...) call time with a message naming the
// backend (`Ox`) and the requested platforms.
//
// The failing call is deferred into a task body so sbt scripted's
// expected-failure step (`-> triggerError`) can observe it WITHOUT
// breaking build.sbt evaluation.

ThisBuild / scalaVersion     := "3.3.4"
ThisBuild / compatKyoVersion := "STUB-FOR-SCRIPTED-TEST"

val triggerError      = taskKey[Unit]("invoke compatLibrary with an empty intersection; expected to throw")
val checkErrorMessage = taskKey[Unit]("invoke compatLibrary inside try/catch and assert the message mentions Ox + js")

// Wrap the failing call in a task so build.sbt evaluation succeeds and
// the scripted step `-> triggerError` can observe the failure.
triggerError := {
    val m = sbt.internal.ProjectMatrix("triggerLib", file("trigger-lib"))
        .compatLibrary(OxLib)(VirtualAxis.js)(Seq("3.3.4"))
    // Force materialization so any deferred error fires.
    val _ = m.componentProjects
    ()
}

checkErrorMessage := {
    val caught: Option[Throwable] =
        try {
            val m = sbt.internal.ProjectMatrix("checkLib", file("check-lib"))
                .compatLibrary(OxLib)(VirtualAxis.js)(Seq("3.3.4"))
            val _ = m.componentProjects
            None
        } catch {
            case t: Throwable => Some(t)
        }
    caught match {
        case None =>
            sys.error("compatLibrary(OxLib)(JS) on a JS-only matrix should have failed but returned a value.")
        case Some(t) =>
            val msg = t.getMessage
            if (msg == null)
                sys.error(s"Empty-intersection error has null message (got: ${t.getClass.getName})")
            val mustContain = Seq("Empty intersection", "Ox", "js")
            val missing     = mustContain.filterNot(msg.contains)
            if (missing.nonEmpty)
                sys.error(s"Error message missing required tokens $missing. Actual message: $msg")
            println(s"checkErrorMessage OK; message contains Ox + js + 'Empty intersection': $msg")
    }
}
