package kyo.internal

import kyo.*
import kyo.CompilerError

class CompilerWorkerTest extends kyo.test.Test[Any]:

    override def timeout = 120.seconds

    /** Finds the first jar on java.class.path whose filename contains the given fragment. */
    private def findJar(fragment: String): String =
        java.lang.System.getProperty("java.class.path", "")
            .split(java.io.File.pathSeparatorChar)
            .find(_.contains(fragment))
            .getOrElse(throw new RuntimeException(s"Jar containing '$fragment' not found on java.class.path"))

    "the worker-side compile path sets shouldReturnDiagnostics: error buffers yield diagnostics, clean yields none" in {
        // Drive the exact worker-side path: parse the per-config Config from the process system
        // properties via WorkerConfig.fromEnv, then host the same LocalBackend the serve-loop drives.
        // The diagnostics flag is set by Wire.compileParams on this side too, so a type error
        // and a syntax error each surface a diagnostic while a clean buffer surfaces none.
        val pcClasspath = Seq(findJar("scala3-library"), findJar("scala-library")).mkString(java.io.File.pathSeparator)
        java.lang.System.setProperty("kyo.internal.WorkerFlags.scalaVersion", "3.8.4")
        java.lang.System.setProperty("kyo.internal.WorkerFlags.classpath", pcClasspath)
        java.lang.System.setProperty("kyo.internal.WorkerFlags.options", "")
        for
            config  <- WorkerConfig.fromEnv()
            backend <- LocalBackend.init(config)
            uri = Compiler.Uri("Worker.scala")

            typeError <- backend.run(Request.Compile(uri, "val x: Int = \"not an int\""))
            _ = typeError match
                case Response.Diagnostics(diags) =>
                    assert(diags.nonEmpty, s"type-error buffer should yield diagnostics, got empty")
                    assert(diags.exists(_.severity == Compiler.Severity.Error), s"expected an Error-severity diagnostic, got $diags")
                case other => assert(false, s"expected Diagnostics, got $other")

            syntaxError <- backend.run(Request.Compile(uri, "object :"))
            _ = syntaxError match
                case Response.Diagnostics(diags) =>
                    assert(diags.nonEmpty, s"syntax-error buffer should yield diagnostics, got empty")
                    assert(diags.exists(_.severity == Compiler.Severity.Error), s"expected an Error-severity diagnostic, got $diags")
                case other => assert(false, s"expected Diagnostics, got $other")

            clean <- backend.run(Request.Compile(uri, "object Main { }"))
            _ = clean match
                case Response.Diagnostics(diags) => assert(diags.isEmpty, s"clean buffer should yield no diagnostics, got $diags")
                case other                       => assert(false, s"expected Diagnostics, got $other")

            _ <- Abort.run[Throwable](backend.close)
        yield ()
        end for
    }

end CompilerWorkerTest
