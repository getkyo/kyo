package kyo

import kyo.ai.Config

/** The generation environment: a config plus the enablements layered for a scope or instance (prompt, tools,
  * thoughts, modes, compactor). The scope's active env is threaded in `LLM.State`, read via `AI.env` / `AI.config`
  * and scoped via `AI.withConfig` and the `enable` methods; each `AI.Session` carries one as its instance env.
  *
  * `config` is `Maybe[Config]`: the scope env always holds `Present` (set at `LLM.run`), while an instance env
  * holds `Absent` to inherit the scope config or `Present` to override it.
  */
case class AIEnv(
    config: Maybe[Config],
    prompt: Prompt[Any],
    tools: Chunk[Tool[Any]],
    thoughts: Chunk[Thought[Any]],
    mode: Chunk[Mode[Any]],
    compactor: Maybe[Compactor[Any]] = Absent
):
    /** Sets the config (`Present`), overriding any inherited one. */
    def config(config: Config): AIEnv = copy(config = Present(config))

    /** Sets the compactor (`Present`). Single active policy, last-wins, not a pipeline: an instance
      * env holds `Absent` to inherit the scope compactor or `Present` to override it.
      */
    // Holds the erased Compactor[Any] carrier, mirroring Chunk[Mode[Any]]/Prompt[Any].
    def compactor(compactor: Compactor[Any]): AIEnv = copy(compactor = Present(compactor))

    /** Transforms the config when one is set; a no-op while it is `Absent`. */
    def mapConfig(f: Config => Config): AIEnv = copy(config = config.map(f))

    /** Replaces the prompt. */
    def prompt(prompt: Prompt[Any]): AIEnv = copy(prompt = prompt)

    /** Layers a prompt after the current prompt. */
    def addPrompt(prompt: Prompt[Any])(using Frame): AIEnv = copy(prompt = this.prompt.andThen(prompt))

    /** Layers tools on. */
    def addTools(tools: Chunk[Tool[Any]]): AIEnv = copy(tools = this.tools ++ tools)

    /** Layers thoughts on. */
    def addThoughts(thoughts: Chunk[Thought[Any]]): AIEnv = copy(thoughts = this.thoughts ++ thoughts)

    /** Appends a mode to the pipeline. */
    def addMode(mode: Mode[Any]): AIEnv = copy(mode = this.mode.append(mode))

    /** Appends modes to the pipeline. */
    def addModes(modes: Chunk[Mode[Any]]): AIEnv = copy(mode = mode ++ modes)
end AIEnv

object AIEnv:
    /** The empty env: no config override, no enablements. */
    val empty: AIEnv = AIEnv(Absent, Prompt.empty, Chunk.empty, Chunk.empty, Chunk.empty, Absent)
end AIEnv
