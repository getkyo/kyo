package kyo

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.util.*
import scala.util.control.NonFatal

import core.*
import core.internal.*
import iosInternal.*

sealed trait IOs extends Effect[IO, IOs]:

    val unit: Unit < IOs = ()

    def apply[T, S](
        f: => T < (IOs & S)
    ): T < (IOs & S) =
        new KyoIO[T, S]:
            def apply(v: Unit < (IOs & S), s: Safepoint[IO, IOs], l: Locals.State) =
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
        def attemptLoop(v: T < S): Try[T] < S =
            v match
                case kyo: Kyo[MX, EX, Any, T, S] @unchecked =>
                    new KyoCont[MX, EX, Any, Try[T], S](kyo):
                        def apply(v: Any < S, s: Safepoint[MX, EX], l: Locals.State) =
                            try
                                attemptLoop(kyo(v, s, l))
                            catch
                                case ex: Throwable if (NonFatal(ex)) =>
                                    Failure(ex)
                case _ =>
                    Success(v.asInstanceOf[T])
        try
            val v0 = v
            if v0 == null then
                throw new NullPointerException
            attemptLoop(v0)
        catch
            case ex: Throwable if (NonFatal(ex)) =>
                Failure(ex)
        end try
    end attempt

    def handle[T, S, U >: T, S2](v: => T < S)(
        pf: PartialFunction[Throwable, U < S2]
    ): U < (S & S2) =
        def handleLoop(v: U < (S & S2)): U < (S & S2) =
            v match
                case kyo: Kyo[MX, EX, Any, U, S & S2] @unchecked =>
                    new KyoCont[MX, EX, Any, U, S & S2](kyo):
                        def apply(v: Any < (S & S2), s: Safepoint[MX, EX], l: Locals.State) =
                            try
                                handleLoop(kyo(v, s, l))
                            catch
                                case ex: Throwable if (NonFatal(ex) && pf.isDefinedAt(ex)) =>
                                    pf(ex)
                case _ =>
                    v.asInstanceOf[T]
        try
            val v0 = v
            if v0 == null then
                throw new NullPointerException
            handleLoop(v0)
        catch
            case ex: Throwable if (NonFatal(ex) && pf.isDefinedAt(ex)) =>
                pf(ex)
        end try
    end handle

    def run[T](v: T < IOs)(using f: Flat[T < IOs]): T =
        val safepoint = Safepoint.noop[IO, IOs]
        @tailrec def runLoop(v: T < IOs): T =
            v match
                case kyo: Kyo[IO, IOs, Unit, T, IOs] @unchecked =>
                    require(kyo.effect == IOs, "Unhandled effect: " + kyo.effect)
                    runLoop(kyo((), safepoint, Locals.State.empty))
                case _ =>
                    v.asInstanceOf[T]
        runLoop(v)
    end run

    def runLazy[T, S](v: T < (IOs & S))(using f: Flat[T < (IOs & S)]): T < S =
        def runLazyLoop(v: T < (IOs & S)): T < S =
            val safepoint = Safepoint.noop[IO, IOs]
            v match
                case kyo: Kyo[?, ?, ?, ?, ?] =>
                    if kyo.effect eq IOs then
                        val k = kyo.asInstanceOf[Kyo[IO, IOs, Unit, T, S & IOs]]
                        runLazyLoop(k((), safepoint, Locals.State.empty))
                    else
                        val k = kyo.asInstanceOf[Kyo[MX, EX, Any, T, S & IOs]]
                        new KyoCont[MX, EX, Any, T, S](k):
                            def apply(v: Any < S, s: Safepoint[MX, EX], l: Locals.State) =
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
        def ensureLoop(v: T < (IOs & S), p: Preempt): T < (IOs & S) =
            v match
                case kyo: Kyo[MX, EX, Any, T, S & IOs] @unchecked =>
                    new KyoCont[MX, EX, Any, T, S & IOs](kyo):
                        def apply(v: Any < (S & IOs), s: Safepoint[MX, EX], l: Locals.State) =
                            val np =
                                (s: Any) match
                                    case s: Preempt =>
                                        s.ensure(ensure)
                                        s
                                    case _ =>
                                        Preempt.never
                            val v2 =
                                try kyo(v, s, l)
                                catch
                                    case ex if (NonFatal(ex)) =>
                                        ensure()
                                        throw ex
                            ensureLoop(v2, np)
                        end apply
                case _ =>
                    p.remove(ensure)
                    IOs {
                        ensure()
                        v
                    }
        ensureLoop(v, Preempt.never)
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

    private[kyo] abstract class KyoIO[T, S]
        extends Kyo[IO, IOs, Unit, T, (IOs & S)]:
        final def value  = ()
        final def effect = IOs
    end KyoIO

    trait Preempt extends Safepoint[IO, IOs]:
        def ensure(f: () => Unit): Unit
        def remove(f: () => Unit): Unit
        def suspend[T, S](v: => T < S): T < (IOs & S) =
            IOs(v)
    end Preempt
    object Preempt:
        val never: Preempt =
            new Preempt:
                def ensure(f: () => Unit) = ()
                def remove(f: () => Unit) = ()
                def check()               = false
    end Preempt
    type IO[+T] = T
end iosInternal
