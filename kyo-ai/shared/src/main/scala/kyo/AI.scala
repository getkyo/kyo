package kyo

import kyo.ai.Config
import kyo.ai.Context
import kyo.ai.Context.*
import kyo.ai.Image

/** First-class identity for one conversation slot. A reference object so the threaded `State` can hold its
  * slot through a `WeakReference` (`LLM.internal.AIRef`): once the surrounding computation no longer
  * references an `AI`, GC reclaims it and the eval loop sweeps its now-dead slot, so instances never
  * accumulate in a long-lived run. Minted by the `init` op, each id drawn from the run's threaded `State`
  * counter, so identity is scoped to one `LLM.run` with no global mutable state. An instance also remembers
  * the run that created it (`owner`), so using it inside a different `LLM.run` fails fast with a clear error
  * instead of silently addressing that run's same-id slot.
  */
final class AI private[kyo] (private[kyo] val id: Long, private[kyo] val owner: AnyRef):
    private[kyo] val ref: LLM.internal.AIRef = new LLM.internal.AIRef(this)

/** The first-class instance API. `AI` is both the identity value (`ai`) and the namespace of operations.
  *
  * `AI.gen` is a one-shot: it mints a fresh, isolated ephemeral instance, generates against it, and drops
  * it, so two one-shots never share state. `ai.gen` on a named instance (from `AI.init`) runs against a
  * persistent slot whose conversation, enablements, and config survive across turns within a single
  * `LLM.run`. Every method is a thin value over the `LLM` effect surface: `AI` summons no `ArrowEffect` op
  * directly, only `LLM`'s `private[kyo]` interface.
  */
object AI:

    given CanEqual[AI, AI] = CanEqual.derived

    given Ordering[AI] = Ordering.by(_.id)

    /** Surfaced under `AI` so `import kyo.*` reaches the settings and content types without a `kyo.ai` import:
      * `AI.Config`, `AI.Context`, `AI.Image`.
      */
    export kyo.ai.Config
    export kyo.ai.Context
    export kyo.ai.Image

    /** A composable element of the generation surface that can be enabled on an `AI`: a [[kyo.Tool]], a
      * [[kyo.Prompt]], a [[kyo.Thought]], a [[kyo.Mode]], or a [[kyo.Compactor]].
      *
      * `AI.enable` layers enablements over a scoped computation; `ai.enable` layers them onto a single
      * instance. Both take varargs or a `Seq` and accept a mix of kinds in one call. `S` is the capability an
      * enablement's code requires (a tool's run, a thought's process hook, an effectful prompt, a mode's
      * pipeline), which rides the row to the run boundary where it must be discharged.
      */
    trait Enablement[-S]:
        // How this enablement layers itself onto the scope env (AI.enable) or one instance's session
        // (ai.enable). private[kyo] so only the module's five kinds implement it; users compose, or
        // implement a kind that is itself open (Compactor); the kind set is closed.
        private[kyo] def enableIn(env: AIEnv)(using Frame): AIEnv
        private[kyo] def enableIn(session: AISession)(using Frame): AISession
    end Enablement

    /** Mints a fresh instance with an empty conversation and no enablements. */
    def init(using Frame): AI < LLM = LLM.init

    def initWith[A, S](f: AI => A < (LLM & S))(using Frame): A < (LLM & S) =
        init.map(f)

    /** Mints an instance carrying its own config, overriding the scope config for its generations. */
    def init(config: Config)(using Frame): AI < LLM =
        init(AISession.empty.config(config))

    /** Mints an instance initialized from an `AISession`: its conversation, enablements, and config. */
    def init(session: AISession)(using Frame): AI < LLM =
        init.map(ai => LLM.setSession(ai, session).andThen(ai))

    /** Recreates an instance from a snapshot, restoring its conversation, enablements, and config. */
    def recover(session: AISession)(using Frame): AI < LLM =
        init(session)

    // Internal: read-modify-write one instance's AISession; returns the instance for chaining.
    private[kyo] def updateSession(ai: AI)(f: AISession => AISession)(using Frame): AI < LLM =
        LLM.session(ai).map(s => LLM.setSession(ai, f(s))).andThen(ai)

    /** A one-shot generation: mints a fresh ephemeral instance, generates against it, then discards its slot
      * on success. Two one-shots never share state.
      */
    def gen[A: Schema](using Frame): A < LLM =
        init.map(ai => ai.gen[A].map(r => ai.reset.andThen(r)))

    def gen[A: Schema](using Frame)[B: Schema](input: B): A < LLM =
        init.map(ai => ai.gen[A](input).map(r => ai.reset.andThen(r)))

    def gen[A: Schema](using Frame)[B: Schema, C: Schema](input1: B, input2: C): A < LLM =
        gen[A]((input1, input2))

    def gen[A: Schema](using Frame)[B: Schema, C: Schema, D: Schema](input1: B, input2: C, input3: D): A < LLM =
        gen[A]((input1, input2, input3))

    def gen[A: Schema](using Frame)[B: Schema, C: Schema, D: Schema, E: Schema](input1: B, input2: C, input3: D, input4: E): A < LLM =
        gen[A]((input1, input2, input3, input4))

    /** Projects a generation as a `Stream`, in one of two forms inferred from `A`. For a `String` the stream
      * is incremental text chunks whose concatenation is the final answer (the chat-UI, token-by-token case).
      * For any other type the stream is object by object: the model produces a sequence of `A` and each element
      * is emitted once it is complete, never a half-filled value (the iterable case, for extracting or generating
      * multiple records). Mints a fresh ephemeral instance to stream against.
      */
    def stream[A: Schema](using Frame, Tag[Emit[Chunk[A]]]): Stream[A, Async & Scope & Abort[AIStreamException]] < LLM =
        init.map(ai => ai.stream[A])

    /** Reads the current scope `AIEnv`: the active config plus the scope's enablements (prompt, tools, thoughts, modes, compactor). */
    def env(using Frame): AIEnv < LLM = LLM.env

    /** Reads the active config. The active env (the scope env, or the scope merged with an instance during a
      * gen) always carries a `Present` config; the `Absent` case lives only in a stored instance env, which is
      * merged into the scope before it is read.
      */
    def config(using Frame): Config < LLM = LLM.env.map(_.config.get)

    /** Layers a transformed config for the duration of `v`, restoring the prior config after. */
    def withConfig[A, S](f: Config => Config)(v: A < (LLM & S))(using Frame): A < (LLM & S) =
        LLM.updateEnv(_.mapConfig(f))(v)

    def withConfig[A, S](config: Config)(v: A < (LLM & S))(using Frame): A < (LLM & S) =
        withConfig(_ => config)(v)

    /** Layers enablements (tools, prompts, thoughts, modes, compactors, in any mix) over a scoped computation,
      * on top of the scope's current enablements. Each enablement's capability `S` rides the row, unified across
      * the varargs to their intersection, so the requirements stay visible until discharged at the run boundary.
      */
    def enable[A, S](enablements: Enablement[S]*)(v: A < S)(using Frame): A < (S & LLM) =
        if enablements.isEmpty then v
        else LLM.updateEnv(env => enablements.foldLeft(env)((acc, e) => e.enableIn(acc)))(v)

    /** The `Seq` form of the varargs `enable`. A `DummyImplicit` differentiates the erased signature from the
      * varargs overload (a `T*` parameter erases to the same `Seq[T]`), so both calling forms can coexist.
      */
    def enable[A, S](enablements: Seq[Enablement[S]])(v: A < S)(using Frame, DummyImplicit): A < (S & LLM) =
        enable(enablements*)(v)

    /** Runs `v`, then restores ALL instances' conversations to their pre-`v` state: every write `v` made is
      * discarded (a scope-wide rollback), so a mode that runs parallel sampling branches can isolate them.
      */
    def forget[A, S](v: A < (LLM & S))(using Frame): A < (LLM & S) =
        LLM.state.map(snapshot => v.map(a => LLM.setState(snapshot).andThen(a)))

    /** Runs `v`, then restores ONLY the named instances to their pre-`v` state; every other instance's
      * writes persist.
      */
    def forget[A, S](ais: AI*)(v: A < (LLM & S))(using Frame): A < (LLM & S) =
        LLM.state.map { before =>
            v.map(a => LLM.state.map(after => LLM.setState(restoreInstances(before, after, ais)).andThen(a)))
        }

    /** Runs `v` with ALL instances' conversations hidden (blank history; enablements and config kept), then
      * restores them on exit (discarding `v`'s writes).
      */
    def fresh[A, S](v: A < (LLM & S))(using Frame): A < (LLM & S) =
        LLM.state.map { snapshot =>
            val blanked = snapshot.copy(instances = snapshot.instances.map((ref, s) => (ref, s.copy(context = Context.empty))))
            LLM.setState(blanked).andThen(v).map(a => LLM.setState(snapshot).andThen(a))
        }

    /** Runs `v` with ONLY the named instances' conversations hidden (blank; their enablements and config
      * kept), then restores them on exit; other instances are untouched.
      */
    def fresh[A, S](ais: AI*)(v: A < (LLM & S))(using Frame): A < (LLM & S) =
        LLM.state.map { before =>
            val blanked = before.copy(instances =
                ais.foldLeft(before.instances)((d, ai) => d.update(ai.ref, before.sessionOf(ai).copy(context = Context.empty)))
            )
            LLM.setState(blanked).andThen(v).map(a =>
                LLM.state.map(after => LLM.setState(restoreInstances(before, after, ais)).andThen(a))
            )
        }

    extension (ai: AI)

        def gen[A: Schema](using Frame): A < LLM =
            LLM.gen(ai, summon[Schema[A]])

        def gen[A: Schema](using Frame)[B: Schema](input: B): A < LLM =
            ai.userMessage(Json.encode(input)).andThen(ai.gen[A])

        def gen[A: Schema](using Frame)[B: Schema, C: Schema](input1: B, input2: C): A < LLM =
            ai.gen[A]((input1, input2))

        def gen[A: Schema](using Frame)[B: Schema, C: Schema, D: Schema](input1: B, input2: C, input3: D): A < LLM =
            ai.gen[A]((input1, input2, input3))

        def gen[A: Schema](using
            Frame
        )[B: Schema, C: Schema, D: Schema, E: Schema](
            input1: B,
            input2: C,
            input3: D,
            input4: E
        ): A < LLM =
            ai.gen[A]((input1, input2, input3, input4))

        def stream[A: Schema](using Frame, Tag[Emit[Chunk[A]]]): Stream[A, Async & Scope & Abort[AIStreamException]] < LLM =
            LLM.stream(ai, summon[Schema[A]])

        def systemMessage(content: String)(using Frame): Unit < LLM =
            LLM.append(ai, SystemMessage(content))

        def userMessage(content: String)(using Frame): Unit < LLM =
            LLM.append(ai, UserMessage(content, Absent))

        def userMessage(content: String, image: Image)(using Frame): Unit < LLM =
            LLM.append(ai, UserMessage(content, Present(image)))

        def assistantMessage(content: String)(using Frame): Unit < LLM =
            LLM.append(ai, AssistantMessage(content))

        def context(using Frame): Context < LLM = LLM.context(ai)

        /** Resets this instance to empty: its conversation, enablements, and config override are dropped, and
          * the instance reads as fresh again (it remains usable).
          */
        def reset(using Frame): Unit < LLM = LLM.discard(ai)

        /** Replaces this instance's conversation `Context` wholesale (history only; enablements and config kept). */
        def setContext(ctx: Context)(using Frame): Unit < LLM = LLM.setContext(ai, ctx)

        /** Transforms this instance's conversation `Context` with `f` (read-modify-write). */
        def updateContext(f: Context => Context)(using Frame): Unit < LLM =
            ai.context.map(c => ai.setContext(f(c)))

        /** Layers enablements (tools, prompts, thoughts, modes, compactors, in any mix) onto this instance, on
          * top of the scope's enablements. Each enablement's capability `S` rides the row, unified across the
          * varargs to their intersection, so a tool/prompt/thought/mode/compactor needing more than `LLM` keeps
          * that requirement visible at this instance's generations.
          */
        def enable[S](enablements: Enablement[S]*)(using Frame): AI < (S & LLM) =
            AI.updateSession(ai)(s => enablements.foldLeft(s)((acc, e) => e.enableIn(acc)))

        /** The `Seq` form of the varargs `enable`; a `DummyImplicit` differentiates the erased signature. */
        def enable[S](enablements: Seq[Enablement[S]])(using Frame, DummyImplicit): AI < (S & LLM) =
            ai.enable(enablements*)

        /** Captures this instance's full state (conversation + enablements + config) as a `AISession`. */
        def snapshot(using Frame): AISession < LLM =
            LLM.session(ai)
    end extension

    // Restores the named instances' AISession to their `before` snapshot, keeping every other
    // field of `after` (so non-named instances and the scope env retain their post-`v` changes).
    private def restoreInstances(before: LLM.State, after: LLM.State, ais: Seq[AI]): LLM.State =
        after.copy(instances =
            ais.foldLeft(after.instances)((d, ai) =>
                before.instances.get(ai.ref).fold(d.remove(ai.ref))(s => d.update(ai.ref, s))
            )
        )
end AI
