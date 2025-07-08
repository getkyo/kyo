package kyo.kernel

import Isolate.internal.*
import kyo.*
import kyo.Ansi.*
import kyo.kernel.internal.*
import scala.annotation.nowarn
import scala.quoted.*

abstract class Isolate[Remove, -Keep, Restore]:
    self =>

    type State
    type Transform[_]

    def capture[A, S](f: State => A < S)(using Frame): A < (Remove & Keep & S)

    def isolate[A, S](state: State, v: A < (S & Remove))(using Frame): Transform[A] < (Keep & S)

    def restore[A, S](v: Transform[A] < S)(using Frame): A < (Restore & S)

    final def run[A, S](v: A < (S & Remove))(using Frame): A < (S & Remove & Keep & Restore) =
        capture(state => restore(isolate(state, v)))

    final def use[A](f: this.type ?=> A): A = f(using this)

    final def andThen[RM2, KP2, RS2](next: Isolate[RM2, KP2, RS2]): Isolate[Remove & RM2, Keep & KP2, Restore & RS2] =
        if self eq noop then next.asInstanceOf[Isolate[Remove & RM2, Keep & KP2, Restore & RS2]]
        else if next eq noop then self.asInstanceOf[Isolate[Remove & RM2, Keep & KP2, Restore & RS2]]
        else
            new Isolate[Remove & RM2, Keep & KP2, Restore & RS2]:
                type State        = (self.State, next.State)
                type Transform[A] = self.Transform[next.Transform[A]]
                def capture[A, S](f: State => A < S)(using Frame) =
                    self.capture(s1 => next.capture(s2 => f((s1, s2))))
                def isolate[A, S](state: State, v: A < (S & (Remove & RM2)))(using Frame) =
                    self.isolate(state._1, next.isolate(state._2, v))
                def restore[A, S](v: Transform[A] < S)(using Frame) =
                    next.restore(self.restore(v))

end Isolate

object Isolate:

    def apply[Remove, Keep, Restore](using i: Isolate[Remove, Keep, Restore]): Isolate[Remove, Keep, Restore] = i

    inline given derive[Remove, Keep, Restore]: Isolate[Remove, Keep, Restore] = ${ deriveImpl[Remove, Keep, Restore] }

    private[kyo] object internal:

        @nowarn("msg=anonymous")
        private[kyo] inline def runDetached[A, S](inline f: (Trace, Context) => A < S)(using inline _frame: Frame): A < S =
            new KyoDefer[A, S]:
                def frame = _frame
                def apply(v: Unit, context: Context)(using safepoint: Safepoint) =
                    f(safepoint.saveTrace(), context.inherit)

        inline def restoring[Ctx, A, S](
            trace: Trace,
            interceptor: Safepoint.Interceptor
        )(
            inline v: => A < (Ctx & S)
        )(using frame: Frame, safepoint: Safepoint): A < (Ctx & S) =
            Safepoint.immediate(interceptor)(safepoint.withTrace(trace)(v))

        object noop extends Isolate[Any, Any, Any]:
            type State        = Unit
            type Transform[A] = A
            def capture[A, S](f: State => A < S)(using Frame)              = f(())
            def isolate[A, S](state: State, v: A < (S & Any))(using Frame) = v
            def restore[A, S](v: A < S)(using Frame)                       = v
        end noop

        def deriveImpl[Remove: Type, Keep: Type, Restore: Type](using Quotes): Expr[Isolate[Remove, Keep, Restore]] =
            import quotes.reflect.*

            def flatten(tpe: TypeRepr): List[TypeRepr] =
                tpe match
                    case AndType(left, right)        => flatten(left) ++ flatten(right)
                    case t if t =:= TypeRepr.of[Any] => Nil
                    case t                           => List(t)

            val keep = flatten(TypeRepr.of[Keep])

            val isolates =
                flatten(TypeRepr.of[Remove])
                    .filterNot(t => keep.exists(t =:= _))
                    .filterNot(_ <:< TypeRepr.of[ContextEffect[Any]])
                    .map { t =>
                        t.asType match
                            case '[tpe] =>
                                t -> Expr.summon[Isolate[tpe, Keep, Restore]]
                    }

            val missing = isolates.filter(_._2.isEmpty).map(_._1)

            if missing.nonEmpty then
                report.errorAndAbort(
                    s"""|This operation requires isolation for effects:
                        |
                        |  ${missing.map(_.show.red).mkString(" & ")}
                        |
                        |Common mistake: Using effects in parallel operations without handling how their state
                        |should be managed across boundaries.
                        |
                        |You have a few options, from simplest to most advanced:
                        |
                        |1. Handle these effects before the operation:
                        |   Async.parallel(parallelism)(tasks.map(MyEffect.run(_)))
                        |
                        |2. Some effects like Var and Emit provide options through their isolate object:
                        |   Var.isolate.update[Int].use {
                        |     Async.parallel(parallelism)(tasks)
                        |   }
                        |
                        |3. For multiple effects, compose isolates with andThen:
                        |   Var.isolate.update[Int]
                        |     .andThen(Emit.isolate.merge[String])
                        |     .use {
                        |       Async.parallel(parallelism)(tasks)
                        |     }
                        |
                        |4. For custom state management:
                        |   val isolate = new Isolate.Stateful[MyEffect, Any] {
                        |     type State = MyState        // Your effect's state
                        |     type Transform[A] = (State, A)
                        |     ...
                        |   }
                        |   isolate.use {
                        |     Async.parallel(parallelism)(tasks)
                        |   }
                        |""".stripMargin
                )
            end if

            isolates.flatMap(_._2).foldLeft('{ noop.asInstanceOf[Isolate[Remove, Keep, Restore]] })((prev, next) =>
                '{ $prev.andThen($next.asInstanceOf[Isolate[Remove, Keep, Restore]]) }
            )
        end deriveImpl
    end internal

end Isolate
