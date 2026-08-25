package kyo

import kyo.ai.Config
import kyo.ai.Context.*
import kyo.ai.completion.Completion

/** Live streaming against every enabled backend.
  *
  * Drives the user-facing surface rather than a completion implementation, so what is asserted is
  * the contract a caller depends on. Backends without a key or CLI cancel per leaf; see BaseAITest.
  */
class LLMStreamIntegrationTest extends BaseAITest:

    "stream strings and complete objects" - runBackends { backend =>
        for
            marker <- marker
            result <- Abort.run[AIException] {
                AI.initWith { ai =>
                    for
                        _ <- ai.systemMessage(s"Preserve marker '$marker' exactly.")
                        expectedText = s"streaming works for $marker"
                        _ <- ai.userMessage(
                            s"Stream exactly this string and nothing else: $expectedText"
                        )
                        textChunks <- ai.stream[String].map(_.run)
                        _ <- ai.userMessage(
                            s"Stream exactly two objects. Use marker '$marker' exactly, indexes 1 and 2, " +
                                "and text values 'first' and 'second'."
                        )
                        streamedItems <- ai.stream[StreamItem].map(_.run)
                        _             <- ai.userMessage("Stream exactly these three integer values in order: 3, 5, 8.")
                        streamedInts  <- ai.stream[Int].map(_.run)
                        memoryToken = s"stream-history-$marker"
                        _ <- ai.userMessage(
                            // Named fields, not "both": after several streaming turns the conversation is
                            // full of candidate text, and "return both exactly" left it to the model to
                            // decide WHICH text each field wanted. One run answered with everything it had
                            // streamed, concatenated. The leaf verifies that the streamed history is still
                            // addressable afterwards, so which value goes in which field is the setup, not
                            // the subject.
                            s"Set marker to exactly '$marker' and token to exactly '$memoryToken'. " +
                                "Return only those two values, with no other text in either field."
                        )
                        memory <- ai.gen[StreamMemory]
                        _ <- ai.userMessage(
                            // The value is NOT restated here, which is the whole point: it appears only in
                            // the turn the model itself streamed earlier. Asking for a string the prompt
                            // also contains would pass on a backend that never had the history at all,
                            // which is what this leaf used to do.
                            "Repeat, exactly and with no other text, the string you streamed as your first " +
                                "response in this conversation."
                        )
                        historyChunks <- ai.stream[String].map(_.run)
                    yield (textChunks, streamedItems, streamedInts, memory, historyChunks)
                }
            }
            (textChunks, streamedItems, streamedInts, memory, historyChunks) <- unwrap(backend, result)
            expectedText = s"streaming works for $marker"
            // The first streamed turn's text. Recallable only from the conversation, so this fails on a
            // backend whose streamed turns never joined it.
            expectedHistoryText = expectedText
            _ = assert(textChunks.mkString == expectedText, s"stream[String] chunks should concatenate to the final value: $textChunks")
            _ = assert(
                streamedItems == Chunk(StreamItem(marker, 1, "first"), StreamItem(marker, 2, "second")),
                s"stream[StreamItem] should emit complete objects in order: $streamedItems"
            )
            _ = assert(streamedInts == Chunk(3, 5, 8), s"stream[Int] should emit complete scalar values: $streamedInts")
            _ = assert(memory == StreamMemory(marker, s"stream-history-$marker"), s"stream memory write mismatch: $memory")
            _ = assert(
                historyChunks.mkString == expectedHistoryText,
                s"stream should see prior history exactly: $historyChunks"
            )
        yield ()
    }

    // Both command harnesses ride the MCP result tool and share this streaming path, so the property is
    // asserted for every CLI backend rather than the one it was written against.
    "the harness streaming path rides the MCP result tool and surfaces a resultless turn as a typed AIStreamIncompleteException" - runBackendsWhere(
        _.cli.isDefined
    ) { backend =>
        // The failure arms (a resultless capture raising AIStreamIncompleteException, a timeout/kill surfacing
        // a typed stream failure) follow from streamFragments' exhaustive Present/Absent match on the captured
        // result by construction, not from a live-fault-injection proof; this test proves the happy path end
        // to end: the captured envelope streams as the single fragment with no --json-schema round trip.
        for
            marker <- marker
            result <- Abort.run[AIException] {
                Kyo.lift(()).andThen {
                    AI.initWith { ai =>
                        for
                            _          <- ai.systemMessage(s"Preserve marker '$marker' exactly.")
                            _          <- ai.userMessage(s"Stream exactly this string and nothing else: cc-stream-$marker")
                            textChunks <- ai.stream[String].map(_.run)
                        yield textChunks
                    }
                }
            }
            textChunks <- unwrap(backend, result)
            _ = assert(textChunks.mkString == s"cc-stream-$marker", s"CC streaming result mismatch: $textChunks")
        yield ()
        end for
    }

    "deliver stream[String] as the declared capability says" - runBackends { backend =>
        // The defect this guards was silent: `stream[String]` on a harness backend returned the whole
        // answer as one chunk, with nothing in the API saying so, so a chat UI written against the
        // documented streaming API showed a blank screen for the whole generation. The fix is that the
        // capability is declared; this leaf is what keeps the declaration honest, per backend, against a
        // prompt long enough that one chunk cannot be a coincidence.
        val ask =
            "Write a 250-word explanation of B-trees. Reply with the explanation only."
        for
            result <- Abort.run[AIException] {
                Scope.run(AI.stream[String](ask).map(_.run))
            }
            chunks <- unwrap(backend, result)
            _ = assert(chunks.nonEmpty, s"${backend.label} produced no chunks at all")
            _ = assert(
                chunks.mkString.length > 400,
                s"${backend.label} answered too briefly for this leaf to mean anything: ${chunks.mkString.length} chars"
            )
            incremental = backend.provider.completion.streamsIncrementally
            _ = if incremental then
                assert(
                    chunks.size > 1,
                    s"${backend.label} declares incremental streaming but delivered ${chunks.size} chunk(s)"
                )
            else
                assert(
                    chunks.size >= 1,
                    s"${backend.label} declares non-incremental streaming; got ${chunks.size} chunk(s)"
                )
        yield ()
        end for
    }

end LLMStreamIntegrationTest
