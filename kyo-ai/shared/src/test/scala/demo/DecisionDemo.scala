package demo

import kyo.*
import kyo.Decider.Query
import kyo.schema.doc

/** Decisions: ask for a judgment as a typed value instead of text.
  *
  * A support triage step decides which handler takes a ticket (`choose`), whether the ticket needs a
  * person (`check`), and how urgent it is (`score`), each answered as the caller's own types, then asks
  * the same three questions in one `batch` for the distribution and confidence behind each answer
  * (`Decision`, `Score`). By default the surrounding completion provider answers, weighing every option
  * by structured output, so the probabilities are its own estimate; with `TYPESAFE_API_KEY` set the same
  * questions go to TypeSafe AI's Jev, which answers them with calibrated probabilities; `Config.default`
  * picks that up from the key alone.
  *
  * Demonstrates: Decider.choose / check / score, `@doc` as the option description, DeciderConfig,
  * Decider.batch with Query.noul / choice / score, Decision.isConfident, Score.level
  * Run on OpenAI:               OPENAI_API_KEY=...    sbt "kyo-aiJVM/Test/runMain demo.DecisionDemo"
  * Run with TypeSafe deciding:  OPENAI_API_KEY=... TYPESAFE_API_KEY=... sbt "kyo-aiJVM/Test/runMain demo.DecisionDemo"
  */
object DecisionDemo extends KyoApp:

    enum Handler derives Schema, CanEqual:
        @doc("Answers from the knowledge base, no account access") case Bot
        @doc("A billing specialist with refund authority") case Billing
        @doc("An engineer who can read logs and roll back deploys") case Engineering
    end Handler

    enum Urgency derives Schema, CanEqual:
        @doc("Nobody is blocked") case Low
        @doc("One customer is blocked") case Medium
        @doc("Many customers are blocked or money is at stake") case High
    end Urgency

    case class Ticket(customer: String, plan: String, message: String) derives Schema

    val ticket = Ticket(
        customer = "acme",
        plan = "enterprise",
        message =
            "Since this morning every export fails with a 500 and our finance close is tomorrow. We were charged twice last month too."
    )

    run {
        for
            // Config.default probes the keys: a TypeSafe key routes the decisions to Jev, without one the
            // completion provider decides. Nothing to configure by hand.
            config <- AI.Config.default
            _      <- Console.printLine(s"deciding with ${config.decider.fold(config.provider.name)(_.provider.name)}")
            (handler, needsHuman, urgency, distributions) <- LLM.run(config) {
                for
                    handler    <- Decider.choose(ticket, "Which handler should take this ticket?", Handler.values.toSeq)
                    needsHuman <- Decider.check(ticket, "The ticket needs a person, not an automated answer")
                    urgency    <- Decider.score(ticket, "How urgent is the ticket?", Urgency.values.toSeq)
                    // The same questions in one request, with the distribution and confidence behind each answer.
                    distributions <- Decider.batch(
                        ticket,
                        Query.noul("The ticket needs a person, not an automated answer"),
                        Query.choice("Which handler should take this ticket?", Handler.values.toSeq),
                        Query.score("How urgent is the ticket?", Urgency.values.toSeq)
                    )
                yield (handler, needsHuman, urgency, distributions)
            }
            (pHuman, decision, score) = distributions
            _ <- Console.printLine(s"handler: $handler, needs a person: $needsHuman, urgency: ${Urgency.values(math.round(urgency).toInt)}")
            _ <- Console.printLine(f"needs a person: $pHuman%.2f")
            _ <- Console.printLine(
                s"handler: ${decision.best} (confidence ${decision.confidence}, confident: ${decision.isConfident}, " +
                    s"ranked: ${decision.ranked.map((h, p) => f"$h $p%.2f").mkString(", ")})"
            )
            _ <- Console.printLine(f"urgency: ${score.level} at ${score.value}%.2f on the scale, ${score.normalized}%.2f normalized")
        yield ()
    }
end DecisionDemo
