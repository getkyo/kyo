package kyo

import kyo.ai.Config
import kyo.ai.Context.*
import kyo.ai.completion.Completion

/** Live generation contract against every enabled backend.
  *
  * Drives the user-facing surface rather than a completion implementation, so what is asserted is
  * the contract a caller depends on. Backends without a key or CLI cancel per leaf; see BaseAITest.
  */
class LLMIntegrationTest extends BaseAITest:

    "preserve context and images" - runBackends { backend =>
        // An entry that declares it reads no image parts cannot run this leaf: sending one anyway gets the
        // whole request refused. Read from the backend's pinned entry, so the matrix cancels this leaf
        // rather than passing it silently, and the report says the backend was not covered here.
        Kyo.lift(assume(backend.entry.modelAcceptsImages, s"${backend.label} declares no image support")).andThen {
            for
                marker <- marker
                result <- Abort.run[AIException] {
                    for
                        ai <- AI.init
                        _ <- ai.systemMessage(
                            "You are validating a Kyo completion backend. Return compact factual values. " +
                                s"Preserve marker '$marker' exactly."
                        )
                        _ <- ai.userMessage(
                            s"Look at the attached image and remember marker: $marker. " +
                                "Return dominantColor as the lowercase dominant color visible in the image. " +
                                "Return imageKind as exactly solid-color if the image is a plain solid-color image. " +
                                "Return hasReadableText true only if readable text is visible. Return description exactly solid red image.",
                            AI.Image.fromBase64(redPixelJpeg)
                        )
                        first <- ai.gen[FirstTurn]
                        _ <- ai.userMessage(
                            "Using only the conversation so far, return the same marker, remembered dominant color, remembered image kind, " +
                                "remembered readable-text flag, and remembered image description exactly solid red image. Set historyUsed to true only if the prior assistant " +
                                "result was used."
                        )
                        second <- ai.gen[SecondTurn]
                    yield (first, second)
                }
                (first, second) <- unwrap(backend, result)
                _ = assert(
                    first == FirstTurn(marker, "red", "solid-color", hasReadableText = false, "solid red image"),
                    s"first turn mismatch: $first"
                )
                _ = assert(
                    second == SecondTurn(
                        marker,
                        "red",
                        "solid-color",
                        rememberedHasReadableText = false,
                        "solid red image",
                        historyUsed = true
                    ),
                    s"second turn mismatch: $second"
                )
            yield ()
        }
    }

    "decode typed AI.gen inputs" - runBackends { backend =>
        for
            marker <- marker
            result <- Abort.run[AIException] {
                AI.initWith { ai =>
                    // The arithmetic is scaffolding: this leaf verifies that a TYPED INPUT reaches the
                    // model with its fields intact, and left/right/sum only implied the operation rather
                    // than stating it. A backend that returned the marker and label correctly and summed
                    // differently had decoded the input perfectly and guessed the unstated part.
                    for
                        _ <- ai.systemMessage("Set sum to left + right, the arithmetic sum of the two numbers.")
                        a <- ai.gen[TypedInputAnswer](TypedInput(marker, 5, 7, "typed-input-ok"))
                    yield a
                }
            }
            answer <- unwrap(backend, result)
            _ = assert(answer == TypedInputAnswer(marker, 12, "typed-input-ok"), s"typed input answer mismatch: $answer")
        yield ()
    }

    "preserve multi-turn tool history" - runBackends { backend =>
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
                        orderCalls.incrementAndGet.map(_ => OrderInfo(s"multi_turn_order_${query.orderId}", 23))
                    }
                    lookupCustomer = Tool.init[CustomerQuery](
                        "lookup_customer",
                        "Look up customer tier and region by id. Use this tool whenever customer account details are requested."
                    ) { query =>
                        customerCalls.incrementAndGet.map(_ => CustomerInfo(s"tier_token_${query.customerId}", "region_token_north"))
                    }
                    result <- AI.initWith { ai =>
                        for
                            _ <- ai.systemMessage(
                                "This test validates multi-turn Kyo tool history. Preserve exact values from prior turns."
                            )
                            orderTurn <- AI.enable(lookupOrder) {
                                for
                                    _ <- ai.userMessage(
                                        s"Turn 1. Call lookup_order exactly once with orderId 811. Do not call lookup_customer. " +
                                            s"Return these values:\nmarker: $marker\n" +
                                            "orderStatus: the status the tool returned\netaDays: the ETA the tool returned\n" +
                                            "orderToolUsed: true"
                                    )
                                    orderTurn <- ai.gen[MultiTurnOrder]
                                yield orderTurn
                            }
                            orderAfterOrder    <- orderCalls.get
                            customerAfterOrder <- customerCalls.get
                            customerTurn <- AI.enable(lookupCustomer) {
                                for
                                    _ <- ai.userMessage(
                                        s"Turn 2. Use the prior assistant JSON field named orderStatus for the order status. " +
                                            "Call lookup_customer exactly once with customerId 51. " +
                                            s"Return these values:\nmarker: $marker\n" +
                                            "rememberedOrderStatus: multi_turn_order_811\n" +
                                            "customerCode exactly tier_token_51, customerZone exactly region_token_north, " +
                                            "orderHistoryUsed true, and customerToolUsed true."
                                    )
                                    customerTurn <- ai.gen[MultiTurnCustomer]
                                yield customerTurn
                            }
                            orderAfterCustomer    <- orderCalls.get
                            customerAfterCustomer <- customerCalls.get
                            _ <- ai.userMessage(
                                s"Turn 3. Do not call any tool. Using conversation history only, return marker $marker. " +
                                    "Copy rememberedOrderStatus from the first tool-backed assistant result. " +
                                    "Copy customerCode and customerZone from the immediately previous assistant result. " +
                                    "Set historyOnly true."
                            )
                            finalTurn     <- ai.gen[MultiTurnFinal]
                            orderFinal    <- orderCalls.get
                            customerFinal <- customerCalls.get
                        yield (
                            orderTurn,
                            customerTurn,
                            finalTurn,
                            orderAfterOrder,
                            customerAfterOrder,
                            orderAfterCustomer,
                            customerAfterCustomer,
                            orderFinal,
                            customerFinal
                        )
                    }
                yield result
            }
            (
                orderTurn,
                customerTurn,
                finalTurn,
                orderAfterOrder,
                customerAfterOrder,
                orderAfterCustomer,
                customerAfterCustomer,
                orderFinal,
                customerFinal
            ) <- unwrap(backend, result)
            _ = assert(
                orderTurn == MultiTurnOrder(marker, "multi_turn_order_811", 23, orderToolUsed = true),
                s"order turn mismatch: $orderTurn"
            )
            _ = assert(
                customerTurn == MultiTurnCustomer(
                    marker,
                    "multi_turn_order_811",
                    "tier_token_51",
                    "region_token_north",
                    orderHistoryUsed = true,
                    customerToolUsed = true
                ),
                s"customer turn mismatch: $customerTurn"
            )
            _ = assert(
                finalTurn == MultiTurnFinal(
                    marker,
                    "multi_turn_order_811",
                    "tier_token_51",
                    "region_token_north",
                    historyOnly = true
                ),
                s"final turn mismatch: $finalTurn"
            )
            _ = assert(orderAfterOrder == 1, s"lookup_order should be called exactly once during turn 1, got $orderAfterOrder")
            _ = assert(customerAfterOrder == 0, s"lookup_customer must not be called during turn 1, got $customerAfterOrder")
            _ = assert(
                orderAfterCustomer == orderAfterOrder,
                s"lookup_order must not be called during turn 2, got $orderAfterOrder then $orderAfterCustomer"
            )
            _ = assert(
                customerAfterCustomer == 1,
                s"lookup_customer should be called exactly once during turn 2, got $customerAfterCustomer"
            )
            _ = assert(orderFinal == orderAfterCustomer, s"lookup_order must not be called during final turn, got $orderFinal")
            _ = assert(customerFinal == customerAfterCustomer, s"lookup_customer must not be called during final turn, got $customerFinal")
        yield ()
    }

    "honor reasoning state on the wire" - runBackendConfigs { (backend, config) =>
        // The reasoning state a caller sets has to reach the endpoint, and the endpoint's own
        // accounting is the only proof. This had been verified with hand-built requests but never
        // through the code that actually builds them, which is the part that can regress.
        //
        // Each backend is asked the same question twice, once reasoning and once not, and the assertion
        // is the invariant that has to hold everywhere. A wire that reports no reasoning accounting
        // reports Absent, and the arm that needs the number stands down rather than inventing one.
        //
        // The prompt takes actual work on purpose: the assertion compares reasoning SPEND between the
        // two states, and on a trivial question a model spends a handful of tokens either way, so the
        // comparison turns on noise (runs were seen at twelve off against seven on, an ordering nothing
        // about the wire had violated). A prompt whose answer takes work separates the two states enough
        // to survive that.
        val ask = kyo.ai.Context.empty.userMessage(
            "A train leaves at 09:20 and arrives at 14:05, stopping twice for 12 minutes each. " +
                "How many minutes is it moving? Work it out, then reply with only the number."
        )
        // Both halves of the result contract come from the module's own constructors: the tool the
        // loop registers, and the envelope schema it advertises. Two backends decode their reply
        // THROUGH that contract and refuse a request that arrives without it.
        val resultSchema = Thought.internal.resultJson(Chunk.empty, Json.jsonSchema[String])
        def once(cfg: Config) =
            Tool.internal.resultTool[String](Chunk.empty).map { (resultTools, _) =>
                Abort.run[AIException](Abort.run[HttpException](
                    cfg.provider.completion(cfg, ask, resultTools, Present(resultSchema))
                ))
            }
        def reasoningOf(result: Result[AIException, Result[HttpException, Completion.Reply]]): Maybe[Long] =
            result match
                case Result.Success(Result.Success(reply)) => reply.usage.reasoningOutputTokens
                case _                                     => Absent
        val off = config.disableReasoning
        for
            onResult  <- LLM.run(config)(once(config))
            offResult <- LLM.run(off)(once(off))
        yield
            assert(
                onResult.isSuccess && offResult.isSuccess,
                s"both turns must complete on ${backend.label}: on=$onResult off=$offResult"
            )
            (reasoningOf(onResult), reasoningOf(offResult)) match
                case (Present(onTokens), Present(offTokens)) =>
                    // What "off" means depends on what the entry declares its wire can do. A wire that CAN
                    // be switched off must spend less for it: the state reaches the endpoint and the turn
                    // is cheaper.
                    //
                    // An endpoint that CANNOT is sent its lowest level, the least it will spend. That
                    // against the default compares two adjacent levels, whose spend is not reliably
                    // ordered: runs were seen at 25 against 27, and 12 against 7, nothing about the
                    // endpoint violated. Asserting the ordering there tests the sampler, so the honest
                    // observable is used instead: kyo did not pretend to disable what cannot be disabled,
                    // and the turn still reasons.
                    if config.reasoningOff.isInstanceOf[Config.ReasoningOff.CannotDisable] then
                        assert(
                            offTokens > 0,
                            s"${backend.label} cannot disable reasoning, so the off turn still reasons: off=$offTokens"
                        )
                    else
                        assert(
                            offTokens <= onTokens,
                            s"${backend.label} spent more reasoning with it switched off: on=$onTokens off=$offTokens"
                        )
                case _ =>
                    // This wire states no reasoning accounting, so there is nothing to compare, and
                    // both turns completing is what it can prove. That is asserted above.
                    ()
            end match
        end for
    }

    "keep AI instance histories isolated" - runBackends { backend =>
        for
            markerA <- marker
            markerB <- marker
            result <- Abort.run[AIException] {
                for
                    aiA <- AI.init
                    aiB <- AI.init
                    _ <- aiA.systemMessage(
                        s"This conversation's marker is '$markerA' and display label is alpha. Use only this conversation's marker."
                    )
                    _ <- aiB.systemMessage(
                        s"This conversation's marker is '$markerB' and display label is beta. Use only this conversation's marker."
                    )
                    // Fields named, values quoted: asking for "this conversation's marker and display label"
                    // left both to be inferred, and one run answered with values invented from the field
                    // names themselves. What this leaf tests is that two instances keep SEPARATE histories,
                    // so which value belongs in which field must not be part of the puzzle.
                    _ <- aiA.userMessage(
                        "Return this conversation's marker and display label, the marker in marker and the " +
                            "label in label. Write the VALUES themselves, never the words describing them."
                    )
                    a1 <- aiA.gen[IsolationAnswer]
                    _ <- aiB.userMessage(
                        "Return this conversation's marker and display label, the marker in marker and the " +
                            "label in label. Write the VALUES themselves, never the words describing them."
                    )
                    b1 <- aiB.gen[IsolationAnswer]
                    _ <- aiA.userMessage(
                        "Using this conversation history only, set marker and label to exactly the same two " +
                            "values you returned before. Copy each verbatim."
                    )
                    a2 <- aiA.gen[IsolationAnswer]
                yield (a1, b1, a2)
            }
            (a1, b1, a2) <- unwrap(backend, result)
            _ = assert(a1 == IsolationAnswer(markerA, "alpha"), s"first A answer mismatch: $a1")
            _ = assert(b1 == IsolationAnswer(markerB, "beta"), s"B answer mismatch: $b1")
            _ = assert(a2 == IsolationAnswer(markerA, "alpha"), s"second A answer mismatch: $a2")
        yield ()
    }

    "decode nested structured outputs" - runBackends { backend =>
        for
            marker <- marker
            result <- Abort.run[AIException] {
                AI.initWith { ai =>
                    for
                        _ <- ai.userMessage(
                            // Each value ends its line rather than sitting against the comma that separates
                            // it from the next: a model has been observed copying that comma, and the value
                            // that followed it, into the field it was told to reproduce exactly.
                            s"Return exactly this structured value:\n" +
                                s"marker: $marker\n" +
                                "address city: Recife\n" +
                                "postalCodes: 50000 and 50010\n" +
                                "tags: alpha, beta, gamma\n" +
                                "scores: first 1, second 2, third 3\n" +
                                "note: nested-ok"
                        )
                        profile <- ai.gen[StructuredProfile]
                    yield profile
                }
            }
            profile <- unwrap(backend, result)
            expected = StructuredProfile(
                marker,
                StructuredAddress("Recife", Chunk(50000, 50010)),
                Chunk("alpha", "beta", "gamma"),
                Map("first" -> 1, "second" -> 2, "third" -> 3),
                Present("nested-ok")
            )
            _ = assert(profile == expected, s"structured output mismatch: $profile")
        yield ()
    }

    "AI.gen produces one completion per generation carrying the require-all result contract on every backend" - runBackends { backend =>
        // Both Claude backends build their model-visible result contract (name, description, required-field
        // set) from the same shared functions (Tool.internal.resultToolDefinition, StrictSchema.requireAll)
        // the HTTP backends use, so contract parity between them holds by construction; this test proves the
        // externally observable half: exactly one completion per generation, routed through the result tool.
        for
            marker <- marker
            result <- Abort.run[AIException] {
                AI.initWith { ai =>
                    for
                        _      <- ai.userMessage(s"Return marker $marker and answer 4.")
                        before <- ai.context
                        answer <- ai.gen[ThoughtAnswer]
                        after  <- ai.context
                    yield (before, answer, after)
                }
            }
            (before, answer, after) <- unwrap(backend, result)
            resultCalls = after.messages.drop(before.messages.size).collect {
                case AssistantMessage(_, calls) => calls.filter(_.function == Completion.resultToolName)
            }.flatten
            _ = assert(answer == ThoughtAnswer(marker, 4), s"generation result mismatch: $answer")
            _ = assert(
                resultCalls.size == 1,
                s"exactly one completion must be routed through the result tool per generation, got: $resultCalls"
            )
        yield ()
    }

    // A CLI transport property, so it holds for every command-harness backend rather than the one it
    // was first written against.
    "kill-on-call: the CLI process is destroyForcibly'd on the first captured result and no request follows it, first capture wins" - runBackendsWhere(
        _.cli.isDefined
    ) { backend =>
        // The kill timing itself (destroyForcibly on first capture, no follow-up request) is proven at the
        // unit level (ClaudeCodeWireTest's set-once resultCapture semantics, ClaudeCodeCompletionTest's
        // bridge partition). This end-to-end proof is the observable behavioral consequence: a turn that
        // could attempt a second result call still resolves to exactly the first answer, and an immediate
        // repeat generation is deterministic (set-once parity holds across turns too).
        for
            marker <- marker
            result <- Abort.run[AIException] {
                Kyo.lift(()).andThen {
                    AI.initWith { ai =>
                        for
                            _ <- ai.userMessage(
                                s"Return marker $marker and answer 1. Call the result tool exactly once; " +
                                    "if you are tempted to call it again, do not, the first call is final."
                            )
                            first  <- ai.gen[ThoughtAnswer]
                            _      <- ai.userMessage(s"Repeat: return marker $marker and answer 1 again.")
                            second <- ai.gen[ThoughtAnswer]
                        yield (first, second)
                    }
                }
            }
            (first, second) <- unwrap(backend, result)
            _ = assert(first == ThoughtAnswer(marker, 1), s"first result mismatch: $first")
            _ = assert(second == ThoughtAnswer(marker, 1), s"repeat result mismatch (first-wins/set-once parity): $second")
        yield ()
        end for
    }

    // Scoped to the one harness whose session inherits an ambient credential: the guarantee is about
    // that inheritance, not about command harnesses in general.
    "the subscription guarantee holds: a bad ambient ANTHROPIC_API_KEY does not reach the CC session by default and the flag opt-in inherits it" - runBackendsWhere(
        _.label == "Claude Code"
    ) { backend =>
        // inheritApiCredentials is a StaticFlag, resolved once at class load from the JVM's own launch-time
        // system property; a deliberately-wrong ambient ANTHROPIC_API_KEY and the flag's value are both
        // fixed at JVM launch, never toggled mid-run. This test observes whichever configuration it was
        // launched under and asserts the matching outcome.
        for
            marker <- marker
            result <- Abort.run[AIException] {
                Kyo.lift(()).andThen {
                    AI.initWith { ai =>
                        for
                            _      <- ai.userMessage(s"Return marker $marker and answer 1.")
                            answer <- ai.gen[ThoughtAnswer]
                        yield answer
                    }
                }
            }
            _ <-
                if kyo.ai.completion.inheritApiCredentials() then
                    Kyo.lift(assert(result.isFailure, s"the opt-in run must fail on a bad ambient API key, got: $result"))
                else
                    unwrap(backend, result).map(answer =>
                        assert(
                            answer == ThoughtAnswer(marker, 1),
                            s"the default (stripped) run must succeed via the CLI's own OAuth session: $answer"
                        )
                    )
        yield ()
        end for
    }

    // The contract every backend must present identically: exactly one completion, the tool payload
    // parsed only in the eval loop (never by the backend), and the same typed result from the same
    // scenario. Asserted per backend against one expected value, so equality ACROSS backends follows
    // from each matching it. Written as two pinned pairs before, which compared nothing the constant
    // did not already pin and left every other backend uncovered.

    "present the same AI.gen contract: one completion, tool-loop-only parse, identical typed result" - runBackends {
        backend =>
            for
                marker <- marker
                result <- Abort.run[AIException](contractScenario(marker))
                answer <- unwrap(backend, result)
                _ = assert(
                    answer == ToolTurn(marker, "paired_733", 17, toolUsed = true),
                    s"${backend.label} contract mismatch: $answer"
                )
            yield ()
    }

end LLMIntegrationTest
