package kyo

import kyo.Schema

/** Generation-interception middleware: transform an `AI.gen` operation transparently to the caller.
  *
  * A `Mode` wraps a generation, running before/around/after it. Enabled modes form a pipeline applied in
  * registration order. A mode can switch models, vary parameters, run parallel generations and synthesize
  * them, or add pre/post processing, all without the caller's awareness. Build one with `Mode.init`.
  *
  * @tparam S
  *   the capability set the mode requires
  */
trait Mode[-S] extends AI.Enablement[S]:

    /** Intercepts and transforms a generation for instance `ai` (the slot the wrapped generation runs
      * against), so a mode can read or write that instance's conversation around the generation. The wrapped
      * generation carries its failures typed in its row (`Abort[AIGenException]`).
      */
    def apply[A: Schema](ai: AI, gen: Maybe[A] < (LLM & Async & Abort[AIGenException]))(using
        Frame
    ): Maybe[A] < (LLM & Async & Abort[AIGenException] & S)

    private[kyo] def enableIn(env: AIEnv)(using Frame): AIEnv =
        env.addMode(this.asInstanceOf[Mode[Any]])
    private[kyo] def enableIn(session: AISession)(using Frame): AISession =
        session.addMode(this.asInstanceOf[Mode[Any]])
end Mode

object Mode:

    import internal.*

    /** Builds a mode from a polymorphic transform of the wrapped generation, the convenient alternative to
      * `new Mode[S]: def apply[A] = ...`. The transform receives the target instance and the generation it
      * wraps (`gen`, carrying its failures typed as `Abort[AIGenException]`) and returns the replacement
      * generation. Use it for a mode whose body treats the generation opaquely; reach for `new Mode` directly
      * only when the body needs the result type's `Schema`.
      */
    def init[S](
        f: [A] => (AI, Maybe[A] < (LLM & Async & Abort[AIGenException])) => (Maybe[A] < (LLM & Async & Abort[AIGenException] & S))
    ): Mode[S] =
        new Mode[S]:
            def apply[A: Schema](ai: AI, gen: Maybe[A] < (LLM & Async & Abort[AIGenException]))(using
                Frame
            ): Maybe[A] < (LLM & Async & Abort[AIGenException] & S) =
                f[A](ai, gen)

    private[kyo] object internal:

        def handle[A: Schema](ai: AI, gen: Maybe[A] < (LLM & Async & Abort[AIGenException]))(using
            Frame
        ): Maybe[A] < (LLM & Async & Abort[AIGenException]) =
            LLM.env.map { e =>
                val modes: Chunk[Mode[LLM]] = e.mode
                def loop(modes: Chunk[Mode[LLM]]): Maybe[A] < (LLM & Async & Abort[AIGenException]) =
                    if modes.isEmpty then gen
                    else modes.head(ai, loop(modes.tail))
                loop(modes)
            }
    end internal
end Mode
