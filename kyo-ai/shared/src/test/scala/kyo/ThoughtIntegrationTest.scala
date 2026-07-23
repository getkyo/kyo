package kyo

import kyo.ai.Config
import kyo.ai.Context.*
import kyo.ai.completion.Completion

/** Live thoughts against every enabled backend.
  *
  * Drives the user-facing surface rather than a completion implementation, so what is asserted is
  * the contract a caller depends on. Backends without a key or CLI cancel per leaf; see BaseAITest.
  */
class ThoughtIntegrationTest extends BaseAITest:

    "decode and process thoughts" - runBackends { backend =>
        for
            marker <- marker
            result <- Abort.run[AIException] {
                for
                    openingRef <- AtomicRef.init(Maybe.empty[Reasoning])
                    closingRef <- AtomicRef.init(Maybe.empty[ClosingCheck])
                    opening = Thought.opening[Reasoning](reasoning => openingRef.set(Present(reasoning)))
                    closing = Thought.closing[ClosingCheck](check => closingRef.set(Present(check)))
                    ai <- AI.init
                    _  <- ai.enable(opening, closing)
                    _ <- ai.systemMessage(
                        "You are validating Kyo thought extraction. Return only the requested values."
                    )
                    _ <- ai.userMessage(
                        s"Use an opening Reasoning thought with marker '$marker' and summary exactly arithmetic-check. " +
                            s"Then answer with marker '$marker' and answer 4. " +
                            s"Use a closing ClosingCheck thought with marker '$marker' and valid true."
                    )
                    thoughtAnswer <- ai.gen[ThoughtAnswer]
                    openingValue  <- openingRef.get
                    closingValue  <- closingRef.get
                yield (thoughtAnswer, openingValue, closingValue)
            }
            (thoughtAnswer, opening, closing) <- unwrap(backend, result)
            _ = assert(thoughtAnswer.marker == marker, s"thought answer marker mismatch: $thoughtAnswer")
            _ = assert(thoughtAnswer.answer == 4, s"thought answer mismatch: $thoughtAnswer")
            _ = assert(opening == Present(Reasoning("arithmetic-check", marker)), s"opening thought hook mismatch: $opening")
            _ = assert(closing == Present(ClosingCheck(marker, valid = true)), s"closing thought hook mismatch: $closing")
        yield ()
    }

end ThoughtIntegrationTest
