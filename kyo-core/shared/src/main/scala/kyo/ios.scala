package kyo

import core.*
import core.internal.*
import iosInternal.*
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.util.*
import scala.util.control.NonFatal

sealed trait IOs extends Effect[IOs]:

    private val tag = Tag[IOs]

    val unit: Unit < IOs = ()

    inline def apply[T, S](
        inline f: => T < (IOs & S)
    ): T < (IOs & S) =
        new KyoIO[T, S]:
            def apply(v: Unit, s: Safepoint[IOs & S], l: Locals.State) =
                f

    def fail[T](ex: Throwable): T < IOs =
        IOs(throw ex)

    def fail[T](msg: String): T < IOs =
        fail(new Exception(msg))

    def fromTry[T, S](v: Try[T] < S): T < (IOs & S) =
        v.map {
            case Success(v) =>
                v
            case Failure(ex) =>
                fail(ex)
        }

    def attempt[T, S](v: => T < S): Try[T] < S =
        eval(v.map(Success(_): Try[T]))((s, k) =>
            try k()
            catch
                case ex if NonFatal(ex) =>
                    Failure(ex)
        )

    def catching[T, S, U >: T, S2](v: => T < S)(
        pf: PartialFunction[Throwable, U < S2]
    ): U < (S & S2) =
        eval(v: U < (S & S2))((s, k) =>
            try k()
            catch
                case ex if NonFatal(ex) && pf.isDefinedAt(ex) =>
                    pf(ex)
        )

    def run[T](v: T < IOs)(using f: Flat[T < IOs]): T =
        @tailrec def runLoop(v: T < IOs): T =
            v match
                case kyo: Suspend[IO, Unit, T, IOs] @unchecked =>
                    bug.checkTag(kyo.tag, tag)
                    runLoop(kyo(()))
                case _ =>
                    v.asInstanceOf[T]
        runLoop(v)
    end run

    def runLazy[T: Flat, S](v: T < (IOs & S)): T < S =
        @tailrec def runLazyLoop(v: T < (IOs & S)): T < S =
            v match
                case kyo: Suspend[?, ?, ?, ?] =>
                    if kyo.tag == tag then
                        val k = kyo.asInstanceOf[Suspend[IO, Unit, T, S & IOs]]
                        runLazyLoop(k(()))
                    else
                        val k = kyo.asInstanceOf[Suspend[MX, Any, T, S & IOs]]
                        new Continue[MX, Any, T, S](k):
                            def apply(v: Any, s: Safepoint[S], l: Locals.State) =
                                runLazyLoop(k(v, s, l))
                case _ =>
                    v.asInstanceOf[T]
            end match
        end runLazyLoop
        runLazyLoop(v)
    end runLazy

    def ensure[T, S](f: => Unit < IOs)(v: T < S): T < (IOs & S) =
        val ensure = new Ensure:
            def run = f
        eval(v: T < (IOs & S))(
            suspend = IOs(_),
            done = (s, v) =>
                Preempt.remove(s, ensure)
                ensure()
                v
            ,
            resume = (s, k) =>
                Preempt.ensure(s, ensure)
                try k()
                catch
                    case ex if NonFatal(ex) =>
                        Preempt.remove(s, ensure)
                        ensure()
                        throw ex
                end try
        )
    end ensure

end IOs
object IOs extends IOs

private[kyo] object iosInternal:

    abstract class Ensure
        extends AtomicReference[Any]
        with Function0[Unit]:

        protected def run: Unit < IOs

        def apply(): Unit =
            if compareAndSet(null, ()) then
                try IOs.run(run)
                catch
                    case ex if NonFatal(ex) =>
                        Logs.unsafe.error(s"IOs.ensure function failed", ex)
    end Ensure

    abstract private[kyo] class KyoIO[T, S]
        extends Suspend[IO, Unit, T, (IOs & S)]:
        final def command = ()
        final def tag     = Tag[IOs].asInstanceOf[Tag[Any]]
    end KyoIO

    trait Preempt extends Safepoint[IOs]:
        def ensure(f: () => Unit): Unit
        def remove(f: () => Unit): Unit
        def suspend[T, S](v: => T < S): T < (IOs & S) =
            IOs(v)
    end Preempt
    object Preempt:
        def ensure(s: Safepoint[?], e: Ensure): Unit =
            s match
                case p: Preempt => p.ensure(e)
                case _          =>

        def remove(s: Safepoint[?], e: Ensure): Unit =
            s match
                case p: Preempt => p.remove(e)
                case _          =>
        val never: Preempt =
            new Preempt:
                def ensure(f: () => Unit) = ()
                def remove(f: () => Unit) = ()
                def preempt()             = false
    end Preempt
    type IO[+T] = T
end iosInternal
