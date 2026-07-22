package kyo

import kyo.ai.Config
import kyo.ai.Context.*
import kyo.ai.completion.Completion

/** Live tool execution against every enabled backend.
  *
  * Drives the user-facing surface rather than a completion implementation, so what is asserted is
  * the contract a caller depends on. Backends without a key or CLI cancel per leaf; see BaseAITest.
  */
class ToolIntegrationTest extends BaseAITest:

    "execute Kyo tools" - runBackends { backend =>
        for
            marker <- marker
            result <- Abort.run[AIException] {
                for
                    calls <- AtomicInt.init(0)
                    lookupOrder = Tool.init[OrderQuery](
                        "lookup_order",
                        "Look up an order by id. Use this tool whenever an order status or ETA is requested."
                    ) { query =>
                        calls.incrementAndGet.map(_ => OrderInfo(s"ready_for_launch_${query.orderId}", 17))
                    }
                    result <- AI.initWith { ai =>
                        for
                            _ <- ai.enable(lookupOrder)
                            _ <- ai.userMessage(
                                s"You must call lookup_order with orderId 733 before answering. " +
                                    s"After the tool result arrives, copy the exact status and ETA days from it, and include marker $marker."
                            )
                            toolTurn <- ai.gen[ToolTurn]
                            count    <- calls.get
                        yield (toolTurn, count)
                    }
                yield result
            }
            (toolTurn, calls) <- unwrap(backend, result)
            _ = assert(toolTurn == ToolTurn(marker, "ready_for_launch_733", 17, toolUsed = true), s"tool turn mismatch: $toolTurn")
            _ = assert(calls == 1, s"lookup_order should have been called exactly once, got $calls")
        yield ()
    }

    "apply tool prompts and reminders" - runBackends { backend =>
        for
            marker <- marker
            result <- Abort.run[AIException] {
                for
                    calls <- AtomicInt.init(0)
                    prompt = Prompt.init(
                        "When using prompted_lookup, the requested code must be exactly tool_prompt_secret_503.",
                        "If a prompt asks for the prompted lookup secret, call prompted_lookup before answering."
                    )
                    promptedLookup = Tool.init[RepairQuery](
                        "prompted_lookup",
                        "Return the code supplied by the tool-specific prompt.",
                        prompt
                    ) { query =>
                        calls.incrementAndGet.map(_ => RepairInfo(query.code, 1))
                    }
                    result <- AI.initWith { ai =>
                        for
                            _ <- ai.enable(promptedLookup)
                            _ <- ai.userMessage(
                                s"Use the prompted lookup secret from the enabled tool prompt. Return these values:\n" +
                                    s"marker: $marker\n" +
                                    "code: tool_prompt_secret_503\n" +
                                    "toolUsed: true"
                            )
                            answer <- ai.gen[ToolPromptAnswer]
                            count  <- calls.get
                        yield (answer, count)
                    }
                yield result
            }
            (answer, calls) <- unwrap(backend, result)
            _ = assert(answer == ToolPromptAnswer(marker, "tool_prompt_secret_503", toolUsed = true), s"tool prompt mismatch: $answer")
            _ = assert(calls == 1, s"prompted_lookup should have been called exactly once, got $calls")
        yield ()
    }

    "decode complex Kyo tool schemas" - runBackends { backend =>
        for
            marker <- marker
            result <- Abort.run[AIException] {
                for
                    calls    <- AtomicInt.init(0)
                    received <- AtomicRef.init(Maybe.empty[ComplexToolInput])
                    complexLookup = Tool.init[ComplexToolInput](
                        "complex_lookup",
                        "Accepts a nested structured query and returns a computed structured response."
                    ) { query =>
                        received.set(Present(query)).andThen {
                            calls.incrementAndGet.map { _ =>
                                ComplexToolOutput(
                                    query.marker,
                                    query.address.city,
                                    query.scores.values.sum,
                                    query.tags.mkString("|"),
                                    query.note.nonEmpty
                                )
                            }
                        }
                    }
                    result <- AI.initWith { ai =>
                        for
                            _ <- ai.enable(complexLookup)
                            _ <- ai.userMessage(
                                // The marker ends its own line rather than sitting against the comma that
                                // separates it from the next argument: a model has been observed copying that
                                // comma into the value it was told to reproduce exactly.
                                s"Call complex_lookup once with these arguments:\n" +
                                    s"marker: $marker\n" +
                                    "address city: Recife\n" +
                                    "postalCodes: 50000 and 50010\n" +
                                    "tags: alpha and beta\n" +
                                    "scores: a 2 and b 5\n" +
                                    "note: tool-note\n" +
                                    "Then return the exact structured values from the tool and set toolUsed true."
                            )
                            answer <- ai.gen[ComplexToolAnswer]
                            seen   <- received.get
                            count  <- calls.get
                        yield (answer, seen, count)
                    }
                yield result
            }
            (answer, received, calls) <- unwrap(backend, result)
            expectedInput = ComplexToolInput(
                marker,
                StructuredAddress("Recife", Chunk(50000, 50010)),
                Chunk("alpha", "beta"),
                Map("a" -> 2, "b" -> 5),
                Present("tool-note")
            )
            _ = assert(
                answer == ComplexToolAnswer(marker, "Recife", 7, "alpha|beta", noteSeen = true, toolUsed = true),
                s"complex tool mismatch: $answer"
            )
            _ = assert(received == Present(expectedInput), s"complex tool input mismatch: $received")
            _ = assert(calls == 1, s"complex_lookup should have been called exactly once, got $calls")
        yield ()
    }

    "repair after a tool run failure" - runBackends { backend =>
        for
            marker <- marker
            result <- Abort.run[AIException] {
                for
                    attempts <- AtomicInt.init(0)
                    flakyLookup = Tool.init[RepairQuery](
                        "flaky_lookup",
                        "Look up a repair code. The first failure is temporary, so retry with the same code when it fails."
                    ) { query =>
                        attempts.incrementAndGet.map { attempt =>
                            if attempt == 1 then
                                throw new RuntimeException("temporary lookup failure; retry flaky_lookup with the same code")
                            else
                                RepairInfo(s"recovered_${query.code}", attempt)
                        }
                    }
                    result <- AI.initWith { ai =>
                        for
                            _ <- ai.enable(flakyLookup)
                            _ <- ai.userMessage(
                                s"Call flaky_lookup with code repair_19. If the tool reports a temporary failure, call flaky_lookup again " +
                                    s"with the same code before answering. Set these values:\n" +
                                    s"marker: $marker\n" +
                                    "value: recovered_repair_19\n" +
                                    "attempt: 2\n" +
                                    "recovered: true\n" +
                                    "Put only the single value in the value field, never a summary of the other fields."
                            )
                            repairTurn <- ai.gen[RepairTurn]
                            count      <- attempts.get
                        yield (repairTurn, count)
                    }
                yield result
            }
            (repairTurn, attempts) <- unwrap(backend, result)
            _ = assert(repairTurn == RepairTurn(marker, "recovered_repair_19", 2, recovered = true), s"repair turn mismatch: $repairTurn")
            _ = assert(attempts == 2, s"flaky_lookup should have been called exactly twice, got $attempts")
        yield ()
    }

    // A turn that uses an action tool and narrates in prose: the CLI can end such a turn in plain text
    // instead of the structured result. The backend must map the result faithfully, and the eval loop must
    // still yield the correct typed result: whether the CLI finalizes structurally or the loop forces the
    // result tool after a non-structured turn, the structured answer comes back, and the tool ran exactly
    // once.

    "finalize a tool turn that narrates in prose" - runBackends { backend =>
        for
            marker <- marker
            result <- Abort.run[AIException] {
                for
                    calls <- AtomicInt.init(0)
                    lookupCode = Tool.init[RepairQuery](
                        "lookup_code",
                        "Look up a code by name. Call this whenever a code is requested."
                    ) { query =>
                        calls.incrementAndGet.map(_ => RepairInfo(s"resolved_${query.code}", 1))
                    }
                    result <- AI.initWith { ai =>
                        for
                            _ <- ai.enable(lookupCode)
                            _ <- ai.userMessage(
                                // One field per line, each value ending the line. Run together in prose, a value sits
                                // against the comma separating it from the next field, and a model has been seen copying
                                // that comma into the value; the boundary, not the value, was the ambiguous part.
                                s"Call lookup_code with code prose_88. After the tool result arrives, first write two plain-English " +
                                    s"sentences explaining what you found, then produce the final answer. Use these values exactly:\n" +
                                    s"marker: $marker\n" +
                                    s"code: resolved_prose_88\n" +
                                    s"sentences: your two explanatory sentences\n" +
                                    s"toolUsed: true"
                            )
                            turn  <- ai.gen[ProseToolTurn]
                            count <- calls.get
                        yield (turn, count)
                    }
                yield result
            }
            (turn, calls) <- unwrap(backend, result)
            _ = assert(turn.marker == marker, s"marker mismatch: $turn")
            _ = assert(turn.code == "resolved_prose_88", s"code mismatch: $turn")
            _ = assert(turn.toolUsed, s"toolUsed should be true: $turn")
            _ = assert(turn.sentences.trim.nonEmpty, s"expected explanatory prose in the structured result: $turn")
            _ = assert(calls == 1, s"lookup_code should have been called exactly once, got $calls")
        yield ()
    }

    // Drives the forced-result path by instructing the model to MISBEHAVE across several turns: after the one
    // lookup, keep calling a no-op progress tool and withhold the final result until it is explicitly told to
    // finalize. The model burns turns on record_progress until the iteration budget runs out (maxIterations =
    // 3), so finalization lands on a forced turn: the HTTP backends drop the user tools and narrow tool_choice
    // to the result tool (a hard force); a command harness cannot compel the call, so if it too ends a turn
    // without a structured result the eval loop's one informed repair turn recovers it. Either way the correct
    // typed result must come back, having read the order exactly once.

    "recover a withheld result on the forced turn" - runBackends { backend =>
        for
            marker <- marker
            result <- Abort.run[AIException] {
                for
                    lookups <- AtomicInt.init(0)
                    lookupOrder = Tool.init[OrderQuery](
                        "lookup_order",
                        "Look up an order by id. Use this tool whenever an order status or ETA is requested."
                    ) { query =>
                        lookups.incrementAndGet.map(_ => OrderInfo("shipped", 3))
                    }
                    recordProgress = Tool.init[ProgressNote](
                        "record_progress",
                        "Record a short progress note. Call this on each turn while you are still working toward the answer."
                    ) { note =>
                        Kyo.lift(note)
                    }
                    result <- AI.initWith { ai =>
                        for
                            _ <- ai.enable(lookupOrder, recordProgress)
                            _ <- ai.userMessage(
                                s"First call lookup_order with orderId 42 to read the status and ETA. Then, on every following turn, " +
                                    s"call record_progress with a one-line status note and keep calling it, WITHOUT producing the final " +
                                    s"answer, until you are explicitly instructed to produce your final result. Only when you are told to " +
                                    s"finalize, produce the final answer, copying the exact status and ETA days from the lookup_order " +
                                    s"result. Return marker $marker and toolUsed true."
                            )
                            turn  <- AI.withConfig(_.maxIterations(3))(ai.gen[ToolTurn])
                            count <- lookups.get
                        yield (turn, count)
                    }
                yield result
            }
            (turn, lookups) <- unwrap(backend, result)
            _ = assert(turn == ToolTurn(marker, "shipped", 3, toolUsed = true), s"forced-turn result mismatch: $turn")
            _ = assert(lookups == 1, s"lookup_order should have been called exactly once, got $lookups")
        yield ()
    }

    "execute multiple Kyo tools in one request" - runBackends { backend =>
        for
            marker <- marker
            result <- Abort.run[AIException] {
                for
                    orderCalls    <- AtomicInt.init(0)
                    customerCalls <- AtomicInt.init(0)
                    lookupOrder = Tool.init[OrderQuery](
                        "lookup_order",
                        "Look up an order by id. Use this tool whenever an order status or ETA is requested."
                    ) { query =>
                        orderCalls.incrementAndGet.map(_ => OrderInfo(s"packed_${query.orderId}", 17))
                    }
                    lookupCustomer = Tool.init[CustomerQuery](
                        "lookup_customer",
                        "Look up customer tier and region by id. Use this tool whenever customer account details are requested."
                    ) { query =>
                        customerCalls.incrementAndGet.map(_ => CustomerInfo(s"platinum_${query.customerId}", "south"))
                    }
                    result <- AI.initWith { ai =>
                        for
                            _ <- ai.enable(lookupOrder, lookupCustomer)
                            _ <- ai.userMessage(
                                s"Before answering, call lookup_order with orderId 733 and lookup_customer with customerId 42. " +
                                    s"Return marker $marker. Set orderStatus to exactly packed_733, etaDays to exactly 17, " +
                                    "customerTier to exactly platinum_42, and customerRegion to exactly south. " +
                                    "Do not put JSON objects or JSON strings in any response field. " +
                                    "Set each toolUsed flag only if that specific tool result was used."
                            )
                            toolTurn      <- ai.gen[MultiToolTurn]
                            orderCount    <- orderCalls.get
                            customerCount <- customerCalls.get
                        yield (toolTurn, orderCount, customerCount)
                    }
                yield result
            }
            (toolTurn, orderCalls, customerCalls) <- unwrap(backend, result)
            _ = assert(
                toolTurn == MultiToolTurn(marker, "packed_733", 17, "platinum_42", "south", orderToolUsed = true, customerToolUsed = true),
                s"multi-tool turn mismatch: $toolTurn"
            )
            _ = assert(orderCalls == 1, s"lookup_order should have been called exactly once, got $orderCalls")
            _ = assert(customerCalls == 1, s"lookup_customer should have been called exactly once, got $customerCalls")
        yield ()
    }

end ToolIntegrationTest
