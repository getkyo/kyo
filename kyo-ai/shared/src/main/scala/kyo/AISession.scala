package kyo

import kyo.ai.Config
import kyo.ai.Context

/** One `AI` instance's full state: its conversation `Context` plus its `AIEnv` (config override and
  * enablements). Both the value the threaded `LLM.State` holds per instance and the snapshot `ai.snapshot`
  * returns / `AI.recover` restores, within a single `LLM.run`. It holds code (tool runners, effectful
  * prompts, modes), so it is in-memory only and not serializable; the serializable slice is
  * `session.rawContext`, the conversation history (`Context derives Schema`).
  */
case class AISession(rawContext: Context, env: AIEnv):

    /** The env a generation for this session runs under: the ambient scope env with this session's
      * enablements layered on top (instance config override wins; prompts, tools, thoughts, modes, and
      * observers append) and the default structured-output guidance last. The ONE construction shared by the
      * eval loop and the faithful [[context]] enrichment, so transcript capture cannot drift from what
      * generation assembles.
      */
    private[kyo] def effectiveEnv(scope: AIEnv)(using Frame): AIEnv =
        scope
            .copy(config = env.config.orElse(scope.config))
            .addPrompt(env.prompt)
            .addTools(env.tools)
            .addThoughts(env.thoughts)
            .addModes(env.mode)
            .addObserves(env.observe)

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

    /** Layers a tool onto this instance. */
    def addTool(tool: Tool[Any]): AISession = copy(env = env.addTools(Chunk(tool)))

    /** Layers a prompt after this instance's current prompt. */
    def addPrompt(prompt: Prompt[Any])(using Frame): AISession = copy(env = env.addPrompt(prompt))

    /** Layers a thought onto this instance. */
    def addThought(thought: Thought[Any]): AISession = copy(env = env.addThoughts(Chunk(thought)))

    /** Appends a mode onto this instance. */
    def addMode(mode: Mode[Any]): AISession = copy(env = env.addMode(mode))

    /** Layers an observer onto this instance. */
    def addObserve(observe: Observe[Any]): AISession = copy(env = env.addObserve(observe))
end AISession

object AISession:
    /** The empty session: no history, no enablements, and no config override (the scope config applies). */
    val empty: AISession = AISession(Context.empty, AIEnv.empty)
end AISession
