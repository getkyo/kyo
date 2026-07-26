package kyo

import kyo.ai.Config
import kyo.ai.Context.*
import kyo.ai.completion.Completion

/** Live prompts and reminders against every enabled backend.
  *
  * Drives the user-facing surface rather than a completion implementation, so what is asserted is
  * the contract a caller depends on. Backends without a key or CLI cancel per leaf; see BaseAITest.
  */
class PromptIntegrationTest extends BaseAITest:

    "apply prompts and reminders" - runBackends { backend =>
        for
            marker <- marker
            result <- Abort.run[AIException] {
                // Unrelated words rather than a shared prefix with differing digits. The earlier pair
                // (primary31 / reminder47) let a model answer the primary field with the right prefix and
                // the reminder's digits, which fails this leaf for a reason it does not test: the request
                // provably carried both labels. Values that share no structure cannot be blended, so the
                // answer distinguishes reading a label from reconstructing one.
                val prompt = Prompt.init(
                    "For this test, the primary prompt label is exactly kestrel.",
                    "For this test, the reminder prompt label is exactly obsidian."
                )
                AI.enable(prompt) {
                    AI.initWith { ai =>
                        for
                            _ <- ai.userMessage(
                                // This leaf tests that a prompt AND its reminder both reach the generation.
                                s"Return these values:\nmarker: $marker\n" +
                                    "primaryLabel: the primary prompt label\n" +
                                    "reminderLabel: the reminder prompt label\n" +
                                    "Write the label VALUES themselves, never the words describing them."
                            )
                            answer <- ai.gen[PromptAnswer]
                        yield answer
                    }
                }
            }
            answer <- unwrap(backend, result)
            _ = assert(answer == PromptAnswer(marker, "kestrel", "obsidian"), s"prompt answer mismatch: $answer")
        yield ()
    }

    // Two PRIMARY instructions in a row, which is the one context shape that exercises the single-slot
    // system-message transform end to end: a contiguous leading run. On a wire that delivers one system
    // instruction (Anthropic's slot, Gemini's) the run merges and BOTH must survive; on a wire that
    // delivers all, both ride as separate system messages. Every backend must return both labels. The
    // deterministic merge is unit-tested; this proves a live model honors the merged block.
    "deliver two leading instructions, whichever way the wire carries system messages" - runBackends { backend =>
        for
            marker <- marker
            result <- Abort.run[AIException] {
                val prompts = Prompt.init("For this test, the first label is exactly kestrel.")
                    .andThen(Prompt.init("For this test, the second label is exactly obsidian."))
                AI.enable(prompts) {
                    AI.initWith { ai =>
                        for
                            _ <- ai.userMessage(
                                s"Return these values:\nmarker: $marker\n" +
                                    "primaryLabel: the first label\n" +
                                    "reminderLabel: the second label\n" +
                                    "Write the label VALUES themselves, never the words describing them."
                            )
                            answer <- ai.gen[PromptAnswer]
                        yield answer
                    }
                }
            }
            answer <- unwrap(backend, result)
            _ = assert(
                answer == PromptAnswer(marker, "kestrel", "obsidian"),
                s"both leading instructions must reach the model: $answer"
            )
        yield ()
    }

end PromptIntegrationTest
