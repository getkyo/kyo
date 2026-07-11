package kyo

import kyo.ai.Config
import kyo.ai.Context

/** One `AI` instance's full state: its conversation `Context` plus its `AIEnv` (the instance's config override
  * and enablements). This is both the value the threaded `LLM.State` holds per instance and the snapshot
  * `ai.snapshot` returns / `AI.recover` restores, within a single `LLM.run`. It holds code (tool runners,
  * effectful prompts, modes), so it is in-memory only and not serializable; the serializable slice is
  * `session.context`, the conversation history (`Context derives Schema`).
  */
case class AISession(context: Context, env: AIEnv):
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
end AISession

object AISession:
    /** The empty session: no history, no enablements, and no config override (the scope config applies). */
    val empty: AISession = AISession(Context.empty, AIEnv.empty)
end AISession
