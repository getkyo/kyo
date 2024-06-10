package kyo

import core.*
import core.internal.*
import java.util.concurrent.atomic.AtomicReference
import kyo.internal.Trace
import scala.annotation.tailrec
import scala.util.*
import scala.util.control.NonFatal

sealed trait IOs extends Effect[Const[Unit], Const[Unit]]

object IOs:

    import internal.*

    private val tag = Tag[IOs]

    val unit: Unit < IOs = ()

    inline def apply[T, S](
        inline f: => T < (IOs & S)
    )(using inline _trace: Trace): T < (IOs & S) =
        < {
            new KyoIO[T, S]:
                def trace = _trace
                def apply(v: Unit, s: Safepoint, l: Locals.State) =
                    f
        }

    def fail[T](ex: Throwable)(using Trace): T < IOs =
        IOs(throw ex)

    def fail[T](msg: String)(using Trace): T < IOs =
        fail(new Exception(msg))

    def fromTry[T, S](v: Try[T] < S)(using Trace): T < (IOs & S) =
        v.map {
            case Success(v) =>
                v
            case Failure(ex) =>
                fail(ex)
        }

    def toTry[T, S](v: => T < S)(using Trace): Try[T] < S =
        core.catching(v.map(Success(_): Try[T]))(Failure(_))

    def catching[T, S, U >: T, S2](v: => T < S)(
        pf: PartialFunction[Throwable, U < S2]
    )(using Trace): U < (S & S2) =
        core.catching(v)(pf)

    def run[T](v: T < IOs)(using Trace): T =
        @tailrec def runLoop(v: T < IOs): T =
            v match
                case <(kyo: Suspend[Const[Unit], Const[Unit], IOs, Unit, T, IOs] @unchecked) =>
                    // bug.checkTag(kyo, tag)
                    runLoop(kyo(()))
                case <(v) =>
                    v.asInstanceOf[T]
        runLoop(v)
    end run

    def runLazy[T, S](v: T < (IOs & S))(using _trace: Trace)(using Trace): T < S =
        @tailrec def runLazyLoop(v: T < (IOs & S)): T < S =
            v match
                case <(kyo: Suspend[?, ?, ?, ?, ?, ?]) =>
                    if kyo.tag =:= tag then
                        val k = kyo.asInstanceOf[Suspend[Const[Unit], Const[Unit], IOs, Unit, T, S & IOs]]
                        runLazyLoop(k(()))
                    else
                        val k = kyo.asInstanceOf[Suspend[IX, OX, EX, Any, T, S & IOs]]
                        <(
                            new Continue[IX, OX, EX, Any, T, S](k):
                                def trace = _trace
                                def apply(v: OX[Any], s: Safepoint, l: Locals.State) =
                                    runLazyLoop(k(v, s, l))
                        )
                case <(v) =>
                    v.asInstanceOf[T]
            end match
        end runLazyLoop
        runLazyLoop(v)
    end runLazy

    def ensure[T, S](f: => Unit < IOs)(v: T < S)(using Trace): T < (IOs & S) =
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

    private[kyo] object internal:

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
            extends Suspend[Const[Unit], Const[Unit], IOs, Unit, T, IOs & S]:
            final def tag   = Tag[IOs]
            final def input = ()
        end KyoIO

        trait Preempt extends Safepoint:
            def ensure(f: () => Unit): Unit
            def remove(f: () => Unit): Unit
            def suspend[T, S](v: => T < S): T < S =
                IOs(v).asInstanceOf[T < S]
        end Preempt
        object Preempt:
            def ensure(s: Safepoint, e: Ensure): Unit =
                s match
                    case p: Preempt => p.ensure(e)
                    case _          =>

            def remove(s: Safepoint, e: Ensure): Unit =
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
    end internal

end IOs
