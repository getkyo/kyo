package kyo

import kyo.ai.completion.Completion

/** Turn-completion notification: react to each completed model turn without altering it.
  *
  * `Observe` is the wire-tier counterpart of [[Mode]]: where a mode receives the generation as a
  * value and returns what the caller sees, an observer receives the completed turn's
  * [[kyo.ai.completion.Completion.Reply]] and returns `Unit`, so nothing can be skipped, rerun, or
  * replaced. Enabled observers fire in enablement order, once per completed model turn, on the fiber
  * that ran the turn, at the moment the wire reply is read: on the generation path before the
  * ceiling adjudication (a turn that stops at the output ceiling still spent its tokens; a streamed
  * ceiling stop fails the stream and, like any failed stream, reports nothing) and on both paths
  * before the turn's messages join the instance context, so `ai.context` is the conversation up to
  * this turn and the reply carries the turn itself. `AI.config` inside the callback is the config
  * the turn ran under, instance overrides included. A fork's turns fire on
  * the fork's own fiber, so branches later discarded (a losing race, a rolled-back `AI.forget`) still
  * report the turns they completed.
  *
  * `S` is the capability the callback requires, riding the row to the run boundary like every
  * enablement's; an observer whose `S` carries `Abort[E]` is a typed guardrail whose failure fails
  * the generation it fired in (on the generation path it surfaces past `LLM.run`; on the streaming
  * path at the consumption site, where it can be recovered in-run). A scope-enabled observer covers
  * a streamed turn only when the stream is consumed inside the enabling bracket, since the env is
  * read at consumption; an instance-enabled observer rides the session and covers its instance's
  * streams wherever they are consumed. Build one with [[Observe.init]]; [[Observe.withStats]] is
  * the provided implementation collecting token usage over a scope.
  */
trait Observe[-S] extends AI.Enablement[S]:

    def apply(ai: AI, reply: Completion.Reply)(using Frame): Unit < (LLM & Sync & S)

    private[kyo] def enableIn(env: AIEnv)(using Frame): AIEnv =
        env.addObserve(this.asInstanceOf[Observe[Any]])
    private[kyo] def enableIn(session: AISession)(using Frame): AISession =
        session.addObserve(this.asInstanceOf[Observe[Any]])
end Observe

object Observe:

    /** Builds an observer from a callback, the convenient alternative to `new Observe[S]`. */
    def init[S](f: (AI, Completion.Reply) => Unit < (LLM & Sync & S)): Observe[S] =
        new Observe[S]:
            def apply(ai: AI, reply: Completion.Reply)(using Frame): Unit < (LLM & Sync & S) =
                f(ai, reply)

    /** Runs `v` and reports what it spent, alongside its result.
      *
      * Covers every turn any instance completes within `v` on this fiber's path and its forks,
      * `AI.gen` one-shots included, and the completed turns of branches later discarded (a losing
      * race, a rolled-back `AI.forget`). A turn interrupted before its reply arrives is uncounted:
      * no number ever reached this side of the wire. Nested brackets are independent; brackets on
      * parallel fibers never see each other's spend. `Sync` in the row is the bracket's own cell:
      * the count is an ordinary observer over an `AtomicRef` this method allocates and reads.
      */
    def withStats[A, S](v: A < (LLM & S))(using Frame): (AIStats, A) < (LLM & Sync & S) =
        AtomicRef.init(AIStats.empty).map { cell =>
            val track = new Observe[Any]:
                def apply(ai: AI, reply: Completion.Reply)(using Frame): Unit < (LLM & Sync) =
                    cell.getAndUpdate(_.add(reply.usage)).unit
            AI.enable(track)(v).map(a => cell.get.map(stats => (stats, a)))
        }

    /** As `withStats`, broken down by the named instances.
      *
      * Every named instance appears, [[AIStats.empty]] for one that completed no turn; spend by any
      * other instance (an `AI.gen` one-shot included) is not in the breakdown. Wrap in the
      * untargeted form for the scope total.
      */
    def withStats[A, S](ais: AI*)(v: A < (LLM & S))(using Frame): (Dict[AI, AIStats], A) < (LLM & Sync & S) =
        // Probing each target's session routes the targets through an existing targeted op, so naming a
        // foreign run's instance fails loud (the cross-run guard) instead of silently reporting an
        // empty entry.
        Kyo.foreachDiscard(ais)(ai => LLM.session(ai).unit).andThen {
            val seeded = ais.foldLeft(Dict.empty[AI, AIStats])((d, ai) => d.update(ai, AIStats.empty))
            AtomicRef.init(seeded).map { cell =>
                val track = new Observe[Any]:
                    def apply(ai: AI, reply: Completion.Reply)(using Frame): Unit < (LLM & Sync) =
                        // Only seeded keys accumulate: an unnamed instance's turn leaves the breakdown
                        // untouched. The write happens on the firing fiber, which is what lets a
                        // discarded branch's completed turns count.
                        cell.getAndUpdate(d => d.get(ai).fold(d)(prev => d.update(ai, prev.add(reply.usage)))).unit
                AI.enable(track)(v).map(a => cell.get.map(byInstance => (byInstance, a)))
            }
        }
end Observe
