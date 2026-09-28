package kyo

import scala.reflect.ClassTag

abstract class BaseWhatsAppTest extends kyo.test.Test[Any]:

    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        HttpClient.withConfig(_.timeout(60.seconds))(body)

    def url(text: String)(using Frame): HttpUrl = HttpUrl.parse(text).getOrThrow

    def epoch(seconds: Long): Instant = Instant.fromJava(java.time.Instant.ofEpochSecond(seconds))

    /** The leaf a failed result holds, of type `L`; fails the test on any other outcome. */
    def failureOf[L](result: Result[Any, Any])(using ct: ClassTag[L], frame: Frame, scope: kyo.test.AssertScope): L =
        result match
            case Result.Failure(l: L) => l
            case other                => fail(s"expected a failure of ${ct.runtimeClass.getSimpleName}, got: $other")

    /** A wrapped cause, of type `C`; fails the test on any other cause. */
    def causeOf[C](cause: Throwable)(using ct: ClassTag[C], frame: Frame, scope: kyo.test.AssertScope): C =
        cause match
            case c: C  => c
            case other => fail(s"expected a ${ct.runtimeClass.getSimpleName} cause, got: $other")

end BaseWhatsAppTest

object BaseWhatsAppTest:

    /** Every text a failure renders: `toString` and `getMessage` of the failure and of each cause in its chain. */
    def renderings(t: Throwable): Chunk[String] =
        @scala.annotation.tailrec
        def loop(current: Throwable, acc: Chunk[String]): Chunk[String] =
            val here = acc :+ current.toString :+ String.valueOf(current.getMessage)
            val next = current.getCause
            if (next eq null) || (next eq current) then here else loop(next, here)
        end loop
        loop(t, Chunk.empty)
    end renderings

    /** A `Log` that records the text of every message at every level, and the rendering of every throwable passed with one. */
    final class RecordingLog(sink: AtomicRef.Unsafe[Chunk[String]]) extends Log.Unsafe:
        private def record(msg: => String, t: Maybe[Throwable])(using AllowUnsafe): Unit =
            val texts = msg +: t.fold(Chunk.empty[String])(renderings)
            discard(sink.updateAndGet(_ ++ texts))
        def level: Log.Level                                                       = Log.Level.trace
        def name: String                                                           = "recording"
        def withName(name: String): Log.Unsafe                                     = this
        def trace(msg: => String)(using Frame, AllowUnsafe): Unit                  = record(msg, Absent)
        def trace(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = record(msg, Present(t))
        def debug(msg: => String)(using Frame, AllowUnsafe): Unit                  = record(msg, Absent)
        def debug(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = record(msg, Present(t))
        def info(msg: => String)(using Frame, AllowUnsafe): Unit                   = record(msg, Absent)
        def info(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = record(msg, Present(t))
        def warn(msg: => String)(using Frame, AllowUnsafe): Unit                   = record(msg, Absent)
        def warn(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = record(msg, Present(t))
        def error(msg: => String)(using Frame, AllowUnsafe): Unit                  = record(msg, Absent)
        def error(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = record(msg, Present(t))
    end RecordingLog

end BaseWhatsAppTest
