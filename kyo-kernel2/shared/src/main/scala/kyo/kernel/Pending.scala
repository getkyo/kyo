package kyo.kernel

import kyo.Frame
import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.language.implicitConversions

opaque type <[+A, -S] >: Kyo[A, S] = A | Kyo[A, S]

object `<`:

    implicit inline def lift[A, S](v: A): A < S =
        inline scala.compiletime.erasedValue[A] match
            case _: (Int | Long | Float | Double | Boolean | Byte | Short | Char | Unit | String) =>
                v.asInstanceOf[A < S]
            case _ =>
                Nested.lift(v)

    extension [A, S](self: A < S)

        @nowarn("msg=anonymous")
        inline def map[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            @nowarn("msg=anonymous") def mapLoop[C, S3](v: A < S3, next: Arrow[B, C, S3]): C < (S & S2 & S3) =
                def arrow =
                    new Arrow.Transform[A, C, S & S2 & S3]:
                        def frame = _frame
                        def apply[D, S4](v: A < S4, next2: Arrow[C, D, S4]) =
                            mapLoop(v, next.chain(next2))
                v match
                    case kyo: Kyo[A, S3] @unchecked =>
                        kyo.map(arrow)
                    case v =>
                        val res  = Kyo.unnest(v)
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            new Kyo.Defer(v, arrow)
                        else
                            val step = next.step
                            val out  = step.head(f(res), step.tail)
                            Safepoint.exit(slot)
                            out
                        end if
                end match
            end mapLoop
            mapLoop(self, Arrow[B])
        end map

        inline def flatMap[B, S2](inline f: A => B < S2)(using inline frame: Frame): B < (S & S2) =
            map(f)

        inline def andThen[B, S2](inline next: => B < S2)(using inline frame: Frame): B < (S & S2) =
            map(_ => next)

        inline def eval(using S =:= Any): A =
            val slot  = Safepoint.get()
            val saved = Safepoint.save(slot)
            val res =
                try Eval(self, Handlers.empty, slot)
                finally Safepoint.restore(slot, saved)
            res match
                case kyo: Kyo[?, ?] => throw new IllegalStateException(s"unhandled suspension: $kyo")
                case v              => Kyo.unnest(v.asInstanceOf[A < Any])
        end eval

        inline def evalPartial(inline stop: () => Boolean): A < S =
            val slot  = Safepoint.get()
            val saved = Safepoint.save(slot)
            @tailrec def evalLoop(v: A < Any): A < Any =
                if stop() then v
                else
                    v match
                        case kyo: Kyo.Defer[?, ?, ?] =>
                            val defer = kyo.asInstanceOf[Kyo.Defer[Any, A, Any]]
                            Safepoint.restore(slot, 0L)
                            val step = defer.cont.step
                            evalLoop(step.head(defer.value, step.tail))
                        case v =>
                            v
            try evalLoop(self.asInstanceOf[A < Any]).asInstanceOf[A < S]
            finally Safepoint.restore(slot, saved)
        end evalPartial

    end extension
end `<`
