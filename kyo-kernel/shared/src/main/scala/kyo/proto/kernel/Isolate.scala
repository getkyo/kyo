package kyo.proto.kernel

import kyo.Ansi.*
import kyo.Frame
import kyo.proto.Arrow
import kyo.proto.kernel.internal.Handler
import kyo.proto.kernel.internal.Kyo
import kyo.proto.kernel.internal.Nested
import kyo.proto.kernel.internal.Pending
import kyo.proto.kernel.internal.Stack
import scala.quoted.*

abstract class Isolate[Remove, -Keep, -Restore]:
    self =>

    type State

    type Transform[_]

    def capture[A, S](f: State => A < S)(using Frame): A < (Remove & S)

    def isolate[A, S](state: State, v: A < (S & Remove))(using Frame): Transform[A] < (Keep & S)

    def restore[A, S](v: Transform[A] < S)(using Frame): A < (Restore & S)

    def nest[A, S](v: A < (Remove & S))(using Frame): A < Restore < (Remove & Keep & S) =
        capture { state =>
            isolate(state, v).map(r => Nested.nest[A < Restore, Any](restore(r)))
        }

    final def run[A, S](v: A < (S & Remove))(using Frame): A < (S & Remove & Keep & Restore) =
        capture(state => run(state, v))

    def run[A, S](state: State, v: A < (S & Remove))(using Frame): A < (Keep & Restore & S) =
        restore(isolate(state, v))

    final def apply[A, S](v: A < (Remove & S))[B, S2](f: (A < (Restore & Keep & S)) => B < S2)(using
        Frame
    ): B < (Remove & Keep & S2) =
        capture(state => f(run(state, v)))

    final def use[A](f: this.type ?=> A): A = f(using this)

    final def andThen[RM2, KP2, RS2](next: Isolate[RM2, KP2, RS2]): Isolate[Remove & RM2, Keep & KP2, Restore & RS2] =
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

    inline def derive[Remove, Keep, Restore]: Isolate[Remove, Keep, Restore] = ${ internal.deriveImpl[Remove, Keep, Restore] }

    inline given [Remove, Keep, Restore <: Remove]: Isolate[Remove, Keep, Restore] = ${ internal.deriveImpl[Remove, Keep, Restore] }

    private[kyo] object internal:

        private[kernel] object Contextual extends Isolate[Any, Any, Any]:
            type State        = Stack.Snapshot
            type Transform[A] = (Stack.Snapshot, A)

            def capture[A, S](f: Stack.Snapshot => A < S)(using _frame: Frame): A < S =
                new Kyo.SnapshotWith[A, S]:
                    override def frame = _frame
                    def cont           = this
                    override def apply[C, S2](v: Stack.Snapshot < S2, cont2: Arrow[A, C, S2]) =
                        v match
                            case p: Pending[Stack.Snapshot, S2] @unchecked => Effect.defer(p, this, cont2)
                            case _                                         => cont2(f(Nested.unnest[Stack.Snapshot](v)), Arrow.id)

            def isolate[A, S](state: Stack.Snapshot, v: A < S)(using Frame): (Stack.Snapshot, A) < S =
                fork(state, 0, new Array[AnyRef](state.regions)).map { forked =>
                    val parked: A < S = Kyo.Park[A, S](v.asInstanceOf[Any < Any], forked)
                    parked.map(a => (forked, a))
                }

            def restore[A, S](v: (Stack.Snapshot, A) < S)(using Frame): A < S =
                v.map { (forked, a) =>
                    capture(current => join(forked, current, 0).andThen(a))
                }

            // Park currency: the snapshot carries erased handlers and states, so the casts
            // reinterpret at that boundary and check nothing at runtime. The states array is
            // a local accumulator for the suspended fold; withStates copies it out.
            private def fork(entries: Stack.Snapshot, i: Int, states: Array[AnyRef])(using Frame): Stack.Snapshot < Any =
                if i >= entries.regions then entries.withStates(states)
                else
                    val hc = entries.handler(i).asInstanceOf[Handler.ContextHandler[Any, ContextEffect[Any], Any, Any, Any]]
                    hc.fork(entries.state(i)).map { forked =>
                        states(i) = forked.asInstanceOf[AnyRef]
                        fork(entries, i + 1, states)
                    }

            // Joins run in entry order at the merge point, observing the origin state current at
            // that moment; a region the origin has already exited is not observed. Without an
            // update lane a branch cannot move its binding, so the branch's final state is its
            // forked state and the result argument threads it.
            private def join(forked: Stack.Snapshot, current: Stack.Snapshot, i: Int)(using Frame): Any < Any =
                if i >= forked.regions then ()
                else
                    val hc = forked.handler(i).asInstanceOf[Handler.ContextHandler[Any, ContextEffect[Any], Any, Any, Any]]
                    stateOf(current, hc) match
                        case kyo.Maybe.Present(cur) =>
                            val fk = forked.state(i)
                            hc.join(cur, fk, fk).map(_ => join(forked, current, i + 1))
                        case _ =>
                            join(forked, current, i + 1)
                    end match

            private def stateOf(current: Stack.Snapshot, hc: Handler.ContextHandler[Any, ?, ?, ?, ?]): kyo.Maybe[Any] =
                var i = 0
                while i < current.regions do
                    if current.handler(i).tag.erased =:= hc.tag.erased then
                        return kyo.Maybe(current.state(i))
                    i += 1
                end while
                kyo.Maybe.empty
            end stateOf
        end Contextual

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
                        |   Async.foreach(parallelism)(tasks.map(MyEffect.run(_)))
                        |
                        |2. Some effects like Var and Emit provide options through their isolate object:
                        |   Var.isolate.update[Int].use {
                        |     Async.foreach(parallelism)(tasks)
                        |   }
                        |
                        |3. For multiple effects, compose isolates with andThen:
                        |   Var.isolate.update[Int]
                        |     .andThen(Emit.isolate.merge[String])
                        |     .use {
                        |       Async.foreach(parallelism)(tasks)
                        |     }
                        |
                        |4. For custom state management:
                        |   val isolate = new Isolate[MyEffect, Any, Any] {
                        |     type State = MyState        // Your effect's state
                        |     type Transform[A] = (State, A)
                        |     ...
                        |   }
                        |   isolate.use {
                        |     Async.foreach(parallelism)(tasks)
                        |   }
                        |
                        |Failed to materialize `Isolate[${TypeRepr.of[Remove].show}, ${TypeRepr.of[Keep].show}, ${TypeRepr.of[
                           Restore
                       ].show}]`.
                        |""".stripMargin
                )
            end if

            isolates.flatMap(_._2).foldLeft('{ Contextual.asInstanceOf[Isolate[Remove, Keep, Restore]] })((prev, next) =>
                '{ $prev.andThen($next.asInstanceOf[Isolate[Remove, Keep, Restore]]) }
            )
        end deriveImpl
    end internal

end Isolate
