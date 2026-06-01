package kyo.internal

import java.io.File
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference
import kyo.*
import scala.collection.mutable

/** Shared classpath fixtures for kyo-tasty decoder-fidelity-2 tests.
  *
  * Extends TestClasspaths with a warning-capture sink (withWarningSink) and a singleton standard classpath that is loaded once per JVM for
  * the fidelity-2 test suite. All loading uses ErrorMode.SoftFail.
  *
  * The withWarningSink helper wires a capturing Log.Unsafe into the fiber-local Log before classpath loading so that TypeUnpickler warning
  * messages can be counted and asserted on without affecting other test fibers. This is fiber-local (not global), so concurrent tests do not
  * interfere.
  *
  * Per HARD RULE 1: real-classpath fixtures only; no synthetic data in this file.
  */
private[kyo] object TestClasspaths2:

    /** A captured batch of log warnings from a single classpath load. */
    final case class WarningSink(messages: Seq[String]):
        def countMatching(needle: String): Int = messages.count(_.contains(needle))
        def unknownTagCount: Int               = countMatching("unhandled cat") + countMatching("unknown TASTy type tag")
    end WarningSink

    /** Execute f with a warning-capturing Log installed for the current fiber.
      *
      * The returned WarningSink collects every warn() call that occurs during f's execution. This is fiber-local via Log.let so parallel test
      * fibers do not see each other's log output.
      */
    def withWarningSink[A, S](f: WarningSink => A < S)(using Frame): A < S =
        import AllowUnsafe.embrace.danger
        val buf = mutable.ArrayBuffer.empty[String]
        val sinkLogger: Log.Unsafe = new Log.Unsafe:
            def level: Log.Level                                                       = Log.Level.warn
            def trace(msg: => String)(using Frame, AllowUnsafe): Unit                  = ()
            def trace(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
            def debug(msg: => String)(using Frame, AllowUnsafe): Unit                  = ()
            def debug(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
            def info(msg: => String)(using Frame, AllowUnsafe): Unit                   = ()
            def info(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = ()
            def warn(msg: => String)(using Frame, AllowUnsafe): Unit =
                val m = msg; buf.synchronized { discard(buf += m) }
            def warn(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit =
                val m = msg; buf.synchronized { discard(buf += m) }
            def error(msg: => String)(using Frame, AllowUnsafe): Unit                  = ()
            def error(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
        end sinkLogger
        Log.let(Log(sinkLogger)) {
            f(WarningSink(buf.toSeq))
        }
    end withWarningSink

    /** The standard 3-root combo: kyo-tasty + kyo-data + scala-library (same as TestClasspaths.standard). */
    def standardRoots: Seq[String] = TestClasspaths.standard

    /** Load the standard classpath (kyo-tasty + kyo-data + scala-library) with a warning sink.
      *
      * Returns both the classpath and the warning sink so callers can assert on both.
      */
    def loadStandardWithSink(using Frame): (Tasty.Classpath, WarningSink) < (Async & Scope & Abort[TastyError]) =
        import AllowUnsafe.embrace.danger
        val buf = mutable.ArrayBuffer.empty[String]
        val sinkLogger: Log.Unsafe = new Log.Unsafe:
            def level: Log.Level                                                       = Log.Level.warn
            def trace(msg: => String)(using Frame, AllowUnsafe): Unit                  = ()
            def trace(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
            def debug(msg: => String)(using Frame, AllowUnsafe): Unit                  = ()
            def debug(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
            def info(msg: => String)(using Frame, AllowUnsafe): Unit                   = ()
            def info(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = ()
            def warn(msg: => String)(using Frame, AllowUnsafe): Unit =
                val m = msg; buf.synchronized { discard(buf += m) }
            def warn(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit =
                val m = msg; buf.synchronized { discard(buf += m) }
            def error(msg: => String)(using Frame, AllowUnsafe): Unit                  = ()
            def error(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
        end sinkLogger
        Log.let(Log(sinkLogger)) {
            TestClasspaths.withClasspath(standardRoots).map: cp =>
                (cp, WarningSink(buf.toSeq))
        }
    end loadStandardWithSink

    /** Subset: the kyo-tasty compiled classes directory or jar from the test classpath (same subset as TestClasspaths.kyoTasty). */
    def kyoTastyRoots: Seq[String] = TestClasspaths.kyoTasty

end TestClasspaths2
