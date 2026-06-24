package kyo

import kyo.ai.Config
import kyo.ai.Context

class AISessionTest extends kyo.test.Test[Any]:

    "empty has no history, no config override, and no enablements" in {
        val s = AISession.empty
        assert(s.context == Context.empty)
        assert(s.env.config.isEmpty)
        assert(s.env.prompt eq Prompt.empty)
        assert(s.env.tools.isEmpty && s.env.thoughts.isEmpty && s.env.mode.isEmpty)
    }

    "config sets the instance config override" in {
        val cfg = Config.OpenAI.default
        AISession.empty.config(cfg).env.config match
            case Present(c) => assert(c eq cfg)
            case Absent     => assert(false, "config override should be Present")
    }

    "addTool appends the tool" in {
        val t = Tool.empty
        val s = AISession.empty.addTool(t)
        assert(s.env.tools.size == 1 && (s.env.tools.head eq t))
    }

    "addThought appends the thought" in {
        val th = Thought.opening[String]
        val s  = AISession.empty.addThought(th)
        assert(s.env.thoughts.size == 1 && (s.env.thoughts.head eq th))
    }

    "addMode appends the mode" in {
        val m: Mode[Any] = Mode.init([A] => (_, gen) => gen)
        val s            = AISession.empty.addMode(m)
        assert(s.env.mode.size == 1 && (s.env.mode.head eq m))
    }

    "addPrompt layers a prompt after the current one" in {
        val s = AISession.empty.addPrompt(Prompt.init("instruction-x"))
        LLM.run(s.env.prompt.prompts).map(ps => assert(ps.toList == List("instruction-x"), s"prompts: $ps"))
    }

end AISessionTest
