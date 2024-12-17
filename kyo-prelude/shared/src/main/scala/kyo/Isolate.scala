package kyo

import kyo.*

/** Provides fine-grained control over state isolation and propagation within computations.
  *
  * The `Isolate` mechanism allows you to control how effect state is handled, contained, and propagated during computation. This is
  * essential for implementing effects that need careful control over their state visibility and ordering of operations.
  *
  * While Isolate provides a low-level API through `use`, `resume`, and `restore` for advanced use cases, most users should rely on the
  * high-level `run` method which composes these operations safely and handles all the complexity of state management internally.
  *
  * Effects typically provide their isolation strategies through their companion objects (e.g. `Var.isolate.update`, `Check.isolate.merge`).
  * These isolates can be composed using `andThen`, which enforces the ordering of effect handling - ensuring effects are handled in the
  * specified sequence.
  *
  * Key use cases include:
  *   - Transactional effects where state changes may need to be contained until commit
  *   - Effects that require explicit control over state propagation order
  *   - Parallel computations where state needs to be merged in specific ways
  *   - Scenarios where effect state should be temporarily scoped or discarded
  *   - Error handling where partial effects must be rolled back atomically
  *   - Complex workflows requiring nested transaction boundaries
  *
  * Implementations typically choose one of three strategies:
  *   - Merge: Combine states when multiple computations need to reconcile their effects
  *   - Update: Unconditionally updates the state with the last available value
  *   - Discard: Prevent state from propagating beyond a defined scope
  *
  * A key feature of Isolate is its interaction with error handling through the Abort effect. When an Abort occurs within an isolated scope,
  * the isolation acts as a transaction boundary - isolated effects within the isolated scope are rolled back or discarded, maintaining
  * consistency of the outer scope. This transactional behavior applies across all effect types:
  *   - State changes are rolled back to their pre-isolation values
  *   - Emitted values are discarded rather than being merged
  *   - Cached computations remain isolated and don't propagate
  *   - Nested isolations maintain these guarantees at each level
  *
  * This combination of isolation strategies and error handling makes Isolate particularly powerful for implementing robust,
  * transaction-like behaviors across different effect types while maintaining clean separation of concerns.
  *
  * @tparam S
  *   The type of effect being isolated
  */
abstract class Isolate[S]:
    self =>

    /** The type of state managed by this isolate.
      *
      * This abstract type member represents the concrete state type that will be managed during isolation. The exact type depends on the
      * effect being isolated.
      */
    type State

    /** Isolates a computation.
      *
      * This is the primary method users should use for isolation. It handles all the complexity of state management internally by composing
      * the lower-level isolation operations.
      *
      * @param v
      *   The computation to run with isolation
      * @return
      *   The computation result with isolated state handling
      */
    def run[A: Flat, S2](v: A < S2)(using Frame): A < (S & S2) =
        use(resume(_, v).map(restore(_, _)))

    /** Starts the isolation flow by providing access to the initial state.
      *
      * Advanced usage: This is the first step in the low-level state isolation API. Most users should use `run` instead. It provides access
      * to the current effect state, which can then be used with resume to begin isolated execution.
      *
      * @param f
      *   Function that receives initial state and starts the isolation flow
      * @return
      *   A computation that includes the isolated effect
      */
    def use[A, S2](f: State => A < S2)(using Frame): A < (S & S2)

    /** Begins isolated execution of a computation using the provided state.
      *
      * Advanced usage: After obtaining initial state via use, resume begins isolated execution of a computation. Most users should use
      * `run` instead. It returns both the final state and computation result, allowing the state to be later restored via restore.
      *
      * @param state
      *   Initial state obtained from use
      * @param v
      *   Computation to execute in isolation
      * @return
      *   A pair of final state and computation result
      */
    def resume[A: Flat, S2](state: State, v: A < (S & S2))(using Frame): (State, A) < S2

    /** Completes the isolation flow by restoring the final state.
      *
      * Advanced usage: This is the final step in the low-level isolation API. Most users should use `run` instead. Takes the state and
      * result from resume and determines how that state should be applied when returning to the normal execution context. The exact
      * behavior (merge/update/discard) depends on the isolation strategy.
      *
      * @param state
      *   Final state from resume to restore
      * @param v
      *   Computation to continue with restored state
      * @return
      *   Computation result with restored state
      */
    def restore[A: Flat, S2](state: State, v: A < S2)(using Frame): A < (S & S2)

    /** Combines this isolate with another one in sequence.
      *
      * Creates a new isolate that manages both effects' states, defining how their respective states interact and propagate when composed
      * together.
      *
      * @param next
      *   The isolate to combine with this one
      * @return
      *   A new isolate managing both effects
      */
    def andThen[S2](next: Isolate[S2])(using Frame): Isolate[S & S2] =
        new Isolate[S & S2]:
            type State = (self.State, next.State)

            def use[A, S2](f: State => A < S2)(using Frame) =
                self.use(s1 => next.use(s2 => f((s1, s2))))

            def resume[A: Flat, S3](state: (self.State, next.State), v: A < (S & S2 & S3))(using Frame) =
                self.resume(state._1, next.resume(state._2, v)).map {
                    case (s1, (s2, r)) => ((s1, s2), r)
                }
            def restore[A: Flat, S2](state: (self.State, next.State), v: A < S2)(using Frame) =
                self.restore(state._1, next.restore(state._2, v))
        end new
    end andThen

end Isolate
