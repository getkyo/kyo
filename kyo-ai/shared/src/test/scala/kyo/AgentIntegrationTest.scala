package kyo

import kyo.ai.Config
import kyo.ai.Context.*
import kyo.ai.completion.Completion

/** Live agent conversations against every enabled backend.
  *
  * Drives the user-facing surface rather than a completion implementation, so what is asserted is
  * the contract a caller depends on. Backends without a key or CLI cancel per leaf; see BaseAITest.
  */
class AgentIntegrationTest extends BaseAITest:

    "preserve Agent conversation history" - runBackendConfigs { (backend, config) =>
        for
            marker <- marker
            result <- Abort.run[AIException] {
                Agent.run[AgentQuestion](config) { (self: AI, question: AgentQuestion) =>
                    for
                        _     <- self.userMessage(question.text)
                        reply <- self.gen[AgentReply]
                    yield reply
                }.map { agent =>
                    for
                        first <- agentAsk(
                            agent,
                            AgentQuestion(
                                // The literal is scaffolding, not the subject: this leaf verifies that the
                                // agent carries its conversation across turns, and "answer agent-first" reads
                                // to a smaller model as a manner of answering rather than as the text to
                                // return. Quoting it removes an ambiguity the assertion never meant to test.
                                s"""Remember marker $marker. Set answer to exactly "agent-first" and """ +
                                    "historyUsed to false."
                            )
                        )
                        second <- agentAsk(
                            agent,
                            AgentQuestion(
                                "Using only this agent conversation history, return the same marker as before " +
                                    """and set answer to exactly "agent-first", the same answer as before. """ +
                                    "Set historyUsed to true."
                            )
                        )
                        closed <- agent.close
                    yield (first, second, closed)
                }
            }
            (first, second, closed) <- unwrap(backend, result)
            _ = assert(first == AgentReply(marker, "agent-first", historyUsed = false), s"agent first reply mismatch: $first")
            _ = assert(second == AgentReply(marker, "agent-first", historyUsed = true), s"agent second reply mismatch: $second")
            _ = assert(closed == Present(Seq.empty), s"agent should close with explicit empty pending messages: $closed")
        yield ()
    }

end AgentIntegrationTest
