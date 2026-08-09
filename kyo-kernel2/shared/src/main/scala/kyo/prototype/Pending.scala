package kyo.prototype

import kyo.Frame
import scala.annotation.tailrec
import scala.language.implicitConversions

opaque type <[+A, -S] >: Kyo[A, S] = A | Kyo[A, S]

object `<`:

    implicit inline def lift[A, S](v: A): A < S =
        v match
            case kyo: Kyo[?, ?] => Kyo.Nested(kyo).asInstanceOf[A < S]
            case v              => v

    extension [A, S](self: A < S)

        def map[B, S2](f: A => B < S2)(using frame: Frame): B < (S & S2) =
            self match
                case kyo: Kyo[A, S] @unchecked => kyo.append(Arrow.lift(f))
                case v                         => new Kyo.Defer(v.asInstanceOf[A < S], Arrow.lift(f))

        def flatMap[B, S2](f: A => B < S2)(using frame: Frame): B < (S & S2) =
            map(f)

        def andThen[B, S2](v: => B < S2)(using frame: Frame): B < (S & S2) =
            map(_ => v)

        def eval(using S =:= Any): A =
            drive(self.asInstanceOf[A < Any], never) match
                case kyo: Kyo[?, ?] => throw new IllegalStateException(s"unhandled suspension: $kyo")
                case v              => Kyo.unnest(v).asInstanceOf[A]

        def evalPartial(stop: () => Boolean): A < S =
            drive(self.asInstanceOf[A < Any], stop).asInstanceOf[A < S]

    end extension

    private val never: () => Boolean = () => false

    private def drive[A](v: A < Any, stop: () => Boolean): A < Any =
        val sp    = Safepoint.get
        val saved = sp.openDrive()
        try driveLoop(v, stop)
        finally sp.closeDrive(saved)
    end drive

    @tailrec
    private def driveLoop[A](v: A < Any, stop: () => Boolean): A < Any =
        if stop() then v
        else
            v match
                case kyo: Kyo.Defer[?, ?, ?] =>
                    val defer = kyo.asInstanceOf[Kyo.Defer[Any, A, Any]]
                    driveLoop(defer.cont(defer.value, Context.empty, Handlers.empty), stop)
                case v =>
                    v
end `<`
