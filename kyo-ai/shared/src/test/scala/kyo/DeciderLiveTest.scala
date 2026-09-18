package kyo

import kyo.Decider.*
import kyo.ai.Config
import kyo.ai.Context.*
import kyo.ai.DeciderConfig
import kyo.schema.doc

object DeciderLiveTest:
    enum Language derives Schema, CanEqual:
        case English, French, Japanese

    enum Handler derives Schema, CanEqual:
        @doc("Answers from the knowledge base, no account access") case Bot
        @doc("A billing specialist with refund authority") case Billing
        @doc("An engineer who can read logs and roll back deploys") case Engineering
    end Handler

    case class Ticket(customer: String, plan: String, message: String) derives Schema
end DeciderLiveTest

/** The whole public surface against real models, once per backend, opt-in on the backend's key:
  * `OPENAI_API_KEY` runs the completion backend (GPT-5.4 mini), `TYPESAFE_API_KEY` runs TypeSafe's Jev.
  * Skipped (cancelled) when a key is absent, so the suite stays deterministic and key-free by default.
  *
  * The assertions are about the shapes the wire layers decode and about answers no model gets wrong (the
  * language of an English ticket), never about a model's judgment on a close call.
  */
class DeciderLiveTest extends kyo.test.Test[Any]:
    import DeciderLiveTest.*

    val ticket = Ticket(
        customer = "acme",
        plan = "enterprise",
        message =
            "Since this morning every export fails with a 500 and our finance close is tomorrow. We were charged twice last month too."
    )

    val languages = Language.values.toSeq
    val handlers  = Handler.values.toSeq
    val severity  = Seq("Cosmetic", "Degraded, with a workaround", "Blocking")

    /** Every public form, one-shot and instance, under the given config. */
    def surface(config: Config)(using Frame, kyo.test.AssertScope) =
        LLM.run(config) {
            for
                // Direct forms.
                english  <- Decider.check(ticket, "The ticket is written in English")
                french   <- Decider.check(ticket, "The ticket is written in French", 0.9)
                language <- Decider.choose(ticket, "Which language is the ticket written in?", languages)
                position <- Decider.score(ticket, "How severe is the failure the ticket reports?", severity)
                pEnglish <- Decider.noul(ticket, "The ticket is written in English")
                // Full answers.
                decision <- Decider.query(ticket, Query.choice("Which handler should take this ticket?", handlers))
                (pBilling, chosen, scored) <- Decider.batch(
                    ticket,
                    Query.noul("The ticket mentions a billing problem", "a charge or an invoice is mentioned", "nothing about money"),
                    Query.choice("Which language is the ticket written in?", languages),
                    Query.score("How severe is the failure the ticket reports?", severity)
                )
                many <- Decider.batch(ticket, Seq("English", "French", "Japanese").map(l => Query.noul(s"The ticket is written in $l")))
                // The instance form: the conversation is the context, the exchange is recorded.
                (recorded, ctx) <- AI.initWith { ai =>
                    for
                        _        <- ai.userMessage(Json.encode(ticket))
                        recorded <- ai.check("The ticket is written in English")
                        ctx      <- ai.context
                    yield (recorded, ctx)
                }
            yield
                assert(english, "an English ticket is English")
                assert(!french, "an English ticket is not French at 0.9")
                assert(language == Language.English)
                assert(position >= 0.0 && position <= 2.0, s"score position: $position")
                assert(pEnglish > 0.5 && pEnglish <= 1.0, s"P(English): $pEnglish")

                assert(handlers.contains(decision.best))
                assert(decision.confidence >= 0.0 && decision.confidence <= 1.0, s"confidence: ${decision.confidence}")
                assert(decision.probabilities.map(_._1) == Chunk.from(handlers), "probabilities keep the caller's order")
                assert(math.abs(decision.probabilities.map(_._2).sum - 1.0) < 0.05, s"probabilities: ${decision.probabilities}")
                assert(decision.ranked.head._1 == decision.best)

                assert(pBilling > 0.5, s"P(billing): $pBilling")
                assert(chosen.best == Language.English)
                assert(chosen.probabilityOf(Language.English) > 0.5)
                assert(scored.value >= 0.0 && scored.value <= 2.0)
                assert(severity.contains(scored.level))
                assert(scored.probabilities.map(_._1) == Chunk.from(severity))
                assert(scored.normalized >= 0.0 && scored.normalized <= 1.0)

                assert(many.size == 3 && many.forall(p => p >= 0.0 && p <= 1.0), s"homogeneous batch: $many")
                assert(many(0) > many(1) && many(0) > many(2), s"English must outrank the others: $many")

                assert(recorded)
                assert(ctx.messages.size == 3, s"the instance records the question and the answer: ${ctx.messages}")
                assert(ctx.messages(
                    1
                ).content.startsWith("""{"questions":[{"type":"noul","instructions":"The ticket is written in English"}"""))
                assert(ctx.messages(2).content.startsWith("""{"answers":[{"type":"noul","noul":"""), ctx.messages(2).content)
        }

    "the completion backend answers every form against a real provider" in {
        System.env[String]("OPENAI_API_KEY").map {
            case Absent     => cancel("OPENAI_API_KEY is not set")
            case Present(_) => Config.credentialed(Config.OpenAI.gpt_5_4_mini).map(surface)
        }
    }

    "the TypeSafe backend answers every form against the real endpoint" in {
        System.env[String]("TYPESAFE_API_KEY").map {
            case Absent => cancel("TYPESAFE_API_KEY is not set")
            case Present(_) =>
                Config.credentialed(Config.OpenAI.gpt_5_4_mini.decider(DeciderConfig.TypeSafe.default)).map(surface)
        }
    }

end DeciderLiveTest
