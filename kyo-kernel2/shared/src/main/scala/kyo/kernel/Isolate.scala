package kyo.kernel

import Isolate.internal.*
import kyo.Ansi.*
import kyo.Arrow
import kyo.Frame
import kyo.Kyo
import kyo.Maybe
import kyo.Maybe.*
import kyo.Span
import kyo.bug
import kyo.kernel.internal.Kyo.Binding
import kyo.kernel.internal.Kyo.Bindings
import kyo.kernel.internal.Kyo.Park
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
  * **Simple State Copying** is used for [[ContextEffect]]s, whose bindings are inherited by a forked computation as-is. These are effects
  * like environment variables, configuration settings, or local values: pieces of state that can simply be carried over when the
  * computation forks.
  *
  * **Complex State Management** handles effects that require structured transformation. When forking a computation with these effects, the
  * isolation:
  *
  *   1. Captures a snapshot of the current state
  *   2. Transforms that state during isolated execution
  *   3. Restores the transformed state when the fork completes
  *
  * #### Operations
  *
  * Operations specify their isolation requirements through the type parameters. For example:
  *   - `Fiber.init` requires `Isolate[S, Sync, S2]` - only Sync effects available during initialization
  *   - `Async.foreach` might use `Isolate[S, Abort[E] & Async, S]` - async operations available, same effects restored
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
  * #### Explicit Composition
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
  *    Async.foreach(parallelism)(tasks)
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

    /** Isolates 'Remove' effects while exposing them as nested 'Restore' effects.
      *
      * This method "tunnels" the Remove effects through the isolation, transforming them into 'Restore' effects that appear nested in the
      * result type. Unlike 'run' which directly applies the 'Restore' effects, 'nest' preserves them as a nested effect layer.
      *
      * This is useful when you want to isolate effects for a specific operation but need to control when and how the resulting 'Restore'
      * effects are applied in your program.
      *
      * @param v
      *   The computation containing effects to tunnel through isolation
      * @return
      *   A computation with 'Restore' effects nested in the result type
      */
    def nest[A, S](v: A < (Remove & S))(using Frame): A < Restore < (Remove & Keep & S) =
        // Kyo.lift is the deliberate nesting: at its generic position the implicit lift's runtime analysis
        // nests the computation payload exactly once. A concrete `A < Restore` argument cannot take the
        // conversion directly, since the lift's guard rejects syntactically pending types
        capture { state =>
            isolate(state, v).map(r => Kyo.lift[A < Restore, Any](restore(r)))
        }

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

    /** Prepares a computation to run in an evaluation of its own, carrying the context that crosses.
      *
      * This is the untyped half of isolation, and the counterpart of the instances above. Where an
      * `Isolate[Remove, Keep, Restore]` says how a handled effect's state crosses a fork, in types the caller
      * writes, this says how the values bound around the fork cross: every named binding in scope is asked,
      * through its own `fork`, what a computation forked from here receives. A binding that answers with a
      * value is inherited, one that answers `Absent` is not, and one that answers with something else crosses
      * as that instead, which is the whole of what the previous kernel's non-inheritable marker said and more.
      *
      * What comes back is a complete value: the computation with the crossed context attached to it, valid in
      * any evaluation and on any thread. Running it installs that context first, so the computation reads what
      * it read here, however far from here it eventually runs. Nothing is taken from the forking computation,
      * which carries on with everything it had.
      *
      * Resources do not cross. A bracket's scope belongs to the computation that opened it, and a fork that
      * inherited one would leave its owner releasing what the fork is still using; the forked computation opens
      * its own. For the same reason the crossed value owes no releases: abandoning it releases nothing, because
      * it holds nothing that was not already the forking computation's to release.
      *
      * @param v
      *   The computation to prepare
      * @return
      *   A computation producing `v` with the crossed context attached
      */
    def apply[A, S](v: A < S)(using _frame: Frame): (A < S) < Any =
        new Bindings[A < S, Any]:
            def resume(bindings: Span[Binding[?, ?, ?, ?]], held: Span[Maybe[Any]]): (A < S) < Any =
                if bindings.isEmpty then Kyo.lift[A < S, Any](v)
                else cross(bindings, held, 0, new Array(bindings.size), new Array(bindings.size), 0, v)

    /** Asks each binding for its crossing, innermost first, and attaches what crossed to `v`.
      *
      * A loop written as a recursion because a crossing is a computation: each answer is awaited before the
      * next is asked, so a strategy that reads or suspends runs where it was defined, with the forking
      * computation's handlers still in place.
      *
      * What is built is a binding of the same name holding what crossed, rather than the one that was asked.
      * The original derives its value from what encloses it, and re-deriving it in the evaluation that resumes
      * the fork would answer with the forking computation's value again, discarding the crossing. Freezing it
      * is what makes `fork` mean anything. The strategies come along, so a fork of the fork asks the same
      * questions.
      */
    private def cross[A, S](
        bindings: Span[Binding[?, ?, ?, ?]],
        held: Span[Maybe[Any]],
        i: Int,
        entries: Array[Arrow[?, ?, ?]],
        states: Array[Maybe[Any]],
        w: Int,
        v: A < S
    )(using _frame: Frame): (A < S) < Any =
        if i == bindings.size then
            if w == 0 then Kyo.lift[A < S, Any](v)
            else
                // trimmed by hand rather than copied through the array utilities: the slot type is opaque,
                // so it is not one of the shapes they are written for
                val es  = new Array[Arrow[?, ?, ?]](w)
                val sts = new Array[Maybe[Any]](w)
                var j   = 0
                while j < w do
                    es(j) = entries(j)
                    sts(j) = states(j)
                    j += 1
                end while
                val parked = new Park[A, S](v, Span.fromUnsafe(es), Span.fromUnsafe(sts), Span.empty)
                // the deliberate nesting: a computation handed out as a value is `Nested`-wrapped exactly
                // once, which is what `Kyo.lift` does at its generic position. See the note in `nest`
                Kyo.lift[A < S, Any](parked)
            end if
        else
            // erasure-forced: one span holds the bindings of every value type, and each was written with its
            // own. The value read from the slot is the one this binding put there, so the pair lines up
            val binding = bindings(i).asInstanceOf[Binding[Any, Nothing, Any, Any]]
            held(i) match
                case Present(h) =>
                    binding.fork(h).map { crossed =>
                        crossed match
                            case Present(value) =>
                                entries(w) = frozen(binding, value)
                                states(w) = Present(value)
                                cross(bindings, held, i + 1, entries, states, w + 1, v)
                            case Absent =>
                                cross(bindings, held, i + 1, entries, states, w, v)
                    }
                case Absent =>
                    cross(bindings, held, i + 1, entries, states, w, v)
            end match
        end if
    end cross

    /** A binding of the same name holding what crossed, with the strategies of the one it came from. */
    @nowarn("msg=anonymous")
    private def frozen(binding: Binding[Any, Nothing, Any, Any], value: Any)(using
        _frame: Frame
    ): Binding[Any, Nothing, Any, Any] =
        new Binding[Any, Nothing, Any, Any]:
            def frame                                 = _frame
            val tag                                   = binding.tag
            val bound                                 = Maybe((_: Maybe[Any]) => value)
            override def fork(held: Any)              = binding.fork(held)
            override def join(held: Any, forked: Any) = binding.join(held, forked)
            // never reached: this is built to stand on a stack, and an entry answers a value flowing back
            // through it with `apply`, which is identity for a binding. Only a binding the eval meets as a
            // computation resumes, and this one is never that
            def resume(held: Maybe[Any]) = bug("a crossed binding was evaluated rather than installed")

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

            isolates.flatMap(_._2).foldLeft('{ Identity.asInstanceOf[Isolate[Remove, Keep, Restore]] })((prev, next) =>
                '{ $prev.andThen($next.asInstanceOf[Isolate[Remove, Keep, Restore]]) }
            )
        end deriveImpl
    end internal

end Isolate
