package kyo.ai.completion

import java.util.UUID
import kyo.*
import kyo.ai.Config

private[kyo] object provider extends StaticFlag[String]("")

class CompletionIntegrationTest extends kyo.test.Test[Any]:

    case class Backend(label: String, provider: Config.Provider, cli: Maybe[String])
    case class FirstTurn(marker: String, dominantColor: String, imageKind: String, hasReadableText: Boolean, description: String)
        derives Schema,
          CanEqual
    case class SecondTurn(
        marker: String,
        rememberedColor: String,
        rememberedImageKind: String,
        rememberedHasReadableText: Boolean,
        description: String,
        historyUsed: Boolean
    ) derives Schema, CanEqual
    case class OrderQuery(orderId: Int) derives Schema, CanEqual
    case class OrderInfo(status: String, etaDays: Int) derives Schema, CanEqual
    case class CustomerQuery(customerId: Int) derives Schema, CanEqual
    case class CustomerInfo(tier: String, region: String) derives Schema, CanEqual
    case class ToolTurn(marker: String, status: String, etaDays: Int, toolUsed: Boolean) derives Schema, CanEqual
    case class RepairQuery(code: String) derives Schema, CanEqual
    case class RepairInfo(value: String, attempt: Int) derives Schema, CanEqual
    case class RepairTurn(marker: String, value: String, attempt: Int, recovered: Boolean) derives Schema, CanEqual
    case class MultiToolTurn(
        marker: String,
        orderStatus: String,
        etaDays: Int,
        customerTier: String,
        customerRegion: String,
        orderToolUsed: Boolean,
        customerToolUsed: Boolean
    ) derives Schema, CanEqual
    case class MultiTurnOrder(marker: String, orderStatus: String, etaDays: Int, orderToolUsed: Boolean) derives Schema, CanEqual
    case class MultiTurnCustomer(
        marker: String,
        rememberedOrderStatus: String,
        customerCode: String,
        customerZone: String,
        orderHistoryUsed: Boolean,
        customerToolUsed: Boolean
    ) derives Schema, CanEqual
    case class MultiTurnFinal(
        marker: String,
        rememberedOrderStatus: String,
        rememberedCustomerCode: String,
        rememberedCustomerZone: String,
        historyOnly: Boolean
    ) derives Schema, CanEqual
    case class StructuredAddress(city: String, postalCodes: Chunk[Int]) derives Schema, CanEqual
    case class StructuredProfile(
        marker: String,
        address: StructuredAddress,
        tags: Chunk[String],
        scores: Map[String, Int],
        note: Maybe[String]
    ) derives Schema, CanEqual
    case class StreamItem(marker: String, index: Int, text: String) derives Schema, CanEqual
    case class StreamMemory(marker: String, token: String) derives Schema, CanEqual
    case class Reasoning(summary: String, marker: String) derives Schema, CanEqual
    case class ClosingCheck(marker: String, valid: Boolean) derives Schema, CanEqual
    case class ThoughtAnswer(marker: String, answer: Int) derives Schema, CanEqual
    case class IsolationAnswer(marker: String, label: String) derives Schema, CanEqual
    case class PromptAnswer(marker: String, primaryLabel: String, reminderLabel: String) derives Schema, CanEqual
    case class ToolPromptAnswer(marker: String, code: String, toolUsed: Boolean) derives Schema, CanEqual
    case class ComplexToolInput(
        marker: String,
        address: StructuredAddress,
        tags: Chunk[String],
        scores: Map[String, Int],
        note: Maybe[String]
    ) derives Schema, CanEqual
    case class ComplexToolOutput(marker: String, city: String, total: Int, tagsJoined: String, noteSeen: Boolean) derives Schema, CanEqual
    case class ComplexToolAnswer(
        marker: String,
        city: String,
        total: Int,
        tagsJoined: String,
        noteSeen: Boolean,
        toolUsed: Boolean
    ) derives Schema, CanEqual
    case class TypedInput(marker: String, left: Int, right: Int, label: String) derives Schema, CanEqual
    case class TypedInputAnswer(marker: String, sum: Int, label: String) derives Schema, CanEqual
    case class ModeAnswer(marker: String, modeSecret: String) derives Schema, CanEqual
    case class AgentQuestion(text: String) derives Schema, CanEqual
    case class AgentReply(marker: String, answer: String, historyUsed: Boolean) derives Schema, CanEqual

    private val allBackends: Chunk[Backend] =
        Chunk(
            Backend("OpenAI", Config.OpenAI, Absent),
            Backend("Anthropic", Config.Anthropic, Absent),
            Backend("Claude Code", Config.ClaudeCode, Present("claude")),
            Backend("Codex", Config.Codex, Present("codex"))
        )

    private val backends: Chunk[Backend] =
        Maybe(provider().trim.toLowerCase).filter(_.nonEmpty).fold(allBackends) { name =>
            val selected = allBackends.filter(backend => providerMatches(name, backend))
            if selected.isEmpty then
                throw IllegalArgumentException(
                    s"Unsupported kyo-ai completion integration provider '$name'. Use openai, anthropic, claude-code, or codex."
                )
            end if
            selected
        }
    end backends

    private var anthropicPreflight: Maybe[Result[HttpException, Unit]] = Absent

    private def providerMatches(name: String, backend: Backend): Boolean =
        val label = backend.label.toLowerCase
        name == label ||
        name == label.replace(" ", "-") ||
        name == label.replace(" ", "_") ||
        (name == "claude" && backend.provider.name == Config.ClaudeCode.name)
    end providerMatches

    override def timeout: Duration = 4.minutes

    override def config =
        super.config.sequential.heartbeatInterval(2.minutes)

    private def runBackends(
        v: Backend => kyo.test.AssertScope ?=> Unit < (LLM & Async & Abort[Any] & Scope)
    )(using Frame): Unit =
        backends.foreach { backend =>
            s"[${backend.label}]" in {
                for
                    config <- requireBackend(backend)
                    _      <- LLM.run(config)(v(backend))
                yield ()
            }
        }
    end runBackends

    private def runBackendConfigs(
        v: (Backend, Config) => kyo.test.AssertScope ?=> Unit < (Async & Abort[Any] & Scope)
    )(using Frame): Unit =
        backends.foreach { backend =>
            s"[${backend.label}]" in {
                for
                    config <- requireBackend(backend)
                    _      <- v(backend, config)
                yield ()
            }
        }
    end runBackendConfigs

    private def requireBackend(backend: Backend)(using Frame, kyo.test.AssertScope): Config < Async =
        for
            config <- Config.init(backend.provider, backend.provider.default.modelName, backend.provider.default.modelMaxTokens)
            _ <- backend.cli match
                case Present(command) =>
                    commandAvailable(command).map(available => assume(available, s"$command CLI is not available"))
                case Absent =>
                    Kyo.lift(assume(config.apiKey.isDefined, s"${backend.provider.keyName} is not available")).andThen(
                        apiBackendAvailable(backend, config)
                    )
        yield config
    end requireBackend

    private def apiBackendAvailable(backend: Backend, config: Config)(using Frame, kyo.test.AssertScope): Unit < Async =
        if backend.provider.name != Config.Anthropic.name then Kyo.unit
        else
            config.apiKey match
                case Absent =>
                    Kyo.unit
                case Present(key) =>
                    anthropicPreflight match
                        case Present(result) =>
                            handleApiPreflight(backend, result)
                        case Absent =>
                            val headers = Seq(
                                "content-type"      -> "application/json",
                                "x-api-key"         -> key,
                                "anthropic-version" -> "2023-06-01"
                            )
                            val body =
                                Json.encode(Structure.Value.Record(Chunk(
                                    "model"      -> Structure.Value.Str(config.modelName),
                                    "max_tokens" -> Structure.Value.Integer(1),
                                    "messages" -> Structure.Value.Sequence(Chunk(Structure.Value.Record(Chunk(
                                        "role"    -> Structure.Value.Str("user"),
                                        "content" -> Structure.Value.Str("ping")
                                    ))))
                                )))
                            Abort.run[HttpException] {
                                HttpClient.postText(s"${config.apiUrl}/messages", body, headers).unit
                            }.map { result =>
                                anthropicPreflight = Present(result)
                                handleApiPreflight(backend, result)
                            }
            end match
        end if
    end apiBackendAvailable

    private def handleApiPreflight(backend: Backend, result: Result[HttpException, Unit])(using
        Frame,
        kyo.test.AssertScope
    ): Unit =
        result match
            case Result.Success(_) =>
                ()
            case Result.Failure(ex) if unavailableCause(ex) =>
                cancel(providerUnavailableMessage(backend, AITransportException(ex)))
            case Result.Failure(ex) =>
                fail(ex)
            case Result.Panic(ex) =>
                fail(ex)
    end handleApiPreflight

    private def unwrap[A](backend: Backend, result: Result[AIException, A])(using Frame, kyo.test.AssertScope): A < Sync =
        result match
            case Result.Success(value) => Kyo.lift(value)
            case Result.Failure(ex) if providerUnavailable(ex) =>
                Kyo.lift(cancel(providerUnavailableMessage(backend, ex)))
            case Result.Failure(ex) =>
                Kyo.lift(fail(ex))
            case Result.Panic(ex) if providerUnavailable(ex) =>
                Kyo.lift(cancel(providerUnavailableMessage(backend, ex)))
            case Result.Panic(ex) =>
                Kyo.lift(fail(ex))
    end unwrap

    private def commandAvailable(command: String)(using Frame): Boolean < Async =
        Abort.run[CommandException] {
            Command(command, "--version").textWithExitCode
        }.map {
            case Result.Success((_, code)) => code.isSuccess
            case _                         => false
        }
    end commandAvailable

    private def providerUnavailable(ex: Throwable): Boolean =
        val renderedUnavailable = unavailableText(ex.getMessage) || unavailableText(ex.toString)
        ex match
            case _: AIMissingApiKeyException       => true
            case _: AIProviderUnavailableException => true
            case transport: AITransportException   => renderedUnavailable || unavailableCause(transport.cause)
            case _: AIStreamException              => renderedUnavailable
            case _: AIGenException                 => renderedUnavailable
            case _                                 => renderedUnavailable
        end match
    end providerUnavailable

    private def unavailableCause(ex: Throwable): Boolean =
        unavailableText(ex.getMessage) ||
            unavailableText(ex.toString) ||
            unavailableProduct(ex) ||
            (ex match
                case status: HttpStatusException =>
                    status.body.exists(unavailableText)
                case _ =>
                    false) ||
            Maybe(ex.getCause).exists(unavailableCause)
    end unavailableCause

    private def unavailableProduct(value: Any): Boolean =
        if value.asInstanceOf[AnyRef] eq null then false
        else
            value match
                case message: String  => unavailableText(message)
                case product: Product => product.productIterator.exists(unavailableProduct)
                case other            => unavailableText(other.toString)
    end unavailableProduct

    private def providerUnavailableMessage(backend: Backend, ex: Throwable): String =
        unavailableDetail(ex).map(detail => s"${backend.label} provider is unavailable: $detail").getOrElse(
            s"${backend.label} provider is unavailable"
        )
    end providerUnavailableMessage

    private def unavailableDetail(ex: Throwable): Maybe[String] =
        ex match
            case provider: AIProviderUnavailableException =>
                Present(provider.detail)
            case transport: AITransportException =>
                unavailableDetail(transport.cause)
            case status: HttpStatusException =>
                status.body
                    .flatMap(extractProviderErrorMessage)
                    .orElse(Maybe(status.getMessage).filter(_.nonEmpty))
            case _ =>
                Maybe(ex.getMessage)
                    .filter(message => message.nonEmpty && unavailableText(message))
                    .orElse(Maybe(ex.getCause).flatMap(unavailableDetail))
        end match
    end unavailableDetail

    private def extractProviderErrorMessage(body: String): Maybe[String] =
        Json.decode[Structure.Value](body).toMaybe.flatMap {
            case Structure.Value.Record(fields) =>
                Maybe.fromOption(fields.collectFirst {
                    case ("error", Structure.Value.Record(errorFields)) =>
                        Maybe.fromOption(errorFields.collectFirst { case ("message", Structure.Value.Str(message)) => message })
                    case ("message", Structure.Value.Str(message)) =>
                        Present(message)
                }).flatten
            case _ =>
                Absent
        }.orElse(Maybe.when(body.nonEmpty)(body))
    end extractProviderErrorMessage

    private def unavailableText(message: String): Boolean =
        if message == null then false
        else
            val text = message.toLowerCase
            text.contains("not authenticated") ||
            text.contains("login") ||
            text.contains("quota") ||
            text.contains("rate limit") ||
            text.contains("rate_limit") ||
            text.contains("session limit") ||
            text.contains("credit balance") ||
            text.contains("billing") ||
            text.contains("429") ||
            text.contains("529") ||
            text.contains("401") ||
            text.contains("403") ||
            text.contains("overloaded") ||
            text.contains("service unavailable") ||
            text.contains("temporarily unavailable") ||
            text.contains("network") ||
            text.contains("could not resolve") ||
            text.contains("connection")
    end unavailableText

    private val redPixelJpeg =
        "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAIBAQEBAQIBAQECAgICAgQDAgICAgUEBAMEBgUGBgYFBgYGBwkIBgcJBwYG" +
            "CAsICQoKCgoKBggLDAsKDAkKCgr/2wBDAQICAgICAgUDAwUKBwYHCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoK" +
            "CgoKCgoKCgoKCgoKCgoKCgoKCgr/wAARCAAgACADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcI" +
            "CQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRol" +
            "JicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ip" +
            "qrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAA" +
            "AAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLR" +
            "ChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaX" +
            "mJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEA" +
            "PwD4Hooor+az/bgKKKKACiiigAooooA//9k="

    private def marker(using Frame): String < Sync =
        Sync.Unsafe.defer("kyo" + UUID.randomUUID().getMostSignificantBits.abs.toString.take(8))

    "Completion implementations preserve context and images" - runBackends { backend =>
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

    "Completion implementations apply prompts and reminders" - runBackends { backend =>
        for
            marker <- marker
            result <- Abort.run[AIException] {
                val prompt = Prompt.init(
                    "For this test, the primary prompt label is exactly primary31.",
                    "For this test, the reminder prompt label is exactly reminder47."
                )
                AI.enable(prompt) {
                    AI.initWith { ai =>
                        for
                            _ <- ai.userMessage(
                                s"Return marker $marker, the primary prompt label, and the reminder prompt label."
                            )
                            answer <- ai.gen[PromptAnswer]
                        yield answer
                    }
                }
            }
            answer <- unwrap(backend, result)
            _ = assert(answer == PromptAnswer(marker, "primary31", "reminder47"), s"prompt answer mismatch: $answer")
        yield ()
    }

    "Completion implementations execute Kyo tools" - runBackends { backend =>
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

    "Completion implementations apply tool prompts and reminders" - runBackends { backend =>
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
                                s"Use the prompted lookup secret from the enabled tool prompt. Return marker $marker, code exactly " +
                                    "tool_prompt_secret_503, and toolUsed true."
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

    "Completion implementations decode complex Kyo tool schemas" - runBackends { backend =>
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
                                s"Call complex_lookup once with marker $marker, address city Recife, postalCodes 50000 and 50010, " +
                                    "tags alpha and beta, scores a 2 and b 5, and note tool-note. " +
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

    "Completion implementations repair after a tool run failure" - runBackends { backend =>
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
                                    s"with the same code before answering. Return marker $marker, value exactly recovered_repair_19, " +
                                    "attempt exactly 2, and recovered true."
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

    "Completion implementations decode typed AI.gen inputs" - runBackends { backend =>
        for
            marker <- marker
            result <- Abort.run[AIException] {
                AI.initWith { ai =>
                    ai.gen[TypedInputAnswer](TypedInput(marker, 5, 7, "typed-input-ok"))
                }
            }
            answer <- unwrap(backend, result)
            _ = assert(answer == TypedInputAnswer(marker, 12, "typed-input-ok"), s"typed input answer mismatch: $answer")
        yield ()
    }

    "Completion implementations execute multiple Kyo tools in one request" - runBackends { backend =>
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

    "Completion implementations run modes around real generations" - runBackends { backend =>
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
                            _      <- ai.userMessage(s"Return marker $marker and the modeSecret provided by the enabled mode.")
                            answer <- ai.gen[ModeAnswer]
                        yield answer
                    }
                }
            }
            answer <- unwrap(backend, result)
            _ = assert(answer == ModeAnswer(marker, "mode_secret_29"), s"mode answer mismatch: $answer")
        yield ()
    }

    "Completion implementations preserve multi-turn tool history" - runBackends { backend =>
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
                                            s"Return marker $marker, the exact order status and ETA from the tool, and set orderToolUsed true."
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
                                            s"Return marker $marker, rememberedOrderStatus exactly multi_turn_order_811 with no ETA or extra text, " +
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

    "Completion implementations preserve Agent conversation history" - runBackendConfigs { (backend, config) =>
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
                                s"Remember marker $marker and answer agent-first. Return historyUsed false."
                            )
                        )
                        second <- agentAsk(
                            agent,
                            AgentQuestion(
                                "Using only this agent conversation history, return the same marker and the prior answer agent-first. " +
                                    "Set historyUsed true."
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

    "Completion implementations keep AI instance histories isolated" - runBackends { backend =>
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
                    _  <- aiA.userMessage("Return this conversation's marker and display label.")
                    a1 <- aiA.gen[IsolationAnswer]
                    _  <- aiB.userMessage("Return this conversation's marker and display label.")
                    b1 <- aiB.gen[IsolationAnswer]
                    _ <- aiA.userMessage(
                        "Using this conversation history only, return the same marker and display label again."
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

    "Completion implementations decode nested structured outputs" - runBackends { backend =>
        for
            marker <- marker
            result <- Abort.run[AIException] {
                AI.initWith { ai =>
                    for
                        _ <- ai.userMessage(
                            s"Return exactly this structured value: marker $marker, address city Recife, postalCodes 50000 and 50010, " +
                                "tags alpha, beta, gamma, scores first 1, second 2, third 3, and note nested-ok."
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

    "Completion implementations stream strings and complete objects" - runBackends { backend =>
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
                            s"Remember marker '$marker' and stream history token '$memoryToken'. Return both exactly."
                        )
                        memory <- ai.gen[StreamMemory]
                        _ <- ai.userMessage(
                            "Stream exactly this string using conversation history only: " +
                                s"history stream uses $memoryToken"
                        )
                        historyChunks <- ai.stream[String].map(_.run)
                    yield (textChunks, streamedItems, streamedInts, memory, historyChunks)
                }
            }
            (textChunks, streamedItems, streamedInts, memory, historyChunks) <- unwrap(backend, result)
            expectedText        = s"streaming works for $marker"
            expectedHistoryText = s"history stream uses stream-history-$marker"
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

    "Completion implementations decode and process thoughts" - runBackends { backend =>
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

    private def agentAsk(agent: Agent[Nothing, AgentQuestion, AgentReply], question: AgentQuestion)(using
        Frame
    ): AgentReply < (Async & Abort[AIGenException]) =
        Abort.run[Closed](agent.ask(question)).map {
            case Result.Success(reply) => reply
            case Result.Failure(ex)    => Abort.fail(AIDecodeException(s"agent closed before replying: ${ex.getMessage}"))
            case Result.Panic(ex)      => Abort.panic(ex)
        }
    end agentAsk

end CompletionIntegrationTest
