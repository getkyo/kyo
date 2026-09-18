package kyo.ai.decider

import kyo.*
import kyo.Decider.internal.*
import kyo.ai.Config
import kyo.ai.Context
import kyo.schema.doc

/** The decider backend that answers through the surrounding `Config`'s completion provider, by structured
  * output: the model's own probability estimates, not calibrated ones.
  *
  * One generation answers every question of a decision at once. The user message lists the questions,
  * each with the keys it offers (`true`/`false` for a noul, the option keys for a choice, the level
  * indices for a score, described the way the caller described them), and the result tool is forced to
  * [[LLMDecider.Answers]]: one probability per offered key per question. The distribution is normalized
  * on decode, a choice's best option is its most probable key, a score is the probability-weighted level
  * index, and the confidence is one minus the distribution's normalized entropy (a single peak scores 1,
  * a flat distribution 0). A malformed answer set (a question skipped, a key unknown or missing, a
  * probability outside [0, 1], a distribution that sums to zero) is fed back once for a repair turn, then
  * fails as `AIDecodeException`.
  *
  * The generation runs under `AI.forget(ai)`, so its turns never reach the instance's history; only the
  * glue's two canonical messages survive, and a transcript has the same shape whichever backend answered.
  * Those turns fire the observers themselves with their real spend, so the reply carries no usage of its
  * own. The scope's and the instance's enablements apply to the generation as they would to any `ai.gen`.
  */
private[kyo] object LLMDecider:

    /** One question as the model sees it: what is asked and the keys it may weigh, in the order offered. */
    case class Asked(
        @doc("The question.")
        question: Structure.Value,
        @doc("The keys this question offers, each with what it means. Weigh exactly these keys.")
        keys: Chunk[Offered]
    ) derives Schema

    /** An offered key and its description. */
    case class Offered(key: String, meaning: Structure.Value) derives Schema

    /** The user message: the questions and the instruction. */
    case class Asking(questions: Chunk[Asked], instructions: String) derives Schema

    /** The forced result shape: one entry per question, in question order. */
    case class Answers(
        @doc("One entry per question, in the order the questions were asked.")
        answers: Chunk[Weights]
    ) derives Schema

    /** One question's answer: a probability for every key it offered. */
    case class Weights(
        @doc("Every key the question offered, each with the probability that it is the right answer; the probabilities sum to 1.")
        probabilities: Chunk[Weight]
    ) derives Schema

    /** One key's probability. */
    case class Weight(
        @doc("A key exactly as offered.")
        key: String,
        @doc("Between 0 and 1.")
        probability: Double
    ) derives Schema

    private val instructions =
        "Answer every question by giving each of its offered keys the probability that it is the right answer, " +
            "judged from the conversation above. Use the keys exactly as offered, give every key a probability " +
            "between 0 and 1, and make each question's probabilities sum to 1. A score's keys are ordered from " +
            "lowest to highest. Answer the questions in the order they are asked."

    def decide(config: Config, ai: AI, context: Context, questions: Chunk[Question])(using
        Frame
    ): Reply < (LLM & Async & Abort[AIGenException]) =
        val asked = questions.map(ask)
        AI.forget(ai) {
            ai.userMessage(Json.encode(Asking(asked, instructions))).andThen(ai.gen[Answers]).map { first =>
                decode(questions, first) match
                    case Result.Success(answers) => answers
                    case Result.Failure(problem) =>
                        ai.systemMessage(s"$problem Answer again, following the instructions exactly.")
                            .andThen(ai.gen[Answers]).map { second =>
                                decode(questions, second) match
                                    case Result.Success(answers) => answers
                                    case Result.Failure(problem) => Abort.fail(AIDecodeException(problem))
                                    case Result.Panic(e)         => Abort.panic(e)
                            }
                    case Result.Panic(e) => Abort.panic(e)
            }
        }.map(answers => Reply(answers, AIStats.empty))
    end decide

    // The keys a question offers, with the caller's descriptions: a noul offers true/false (described by
    // the criteria when given), a choice its options, a score its level indices lowest first.
    private def ask(question: Question): Asked =
        def str(s: String) = Structure.Value.Str(s)
        question match
            case Question.Noul(instructions, whenTrue, whenFalse, _) =>
                Asked(
                    instructions,
                    Chunk(
                        Offered("true", whenTrue.getOrElse(str("the question holds"))),
                        Offered("false", whenFalse.getOrElse(str("the question does not hold")))
                    )
                )
            case Question.Choice(instructions, options) =>
                Asked(instructions, options.map((key, description) => Offered(key, description)))
            case Question.Score(instructions, levels) =>
                Asked(instructions, levels.zipWithIndex.map((level, i) => Offered(i.toString, level)))
        end match
    end ask

    // The answer set against the questions: every question answered, every offered key weighed once with
    // a probability in [0, 1], nothing else weighed, each distribution normalized. The failure is the
    // sentence fed back for the repair turn.
    private def decode(questions: Chunk[Question], answers: Answers): Result[String, Chunk[Answer]] =
        if answers.answers.size != questions.size then
            Result.fail(s"Expected ${questions.size} answer(s), got ${answers.answers.size}.")
        else
            questions.zip(answers.answers).zipWithIndex.foldLeft(Result.succeed[String, Chunk[Answer]](Chunk.empty)) {
                case (acc, ((question, weights), i)) =>
                    acc.flatMap(as => decodeOne(question, ask(question).keys.map(_.key), weights, i + 1).map(as.append))
            }

    private def decodeOne(question: Question, keys: Chunk[String], weights: Weights, position: Int): Result[String, Answer] =
        val weighed = weights.probabilities
        Maybe.fromOption(weighed.find(w => !keys.contains(w.key))) match
            case Present(unknown) =>
                Result.fail(s"Answer $position weighs '${unknown.key}', which is not one of its keys: ${keys.mkString(", ")}.")
            case Absent =>
                Maybe.fromOption(keys.find(k => weighed.count(_.key == k) != 1)) match
                    case Present(key) =>
                        Result.fail(s"Answer $position must weigh '$key' exactly once.")
                    case Absent if weighed.exists(w => w.probability < 0.0 || w.probability > 1.0 || w.probability.isNaN) =>
                        Result.fail(s"Answer $position has a probability outside [0, 1].")
                    case Absent =>
                        val raw = keys.map(k => weighed.find(_.key == k).get.probability)
                        val sum = raw.sum
                        if sum <= 0.0 then Result.fail(s"Answer $position gives every key a probability of 0.")
                        else
                            val ps = raw.map(_ / sum)
                            Result.succeed(question match
                                case _: Question.Noul => Answer.Noul(ps(0))
                                case _: Question.Choice =>
                                    val best = ps.indexOf(ps.max)
                                    Answer.Choice(keys(best), confidence(ps), keys.zip(ps))
                                case _: Question.Score =>
                                    val value = ps.zipWithIndex.map((p, i) => p * i).sum
                                    Answer.Score(value, confidence(ps), ps))
                        end if
                end match
        end match
    end decodeOne

    // One minus the normalized entropy: 1 for a single peak, 0 for a flat distribution, 1 with one key.
    private def confidence(ps: Chunk[Double]): Double =
        if ps.size <= 1 then 1.0
        else
            val entropy = ps.map(p => if p > 0.0 then -p * math.log(p) else 0.0).sum
            (1.0 - entropy / math.log(ps.size.toDouble)).max(0.0).min(1.0)

end LLMDecider
