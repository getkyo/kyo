package kyo.proto.kernel

import kyo.Ansi.*
import kyo.Frame
import kyo.proto.kernel.internal.Nested
import scala.quoted.*

/** Provides mechanisms for handling pending effects when forking computations.
  *
  * Isolate enables state management across execution boundaries like fibers, parallel operations, and detached computations. When forking
  * execution, effects need handling to prevent state leakage, ensure consistency, and determine what effects are available after the fork
  * completes.
  *
  * The abstraction uses three type parameters to precisely control effect flow:
  *   - `Remove`: effects that will be satisfied (handled) by the isolation
  *   - `Keep`: effects that remain available during isolated execution
  *   - `Restore`: effects that become available after isolation completes
  *
  * Context bindings need no isolation in this kernel. A fork operation is an effect, and its suspension travels to the region that answers
  * it with every crossed region rebuilt around the suspension's continuation, context bindings included. A forked body carried on that
  * continuation re-installs the bindings wherever it is evaluated, so inheritance is a property of suspension itself; the neutral
  * [[Isolate.internal.Contextual]] passes the computation through untouched and the derivation folds every instance from it.
  *
  * What does need an instance is structured state: an effect whose region threads a value the fork must snapshot, transform, and bring
  * back. When forking a computation with such effects, the isolation:
  *
  *   1. Captures a snapshot of the current state
  *   2. Transforms that state during isolated execution
  *   3. Restores the transformed state when the fork completes
  *
  * For effect intersections, instances are automatically derived when each component effect has one, and explicit composition through
  * [[andThen]] gives precise control over ordering when the derived order is not the wanted one.
  *
  * Effects that short circuit execution, like aborts and choice, should not provide isolation: the composition order of the automatic
  * derivation would otherwise decide results.
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

    /** Captures the current state for isolation.
      *
      * The first phase, obtaining the state the isolated execution manages. The state is read through the effects being isolated, so the
      * row is `Remove & S` and nothing more: capture runs where the fork happens, and a fork is possible from any context that can handle
      * the isolated effects, including ones that cannot handle Keep. Keep effects belong to the later phases, which run inside the fork
      * and at the join.
      *
      * @param f
      *   Function that receives the captured state
      * @return
      *   Computation with Remove and additional effects
      */
    def capture[A, S](f: State => A < S)(using Frame): A < (Remove & S)

    /** Executes a computation with isolated state.
      *
      * The second phase, where the computation runs in an isolated context. Only Keep effects and additional effects S are available:
      * Remove effects have been captured and isolated. The result is wrapped in Transform to track any state changes.
      *
      * @param state
      *   The captured state from the first phase
      * @param v
      *   The computation to run in isolation
      * @return
      *   Transformed result with only Keep and additional effects
      */
    def isolate[A, S](state: State, v: A < (S & Remove))(using Frame): Transform[A] < (Keep & S)

    /** Restores state after isolated execution.
      *
      * The final phase, deciding how the transformed state propagates back. The Transform wrapper is unwrapped and Restore effects become
      * available, which may differ from the original Remove effects.
      *
      * @param v
      *   The transformed computation from the second phase
      * @return
      *   Final result with Restore and additional effects
      */
    def restore[A, S](v: Transform[A] < S)(using Frame): A < (Restore & S)

    /** Isolates 'Remove' effects while exposing them as nested 'Restore' effects.
      *
      * This method tunnels the Remove effects through the isolation, transforming them into 'Restore' effects that appear nested in the
      * result type. Unlike 'run', which directly applies the 'Restore' effects, 'nest' preserves them as a nested effect layer, so the
      * caller decides when and how the resulting 'Restore' effects apply.
      *
      * @param v
      *   The computation containing effects to tunnel through isolation
      * @return
      *   A computation with 'Restore' effects nested in the result type
      */
    def nest[A, S](v: A < (Remove & S))(using Frame): A < Restore < (Remove & Keep & S) =
        capture { state =>
            // the nest, not the lift: the payload is deliberately a computation held as data, which
            // the implicit lift's guard rejects at a syntactically pending type, and the nest's
            // runtime analysis wraps exactly once
            isolate(state, v).map(r => Nested.nest[A < Restore, Any](restore(r)))
        }

    /** Runs a computation with the full state lifecycle.
      *
      * Convenience method composing all three phases: capture, isolate, and restore.
      *
      * @param v
      *   The computation to run with isolation
      * @return
      *   Result with original Remove effects handled and Restore effects available
      */
    final def run[A, S](v: A < (S & Remove))(using Frame): A < (S & Remove & Keep & Restore) =
        capture(state => run(state, v))

    /** Runs a computation against a state already captured, and brings it back.
      *
      * The half of [[run]] that is not the capture, for the callers that cannot have one fused in. A fork captures once and isolates many
      * times, one per branch, so the capture is hoisted above them and each branch takes the state as given. The row is why the split
      * matters rather than being a convenience: what this answers with has no `Remove` in it, so it is what crosses to an evaluation that
      * cannot handle those effects, while the capture's own `Remove` stays with the computation that forked.
      *
      * Overridable, and the composition below is the meaning rather than the implementation: an isolate that can carry a value across
      * without building the `Transform` between the two halves is free to say so here, and every caller gets it without the protocol
      * changing.
      *
      * @param state
      *   what [[capture]] answered with
      * @param v
      *   the computation to run isolated
      */
    def run[A, S](state: State, v: A < (S & Remove))(using Frame): A < (Keep & Restore & S) =
        restore(isolate(state, v))

    /** Prepares a computation to run detached, with this isolate's state attached.
      *
      * The two halves of isolation in one operation: the handled effects cross through this instance, which captures their state here and
      * restores it there. What `f` receives carries no `Remove`, so it is what crosses to an evaluation that cannot handle those effects;
      * nothing is taken from the forking computation, which carries on with what it had.
      *
      * @param v
      *   The computation to prepare
      * @param f
      *   What consumes it, with the isolated state attached
      */
    final def apply[A, S](v: A < (Remove & S))[B, S2](f: (A < (Restore & Keep & S)) => B < S2)(using
        Frame
    ): B < (Remove & Keep & S2) =
        capture(state => f(run(state, v)))

    /** Applies this isolate to a computation that requires it.
      *
      * Provides a more ergonomic way to use isolates with operations:
      * {{{
      * Var.isolate.update[Int].use {
      *   Async.mask {
      *     // computation with isolated Var[Int] effect
      *   }
      * }
      * }}}
      *
      * @param f
      *   The computation requiring this isolate
      * @return
      *   The result of running the computation with this isolate
      */
    final def use[A](f: this.type ?=> A): A = f(using this)

    /** Composes this isolate with another, managing both states.
      *
      * Creates a new isolate that handles the state lifecycles of both this isolate and the next one, maintaining proper ordering and
      * effect tracking. The composition:
      *   - Combines Remove effects: `Remove & RM2`
      *   - Intersects Keep effects: `Keep & KP2`
      *   - Combines Restore effects: `Restore & RS2`
      *
      * @param next
      *   The isolate to compose with this one
      * @return
      *   A new isolate handling both state managements
      */
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

    /** Gets the Isolate instance for given effect types. */
    def apply[Remove, Keep, Restore](using i: Isolate[Remove, Keep, Restore]): Isolate[Remove, Keep, Restore] = i

    /** Derives an Isolate instance based on available instances.
      *
      * The derivation automatically composes isolates for intersection types. For example, if isolates exist for `Var[Int]` and
      * `Emit[String]`, it will automatically derive an isolate for `Var[Int] & Emit[String]`.
      *
      * The derived instance will:
      *   - Remove all effects in the Remove type that aren't in Keep
      *   - Only derive if isolates exist for all non-Keep effects in Remove
      *   - Compose isolates using andThen in the order they appear
      */
    inline def derive[Remove, Keep, Restore]: Isolate[Remove, Keep, Restore] = ${ internal.deriveImpl[Remove, Keep, Restore] }

    // `Restore <: Remove` is used to help with implicit resolution. Without it, the compiler infers `Restore` as `Any` in some cases.
    inline given [Remove, Keep, Restore <: Remove]: Isolate[Remove, Keep, Restore] = ${ internal.deriveImpl[Remove, Keep, Restore] }

    private[kyo] object internal:

        /** The isolate of the values bound around a fork: the neutral element every derivation folds from.
          *
          * It manages nothing, and that is the design rather than a placeholder. A fork operation is an effect, and by the time its
          * suspension reaches the region that answers it, every region it crossed stands rebuilt around the suspension's continuation,
          * context bindings included. A forked body carried on that continuation re-installs them wherever it runs: a constant binding
          * rebinds its value, a derived binding derives again layer by layer, and the layers travel together, so the re-derivation
          * composes to the fork-point values. Nothing is left for an isolate to enumerate, freeze, or write back, so the three phases
          * pass the computation through.
          *
          * The crossing pins live in `IsolateTest`: what a boundary's continuation carries, what a detached resume reads, and what
          * shadowed and derived layers answer after the crossing.
          */
        private[kernel] object Contextual extends Isolate[Any, Any, Any]:
            type State        = Unit
            type Transform[A] = A
            def capture[A, S](f: Unit => A < S)(using Frame): A < S      = f(())
            def isolate[A, S](state: Unit, v: A < S)(using Frame): A < S = v
            def restore[A, S](v: A < S)(using Frame): A < S              = v
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
