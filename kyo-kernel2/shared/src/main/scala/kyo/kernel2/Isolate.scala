package kyo.kernel2

import Isolate.internal.*
import kyo.Ansi.*
import kyo.Frame
import kyo.kernel2.internal.*
import scala.annotation.nowarn
import scala.quoted.*

/** Provides mechanisms for handling pending effects when forking computations.
  *
  * Isolate enables proper state management across execution boundaries like fibers, parallel operations, and detached computations. When
  * forking execution, effects need special handling to prevent state leakage, ensure consistency, and determine what effects are available
  * after the fork completes.
  *
  * The abstraction uses three type parameters to precisely control effect flow:
  *   - `Remove`: Effects that will be satisfied (handled) by the isolation
  *   - `Keep`: Effects that remain available during isolated execution
  *   - `Restore`: Effects that become available after isolation completes
  *
  * [[ContextEffect]]s are copied wholesale at the boundary (the fork inherits the visible bindings as a snapshot) and never need an
  * instance; the derivation filters them out. Stateful arrow effects need explicit instances built from ordinary handlers: capture reads
  * the current state, isolate runs the forked computation under a local handler seeded with the snapshot, and restore re-suspends the
  * transformed state against the handlers surrounding the join.
  *
  * Effects that short circuit execution like Abort and Choice should not provide isolation since the ordering of the handling in the
  * automatic derivation could produce different results depending on which effect is handled first.
  *
  * @tparam Remove
  *   Effects that will be satisfied (handled) by the isolation
  * @tparam Keep
  *   Effects that remain available during isolated execution
  * @tparam Restore
  *   Effects that become available after isolation completes
  */
abstract class Isolate[Remove, -Keep, -Restore]:
    self =>

    /** The type of state being managed */
    type State

    /** How state is transformed during isolated execution */
    type Transform[_]

    /** Captures the current state for isolation. */
    def capture[A, S](f: State => A < S)(using Frame): A < (Remove & Keep & S)

    /** Executes a computation with isolated state. */
    def isolate[A, S](state: State, v: A < (S & Remove))(using Frame): Transform[A] < (Keep & S)

    /** Restores state after isolated execution. */
    def restore[A, S](v: Transform[A] < S)(using Frame): A < (Restore & S)

    /** Isolates 'Remove' effects while exposing them as nested 'Restore' effects. */
    def nest[A, S](v: A < (Remove & S))(using Frame): A < Restore < (Remove & Keep & S) =
        capture { state =>
            isolate(state, v).map(r => Kyo.lift(restore(r)))
        }

    /** Runs a computation with full state lifecycle management. */
    final def run[A, S](v: A < (S & Remove))(using Frame): A < (S & Remove & Keep & Restore) =
        capture(state => restore(isolate(state, v)))

    /** Applies this isolate to a computation that requires it. */
    final def use[A](f: this.type ?=> A): A = f(using this)

    /** Composes this isolate with another, managing both states. */
    final def andThen[RM2, KP2, RS2](next: Isolate[RM2, KP2, RS2]): Isolate[Remove & RM2, Keep & KP2, Restore & RS2] =
        if self eq Identity then next.asInstanceOf[Isolate[Remove & RM2, Keep & KP2, Restore & RS2]]
        else if next eq Identity then self.asInstanceOf[Isolate[Remove & RM2, Keep & KP2, Restore & RS2]]
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

    /** Gets the Isolate instance for given effect types. */
    def apply[Remove, Keep, Restore](using i: Isolate[Remove, Keep, Restore]): Isolate[Remove, Keep, Restore] = i

    /** Derives an Isolate instance based on available instances.
      *
      * The derivation automatically composes isolates for intersection types. For example, if isolates exist for `Var[Int]` and
      * `Emit[String]`, it will automatically derive an isolate for `Var[Int] & Emit[String]`.
      */
    inline def derive[Remove, Keep, Restore]: Isolate[Remove, Keep, Restore] = ${ deriveImpl[Remove, Keep, Restore] }

    // `Restore <: Remove` is used to help with implicit resolution. Without it, the compiler infers `Restore` as `Any` in some cases.
    inline given [Remove, Keep, Restore <: Remove]: Isolate[Remove, Keep, Restore] = ${ deriveImpl[Remove, Keep, Restore] }

    private[kyo] object internal:

        /** No-op isolate that performs no state management.
          *
          * Used as a base case for isolate composition and when no isolation is needed.
          */
        object Identity extends Isolate[Any, Any, Any]:
            type State        = Unit
            type Transform[A] = A
            def capture[A, S](f: State => A < S)(using Frame)              = f(())
            def isolate[A, S](state: State, v: A < (S & Any))(using Frame) = v
            def restore[A, S](v: A < S)(using Frame)                       = v
        end Identity

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
                        |   val isolate = new Isolate.Stateful[MyEffect, Any] {
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

            isolates.flatMap(_._2).foldLeft('{ Identity.asInstanceOf[Isolate[Remove, Keep, Restore]] })((prev, next) =>
                '{ $prev.andThen($next.asInstanceOf[Isolate[Remove, Keep, Restore]]) }
            )
        end deriveImpl
    end internal

end Isolate
