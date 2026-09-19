package kyo

import kyo.ai.Config
import kyo.ai.Context
import kyo.ai.Context.*
import kyo.ai.DeciderConfig
import kyo.ai.completion.Completion
import kyo.ai.decider.LLMDecider
import kyo.schema.doc

/** Typed decisions about a value or a conversation: a yes/no probability, a pick among known options, or a
  * rating against ordered levels, each returned with a probability distribution.
  *
  * The three question kinds and their names are TypeSafe AI's primitives (`noul`, `choice`, `score`), so
  * that vendor's documentation and cookbooks (https://docs.typesafe.ai) apply directly. Which model
  * answers is a `Config` setting: by default the config's own completion provider, asked for every
  * question's probabilities by structured output in one generation, so the probabilities are the model's
  * own estimate; with a `decider` set (`AI.DeciderConfig`), TypeSafe's Jev, a model built to answer these
  * questions with calibrated probabilities.
  *
  * Direct methods (`check`, `choose`, `score`) return one value. `noul`, `query` and `batch` return the full
  * answer a backend gave: a probability for a noul, a [[Decider.Decision]] for a choice, a [[Decider.Score]]
  * for a score; `batch` carries several questions in one request and answers them in order. The one-shot
  * forms on this object record nothing; the same methods on an `AI` instance use its conversation as the
  * context and record the question and the answer as two messages.
  *
  * Context, questions, options and levels are any value with a `Schema`, sent as JSON; a plain `String`
  * works in every slot. A choice option's wire key is inferred from how it encodes: a string is its own
  * key with no description, an enum case is keyed by its case name and described by its `@doc` (else by
  * its fields, if any), and anything else gets a positional `c<i>` key with the encoded value as the
  * description. A level is described the same way.
  *
  * A decision fails the way a generation does: `AIInvalidQuestionException` for an out-of-bounds question,
  * before any request; the transport, auth and decode leaves otherwise. All ride `Abort[AIGenException]`
  * on `LLM.run`'s residual, so a decision is recovered outside the run, like a generation.
  *
  * @see
  *   [[Decider.Query]] for the question factories behind `query` and `batch`
  * @see
  *   [[kyo.ai.DeciderConfig]] for choosing and configuring the backend
  * @see
  *   [[AI]] for the instance forms, which record on the conversation
  */
object Decider:

    // ---------------------------------------------------------------- one-shot surface
    // Every method builds a Plan and runs it on a fresh ephemeral instance; nothing is recorded. The
    // context, when given, is what the question is about. The instance forms live on `AI` and record on
    // the instance.

    /** Whether the question holds: the noul probability against 0.5. */
    def check[Q: Schema](question: Q)(using Frame): Boolean < LLM =
        check(question, internal.defaultThreshold)

    /** Whether the question holds: the noul probability against `threshold`, which must be within [0, 1].
      * A threshold away from 0.5 discriminates only as well as the backend's probabilities are calibrated,
      * which TypeSafe's are and a completion provider's own estimates are not.
      */
    def check[Q: Schema](question: Q, threshold: Double)(using Frame): Boolean < LLM =
        internal.oneShot(Absent, internal.checkPlan(Structure.encode(question), threshold))

    /** Whether the question holds against `context`: the noul probability against 0.5. */
    def check[C: Schema, Q: Schema](context: C, question: Q)(using Frame): Boolean < LLM =
        check(context, question, internal.defaultThreshold)

    /** Whether the question holds against `context`: the noul probability against `threshold`. */
    def check[C: Schema, Q: Schema](context: C, question: Q, threshold: Double)(using Frame): Boolean < LLM =
        internal.oneShot(Present(Structure.encode(context)), internal.checkPlan(Structure.encode(question), threshold))

    /** The most probable of the options. */
    def choose[Q: Schema, A: Schema](question: Q, options: Seq[A])(using Frame): A < LLM =
        internal.oneShot(Absent, internal.choosePlan(Structure.encode(question), options))

    /** The most probable of the options, judged against `context`. */
    def choose[C: Schema, Q: Schema, A: Schema](context: C, question: Q, options: Seq[A])(using Frame): A < LLM =
        internal.oneShot(Present(Structure.encode(context)), internal.choosePlan(Structure.encode(question), options))

    /** The position on the levels (lowest first): the probability-weighted level index, which can fall
      * between two levels; `math.round` on the result gives the index of the nearest level.
      */
    def score[Q: Schema, A: Schema](question: Q, levels: Seq[A])(using Frame): Double < LLM =
        internal.oneShot(Absent, internal.scorePlan(Structure.encode(question), levels))

    /** The position on the levels, judged against `context`. */
    def score[C: Schema, Q: Schema, A: Schema](context: C, question: Q, levels: Seq[A])(using Frame): Double < LLM =
        internal.oneShot(Present(Structure.encode(context)), internal.scorePlan(Structure.encode(question), levels))

    /** The probability that the question holds. */
    def noul[Q: Schema](question: Q)(using Frame): Double < LLM =
        internal.oneShot(Absent, internal.noulPlan(Structure.encode(question)))

    /** The probability that the question holds against `context`. */
    def noul[C: Schema, Q: Schema](context: C, question: Q)(using Frame): Double < LLM =
        internal.oneShot(Present(Structure.encode(context)), internal.noulPlan(Structure.encode(question)))

    /** One question, with its full answer. */
    def query[R](query: Query[R])(using Frame): R < LLM =
        internal.oneShot(Absent, internal.queryPlan(query))

    /** One question about `context`, with its full answer. */
    def query[C: Schema, R](context: C, query: Query[R])(using Frame): R < LLM =
        internal.oneShot(Present(Structure.encode(context)), internal.queryPlan(query))

    /** Any number of same-typed questions in one request, answered in order; the shape for a data-driven
      * set (score every line of a document, rank every candidate). Empty input asks nothing and returns
      * an empty chunk.
      */
    def batch[R](queries: Seq[Query[R]])(using Frame): Chunk[R] < LLM =
        if queries.isEmpty then Chunk.empty
        else internal.oneShot(Absent, internal.batchPlan(queries))

    /** Any number of same-typed questions about `context` in one request, answered in order. */
    def batch[C: Schema, R](context: C, queries: Seq[Query[R]])(using Frame): Chunk[R] < LLM =
        if queries.isEmpty then Chunk.empty
        else internal.oneShot(Present(Structure.encode(context)), internal.batchPlan(queries))

    /** Two questions in one request, answered in order. */
    def batch[A, B](q1: Query[A], q2: Query[B])(using Frame): (A, B) < LLM =
        internal.oneShot(Absent, internal.batchPlan(q1, q2))

    /** Three questions in one request, answered in order. */
    def batch[A, B, D](q1: Query[A], q2: Query[B], q3: Query[D])(using Frame): (A, B, D) < LLM =
        internal.oneShot(Absent, internal.batchPlan(q1, q2, q3))

    /** Four questions in one request, answered in order. */
    def batch[A, B, D, E](q1: Query[A], q2: Query[B], q3: Query[D], q4: Query[E])(using Frame): (A, B, D, E) < LLM =
        internal.oneShot(Absent, internal.batchPlan(q1, q2, q3, q4))

    /** Two questions about `context` in one request, answered in order. */
    def batch[C: Schema, A, B](context: C, q1: Query[A], q2: Query[B])(using Frame): (A, B) < LLM =
        internal.oneShot(Present(Structure.encode(context)), internal.batchPlan(q1, q2))

    /** Three questions about `context` in one request, answered in order. */
    def batch[C: Schema, A, B, D](context: C, q1: Query[A], q2: Query[B], q3: Query[D])(using Frame): (A, B, D) < LLM =
        internal.oneShot(Present(Structure.encode(context)), internal.batchPlan(q1, q2, q3))

    /** Four questions about `context` in one request, answered in order. */
    def batch[C: Schema, A, B, D, E](context: C, q1: Query[A], q2: Query[B], q3: Query[D], q4: Query[E])(using Frame): (A, B, D, E) < LLM =
        internal.oneShot(Present(Structure.encode(context)), internal.batchPlan(q1, q2, q3, q4))

    // ---------------------------------------------------------------- nested types

    /** A question with an answer of type `R`, built by the factories on `Decider.Query`: [[Query.noul]],
      * [[Query.choice]] and [[Query.score]]. Opaque to callers: it carries the encoded question and the
      * decoder from the backend's answer to `R`, so a `Query[Decision[Tool]]` hands back the caller's own
      * `Tool` values, never wire keys. Ask one with [[Decider.query]] and several with [[Decider.batch]].
      * Every `Query` returns the backend's full distribution.
      *
      * @tparam R
      *   the answer type this question decodes to
      * @see
      *   [[Decider.Decision]] the answer to a choice
      * @see
      *   [[Decider.Score]] the answer to a score
      */
    sealed abstract class Query[R] private[kyo] ():
        private[kyo] def question: internal.Question
        private[kyo] def decode(answer: internal.Answer)(using Frame): Result[AIGenException, R]
    end Query

    object Query:

        /** A yes/no question. Answer: the probability that the answer is yes. */
        def noul[Q: Schema](question: Q)(using Frame): Query[Double] =
            NoulQuery(internal.Question.Noul(Structure.encode(question), Absent, Absent, Absent))

        /** A yes/no question with descriptions of what each side means, for a subtle boundary. */
        def noul[Q: Schema, D: Schema](question: Q, whenTrue: D, whenFalse: D)(using Frame): Query[Double] =
            NoulQuery(internal.Question.Noul(
                Structure.encode(question),
                Present(Structure.encode(whenTrue)),
                Present(Structure.encode(whenFalse)),
                Absent
            ))

        /** A pick among unordered options. Answer: the distribution over the options. */
        def choice[Q: Schema, A: Schema](question: Q, options: Seq[A])(using Frame): Query[Decision[A]] =
            val encoded = internal.encodeOptions(options)
            ChoiceQuery(internal.Question.Choice(Structure.encode(question), encoded), Chunk.from(options), encoded.map(_._1))

        /** A rating against ordered levels, lowest first. Answer: the position on the levels and the
          * distribution over them.
          */
        def score[Q: Schema, A: Schema](question: Q, levels: Seq[A])(using Frame): Query[Score[A]] =
            ScoreQuery(internal.Question.Score(Structure.encode(question), internal.encodeLevels(levels)), Chunk.from(levels))

        final private class NoulQuery(val question: internal.Question) extends Query[Double]:
            def decode(answer: internal.Answer)(using Frame): Result[AIGenException, Double] =
                answer match
                    case internal.Answer.Noul(p) => Result.succeed(p)
                    case other                   => Result.fail(internal.mismatch("noul", other))
        end NoulQuery

        final private class ChoiceQuery[A](val question: internal.Question, values: Chunk[A], keys: Chunk[String])
            extends Query[Decision[A]]:
            def decode(answer: internal.Answer)(using Frame): Result[AIGenException, Decision[A]] =
                answer match
                    case internal.Answer.Choice(key, confidence, probabilities) =>
                        internal.decodeDecision(values, keys, key, confidence, probabilities)
                    case other => Result.fail(internal.mismatch("choice", other))
        end ChoiceQuery

        final private class ScoreQuery[A](val question: internal.Question, values: Chunk[A]) extends Query[Score[A]]:
            def decode(answer: internal.Answer)(using Frame): Result[AIGenException, Score[A]] =
                answer match
                    case internal.Answer.Score(value, confidence, probabilities) =>
                        internal.decodeScore(values, value, confidence, probabilities)
                    case other => Result.fail(internal.mismatch("score", other))
        end ScoreQuery
    end Query

    /** The answer to a choice question: the most probable option, the backend's confidence, and the
      * probability of every option in the order the options were given.
      *
      * The numbers are the backend's own, not the library's: TypeSafe's Jev returns a calibrated
      * distribution over the options, so `probabilities` can be thresholded, compared and ranked, which is
      * what [[probabilityOf]], [[ranked]] and [[isAmbiguous]] are for. `confidence` is the backend's own
      * concentration statistic over that distribution (a flat distribution scores low, a single peak
      * high), not the winner's probability; TypeSafe leaves its formula unspecified. IMPORTANT: the 0.70
      * default of [[isConfident]] is a convention on that unspecified statistic, not a calibrated point;
      * tune it against your own decisions and pin the model version once you have.
      *
      * @tparam A
      *   the option type the caller supplied
      * @see
      *   [[Decider.choose]] for the pick alone
      * @see
      *   [[Decider.Query.choice]] for the question that yields this answer
      */
    final case class Decision[A](best: A, confidence: Double, probabilities: Chunk[(A, Double)]) derives CanEqual:

        /** Whether `confidence` reaches 0.70. */
        def isConfident: Boolean = isConfident(internal.defaultConfidence)

        /** Whether `confidence` reaches the threshold. */
        def isConfident(threshold: Double): Boolean = confidence >= threshold

        /** Whether the runner-up's probability is within 0.15 of the winner's. False with one option. */
        def isAmbiguous: Boolean = isAmbiguous(internal.defaultMargin)

        /** Whether the runner-up's probability is within `margin` of the winner's. False with one option. */
        def isAmbiguous(margin: Double): Boolean =
            val sorted = ranked
            sorted.size >= 2 && (sorted(0)._2 - sorted(1)._2) <= margin

        /** The probability of an option, 0 for one that was not in the question. Comparing options needs
          * `CanEqual[A, A]`, which `derives CanEqual` on the option type provides.
          */
        def probabilityOf(option: A)(using CanEqual[A, A]): Double =
            probabilities.collectFirst { case (a, p) if a == option => p }.getOrElse(0.0)

        /** The options from most to least probable. */
        def ranked: Chunk[(A, Double)] = probabilities.sortBy(-_._2)
    end Decision

    /** The answer to a score question: the position on the levels (the probability-weighted level index,
      * which can fall between two levels), the level nearest that position (a tie at `.5` rounds up), the
      * backend's confidence, and the probability of every level in the order the levels were given.
      *
      * The position is the backend's expectation over its own distribution, so it compares against a
      * threshold or another score well. It is not a calibrated magnitude: TypeSafe advises against
      * reading the fraction between two levels as an exact quantity, so [[normalized]] is for thresholds
      * and ordering, not for arithmetic on the gap between levels.
      *
      * @tparam A
      *   the level type the caller supplied
      * @see
      *   [[Decider.score]] for the position alone
      * @see
      *   [[Decider.Query.score]] for the question that yields this answer
      */
    final case class Score[A](value: Double, level: A, confidence: Double, probabilities: Chunk[(A, Double)]) derives CanEqual:

        /** The position scaled to [0, 1]: 0 is the first level, 1 the last. A score has at least two
          * levels, so the scale is never degenerate.
          */
        def normalized: Double = value / (probabilities.size - 1)
    end Score

    /** A decision provider's backend, reached through a `DeciderConfig.Provider` and never named by users.
      * The config's own completion provider (`kyo.ai.decider.LLMDecider`, the path taken when no decider
      * is set) has the same shape without the `DeciderConfig`.
      *
      * Answers questions about a conversation and returns one answer per question, in order, each with
      * its distribution. Everything it needs arrives as an argument, so a backend reads no ambient state:
      * `config` is the effective config the decision runs under (its transport settings are the
      * fallbacks), `decider` the provider's own settings, and `context` the instance's conversation as
      * the glue resolved it, the same prompt-enriched view a generation sees, minus tool guidance. The
      * conversation is what the decision is about; a one-shot's context value is its single user message.
      *
      * A backend never leaves messages on the instance and never records the decision: recording is the
      * glue's job, done identically for every backend, so a transcript has the same shape whichever
      * answered. A backend that generates (the completion backend) runs under `AI.forget`, so its own
      * turns roll back; those turns fire the observers with their real spend, which is why `Reply.usage`
      * carries only spend the observers have NOT seen. The glue forwards `Reply.usage` to the observers
      * on every decision, recorded or not.
      */
    private[kyo] trait Backend:
        def decide(config: Config, decider: DeciderConfig, context: Context, questions: Chunk[internal.Question])(using
            Frame
        ): internal.Reply < (LLM & Async & Abort[AIGenException])
    end Backend

    private[kyo] object internal:

        /** One question, fully encoded: every `Schema` was applied at the factory. A choice carries its
          * options as (key, description) pairs; a score carries its level descriptions lowest first. A
          * `check`'s noul carries the threshold it will be decoded against, so it is validated with the
          * question before any request; the threshold never reaches a wire or a transcript.
          */
        enum Question derives CanEqual:
            case Noul(
                instructions: Structure.Value,
                whenTrue: Maybe[Structure.Value],
                whenFalse: Maybe[Structure.Value],
                threshold: Maybe[Double]
            )
            case Choice(instructions: Structure.Value, options: Chunk[(String, Structure.Value)])
            case Score(instructions: Structure.Value, levels: Chunk[Structure.Value])

            /** The question kind's wire name. */
            def kind: String = this match
                case _: Question.Noul   => "noul"
                case _: Question.Choice => "choice"
                case _: Question.Score  => "score"
        end Question

        /** A backend's answer, in question order, always with its distribution: a noul's probability of
          * yes; a choice's best key, the backend's confidence, and one probability per option key; a
          * score's probability-weighted level index, the confidence, and one probability per level index.
          */
        enum Answer derives CanEqual:
            case Noul(probability: Double)
            case Choice(key: String, confidence: Double, probabilities: Chunk[(String, Double)])
            case Score(value: Double, confidence: Double, probabilities: Chunk[Double])

            /** The answer kind's wire name. */
            def kind: String = this match
                case _: Answer.Noul   => "noul"
                case _: Answer.Choice => "choice"
                case _: Answer.Score  => "score"
        end Answer

        /** What a backend returns: one answer per question, in order, and the spend observers have not yet
          * seen. The TypeSafe backend reports its request's tokens as one turn; the completion backend
          * reports nothing here, since its generation turns fired the observers themselves. The glue
          * forwards it to the observers on every decision, recorded or not.
          */
        final case class Reply(answers: Chunk[Answer], usage: AIStats) derives CanEqual

        /** One `Decide` op's worth of questions plus the decoder from their answers to the caller's result,
          * which is where option keys turn back into the caller's values. Decoding runs in the handler,
          * where a bad answer aborts as `AIDecodeException`, so the public surface stays `< LLM`.
          */
        final case class Plan[R](questions: Chunk[Question], decode: Chunk[Answer] => Result[AIGenException, R])

        /** The one code path every public method and both backends share: validate, resolve the effective
          * config and the conversation, ask the configured backend, report the spend to the observers,
          * record the exchange when asked to, decode. Nothing here depends on which backend is configured.
          *
          * `record` is whether the two canonical messages join the instance's history.
          */
        def decide[R](target: AI, plan: Plan[R], record: Boolean)(using
            Frame
        ): R < (LLM & Async & Abort[AIGenException]) =
            Abort.get(validate(plan.questions)).andThen {
                // The effective env is the scope merged with the instance, the merge genLoop installs, so an
                // instance config override and instance observers apply to its decisions. It is handed to
                // the backend as a value and installed only around the observer fire: the completion
                // backend's own generations re-merge the session, so installing it around the whole call
                // would double every instance enablement.
                LLM.session(target).map { session =>
                    LLM.env.map { scopeEnv =>
                        val env    = session.effectiveEnv(scopeEnv)
                        val config = env.config.get
                        Prompt.internal.enrichedContext(env.prompt, session.rawContext, Chunk.empty).map { context =>
                            val backend = config.decider.fold("completion")(_.provider.name)
                            val log     = Log.debug(
                                s"kyo-ai decide backend=$backend questions=${plan.questions.size} " +
                                    s"kinds=${plan.questions.map(_.kind).mkString(",")} messages=${context.messages.size} record=$record"
                            )
                            // No decider: the config's own completion provider decides. A decider: its
                            // provider's backend does, with its own settings.
                            val reply = config.decider match
                                case Present(decider) => decider.provider.backend.decide(config, decider, context, plan.questions)
                                case Absent           => LLMDecider.decide(config, target, context, plan.questions)
                            log.andThen(reply).map { reply =>
                                if reply.answers.size != plan.questions.size then
                                    Abort.fail(AIDecodeException(
                                        s"expected ${plan.questions.size} answer(s), got ${reply.answers.size}"
                                    ))
                                else
                                    val user      = questionMessage(plan.questions)
                                    val assistant = answerMessage(reply.answers)
                                    val wire      = Completion.Reply(Chunk(user, assistant), Completion.StopReason.Completed, reply.usage)
                                    // Observers first, then the messages join the context: genLoop's order, so
                                    // a callback sees the conversation up to this decision and the reply
                                    // carries the decision itself. Observers fire on every decision, one-shots
                                    // included, under the effective env so `AI.config` inside a callback
                                    // reads the decision's config.
                                    fireObservers(env, target, wire)
                                        .andThen(Kyo.when(record)(LLM.append(target, user).andThen(LLM.append(target, assistant))))
                                        .andThen(Abort.get(plan.decode(reply.answers)))
                            }
                        }
                    }
                }
            }

        private def fireObservers(env: AIEnv, target: AI, wire: Completion.Reply)(using
            Frame
        ): Unit < (LLM & Async & Abort[AIGenException]) =
            LLM.setEnv(env).map { prevEnv =>
                Abort.recover[AIGenException](e => LLM.setEnv(prevEnv).andThen(Abort.fail(e))) {
                    Kyo.foreachDiscard(env.observe.asInstanceOf[Chunk[Observe[LLM]]])(_(target, wire))
                }.andThen(LLM.setEnv(prevEnv).unit)
            }

        /** A one-shot: a fresh ephemeral instance carrying the context (if any) as its single user message,
          * the same way `AI.gen(input)` records an input, decided against and discarded afterwards.
          */
        def oneShot[R](context: Maybe[Structure.Value], plan: Plan[R])(using Frame): R < LLM =
            AI.init.map { ai =>
                val seeded = context match
                    case Present(value) => LLM.append(ai, UserMessage(Json.encode(value), Absent))
                    case Absent         => Kyo.unit
                seeded.andThen(LLM.decide(ai, plan, record = false)).map(r => LLM.discard(ai).andThen(r))
            }

        def plan[R](question: Question)(decode: Answer => Result[AIGenException, R])(using Frame): Plan[R] =
            Plan(Chunk(question), answers => decode(answers(0)))

        /** The probability that the question holds. */
        def noulPlan(instructions: Structure.Value)(using Frame): Plan[Double] =
            plan(Question.Noul(instructions, Absent, Absent, Absent)) {
                case Answer.Noul(p) => Result.succeed(p)
                case other          => Result.fail(mismatch("noul", other))
            }

        /** Whether the question holds: the probability against `threshold`. */
        def checkPlan(instructions: Structure.Value, threshold: Double)(using Frame): Plan[Boolean] =
            plan(Question.Noul(instructions, Absent, Absent, Present(threshold))) {
                case Answer.Noul(p) => Result.succeed(p >= threshold)
                case other          => Result.fail(mismatch("noul", other))
            }

        def choosePlan[A: Schema](instructions: Structure.Value, options: Seq[A])(using Frame): Plan[A] =
            val encoded = encodeOptions(options)
            val values  = Chunk.from(options)
            val keys    = encoded.map(_._1)
            plan(Question.Choice(instructions, encoded)) {
                case Answer.Choice(key, _, _) =>
                    val index = keys.indexOf(key)
                    if index < 0 then Result.fail(AIDecodeException(s"choice answer names an unknown option '$key'"))
                    else Result.succeed(values(index))
                case other => Result.fail(mismatch("choice", other))
            }
        end choosePlan

        def scorePlan[A: Schema](instructions: Structure.Value, levels: Seq[A])(using Frame): Plan[Double] =
            plan(Question.Score(instructions, encodeLevels(levels))) {
                case Answer.Score(value, _, _) => Result.succeed(value)
                case other                     => Result.fail(mismatch("score", other))
            }

        def queryPlan[R](query: Query[R])(using Frame): Plan[R] =
            Plan(Chunk(query.question), answers => query.decode(answers(0)))

        /** Any number of same-typed questions in one request, answered in order. */
        def batchPlan[R](queries: Seq[Query[R]])(using Frame): Plan[Chunk[R]] =
            val qs = Chunk.from(queries)
            Plan(
                qs.map(_.question),
                answers =>
                    qs.zip(answers).foldLeft(Result.succeed[AIGenException, Chunk[R]](Chunk.empty)) { case (acc, (q, a)) =>
                        acc.flatMap(rs => q.decode(a).map(rs.append))
                    }
            )
        end batchPlan

        def batchPlan[A, B](q1: Query[A], q2: Query[B])(using Frame): Plan[(A, B)] =
            Plan(Chunk(q1.question, q2.question), as => q1.decode(as(0)).flatMap(a => q2.decode(as(1)).map(b => (a, b))))

        def batchPlan[A, B, C](q1: Query[A], q2: Query[B], q3: Query[C])(using Frame): Plan[(A, B, C)] =
            Plan(
                Chunk(q1.question, q2.question, q3.question),
                as => q1.decode(as(0)).flatMap(a => q2.decode(as(1)).flatMap(b => q3.decode(as(2)).map(c => (a, b, c))))
            )

        def batchPlan[A, B, C, D](q1: Query[A], q2: Query[B], q3: Query[C], q4: Query[D])(using Frame): Plan[(A, B, C, D)] =
            Plan(
                Chunk(q1.question, q2.question, q3.question, q4.question),
                as =>
                    q1.decode(as(0)).flatMap(a =>
                        q2.decode(as(1)).flatMap(b => q3.decode(as(2)).flatMap(c => q4.decode(as(3)).map(d => (a, b, c, d))))
                    )
            )

        val defaultThreshold: Double  = 0.5
        val defaultConfidence: Double = 0.70
        val defaultMargin: Double     = 0.15

        /** TypeSafe's limits, enforced for every backend before a request so a bad question never spends
          * one and fails the same way whichever backend is configured.
          */
        val maxOptions: Int = 255
        val minLevels: Int  = 2
        val maxLevels: Int  = 10

        /** Infers an option's wire key and description from its encoding: a string is its own key with no
          * description; an enum case is keyed by its case name, described by its `@doc` when it has one,
          * else by its fields when it has any; anything else is keyed positionally with the whole value as
          * the description.
          */
        def keyOf(encoded: Structure.Value, index: Int, docs: Dict[String, String]): (String, Structure.Value) =
            encoded match
                case Structure.Value.Str(s)                     => (s, Structure.Value.Null)
                case Structure.Value.VariantCase(name, payload) =>
                    docs.get(name) match
                        case Present(text) => (name, Structure.Value.Str(text))
                        case Absent        =>
                            payload match
                                case Structure.Value.Record(fields) if fields.isEmpty => (name, Structure.Value.Null)
                                case _                                                => (name, payload)
                case other => (s"c$index", other)

        /** The `@doc` text of every documented case of a sum type, by case name; empty for any other type. */
        def docsOf[A](using schema: Schema[A]): Dict[String, String] =
            schema.structure match
                case sum: Structure.Type.Sum =>
                    sum.variants.foldLeft(Dict.empty[String, String]) { (acc, variant) =>
                        Maybe.fromOption(variant.annotations.collectFirst { case d: doc => d.text }) match
                            case Present(text) => acc.update(variant.name, text)
                            case Absent        => acc
                    }
                case _ => Dict.empty

        def encodeOptions[A: Schema](options: Seq[A])(using Frame): Chunk[(String, Structure.Value)] =
            val docs = docsOf[A]
            Chunk.from(options.zipWithIndex.map((a, i) => keyOf(Structure.encode(a), i, docs)))

        /** A level's wire description: its `@doc` for a documented enum case, else its encoding (a string
          * level is its own description).
          */
        def encodeLevels[A: Schema](levels: Seq[A])(using Frame): Chunk[Structure.Value] =
            val docs = docsOf[A]
            Chunk.from(levels).map { level =>
                Structure.encode(level) match
                    case Structure.Value.VariantCase(name, payload) =>
                        docs.get(name) match
                            case Present(text) => Structure.Value.Str(text)
                            case Absent        =>
                                payload match
                                    case Structure.Value.Record(fields) if fields.isEmpty => Structure.Value.Str(name)
                                    case _                                                => Structure.Value.VariantCase(name, payload)
                    case other => other
            }
        end encodeLevels

        /** Checks every question against the limits and the key uniqueness the decoding relies on, before
          * any request. The limits are TypeSafe's, enforced for every backend, so a question fails the same
          * way whichever backend is configured.
          */
        def validate(questions: Chunk[Question])(using Frame): Result[AIInvalidQuestionException, Unit] =
            questions.zipWithIndex.foldLeft(Result.succeed[AIInvalidQuestionException, Unit](())) { case (acc, (q, i)) =>
                acc.flatMap { _ =>
                    q match
                        case Question.Noul(_, _, _, threshold) if threshold.exists(t => t < 0.0 || t > 1.0) =>
                            Result.fail(AIInvalidQuestionException(
                                s"question ${i + 1}: a threshold must be within [0, 1], got ${threshold.getOrElse(0.0)}"
                            ))
                        case Question.Choice(_, options) if options.isEmpty =>
                            Result.fail(AIInvalidQuestionException(s"question ${i + 1}: a choice needs at least one option"))
                        case Question.Choice(_, options) if options.size > maxOptions =>
                            Result.fail(AIInvalidQuestionException(
                                s"question ${i + 1}: a choice takes at most $maxOptions options, got ${options.size}"
                            ))
                        case Question.Choice(_, options) =>
                            val keys = options.map(_._1)
                            Maybe.fromOption(keys.zipWithIndex.collectFirst { case (k, at) if keys.indexOf(k) != at => k }) match
                                case Present(dup) =>
                                    Result.fail(AIInvalidQuestionException(s"question ${i + 1}: options share the key '$dup'"))
                                case Absent => Result.succeed(())
                            end match
                        case Question.Score(_, levels) if levels.size < minLevels || levels.size > maxLevels =>
                            Result.fail(AIInvalidQuestionException(
                                s"question ${i + 1}: a score takes $minLevels to $maxLevels levels, got ${levels.size}"
                            ))
                        case _ => Result.succeed(())
                }
            }
        end validate

        def mismatch(expected: String, answer: Answer)(using Frame): AIDecodeException =
            AIDecodeException(s"expected a $expected answer, got ${answer.kind}")

        def decodeDecision[A](
            values: Chunk[A],
            keys: Chunk[String],
            key: String,
            confidence: Double,
            probabilities: Chunk[(String, Double)]
        )(using Frame): Result[AIGenException, Decision[A]] =
            val index = keys.indexOf(key)
            if index < 0 then Result.fail(AIDecodeException(s"choice answer names an unknown option '$key'"))
            else
                // Every option must have its probability: a distribution with holes would feed zeros into
                // `isAmbiguous` and `probabilityOf` and read as a genuine answer, so a missing key fails
                // the way a missing level probability fails on the score path.
                val byKey = probabilities.foldLeft(Dict.empty[String, Double])((acc, kp) => acc.update(kp._1, kp._2))
                Maybe.fromOption(keys.find(k => !byKey.contains(k))) match
                    case Present(k) => Result.fail(AIDecodeException(s"choice answer has no probability for '$k'"))
                    case Absent     =>
                        Result.succeed(Decision(
                            values(index),
                            confidence,
                            values.zip(keys).map((a, k) => (a, byKey(k)))
                        ))
                end match
            end if
        end decodeDecision

        def decodeScore[A](values: Chunk[A], value: Double, confidence: Double, probabilities: Chunk[Double])(using
            Frame
        ): Result[AIGenException, Score[A]] =
            if probabilities.size != values.size then
                Result.fail(AIDecodeException(s"score answer has ${probabilities.size} probabilities for ${values.size} levels"))
            else
                val nearest = math.max(0, math.min(values.size - 1, math.round(value).toInt))
                Result.succeed(Score(value, values(nearest), confidence, values.zip(probabilities)))

        /** A question in TypeSafe's wire shape (`type`, `instructions`, optional `criteria`): what the
          * endpoint receives and what a recorded decision carries, whichever backend answered. The
          * instructions and criteria are the caller's values, so they stay `Structure.Value`.
          */
        final case class WireQuestion(`type`: String, instructions: Structure.Value, criteria: Maybe[Structure.Value] = Absent)
            derives Schema

        /** An answer in TypeSafe's wire shape: one flat record with the fields of its `type` set, since the
          * endpoint tags answers rather than wrapping them the way kyo-schema encodes a sum type. Choice
          * probabilities are keyed by option key, score probabilities by level index.
          */
        final case class WireAnswer(
            `type`: String,
            noul: Maybe[Double] = Absent,
            choice: Maybe[String] = Absent,
            score: Maybe[Double] = Absent,
            confidence: Maybe[Double] = Absent,
            probabilities: Maybe[OrderedDict[String, Double]] = Absent
        ) derives Schema

        /** The recorded form of a question set. */
        final case class RecordedQuestions(questions: Chunk[WireQuestion]) derives Schema

        /** The recorded form of a question set's answers. */
        final case class RecordedAnswers(answers: Chunk[WireAnswer]) derives Schema

        /** The recorded form of a question set: a user message carrying the questions as JSON. */
        def questionMessage(questions: Chunk[Question])(using Frame): UserMessage =
            UserMessage(Json.encode(RecordedQuestions(questions.map(wireQuestion))), Absent)

        /** The recorded form of a question set's answers: an assistant message carrying the answers as
          * JSON.
          */
        def answerMessage(answers: Chunk[Answer])(using Frame): AssistantMessage =
            AssistantMessage(Json.encode(RecordedAnswers(answers.map(wireAnswer))))

        def wireQuestion(question: Question): WireQuestion =
            question match
                case Question.Noul(instructions, Absent, Absent, _)      => WireQuestion("noul", instructions)
                case Question.Noul(instructions, whenTrue, whenFalse, _) =>
                    WireQuestion(
                        "noul",
                        instructions,
                        Present(Structure.Value.Record(
                            Chunk.from(whenTrue.map(v => ("true", v))) ++ Chunk.from(whenFalse.map(v => ("false", v)))
                        ))
                    )
                case Question.Choice(instructions, options) =>
                    WireQuestion("choice", instructions, Present(Structure.Value.Record(options)))
                case Question.Score(instructions, levels) =>
                    WireQuestion("score", instructions, Present(Structure.Value.Sequence(levels)))

        def wireAnswer(answer: Answer): WireAnswer =
            answer match
                case Answer.Noul(probability)                      => WireAnswer("noul", noul = Present(probability))
                case Answer.Choice(key, confidence, probabilities) =>
                    WireAnswer(
                        "choice",
                        choice = Present(key),
                        confidence = Present(confidence),
                        probabilities = Present(OrderedDict(probabilities*))
                    )
                case Answer.Score(value, confidence, probabilities) =>
                    WireAnswer(
                        "score",
                        score = Present(value),
                        confidence = Present(confidence),
                        probabilities = Present(OrderedDict(probabilities.zipWithIndex.map((p, i) => (i.toString, p))*))
                    )
    end internal
end Decider
