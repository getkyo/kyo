package kyo

import java.util.UUID
import kyo.ai.Config
import kyo.ai.Context.*
import kyo.ai.completion.Completion
import kyo.ai.completion.provider

/** Shared base for the live kyo-ai integration suites.
  *
  * Holds the backend matrix and everything every suite needs to drive it: which providers are enabled,
  * the entry each is pinned to, the availability pre-flight that CANCELS a leaf when a key or CLI is
  * absent rather than failing it, and the shared result types. Suites extending this assert the
  * user-facing contract; nothing here reaches into a completion implementation.
  */
abstract class BaseAITest extends kyo.test.Test[Any]:

    case class Backend(label: String, provider: Config.Provider, cli: Maybe[String], entry: Config)

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

    case class ProseToolTurn(marker: String, code: String, sentences: String, toolUsed: Boolean) derives Schema, CanEqual

    case class ProgressNote(note: String) derives Schema, CanEqual

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

    /** Every backend this matrix can drive, each pinned to its provider's CHEAPEST tool-capable entry.
      *
      * Pinned rather than tracking each provider's catalog default: a default is a moving target, so
      * promoting a new flagship there would silently make every integration run slower and dearer with
      * nothing in the diff to show it. Pinning also makes the reasoning-encoding spread deliberate
      * rather than incidental, since the entries chosen here span a token budget, a graded level, and
      * provider-managed reasoning.
      */
    private[kyo] val allBackends: Chunk[Backend] =
        Chunk(
            Backend("Claude Code", Config.ClaudeCode, Present("claude"), Config.ClaudeCode.haiku),
            Backend("Codex", Config.Codex, Present("codex"), Config.Codex.auto),
            Backend("DeepSeek", Config.DeepSeek, Absent, Config.DeepSeek.deepseek_v4_flash),
            Backend("Anthropic", Config.Anthropic, Absent, Config.Anthropic.haiku_4_5),
            Backend("OpenAI", Config.OpenAI, Absent, Config.OpenAI.gpt_5_4_mini),
            // The lite tier rather than the provider's default: the default entry answers 503 under load.
            Backend("Gemini", Config.Gemini, Absent, Config.Gemini.gemini_3_1_flash_lite),
            Backend("Groq", Config.Groq, Absent, Config.Groq.gpt_oss_120b),
            Backend("xAI", Config.XAI, Absent, Config.XAI.grok_4_5),
            Backend("Moonshot", Config.Moonshot, Absent, Config.Moonshot.kimi_k2_6),
            // Pinned to a routed model that holds the larger schemas; the aggregator's smaller routed
            // models fail rotating leaves on payload validation, which this matrix cannot fix, so the pin
            // was chosen by measuring the routed models rather than taking the first that passed.
            Backend("OpenRouter", Config.OpenRouter, Absent, Config.OpenRouter.deepseek_v4_pro),
            Backend("Baseten", Config.Baseten, Absent, Config.Baseten.gpt_oss_120b)
        )

    /** The backends this run exercises: every one by default, narrowed by the `kyo.ai.provider` flag.
      *
      * There is no curated enabled list. A run costs nothing for a backend whose key or CLI is absent,
      * because [[requireBackend]] cancels that arm and reports it, so the honest default is all of them:
      * a curated subset silently stops seeing regressions in the columns it omits, which happened once.
      * A keyed box that wants a cheap run narrows with the flag, which takes a comma-separated list of
      * provider names; exporting a key is the opt-in to paying for that column.
      */
    private[kyo] val backends: Chunk[Backend] = selectBackends(provider())

    /** Pure selection: the flag string in, the backends to run out. Empty flag runs all of them; a
      * comma-separated list narrows; a list matching nothing throws with the known names.
      */
    private[kyo] def selectBackends(flag: String): Chunk[Backend] =
        val names = flag.split(",").iterator.map(_.trim.toLowerCase).filter(_.nonEmpty).toList
        if names.isEmpty then allBackends
        else
            val selected = allBackends.filter(backend => names.exists(providerMatches(_, backend)))
            if selected.isEmpty then
                val known = allBackends.map(_.label.toLowerCase.replace(" ", "-")).mkString(", ")
                throw IllegalArgumentException(
                    s"No kyo-ai integration backend matches '${names.mkString(", ")}'. Known: $known"
                )
            end if
            selected
        end if
    end selectBackends

    private[kyo] var anthropicPreflight: Maybe[Result[HttpException, Unit]] = Absent

    private[kyo] def providerMatches(name: String, backend: Backend): Boolean =
        val label = backend.label.toLowerCase
        name == label ||
        name == label.replace(" ", "-") ||
        name == label.replace(" ", "_") ||
        (name == "claude" && backend.provider.name == Config.ClaudeCode.name)
    end providerMatches

    override def timeout: Duration = 4.minutes

    override def config =
        super.config.sequential.globallySequential(true).heartbeatInterval(2.minutes)

    private[kyo] def runBackends(
        v: Backend => kyo.test.AssertScope ?=> Unit < (LLM & Async & Abort[Any] & Scope)
    )(using Frame): Unit = runBackendsWhere(_ => true)(v)

    /** [[runBackends]] over the subset a leaf applies to.
      *
      * Every backend-touching leaf registers through here, so selecting one provider runs that
      * provider's leaves and no others. A leaf that named its backends itself and built its own
      * `Backend` values sidestepped the selection entirely, and a column run for one provider then
      * reported failures belonging to another, which is indistinguishable from the provider under
      * test being broken.
      *
      * The predicate states what the leaf needs (a CLI transport, a named backend), never a
      * provider the author had in mind: a backend added later that meets the condition is covered
      * without editing the leaf.
      */
    private[kyo] def runBackendsWhere(pred: Backend => Boolean)(
        v: Backend => kyo.test.AssertScope ?=> Unit < (LLM & Async & Abort[Any] & Scope)
    )(using Frame): Unit =
        backends.filter(pred).foreach { backend =>
            s"[${backend.label}]" in {
                for
                    config <- requireBackend(backend)
                    _      <- LLM.run(config)(v(backend))
                yield ()
            }
        }
    end runBackendsWhere

    private[kyo] def runBackendConfigs(
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

    private[kyo] def requireBackend(backend: Backend)(using Frame, kyo.test.AssertScope): Config < Async =
        for
            config <- Config.credentialed(backend.entry)
            _ <- backend.cli match
                case Present(command) =>
                    commandAvailable(command).map(available => assume(available, s"$command CLI is not available"))
                case Absent =>
                    Kyo.lift(assume(config.apiKey.isDefined, s"${backend.provider.keyName} is not available")).andThen(
                        apiBackendAvailable(backend, config)
                    )
        yield config
    end requireBackend

    private[kyo] def apiBackendAvailable(backend: Backend, config: Config)(using Frame, kyo.test.AssertScope): Unit < Async =
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
                                // The ping gets a generous timeout: under account throttle pressure the
                                // API slow-walks even a 1-token request past the 5-second client default,
                                // and a slow-but-working provider should run the arms, not cancel them.
                                HttpClient.withConfig(_.timeout(30.seconds)) {
                                    HttpClient.postText(s"${config.apiUrl}/messages", body, headers).unit
                                }
                            }.map { result =>
                                anthropicPreflight = Present(result)
                                handleApiPreflight(backend, result)
                            }
            end match
        end if
    end apiBackendAvailable

    private[kyo] def handleApiPreflight(backend: Backend, result: Result[HttpException, Unit])(using
        Frame,
        kyo.test.AssertScope
    ): Unit =
        result match
            case Result.Success(_) =>
                ()
            // A timed-out availability ping is the provider being slow or throttled, the same
            // unavailability class as a refused connection: cancel the arm, never fail it.
            case Result.Failure(ex: HttpTimeoutException) =>
                cancel(providerUnavailableMessage(backend, AITransportException(ex)))
            case Result.Failure(ex) if unavailableCause(ex) =>
                cancel(providerUnavailableMessage(backend, AITransportException(ex)))
            case Result.Failure(ex) =>
                fail(ex)
            case Result.Panic(ex) =>
                fail(ex)
    end handleApiPreflight

    private[kyo] def unwrap[A](backend: Backend, result: Result[AIException, A])(using Frame, kyo.test.AssertScope): A < Sync =
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

    private[kyo] def commandAvailable(command: String)(using Frame): Boolean < Async =
        Abort.run[CommandException] {
            Command(command, "--version").textWithExitCode
        }.map {
            case Result.Success((_, code)) => code.isSuccess
            case _                         => false
        }
    end commandAvailable

    private[kyo] def providerUnavailable(ex: Throwable): Boolean =
        val renderedUnavailable = unavailableText(ex.getMessage) || unavailableText(ex.toString)
        ex match
            case _: AIMissingApiKeyException       => true
            case _: AIProviderUnavailableException => true
            case _: AIProviderAuthException        => true
            // AIRateLimitException is deliberately NOT an unavailability. An exhausted quota means the
            // arm verified nothing, and cancelling would report that as "not covered" in a way that
            // reads like a missing key: something to shrug at. It FAILS, so the run states plainly that
            // the backend was not exercised and the quota has to be dealt with rather than absorbed.
            // AICompletionTimeoutException is deliberately NOT an unavailability: a real arm that connected
            // and then exceeded the client deadline is indistinguishable from a hang bug in the backend
            // under test, so it FAILS the arm. Provider slowness cancels through the availability preflight
            // (HttpTimeoutException on the ping) and the throttle/overload text classifiers below.
            case transport: AITransportException => renderedUnavailable || unavailableCause(transport.cause)
            case _: AIStreamException            => renderedUnavailable
            case _: AIGenException               => renderedUnavailable
            case _                               => renderedUnavailable
        end match
    end providerUnavailable

    private[kyo] def unavailableCause(ex: Throwable): Boolean =
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

    private[kyo] def unavailableProduct(value: Any): Boolean =
        if value.asInstanceOf[AnyRef] eq null then false
        else
            value match
                case message: String  => unavailableText(message)
                case product: Product => product.productIterator.exists(unavailableProduct)
                case other            => unavailableText(other.toString)
    end unavailableProduct

    private[kyo] def providerUnavailableMessage(backend: Backend, ex: Throwable): String =
        unavailableDetail(ex).map(detail => s"${backend.label} provider is unavailable: $detail").getOrElse(
            s"${backend.label} provider is unavailable"
        )
    end providerUnavailableMessage

    private[kyo] def unavailableDetail(ex: Throwable): Maybe[String] =
        ex match
            case provider: AIProviderUnavailableException =>
                Present(provider.detail)
            case rateLimit: AIRateLimitException =>
                Present(rateLimit.detail)
            case auth: AIProviderAuthException =>
                Present(auth.detail)
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

    private[kyo] def extractProviderErrorMessage(body: String): Maybe[String] =
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

    private[kyo] def unavailableText(message: String): Boolean =
        if message == null then false
        else
            // Exhausted quota, a spent rate-limit window, a session cap, and an empty balance are all
            // absent from this list on purpose. Each of them means the arm ran nothing, and reporting
            // that as unavailability files it beside a missing key, which is the one case here that is
            // genuinely nothing to act on. They FAIL, so a run that verified nothing says so.
            val text = message.toLowerCase
            text.contains("not authenticated") ||
            text.contains("login") ||
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

    private[kyo] val redPixelJpeg =
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

    private[kyo] def marker(using Frame): String < Sync =
        Sync.Unsafe.defer("kyo" + UUID.randomUUID().getMostSignificantBits.abs.toString.take(8))

    /** The tool-call scenario whose typed result every backend must produce identically. */
    private[kyo] def contractScenario(marker: String)(using Frame): ToolTurn < (LLM & Async & Abort[AIGenException] & Scope) =
        for
            calls <- AtomicInt.init(0)
            lookupOrder = Tool.init[OrderQuery](
                "lookup_order",
                "Look up an order by id. Use this tool whenever an order status or ETA is requested."
            ) { query =>
                calls.incrementAndGet.map(_ => OrderInfo(s"paired_${query.orderId}", 17))
            }
            turn <- AI.initWith { ai =>
                for
                    _ <- ai.enable(lookupOrder)
                    _ <- ai.userMessage(
                        s"Call lookup_order with orderId 733 before answering. Return these values:\n" +
                            s"marker: $marker\n" +
                            "status: the status the tool result gave\netaDays: the ETA days the tool result gave\n" +
                            "toolUsed: true"
                    )
                    turn <- ai.gen[ToolTurn]
                yield turn
            }
        yield turn
    end contractScenario

    private[kyo] def agentAsk(agent: Agent[Nothing, AgentQuestion, AgentReply], question: AgentQuestion)(using
        Frame
    ): AgentReply < (Async & Abort[AIGenException]) =
        Abort.run[Closed](agent.ask(question)).map {
            case Result.Success(reply) => reply
            case Result.Failure(ex)    => Abort.fail(AIDecodeException(s"agent closed before replying: ${ex.getMessage}"))
            case Result.Panic(ex)      => Abort.panic(ex)
        }
    end agentAsk

end BaseAITest
