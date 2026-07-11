package kyo

import kyo.ai.Context
import kyo.ai.Context.*

class PromptTest extends kyo.test.Test[Any]:

    "p interpolator normalizes whitespace" in {
        val result = p"""
            This is a multi-line
            instruction with spaces
            that should be normalized
        """
        assert(result == "This is a multi-line\ninstruction with spaces\nthat should be normalized")
    }

    "empty prompt has no prompts and no reminders" in {
        LLM.run(
            for
                prompts   <- Prompt.empty.prompts
                reminders <- Prompt.empty.reminders
            yield (prompts.isEmpty, reminders.isEmpty)
        ).map { case (p, r) =>
            assert(p && r)
        }
    }

    "andThen merges and deduplicates" in {
        LLM.run(
            for
                merged <- Prompt.init("First").andThen(Prompt.init("First")).prompts
            yield merged.size
        ).map(size => assert(size == 1))
    }

    "andThen of distinct prompts keeps both with reminders" in {
        val pA = Prompt.init("A", "ra")
        val pB = Prompt.init("B", "rb")
        LLM.run(
            for
                merged <- pA.andThen(pB).prompts
                rems   <- pA.andThen(pB).reminders
            yield (merged.toList, rems.toList)
        ).map { case (prompts, reminders) =>
            assert(prompts == List("A", "B"))
            assert(reminders == List("ra", "rb"))
        }
    }

    "enrichedContext orders prompts-first, context-merged, reminders-last" in {
        val prompt = Prompt.init("instruction", "reminder text")
        LLM.run(
            AI.enable(prompt)(
                Prompt.internal.enrichedContext(Context.empty.add(UserMessage("hello", Absent)), Chunk.empty)
            )
        ).map { ctx =>
            val msgs = ctx.messages.toList
            assert(msgs.size == 3)
            assert(msgs(0).isInstanceOf[SystemMessage])
            assert(msgs(0).content == "instruction")
            assert(msgs(1).isInstanceOf[UserMessage])
            assert(msgs(1).content == "hello")
            assert(msgs(2).isInstanceOf[SystemMessage])
            assert(msgs(2).content.contains("reminder text"))
        }
    }

    "each instruction is a separate system message" in {
        val prompt = Prompt.init("A").andThen(Prompt.init("B"))
        LLM.run(
            AI.enable(prompt)(
                Prompt.internal.enrichedContext(Context.empty, Chunk.empty)
            )
        ).map { ctx =>
            val sysMsgs = ctx.messages.toList.collect { case m: SystemMessage => m }
            assert(sysMsgs.size == 2)
            assert(sysMsgs(0).content == "A")
            assert(sysMsgs(1).content == "B")
        }
    }

end PromptTest
