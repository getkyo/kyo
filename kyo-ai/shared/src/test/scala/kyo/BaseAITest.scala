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

    /** The backends this run exercises: every one by default, narrowed by the `kyo.ai.completion.provider` flag
      * (`KYO_AI_COMPLETION_PROVIDER`).
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
                // Recovers typed failures only: a leaf's own cancel travels as a panic, and routing that through `unwrap` would fail it.
                requireBackend(backend).map { config =>
                    Abort.recover[AIException](ex => unwrap(backend, Result.fail(ex)))(LLM.run(config)(v(backend)))
                }
            }
        }
    end runBackendsWhere

    private[kyo] def runBackendConfigs(
        v: (Backend, Config) => kyo.test.AssertScope ?=> Unit < (Async & Abort[Any] & Scope)
    )(using Frame): Unit =
        backends.foreach { backend =>
            s"[${backend.label}]" in {
                requireBackend(backend).map { config =>
                    Abort.recover[AIException](ex => unwrap(backend, Result.fail(ex)))(v(backend, config))
                }
            }
        }
    end runBackendConfigs

    /** The backend's credentialed config, or a cancelled arm naming why the backend cannot run. */
    private[kyo] def requireBackend(backend: Backend)(using Frame, kyo.test.AssertScope): Config < Async =
        Config.credentialed(backend.entry).map { config =>
            BaseAITest.unavailability(backend.provider, backend.cli, config).map {
                case Present(reason) => cancel(s"${backend.label} is unavailable: $reason")
                case Absent          => config
            }
        }
    end requireBackend

    private[kyo] def unwrap[A](backend: Backend, result: Result[AIException, A])(using Frame, kyo.test.AssertScope): A < Sync =
        result match
            case Result.Success(value)                         => Kyo.lift(value)
            case Result.Failure(ex) if providerUnavailable(ex) =>
                Kyo.lift(cancel(providerUnavailableMessage(backend, ex)))
            case Result.Failure(ex) =>
                Kyo.lift(failArm(backend, ex))
            case Result.Panic(ex) if providerUnavailable(ex) =>
                Kyo.lift(cancel(providerUnavailableMessage(backend, ex)))
            case Result.Panic(ex) =>
                Kyo.lift(failArm(backend, ex))
    end unwrap

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

    /** A cancel reason lands in CI logs, and a failure's detail carries the provider's response body, which can echo the key. So the
      * reason names the failure's type, or the status and error codes of a raw refusal, never its text.
      */
    private[kyo] def providerUnavailableMessage(backend: Backend, ex: Throwable): String =
        s"${backend.label} provider is unavailable: ${BaseAITest.unavailableDetail(ex)}"

    private def failArm(backend: Backend, ex: Throwable)(using Frame, kyo.test.AssertScope): Nothing =
        BaseAITest.failureReport(ex) match
            case Present(report) => fail(s"${backend.label} failed: $report")
            case Absent          => fail(ex)

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

object BaseAITest:

    // Unsafe: the cache outlives every suite in the test JVM, so no suite's effect scope can allocate it.
    private val probes: AtomicRef[Map[String, Maybe[String]]] =
        AtomicRef.Unsafe.init(Map.empty[String, Maybe[String]])(using AllowUnsafe.embrace.danger).safe

    /** Why a backend cannot run, or Absent when it can, probed once per test JVM and shared by every suite.
      *
      * A set key or an installed CLI says nothing about whether the credential works, and an arm run against a dead one fails on the
      * provider's refusal rather than on anything under test. The probe exercises no kyo-ai code, so its failure cannot be a kyo-ai
      * defect: every failure, a refusal, a timeout or a broken transport alike, cancels the arms with the reason.
      */
    private[kyo] def unavailability(provider: Config.Provider, cli: Maybe[String], config: Config)(using Frame): Maybe[String] < Async =
        probes.get.map { cached =>
            cached.get(provider.name) match
                case Some(known) => known
                case None        =>
                    probe(cli, provider, config).map(found => probes.updateAndGet(_.updated(provider.name, found)).andThen(found))
        }
    end unavailability

    private def probe(cli: Maybe[String], provider: Config.Provider, config: Config)(using Frame): Maybe[String] < Async =
        cli match
            case Present(command) => cliProbe(command)
            case Absent           =>
                config.apiKey match
                    case Absent       => Present(s"${provider.keyName} is not set")
                    case Present(key) => apiProbe(provider, config.apiUrl, key)
    end probe

    /** An authenticated read that spends no tokens: the model listing, or the key's own record on OpenRouter, whose listing is public.
      *
      * Providers refuse a bad key with 400, 401 or 403, so any answer outside 2xx is an unusable credential.
      */
    private[kyo] def apiProbe(provider: Config.Provider, apiUrl: String, key: String)(using Frame): Maybe[String] < Async =
        val base    = apiUrl.stripSuffix("/")
        val url     = if provider.name == Config.OpenRouter.name then s"$base/key" else s"$base/models"
        val headers =
            if provider.name == Config.Anthropic.name then Seq("x-api-key" -> key, "anthropic-version" -> "2023-06-01")
            else Seq("Authorization"                                       -> s"Bearer $key")
        Abort.run[HttpException] {
            // Under throttle pressure a provider slow-walks even a listing past the 5-second client default, and a slow but
            // working provider should run its arms.
            HttpClient.withConfig(_.timeout(30.seconds))(HttpClient.getText(url, headers))
        }.map {
            case Result.Success(_)                           => Absent
            case Result.Failure(status: HttpStatusException) =>
                Present(s"GET $url answered ${statusDetail(status.status.code, status.body)}")
            case Result.Failure(ex) => Present(s"GET $url failed: ${ex.getMessage}")
            case Result.Panic(ex)   => Present(s"GET $url failed: $ex")
        }
    end apiProbe

    private def cliProbe(command: String)(using Frame): Maybe[String] < Async =
        succeeds(command, "--version").map {
            case false => Present(s"the $command CLI is not installed")
            case true  =>
                command match
                    case "claude" => claudeLoggedIn.map(loggedIn => Maybe.when(!loggedIn)("the claude CLI is not logged in"))
                    case "codex"  => succeeds("codex", "login", "status").map(ok => Maybe.when(!ok)("the codex CLI is not logged in"))
                    case _        => Absent
        }
    end cliProbe

    private def succeeds(command: String*)(using Frame): Boolean < Async =
        Abort.run[CommandException](Command(command*).textWithExitCode).map {
            case Result.Success((_, code)) => code.isSuccess
            case _                         => false
        }
    end succeeds

    private def claudeLoggedIn(using Frame): Boolean < Async =
        Abort.run[CommandException](Command("claude", "auth", "status").textWithExitCode).map {
            case Result.Success((output, code)) => code.isSuccess && claudeAuthenticated(output)
            case _                              => false
        }
    end claudeLoggedIn

    private[kyo] def claudeAuthenticated(output: String): Boolean =
        Json.decode[Structure.Value](output).toMaybe.exists {
            case Structure.Value.Record(fields) =>
                fields.exists {
                    case ("loggedIn", Structure.Value.Bool(value)) => value
                    case _                                         => false
                }
            case _ => false
        }
    end claudeAuthenticated

    /** How a live leaf reports a failure that carries what a provider or harness answered: its type, the status, the error's codes and
      * a stated retry wait, never the text, which can echo the key and would land in CI logs. Absent for any other failure, which
      * reports as it is.
      */
    private[kyo] def failureReport(ex: Throwable): Maybe[String] =
        ex match
            case _: AIMissingApiKeyException     => Absent
            case transport: AITransportException => Present(s"AITransportException ${unavailableDetail(transport)}")
            case status: HttpStatusException     => Present(s"HttpStatusException ${unavailableDetail(status)}")
            case answered: AIException           =>
                // `detail`, not getMessage: the rendered message appends the frame after the body.
                val detail = answered match
                    case e: AIRateLimitException           => Present(e.detail)
                    case e: AIProviderUnavailableException => Present(e.detail)
                    case e: AIProviderAuthException        => Present(e.detail)
                    case e: AIRequestRejectedException     => Present(e.detail)
                    case e: AIToolCallRejectedException    => Present(e.detail)
                    case e: AIHarnessException             => Present(e.detail)
                    case _                                 => Absent
                detail.map { text =>
                    val status = statusInMessage(text).map(" " + _).getOrElse("")
                    val wait   = answered match
                        case AIRateLimitException(_, _, Present(retryAfter)) => s", retry after ${retryAfter.show}"
                        case _                                               => ""
                    s"${answered.getClass.getSimpleName}$status$wait"
                }
            case _ => Absent
    end failureReport

    /** A live leaf's outcome for a report, with any failure through [[failureReport]]. */
    private[kyo] def reported(result: Result[Any, Any]): String =
        def report(ex: Throwable): String = failureReport(ex).getOrElse(ex.toString)
        result match
            case Result.Success(_)             => "Success"
            case Result.Failure(ex: Throwable) => report(ex)
            case Result.Failure(other)         => other.toString
            case Result.Panic(ex)              => report(ex)
        end match
    end reported

    private[kyo] def unavailableDetail(ex: Throwable): String =
        ex match
            case transport: AITransportException => unavailableDetail(transport.cause)
            case status: HttpStatusException     => statusDetail(status.status.code, status.body)
            case other                           => other.getClass.getSimpleName
    end unavailableDetail

    /** The status, then the refusal's error type, code and status words. A body's free text can echo the key, and providers also put
      * free text in fields meant for a code, so only a value made of letters and underscores alone is kept, a shape no provider key
      * takes.
      */
    private[kyo] def statusDetail(code: Int, body: Maybe[String]): String =
        val tokens = body.flatMap(leadingJson).map(errorCodes).getOrElse(Chunk.empty)
        (Chunk(code.toString) ++ tokens.distinct).mkString(" ")
    end statusDetail

    /** The JSON value a text starts with. A rendered exception message continues after the body, so the shortest prefix ending in a
      * closing bracket that decodes is the whole value: a shorter one is unbalanced.
      */
    private def leadingJson(text: String): Maybe[Structure.Value] =
        val ends = text.indices.iterator.filter(i => text(i) == '}' || text(i) == ']')
        Maybe.fromOption(ends.map(i => Json.decode[Structure.Value](text.take(i + 1)).toMaybe).collectFirst { case Present(v) => v })
    end leadingJson

    /** The status detail of a message rendered from an `HttpStatusException` ("... returned 429 (Too Many Requests). Body: ..."), the
      * only form a classified failure keeps of the response.
      */
    private[kyo] def statusInMessage(message: String): Maybe[String] =
        Maybe.fromOption(statusMessage.findFirstMatchIn(message)).map { found =>
            statusDetail(found.group(1).toInt, Maybe(found.group(2)))
        }

    private val statusMessage = """returned (\d{3}) \([^)]*\)\.(?: Body: ([\s\S]*))?""".r

    private def errorCodes(value: Structure.Value): Chunk[String] =
        value match
            case Structure.Value.Sequence(elements) => elements.headMaybe.map(errorCodes).getOrElse(Chunk.empty)
            case Structure.Value.Record(fields)     =>
                val error = fields.collectFirst { case ("error", Structure.Value.Record(inner)) => inner }.getOrElse(fields)
                error.collect {
                    case (("type" | "code" | "status"), Structure.Value.Str(word)) if errorCode.matches(word) => word
                }
            case _ => Chunk.empty
    end errorCodes

    private val errorCode = "[A-Za-z_]{1,64}".r

end BaseAITest
