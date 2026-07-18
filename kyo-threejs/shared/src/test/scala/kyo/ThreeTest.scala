package kyo

/** Base class for the kyo-threejs test suites: the UITest analog for this module.
  *
  * Node-testable suites (AST, reconciler, transforms, raycast, dispose, animation math) extend this
  * directly and run against real three.js on the Scala.js Node environment. The browser-backed suites
  * (the WebGL acceptance gate, the GL-submit and visual paths) extend it and drive a real Chrome
  * through kyo-browser; for those the `.sequential` config keeps each suite's leaves from launching
  * Chrome processes concurrently under the default leaf parallelism, which otherwise races the CDP
  * connections.
  */
abstract class ThreeTest extends kyo.test.Test[Any]:

    override def timeout = 60.seconds

    override def config = super.config.sequential

end ThreeTest

/** Captures the WARN lines emitted under `Log.let(Log(backend))`, so a leaf can assert on what the
  * library actually SAID rather than on the private bookkeeping behind it.
  *
  * kyo-threejs drops an op it cannot apply and keeps rendering the last good frame, so a drop is
  * invisible unless it speaks. The seams that speak are therefore behaviour, and the tests that pin them
  * assert on the emitted text: a warning that is deduped away, or one that never fires, is exactly the
  * bug. Every other level is discarded; only `warn` is under test here.
  */
class CapturingLog(val level: Log.Level = Log.Level.warn) extends Log.Unsafe:
    @volatile private var captured: Chunk[String] = Chunk.empty
    def warnings: Chunk[String]                   = captured

    val name: String                                                           = "capturing"
    def withName(n: String): Log.Unsafe                                        = this
    def trace(msg: => String)(using Frame, AllowUnsafe): Unit                  = ()
    def trace(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
    def debug(msg: => String)(using Frame, AllowUnsafe): Unit                  = ()
    def debug(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
    def info(msg: => String)(using Frame, AllowUnsafe): Unit                   = ()
    def info(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = ()
    def warn(msg: => String)(using Frame, AllowUnsafe): Unit                   = captured = captured.appended(msg)
    def warn(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = captured = captured.appended(msg)
    def error(msg: => String)(using Frame, AllowUnsafe): Unit                  = ()
    def error(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
end CapturingLog
