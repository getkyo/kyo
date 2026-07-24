package kyo

import kyo.ai.Config

class AIEnvTest extends kyo.test.Test[Any]:

    val base: AIEnv = AIEnv(Present(Config.OpenAI.default), Prompt.empty, Chunk.empty, Chunk.empty, Chunk.empty, Chunk.empty)

    "config replaces the config" in {
        val cfg = Config.Anthropic.default
        assert(base.config(cfg).config.get eq cfg)
    }

    "mapConfig transforms the config" in {
        assert(base.mapConfig(_.temperature(0.1)).config.get.temperature == Present(0.1))
    }

    "prompt replaces the scope prompt wholesale" in {
        val env = base.prompt(Prompt.init("only-this"))
        LLM.run(env.prompt.prompts).map(ps => assert(ps.toList == List("only-this"), s"prompts: $ps"))
    }

    "addPrompt layers after the current prompt, in order" in {
        val env = base.addPrompt(Prompt.init("p1")).addPrompt(Prompt.init("p2"))
        LLM.run(env.prompt.prompts).map(ps => assert(ps.toList == List("p1", "p2"), s"prompts: $ps"))
    }

    "addTools layers tools" in {
        val t   = Tool.empty
        val env = base.addTools(Chunk(t))
        assert(env.tools.size == 1 && (env.tools.head eq t))
    }

    "addThoughts layers thoughts" in {
        val th  = Thought.opening[String]
        val env = base.addThoughts(Chunk(th))
        assert(env.thoughts.size == 1 && (env.thoughts.head eq th))
    }

    "addMode appends a mode; addModes appends several, preserving order" in {
        val m1: Mode[Any] = Mode.init([A] => (_, gen) => gen)
        val m2: Mode[Any] = Mode.init([A] => (_, gen) => gen)
        assert(base.addMode(m1).mode.size == 1)
        val env = base.addMode(m1).addModes(Chunk(m2))
        assert(env.mode.size == 2 && (env.mode.head eq m1) && (env.mode(1) eq m2))
    }

end AIEnvTest
