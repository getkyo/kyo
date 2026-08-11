package kyo.kernel.internal
// TODO I think the file is named KyoInternal in the old kernel? please keep the same organization as the old kernel where possible
import kyo.Frame
import kyo.Tag
import kyo.kernel.*
import scala.annotation.static

sealed trait Boxed

sealed trait Kyo[+A, -S] extends Boxed:
    def map[B, S2](f: Arrow[A, B, S2]): B < (S & S2)

final case class Nested[+A](value: A) extends Boxed

object Nested:

    @static private[kernel] def lift[A, S](v: A): A < S =
        v match
            case boxed: Boxed => Nested(boxed).asInstanceOf[A < S]
            case v            => v.asInstanceOf[A < S]

    @static private[kernel] def unnest[A](v: Any): A =
        v match
            case n: Nested[?] => n.value.asInstanceOf[A]
            case _            => v.asInstanceOf[A]
end Nested

object Kyo:

    private[kernel] inline def unnest[A, S](inline v: A < S): A =
        inline scala.compiletime.erasedValue[A] match
            case _: (Int | Long | Float | Double | Boolean | Byte | Short | Char | Unit | String) =>
                v.asInstanceOf[A]
            case _ =>
                Nested.unnest(v)

    // a suspension carrying a fallback: evaluation resolves it through a
    // matching handler like any suspension, and the miss path resumes with
    // the default instead of failing, which is how optional context works
    // TODO no, this is not acceptable, we need to fully review
    private[kyo] trait Defaulted:
        self: Suspend[?, ?, ?, ?, ?, ?] =>
        def default: Any

    trait Suspend[I[_], O[_], E <: ArrowEffect[I, O], X, +A, -S] extends Kyo[A, S]:
        self =>

        def tag: Tag[E]
        def input: I[X]
        def frame: Frame
        def cont: Arrow[O[X], A, S]

        private[kernel] def root: Suspend[I, O, E, X, ?, ?] = this

        final override def toString = // TODO let's make sure we have proper to string for other classes that represent computaitons as well
            s"Kyo(${tag.show}, Input($input), ${frame.position.show}, ${frame.snippetShort})"

        final def map[B, S2](f: Arrow[A, B, S2]): B < (S & S2) =
            val r = root
            val c = cont.chain(f)
            new Suspend[I, O, E, X, B, S & S2]:
                override val root = r
                def tag           = root.tag
                def input         = root.input
                def frame         = root.frame
                val cont          = c
            end new
        end map
    end Suspend

    final class Defer[A, +B, -S](val value: A < S, val cont: Arrow[A, B, S]) extends Kyo[B, S]:
        def map[C, S2](f: Arrow[B, C, S2]) =
            new Defer(value, cont.chain(f))
    end Defer

    final class Handled[I[_], O[_], E <: ArrowEffect[I, O], A, +B, -S](
        val value: A < (E & S),
        val handler: Handler[I, O, E, A, S],
        val cont: Arrow[A, B, S]
    ) extends Kyo[B, S]:
        def map[C, S2](f: Arrow[B, C, S2]) =
            new Handled[I, O, E, A, C, S & S2](value, handler, cont.chain(f))
    end Handled

end Kyo
