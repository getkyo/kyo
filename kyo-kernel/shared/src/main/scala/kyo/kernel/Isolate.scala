package kyo.kernel

import kyo.Ansi.*
import kyo.Frame
import kyo.kernel.internal.*
import scala.annotation.nowarn
import scala.quoted.*

/** Provides mechanisms for handling pending effects when forking computations.
  *
  * Isolate provide two ways to handle state isolation, each designed for a specific category of effects:
  *
  * [[Contextual]] isolation is designed for [[ContextEffect]]s, which store their state in a simple format that can be directly copied from
  * the original computation to the forked one. These are effects like environment variables, configuration settings, or local values -
  * pieces of state that can simply be copied as-is when the computation forks.
  *
  * [[Stateful]] isolation handles effects with more complex state that requires structured management. When forking a computation with
  * these effects, the solution needs to:
  *
  *   1. Capture a snapshot of the current state
  *   2. Transform that state during isolated execution
  *   3. Merge the transformed state back when the fork completes
  *
  * ==Operations==
  *
  * Operations determine which isolation capability they require. Some operations like Async.run require Contextual isolation to enable the
  * forked computation to execute to completion, while others like Async.parallel allow Stateful isolation given that the return type allows
  * restoring forked effects after the parallel execution finishes. The choice between Contextual and Stateful isolation is made by the
  * operation, not the user.
  *
  * Most effects provide implicit instances based on their state management needs. Some effects like Var and Emit provide multiple
  * strategies through a dedicated isolate object, allowing users to choose between updating final values, merging changes, or keeping
  * modifications local.
  *
  * For effect intersections like (Env[Config] & Var[State]), instances are automatically derived if each component effect has an instance.
  * Stateful isolation subsumes Contextual - if any effect needs Stateful isolation, the derived instance will be Stateful since it can
  * handle both simple copying and complex state management.
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
  *  // Use in operations that accept Stateful isolation
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
  * @tparam Retain
  *   Effects that will be satisfied (handled) by the isolation
  * @tparam Passthrough
  *   Additional effects that will remain pending after isolation
  */
object Isolate:

    /** Controls effect propagation during forking by copying effect state.
      *
      * @tparam Retain
      *   The effects that will be satisfied by this isolation
      * @tparam Passthrough
      *   Additional effects that will remain pending after isolation
      */
    sealed abstract class Contextual[Retain, -Passthrough] extends Serializable:

        /** Runs a computation with transformed effects.
          *
          * Takes a computation with effects that include Retain, and produces a computation where Retain effects are satisfied while
          * Passthrough effects remain pending.
          *
          * @param v
          *   The computation to transform
          * @param frame
          *   The execution frame
          * @return
          *   The transformed computation
          */
        @nowarn("msg=anonymous")
        inline def run[A, S](inline v: => A < (S & Retain))(inline _frame: Frame): A < (S & Passthrough) =
            new KyoDefer[A, S & Passthrough]:
                def frame = _frame
                def apply(ign: Unit, context: Context)(using safepoint: Safepoint) =
                    def loop(v: A < (S & Retain)): A < (S & Passthrough) =
                        v match
                            case kyo: KyoSuspend[IX, OX, EX, Any, A, S & Retain] @unchecked =>
                                new KyoContinue[IX, OX, EX, Any, A, S & Passthrough](kyo):
                                    def frame = _frame
                                    def apply(v: OX[Any], context: Context)(using Safepoint) =
                                        loop(kyo(v, context))
                            case _ =>
                                v.unsafeGet
                    loop(v)
                end apply

        // TODO: Double-check all the Frames, make it non-implicit?
        /** Internal API for running computations with trace and context management. */
        @nowarn("msg=anonymous")
        private[kyo] inline def runInternal[A, S](inline f: (Trace, Context) => A < S)(using inline _frame: Frame): A < (S & Passthrough) =
            new KyoDefer[A, S & Passthrough]:
                def frame = _frame
                def apply(v: Unit, context: Context)(using safepoint: Safepoint) =
                    f(safepoint.saveTrace(), context.inherit)
    end Contextual

    object Contextual:

        /** Cached instance to avoid allocations */
        private[Isolate] val cached: Contextual[Any, Any] =
            new Contextual[Any, Any]:
                def run[A, S2](v: A < S2): A < S2 = v

        /** Gets the Contextual isolate instance for given effect types. */
        def apply[R, P](using s: Contextual[R, P]): Contextual[R, P] = s

        /** Derives a Contextual isolate instance based on available instances. */
        inline given derive[R, P]: Contextual[R, P] = ${ deriveImpl[R, P] }

        private def deriveImpl[R: Type, P: Type](using Quotes): Expr[Isolate.Contextual[R, P]] =
            commonDeriveImpl[R, P, Isolate.Contextual[R, P]] { (retain, _) =>
                import quotes.reflect.*

                val missing = retain.filter { t =>
                    t.asType match
                        case '[tpe] => Expr.summon[Isolate.Contextual[tpe, P]].isEmpty
                }

                if missing.nonEmpty then
                    report.errorAndAbort(
                        s"""|This operation requires Contextual isolation for effects:
                            |
                            |  ${missing.map(_.show.red).mkString(" & ")}
                            |
                            |Common mistake: Using operations like Async.run with effects that need complex state management.
                            |
                            |You have a couple of options, from simplest to most advanced:
                            |
                            |1. Handle these effects before the operation:
                            |   Async.run(MyEffect.run(computation))
                            |
                            |2. Use an operation that supports complex state:
                            |   Instead of Async.run, use Async.parallel 
                            |""".stripMargin
                    )
                end if

                '{ cached.asInstanceOf[Isolate.Contextual[R, P]] }
            }

    end Contextual

    /** Controls state isolation for effects requiring structured state management.
      *
      * Provides a three-phase approach to state isolation:
      *   1. Capture initial state before forking
      *   2. Transform state during isolated execution
      *   3. Restore transformed state when the fork completes
      *
      * @tparam Retain
      *   The effects that will be satisfied by this isolation
      * @tparam Passthrough
      *   Additional effects that will remain pending after isolation
      */
    abstract class Stateful[Retain, -Passthrough] extends Serializable:
        self =>

        /** The type of state being managed */
        type State

        /** How state is transformed during isolated execution */
        type Transform[_]

        /** Runs a computation with full state lifecycle management.
          *
          * Convenience method that composes capture, isolate and restore to handle the complete state lifecycle.
          */
        final def run[A, S](v: A < (S & Retain))(using Frame): A < (S & Retain & Passthrough) =
            capture(state => restore(isolate(state, v)))

        /** Phase 1: Capture Initial State
          *
          * Obtains the initial state that will be managed during isolation. This begins the isolation process by capturing current state.
          */
        def capture[A, S](f: State => A < S)(using Frame): A < (S & Retain & Passthrough)

        /** Phase 2: Isolated Execution
          *
          * Executes a computation with isolated state. The computation runs with a transformed copy of the state, preventing effects from
          * leaking.
          */
        def isolate[A, S](state: State, v: A < (S & Retain))(using Frame): Transform[A] < (S & Passthrough)

        /** Phase 3: State Restoration
          *
          * Restores/merges state after isolated execution completes. Determines how transformed state is propagated back to the outer
          * context.
          */
        def restore[A, S](v: Transform[A] < S)(using Frame): A < (S & Retain & Passthrough)

        /** Applies this isolate to a computation that requires it.
          *
          * Provides a more ergonomic way to use isolates with effects:
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
          * effect tracking.
          */
        def andThen[R2, P2](next: Stateful[R2, P2]): Stateful[Retain & R2, Passthrough & P2] =
            new Stateful[Retain & R2, Passthrough & P2]:
                type State        = (self.State, next.State)
                type Transform[A] = self.Transform[next.Transform[A]]
                def capture[A, S2](f: State => A < S2)(using Frame) =
                    self.capture(s1 => next.capture(s2 => f((s1, s2))))

                def isolate[A, S3](state: (self.State, next.State), v: A < (Retain & R2 & S3))(using Frame) =
                    self.isolate(state._1, next.isolate(state._2, v))

                def restore[A, S2](v: self.Transform[next.Transform[A]] < S2)(using Frame) =
                    next.restore(self.restore(v))
            end new
        end andThen
    end Stateful

    object Stateful:

        /** No-op isolate with no state. */
        val noop: Stateful[Any, Any] =
            new Stateful[Any, Any]:
                type State        = Unit
                type Transform[A] = A
                def capture[A, S2](f: State => A < S2)(using Frame)             = f(())
                def isolate[A, S2](state: Unit, v: A < (Any & S2))(using Frame) = v
                def restore[A, S2](v: A < S2)(using Frame)                      = v
                override def andThen[R2, P2](next: Stateful[R2, P2])            = next

        /** Gets the Stateful isolate instance for given effect types. */
        def apply[Retain, Passthrough](using s: Stateful[Retain, Passthrough]): Stateful[Retain, Passthrough] = s

        /** Derives a Stateful isolate instance based on available instances. */
        inline given derive[R, P]: Stateful[R, P] = ${ deriveImpl[R, P] }

        private def deriveImpl[R: Type, P: Type](using Quotes): Expr[Isolate.Stateful[R, P]] =
            commonDeriveImpl[R, P, Isolate.Stateful[R, P]] { (retain, _) =>
                import quotes.reflect.*

                val isolates = retain.map { t =>
                    t.asType match
                        case '[tpe] =>
                            t ->
                                Expr.summon[Isolate.Contextual[tpe, P]].map(Left(_)).orElse(
                                    Expr.summon[Isolate.Stateful[tpe, P]].map(Right(_))
                                )
                }

                isolates.filter(_._2.isEmpty) match
                    case Nil =>
                    case missing =>
                        report.errorAndAbort(
                            s"""|This operation requires Stateful isolation for effects:
                                |
                                |  ${missing.map(_._1.show.red).mkString(" & ")}
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
                end match

                val statefulIsolates =
                    isolates.flatMap {
                        case (_, Some(Right(isolate))) => Some(isolate)
                        case _                         => None
                    }

                statefulIsolates.foldLeft('{ noop.asInstanceOf[Stateful[R, P]] })((prev, next) =>
                    '{ $prev.andThen($next.asInstanceOf[Isolate.Stateful[R, P]]) }
                ).asExprOf[Isolate.Stateful[R, P]]
            }

    end Stateful

    private def commonDeriveImpl[R: Type, P: Type, T: Type](
        using Quotes
    )(
        handler: (List[quotes.reflect.TypeRepr], List[quotes.reflect.TypeRepr]) => Expr[T]
    ): Expr[T] =
        import quotes.reflect.*

        def flatten(tpe: TypeRepr): List[TypeRepr] =
            tpe match
                case AndType(left, right) => flatten(left) ++ flatten(right)
                case OrType(left, right)  => report.errorAndAbort("Isolate: Unsupported type union in Pending Effects: ${tpe.show}\n".red)
                case t if t =:= TypeRepr.of[Any] => Nil
                case t                           => List(t)

        val pass   = flatten(TypeRepr.of[P])
        val retain = flatten(TypeRepr.of[R]).filterNot(t => pass.exists(t =:= _))
        handler(retain, pass)
    end commonDeriveImpl

    private[kyo] inline def restoring[Ctx, A, S](trace: Trace, interceptor: Safepoint.Interceptor)(
        inline v: => A < (Ctx & S)
    )(using frame: Frame, safepoint: Safepoint): A < (Ctx & S) =
        Safepoint.immediate(interceptor)(safepoint.withTrace(trace)(v))

end Isolate
