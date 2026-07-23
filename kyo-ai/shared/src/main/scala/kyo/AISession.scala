package kyo

import kyo.ai.Config
import kyo.ai.Context

/** One `AI` instance's full state: its conversation `Context` plus its `AIEnv` (config override and
  * enablements). Both the value the threaded `LLM.State` holds per instance and the snapshot `ai.snapshot`
  * returns / `AI.recover` restores, within a single `LLM.run`. It holds code (tool runners, effectful
  * prompts, modes), so it is in-memory only and not serializable; the serializable slice is
  * `session.rawContext`, the conversation history (`Context derives Schema`).
  */
case class AISession(
    rawContext: Context,
    env: AIEnv,
    preparation: Maybe[Compactor.internal.Preparation] = Absent,
    streamAnchor: Maybe[Compactor.internal.StreamAnchor] = Absent
):

    /** The env a generation for this session runs under: the ambient scope env with this session's
      * enablements layered on top (instance config override and compactor override win; prompts, tools,
      * thoughts, and modes append). The ONE construction shared by the eval loop and the faithful
      * [[context]] enrichment, so transcript capture cannot drift from what generation assembles.
      */
    private[kyo] def effectiveEnv(scope: AIEnv)(using Frame): AIEnv =
        scope
            .copy(
                config = env.config.orElse(scope.config),
                compactor = env.compactor.orElse(scope.compactor)
            )
            .addPrompt(env.prompt)
            .addTools(env.tools)
            .addThoughts(env.thoughts)
            .addModes(env.mode)

    /** The conversation as the model actually receives it: `rawContext` enriched with the effective
      * generation env's prompt stack ([[effectiveEnv]]), instructions as system messages at the start,
      * reminders as system messages at the end. Nothing is added on the caller's behalf. It reads the scope
      * at call time, so for a faithful capture take it inside the same enable scopes the generation ran
      * under. The faithful form for transcript capture and fine-tuning datasets; `rawContext` is the bare
      * exchange.
      */
    def context(using Frame): Context < LLM =
        LLM.env.map { scopeEnv =>
            val effective = effectiveEnv(scopeEnv)
            Prompt.internal.enrichedContext(
                effective.prompt,
                rawContext,
                (effective.tools.flatMap(_.infos) ++ Tool.internal.resultToolDefinition.infos)
                    .asInstanceOf[Chunk[Tool.internal.Info[?, ?, LLM]]]
            )
        }

    /** Sets this instance's config override. */
    def config(config: Config): AISession = copy(env = env.config(config))

    /** Seats (or replaces) this instance's background-preparation cell. Ephemeral: it holds
      * AtomicRef staging + the single-flight fiber handle, never serialized, so a snapshot/recover
      * loses only in-flight preparation, never a surfaced failure. private[kyo] carrier.
      */
    private[kyo] def withPreparation(prep: Compactor.internal.Preparation): AISession =
        copy(preparation = Present(prep))

    /** Seats this instance's pending stream re-anchor: the reported-usage sink written by
      * the streaming SSE projection plus the rendered sent view and active tokenizer captured when the
      * stream request was assembled, consumed at the next turn's start. Ephemeral (an AtomicRef sink),
      * never serialized. private[kyo] carrier.
      */
    private[kyo] def withStreamAnchor(anchor: Compactor.internal.StreamAnchor): AISession =
        copy(streamAnchor = Present(anchor))

    /** Clears the pending stream re-anchor once applied. */
    private[kyo] def clearStreamAnchor: AISession =
        copy(streamAnchor = Absent)

    /** Layers a tool onto this instance. */
    def addTool(tool: Tool[Any]): AISession = copy(env = env.addTools(Chunk(tool)))

    /** Layers a prompt after this instance's current prompt. */
    def addPrompt(prompt: Prompt[Any])(using Frame): AISession = copy(env = env.addPrompt(prompt))

    /** Layers a thought onto this instance. */
    def addThought(thought: Thought[Any]): AISession = copy(env = env.addThoughts(Chunk(thought)))

    /** Appends a mode onto this instance. */
    def addMode(mode: Mode[Any]): AISession = copy(env = env.addMode(mode))
end AISession

object AISession:
    /** The empty session: no history, no enablements, and no config override (the scope config applies). */
    val empty: AISession = AISession(Context.empty, AIEnv.empty)
end AISession
