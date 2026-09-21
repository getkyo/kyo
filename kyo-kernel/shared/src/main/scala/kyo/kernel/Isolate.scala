package kyo.kernel

import Isolate.internal.*
import kyo.*
import kyo.Ansi.*
import kyo.kernel.Arrow
import kyo.kernel.internal.*
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
  * original computation to the forked one. These are effects like environment variables, configuration settings, or local values: pieces of
  * state that can simply be copied as-is when the computation forks.
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
      */
    def capture[A, S](f: State => A < S)(using Frame): A < (Remove & S)

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
        capture { state =>
            isolate(state, v).map(r => Nested.nest[A < Restore, Any](restore(r)))
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
        capture(state => run(state, v))

    def run[A, S](state: State, v: A < (S & Remove))(using Frame): A < (Keep & Restore & S) =
        restore(isolate(state, v))

    final def apply[A, S](v: A < (Remove & S))[B, S2](f: (A < (Restore & Keep & S)) => B < S2)(using
        Frame
    ): B < (Remove & Keep & S2) =
        capture(state => f(run(state, v)))

    /** Applies this isolate to a computation that requires it.
      *
      * Provides a more ergonomic way to use isolates with operations:
      * {{{
      * Var.isolate.update[Int].use {
      *   Async.uninterruptible {
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

    /** This isolate, plus the crossing that leaving one fiber for another makes.
      *
      * Isolating state in place and carrying it to another fiber are different acts, and only the second asks a context region what a fork
      * of it holds.
      *
      * Capture and isolate must come from the same instance, so a spawn holds the result of one call rather than calling this twice.
      */
    final private[kyo] def crossing: Isolate[Remove, Keep, Restore] =
        Contextual.andThen(this)

end Isolate

object Isolate:

    /** The effect that marks a computation as unable to cross an isolation boundary.
      *
      * A continuation a handler clause receives carries `Region.NoEscape`, which is this effect (see [[Region]]). The derivation refuses
      * it with an explanation instead of looking for an instance, so moving such a computation to another fiber does not compile.
      */
    sealed abstract class Disallowed extends Effect

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
    inline def derive[Remove, Keep, Restore]: Isolate[Remove, Keep, Restore] = ${ deriveImpl[Remove, Keep, Restore] }

    // `Restore <: Remove` is used to help with implicit resolution. Without it, the compiler infers `Restore` as `Any` in some cases.
    inline given [Remove, Keep, Restore <: Remove]: Isolate[Remove, Keep, Restore] = ${ deriveImpl[Remove, Keep, Restore] }

    private[kyo] object internal:

        /** The isolate that manages nothing, and the base case a composition folds onto.
          *
          * The base has to be the identity of `andThen`, so an isolate for effects nobody named does nothing at all.
          */
        private[kernel] object Identity extends Isolate[Any, Any, Any]:
            type State        = Unit
            type Transform[A] = A
            def capture[A, S](f: State => A < S)(using Frame)              = f(())
            def isolate[A, S](state: State, v: A < (S & Any))(using Frame) = v
            def restore[A, S](v: A < S)(using Frame)                       = v
        end Identity

        // Carries the context across a fork: snapshots the context regions, runs the isolated computation over a
        // forked snapshot, and joins each region back through its own fork and join strategy. Reached through
        // `crossing`, at the sites that leave one fiber for another, never as the base case of a composition: an
        // isolate asked for in place forks nothing.
        private[kernel] object Contextual extends Isolate[Any, Any, Any]:
            type State        = Stack.Snapshot
            type Transform[A] = (Stack.Snapshot, Stack.Snapshot, A)

            def capture[A, S](f: Stack.Snapshot => A < S)(using _frame: Frame): A < S =
                new Pending.SnapshotWith[A, S]:
                    override def frame = _frame
                    def cont           = this

                    override def apply[C, S2](v: Stack < S2, cont2: Arrow[A, C, S2]) =
                        v match
                            case p: Pending[Stack, S2] @unchecked => Effect.defer(p, this, cont2)
                            case _                                => cont2(f(Nested.unnest[Stack](v).contextual()), Arrow.id)

            def isolate[A, S](state: Stack.Snapshot, v: A < S)(using Frame): (Stack.Snapshot, Stack.Snapshot, A) < S =
                val forked                          = fork(state)
                val inner: (Stack.Snapshot, A) < S  = v.map(a => capture(finals => (finals, a)))
                val parked: (Stack.Snapshot, A) < S = Pending.Park[(Stack.Snapshot, A), S](inner.asInstanceOf[Any < Any], forked)
                parked.map((finals, a) => (forked, finals, a))
            end isolate

            def restore[A, S](v: (Stack.Snapshot, Stack.Snapshot, A) < S)(using _frame: Frame): A < S =
                v.map { (forked, finals, a) =>
                    new Pending.SnapshotWith[A, S]:
                        override def frame = _frame
                        def cont           = this

                        override def apply[C, S2](cur: Stack < S2, cont2: Arrow[A, C, S2]) =
                            cur match
                                case p: Pending[Stack, S2] @unchecked => Effect.defer(p, this, cont2)
                                case _                                =>
                                    val av: A < Any = a
                                    join(forked, finals, Nested.unnest[Stack](cur))
                                    cont2(av, Arrow.id)
                }

            // Marks a region's handler as the fork of `origin`, so `join` can find the region it was forked from.
            final private class Forked[State, E <: ContextEffect[State], A, S](val origin: Handler.ContextHandler[State, E, A, S])
                extends Handler.ContextHandler[State, E, A, S]:
                def tag = origin.tag

                def derive(outer: Maybe[State]): State                      = origin.derive(outer)
                def fork(parent: State): State                              = origin.fork(parent)
                def join(parent: State, forked: State, child: State): State = origin.join(parent, forked, child)
                def release(state: State, failure: Maybe[Throwable]): Unit  = origin.release(state, failure)
            end Forked

            private def fork(entries: Stack.Snapshot): Stack.Snapshot =
                if entries.isEmpty then entries
                else
                    val out = Stack.Snapshot.Builder(entries.regions)
                    var i   = 0
                    while i < entries.regions do
                        val origin = entries.handler(i).asInstanceOf[Handler.ContextHandler[Any, ContextEffect[Any], Any, Any]]
                        out.add(new Forked(origin), origin.fork(entries.state(i)))
                        i += 1
                    end while
                    out.result()
            end fork

            private def join(forked: Stack.Snapshot, finals: Stack.Snapshot, stack: Stack): Unit =
                var i = 0
                while i < forked.regions do
                    forked.handler(i) match
                        case copy: Forked[Any, ContextEffect[Any], Any, Any] @unchecked =>
                            val origin = copy.origin
                            var j      = stack.depth - 1
                            while j >= 0 && !(stack.handler(j) eq origin) do j -= 1
                            if j >= 0 then
                                val parent = stack.state(j)
                                var child  = forked.state(i)
                                var k      = finals.regions - 1
                                while k >= 0 do
                                    if finals.handler(k) eq copy then
                                        child = finals.state(k)
                                        k = -1
                                    else k -= 1
                                end while
                                val joined = origin.join(parent, forked.state(i), child)
                                if joined.asInstanceOf[AnyRef] ne parent.asInstanceOf[AnyRef] then stack.setState(j, joined)
                            end if
                        case _ => ()
                    end match
                    i += 1
                end while
            end join
        end Contextual

        def deriveImpl[Remove: Type, Keep: Type, Restore: Type](using Quotes): Expr[Isolate[Remove, Keep, Restore]] =
            import quotes.reflect.*

            def flatten(tpe: TypeRepr): List[TypeRepr] =
                tpe match
                    case AndType(left, right)        => flatten(left) ++ flatten(right)
                    case t if t =:= TypeRepr.of[Any] => Nil
                    // The bottom type has to be dropped rather than left to the tests: every `t <:< X` holds
                    // for it, so a row inferred as Nothing, which is what an unconstrained row in a contravariant
                    // position becomes, would read as naming the no-escape marker and be refused as a region escape.
                    case t if t =:= TypeRepr.of[Nothing] => Nil
                    case t                               => List(t)

            val keep   = flatten(TypeRepr.of[Keep])
            val remove = flatten(TypeRepr.of[Remove])

            // before Keep is subtracted: naming the marker in Keep must not buy an isolate for it
            remove.find(_ <:< TypeRepr.of[Isolate.Disallowed]).foreach { t =>
                report.errorAndAbort(
                    s"""|This computation cannot leave the region that handed it out:
                        |
                        |  ${t.show.red}
                        |
                        |It is the continuation a handler clause received, and it carries the regions that sat
                        |between the handler and the suspension, a bracket included. The handler releases what
                        |they carry when the clause returns, so the continuation is only valid on this fiber,
                        |inside that clause. It cannot be forked, raced, timed out, or sent to another fiber.
                        |
                        |Answer with it here, or move its values across the boundary through a Channel and
                        |consume them on this fiber.
                        |""".stripMargin
                )
            }

            val isolates =
                remove
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
                        |   val isolate = new Isolate[MyEffect, Any, MyEffect] {
                        |     type State = MyState        // Your effect's state
                        |     type Transform[A] = (State, A)
                        |     ...                         // capture, isolate, restore
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
