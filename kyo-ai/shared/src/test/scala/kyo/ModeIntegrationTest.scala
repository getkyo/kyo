package kyo

import kyo.ai.Config
import kyo.ai.Context.*
import kyo.ai.completion.Completion

/** Live modes against every enabled backend.
  *
  * Drives the user-facing surface rather than a completion implementation, so what is asserted is
  * the contract a caller depends on. Backends without a key or CLI cancel per leaf; see BaseAITest.
  */
class ModeIntegrationTest extends BaseAITest:

    "run modes around real generations" - runBackends { backend =>
        for
            marker <- marker
            result <- Abort.run[AIException] {
                val mode = Mode.init([A] =>
                    (ai, gen) =>
                        ai.systemMessage("A custom Kyo Mode added this instruction: modeSecret must be exactly mode_secret_29.")
                            .andThen(gen))
                AI.enable(mode) {
                    AI.initWith { ai =>
                        for
                            _ <-
                                ai.userMessage( // The secret is a literal to copy, not a value to render: one run returned it respelled with
                                    // different punctuation and digits. This leaf tests that a Mode's instruction REACHES the
                                    // generation, so the copying must not be where it fails.
                                    s"Return marker $marker and the modeSecret value stated by the enabled mode. Write " +
                                        "that value itself, character for character including underscores and " +
                                        "digits, never a description of it and never a value derived from the marker."
                                )
                            answer <- ai.gen[ModeAnswer]
                        yield answer
                    }
                }
            }
            answer <- unwrap(backend, result)
            _ = assert(answer == ModeAnswer(marker, "mode_secret_29"), s"mode answer mismatch: $answer")
        yield ()
    }

end ModeIntegrationTest
