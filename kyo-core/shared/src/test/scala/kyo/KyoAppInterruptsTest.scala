package kyo

import java.io.ByteArrayOutputStream
import kyo.internal.KyoAppRunner
import kyo.internal.KyoAppRunnerWithInterrupts

/** How a signalled stop is reported.
  *
  * A host stops a stdio server by sending SIGTERM, so this is the ordinary end of a long-running kyo
  * application, not a fault. It used to end by rethrowing the interrupt, which printed
  * `Exception in thread "main" kyo.Interrupted: Fiber interrupted ... by Interrupt Signal: TERM` and
  * exited non-zero. A supervisor that surfaces a subprocess's stderr then shows a stack-trace-shaped line
  * on every normal stop, which teaches an operator to ignore that stream.
  */
class KyoAppInterruptsTest extends kyo.test.Test[Any]:

    given AllowUnsafe = AllowUnsafe.embrace.danger

    /** A runner that records what it would have done to the process. */
    private class Recording extends KyoAppRunner:
        var exited: Maybe[Int]                                     = Absent
        protected def exitHook(code: Int)(using AllowUnsafe): Unit = exited = Present(code)
        protected def handle[A](v: A < (Async & Scope & Abort[Any]))(using Frame): A < (Async & Abort[Throwable]) =
            throw new UnsupportedOperationException("not used by these tests")

        /** Exposes the entrypoint's result handling, which is `protected` on the runner. */
        def report[E, A](result: Result[E, A])(using Render[Result[E, A]], AllowUnsafe): Unit =
            onResult(result)
    end Recording

    /** The same, with the real signal wiring mixed in. */
    private class RecordingWithInterrupts extends Recording, KyoAppRunnerWithInterrupts

    /** Runs `f` with stdout and stderr captured. */
    private def captured(f: => Unit): (String, String) =
        val out = new ByteArrayOutputStream
        val err = new ByteArrayOutputStream
        scala.Console.withOut(out)(scala.Console.withErr(err)(f))
        (out.toString, err.toString)
    end captured

    "a signalled stop" - {

        "ends quietly with the signal's conventional exit code" in {
            val runner = new RecordingWithInterrupts
            val cause  = runner.recordSignal("TERM", 143)
            val (out, err) = captured {
                runner.report(Result.panic(cause))
            }
            assert(runner.exited == Present(143), s"SIGTERM must exit 143; got: ${runner.exited}")
            assert(out.isEmpty, s"a normal stop must print nothing to stdout; got: $out")
            assert(err.isEmpty, s"a normal stop must print nothing to stderr; got: $err")
        }

        "reports 130 for SIGINT" in {
            val runner = new RecordingWithInterrupts
            val cause  = runner.recordSignal("INT", 130)
            captured(runner.report(Result.panic(cause)))
            assert(runner.exited == Present(130), s"SIGINT must exit 130; got: ${runner.exited}")
        }

        "is recognised by identity, so an application's own interrupt is still a failure" in {
            // The message alone would not distinguish them: an application can raise an interrupt whose
            // text says the same thing, and that is a real failure the operator must see.
            val runner    = new RecordingWithInterrupts
            val _         = runner.recordSignal("TERM", 143)
            val lookalike = Interrupted(Frame.internal, "Interrupt Signal: TERM")
            val thrown =
                try
                    captured(runner.report(Result.panic(lookalike)))
                    Absent
                catch case e: Throwable => Present(e)
            assert(thrown == Present(lookalike), s"an unrelated interrupt must still propagate; got: $thrown")
            assert(runner.exited == Absent, s"and must not report a clean exit; got: ${runner.exited}")
        }
    }

    "a value still reaches the caller's output" in {
        // The signalled-stop branch added above sits in front of the reporting, so it must not swallow
        // the ordinary cases. Which STREAM each outcome lands on is a separate concern and is not
        // asserted here.
        val runner     = new Recording
        val (out, err) = captured(runner.report(Result.succeed(42)))
        assert((out + err).contains("42"), s"a returned value must still be reported; got out=$out err=$err")
        assert(runner.exited == Absent)
    }

    "an ordinary failure still propagates" in {
        // The throw is caught INSIDE the captured block on purpose: catching it outside would let it
        // escape before the captured streams were read.
        val runner                   = new Recording
        val failure                  = new RuntimeException("boom")
        var thrown: Maybe[Throwable] = Absent
        val (out, err) = captured {
            try runner.report(Result.panic(failure))
            catch case e: Throwable => thrown = Present(e)
        }
        assert(thrown == Present(failure), s"a real failure must still be thrown; got: $thrown")
        assert((out + err).contains("boom"), s"the failure must still be reported; got out=$out err=$err")
    }

    "a successful unit result prints nothing and does not exit" in {
        val runner     = new Recording
        val (out, err) = captured(runner.report(Result.succeed(())))
        assert(out.isEmpty && err.isEmpty, s"a quiet success must stay quiet; out=$out err=$err")
        assert(runner.exited == Absent)
    }

end KyoAppInterruptsTest
