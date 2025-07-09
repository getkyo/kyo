package kyo.kernel

import Isolate.internal.*
import kyo.*
import kyo.Ansi.*
import kyo.kernel.internal.*
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
  * This design unifies two categories of state management:
  *
  * **Simple State Copying** is used for [[ContextEffect]]s, which store their state in a format that can be directly copied from the
  * original computation to the forked one. These are effects like environment variables, configuration settings, or local values - pieces
  * of state that can simply be copied as-is when the computation forks.
  *
  * **Complex State Management** handles effects that require structured transformation. When forking a computation with these effects, the
  * isolation:
  *
  *   1. Captures a snapshot of the current state
  *   2. Transforms that state during isolated execution
  *   3. Restores the transformed state when the fork completes
  *
  * ==Operations==
  *
  * Operations specify their isolation requirements through the type parameters. For example:
  *   - `Fiber.init` requires `Isolate[S, Sync, S2]` - only Sync effects available during initialization
  *   - `Async.parallel` might use `Isolate[S, Abort[E] & Async, S]` - async operations available, same effects restored
  *
  * The distinction between `Remove` and `Restore` is crucial: it allows operations to transform effects during isolation. A fiber might
  * capture `Var[Int]` effects but only restore the final value, not intermediate updates.
  *
  * Most effects provide implicit instances based on their state management needs. Some effects like Var and Emit provide multiple
  * strategies through a dedicated isolate object, allowing users to choose between updating final values, merging changes, or keeping
  * modifications local.
  *
  * For effect intersections like (Env[Config] & Var[State]), instances are automatically derived if each component effect has an instance.
  *
  * ==Explicit Composition==
  *
  * While automatic derivation works for most cases, you can explicitly compose isolate instances for precise control over state handling:
  * {{{
  *  // Compose specific isolation strategies
  *  val isolate =
  *     Var.isolate.update[Int]                         // Update final Int value
  *         .andThen(Emit.isolate.merge[String])        // Preserve String emissions
  *
  *  // Use in operations that accept isolation
  *  isolate.use {
  *    Async.parallel(parallelism)(tasks)
  *  }
  * }}}
  *
  * This explicit composition is useful when:
  *
  *   1. The order of state capture/restore matters
  *   2. You need specific isolation strategies for each effect
  *   3. The default derived instance doesn't handle effects as desired
  *
  * **Important**: Effects that short circuit execution like Abort and Choice should not provide isolation since the ordering of the
  * handling in the automatic derivation could produce different results depending on which effect is handled first.
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
      * This is the first phase of isolation, obtaining the state that will be managed during the isolated execution. The computation
      * continues with all original effects plus Keep effects available.
      *
      * @param f
      *   Function that receives the captured state
      * @return
      *   Computation with Remove, Keep, and additional effects
      */
    def capture[A, S](f: State => A < S)(using Frame): A < (Remove & Keep & S)

    /** Executes a computation with isolated state.
      *
      * This is the second phase where the computation runs in an isolated context. Only Keep effects and additional effects S are available -
      * Remove effects have been captured and isolated. The result is wrapped in Transform to track any state changes.
      *
      * @param state
      *   The captured state from phase 1
      * @param v
      *   The computation to run in isolation
      * @return
      *   Transformed result with only Keep and additional effects
      */
    def isolate[A, S](state: State, v: A < (S & Remove))(using Frame): Transform[A] < (Keep & S)

    /** Restores state after isolated execution.
      *
      * This is the final phase that determines how the transformed state is propagated back. The Transform wrapper is unwrapped and Restore
      * effects become available, which may differ from the original Remove effects.
      *
      * @param v
      *   The transformed computation from phase 2
      * @return
      *   Final result with Restore and additional effects
      */
    def restore[A, S](v: Transform[A] < S)(using Frame): A < (Restore & S)

    /** Runs a computation with full state lifecycle management.
      *
      * Convenience method that composes all three phases: capture, isolate, and restore. This handles the complete isolation lifecycle in
      * one call.
      *
      * @param v
      *   The computation to run with isolation
      * @return
      *   Result with original Remove effects handled and Restore effects available
      */
    final def run[A, S](v: A < (S & Remove))(using Frame): A < (S & Remove & Keep & Restore) =
        capture(state => restore(isolate(state, v)))

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
      *
      * The derived instance will:
      *   - Remove all effects in the Remove type that aren't in Keep
      *   - Only derive if isolates exist for all non-Keep effects in Remove
      *   - Compose isolates using andThen in the order they appear
      */
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

            isolates.flatMap(_._2).foldLeft('{ Identity.asInstanceOf[Isolate[Remove, Keep, Restore]] })((prev, next) =>
                '{ $prev.andThen($next.asInstanceOf[Isolate[Remove, Keep, Restore]]) }
            )
        end deriveImpl
    end internal

end Isolate
