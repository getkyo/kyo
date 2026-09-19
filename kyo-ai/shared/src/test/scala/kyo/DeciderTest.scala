package kyo

import kyo.Decider.*
import kyo.Decider.internal.*
import kyo.ai.Context.*
import kyo.schema.doc

object DeciderTest:

    // Fixtures live on the companion: Schema derivation needs a static owner, not an enclosing test instance.
    enum Tool derives Schema, CanEqual:
        case Shell, Database, None

    enum Route derives Schema, CanEqual:
        case Direct
        case Via(host: String, port: Int)

    enum Handler derives Schema, CanEqual:
        @doc("Runs a shell command on the host") case Shell
        @doc("Queries the users database") case Database
        case Browser
        @doc("Hands the task to a person") case Escalate(team: String)
    end Handler

    enum Severity derives Schema, CanEqual:
        @doc("Cosmetic, nobody is blocked") case Low
        case Medium
        @doc("Blocking, users cannot proceed") case High
    end Severity

    case class Candidate(name: String, what: String) derives Schema, CanEqual
end DeciderTest

class DeciderTest extends kyo.test.Test[Any]:
    import DeciderTest.*

    val str = Structure.Value.Str
    val num = Structure.Value.Decimal

    "key inference" - {
        val noDocs = Dict.empty[String, String]

        "a string is its own key with no description" in {
            assert(keyOf(Structure.encode("January"), 3, noDocs) == ("January", Structure.Value.Null))
        }
        "a field-less enum case is keyed by its name with no description" in {
            assert(keyOf(Structure.encode(Tool.Database), 1, noDocs) == ("Database", Structure.Value.Null))
        }
        "an enum case with fields is keyed by its name with the fields as the description" in {
            val (key, description) = keyOf(Structure.encode(Route.Via("db", 5432)), 1, noDocs)
            assert(key == "Via")
            assert(description == Structure.Value.Record(Chunk(("host", str("db")), ("port", Structure.Value.Integer(5432L)))))
        }
        "a case class is keyed positionally with the whole value as the description" in {
            val (key, description) = keyOf(Structure.encode(Candidate("shell", "run commands")), 2, noDocs)
            assert(key == "c2")
            assert(description == Structure.Value.Record(Chunk(("name", str("shell")), ("what", str("run commands")))))
        }
        "a number is keyed positionally" in {
            assert(keyOf(Structure.encode(42), 0, noDocs) == ("c0", Structure.Value.Integer(42L)))
        }
        "encodeOptions keeps the caller's order" in {
            val encoded = encodeOptions(Seq(Tool.None, Tool.Shell))
            assert(encoded.map(_._1) == Chunk("None", "Shell"))
        }
        "docsOf reads the @doc of every documented case" in {
            val docs = docsOf[Handler]
            assert(docs.get("Shell") == Present("Runs a shell command on the host"))
            assert(docs.get("Database") == Present("Queries the users database"))
            assert(docs.get("Browser") == Absent)
            assert(docs.get("Escalate") == Present("Hands the task to a person"))
            assert(docsOf[String].isEmpty)
            assert(docsOf[Candidate].isEmpty)
        }
        "a documented enum case is described by its @doc, an undocumented one by nothing" in {
            val encoded = encodeOptions(Seq(Handler.Shell, Handler.Browser, Handler.Escalate("ops")))
            assert(encoded == Chunk(
                ("Shell", str("Runs a shell command on the host")),
                ("Browser", Structure.Value.Null),
                ("Escalate", str("Hands the task to a person"))
            ))
        }
        "a level is its @doc, its case name, or its own encoding" in {
            assert(encodeLevels(Seq(Severity.Low, Severity.Medium, Severity.High)) ==
                Chunk(str("Cosmetic, nobody is blocked"), str("Medium"), str("Blocking, users cannot proceed")))
            assert(encodeLevels(Seq("low", "high")) == Chunk(str("low"), str("high")))
            assert(encodeLevels(Seq(Route.Via("db", 1))) ==
                Chunk(Structure.Value.VariantCase(
                    "Via",
                    Structure.Value.Record(Chunk(("host", str("db")), ("port", Structure.Value.Integer(1L))))
                )))
        }
    }

    "validation" - {
        def choice(n: Int): Question =
            Question.Choice(str("q"), Chunk.from((0 until n).map(i => (s"o$i", Structure.Value.Null))))
        def score(n: Int): Question =
            Question.Score(str("q"), Chunk.from((0 until n).map(i => str(s"level $i"))))
        def noul(threshold: Maybe[Double]): Question =
            Question.Noul(str("q"), Absent, Absent, threshold)

        "accepts 1 and 255 options" in {
            assert(validate(Chunk(choice(1))).isSuccess)
            assert(validate(Chunk(choice(255))).isSuccess)
        }
        "rejects 0 options" in {
            val r = validate(Chunk(choice(0)))
            assert(r.failure.exists(_.getMessage.contains("at least one option")))
        }
        "rejects 256 options" in {
            val r = validate(Chunk(choice(256)))
            assert(r.failure.exists(_.getMessage.contains("at most 255 options, got 256")))
        }
        "accepts 2 and 10 levels" in {
            assert(validate(Chunk(score(2))).isSuccess)
            assert(validate(Chunk(score(10))).isSuccess)
        }
        "rejects 1 and 11 levels" in {
            assert(validate(Chunk(score(1))).failure.exists(_.getMessage.contains("2 to 10 levels, got 1")))
            assert(validate(Chunk(score(11))).failure.exists(_.getMessage.contains("2 to 10 levels, got 11")))
        }
        "rejects colliding keys" in {
            val q = Question.Choice(str("q"), Chunk(("Shell", Structure.Value.Null), ("Shell", str("again"))))
            assert(validate(Chunk(q)).failure.exists(_.getMessage.contains("share the key 'Shell'")))
        }
        "names the failing question by position" in {
            val r = validate(Chunk(choice(1), score(1)))
            assert(r.failure.exists(_.getMessage.contains("question 2:")))
        }
        "a noul passes without a threshold and with one within [0, 1]" in {
            assert(validate(Chunk(noul(Absent))).isSuccess)
            assert(validate(Chunk(noul(Present(0.0)))).isSuccess)
            assert(validate(Chunk(noul(Present(1.0)))).isSuccess)
        }
        "rejects a threshold outside [0, 1]" in {
            assert(validate(Chunk(noul(Present(1.5)))).failure.exists(_.getMessage.contains("within [0, 1], got 1.5")))
            // -0.5 renders the same on every platform (Scala.js prints -1.0 as -1).
            assert(validate(Chunk(noul(Present(-0.5)))).failure.exists(_.getMessage.contains("within [0, 1], got -0.5")))
        }
    }

    "Decision" - {
        val decision = Decision(Tool.Database, 0.76, Chunk((Tool.Shell, 0.16), (Tool.Database, 0.82), (Tool.None, 0.02)))

        "isConfident compares confidence to the threshold, 0.70 without one" in {
            assert(decision.isConfident)
            assert(!Decision(Tool.Shell, 0.69, Chunk((Tool.Shell, 1.0))).isConfident)
            assert(decision.isConfident(0.70))
            assert(decision.isConfident(0.76))
            assert(!decision.isConfident(0.77))
        }
        "isAmbiguous compares the top two probabilities, not confidence, within 0.15 without a margin" in {
            assert(!decision.isAmbiguous)
            assert(Decision(Tool.Shell, 0.9, Chunk((Tool.Shell, 0.5), (Tool.Database, 0.4))).isAmbiguous)
            assert(!decision.isAmbiguous(0.15))
            assert(decision.isAmbiguous(0.66))
            assert(!Decision(Tool.Shell, 0.3, Chunk((Tool.Shell, 1.0))).isAmbiguous(1.0))
        }
        "probabilityOf reads the option's probability, 0 when absent" in {
            assert(decision.probabilityOf(Tool.Shell) == 0.16)
            assert(Decision(Tool.Shell, 1.0, Chunk((Tool.Shell, 1.0))).probabilityOf(Tool.None) == 0.0)
        }
        "ranked sorts by probability, probabilities keep the caller's order" in {
            assert(decision.ranked == Chunk((Tool.Database, 0.82), (Tool.Shell, 0.16), (Tool.None, 0.02)))
            assert(decision.probabilities.map(_._1) == Chunk(Tool.Shell, Tool.Database, Tool.None))
        }
    }

    "Score" - {
        "normalized scales the position onto [0, 1]" in {
            val s = Score(1.3, "Degraded", 0.9, Chunk(("Healthy", 0.0), ("Degraded", 0.7), ("Corrupt", 0.3)))
            assert(math.abs(s.normalized - 0.65) < 1e-9)
            assert(Score(0.0, "a", 1.0, Chunk(("a", 1.0), ("b", 0.0))).normalized == 0.0)
            assert(Score(1.0, "b", 1.0, Chunk(("a", 0.0), ("b", 1.0))).normalized == 1.0)
        }
    }

    "queries" - {
        "noul encodes the question and optional criteria" in {
            assert(Query.noul("Is it?").question == Question.Noul(str("Is it?"), Absent, Absent, Absent))
            assert(Query.noul("Is it?", "yes means", "no means").question ==
                Question.Noul(str("Is it?"), Present(str("yes means")), Present(str("no means")), Absent))
        }
        "choice encodes the options with inferred keys" in {
            val q = Query.choice("Which?", Seq(Tool.Shell, Tool.None))
            assert(q.question == Question.Choice(str("Which?"), Chunk(("Shell", Structure.Value.Null), ("None", Structure.Value.Null))))
        }
        "score encodes the levels in order" in {
            val q = Query.score("How bad?", Seq("low", "high"))
            assert(q.question == Question.Score(str("How bad?"), Chunk(str("low"), str("high"))))
        }
        "a noul decodes its probability and rejects other kinds" in {
            val q = Query.noul("Is it?")
            assert(q.decode(Answer.Noul(0.42)) == Result.succeed(0.42))
            assert(q.decode(Answer.Choice(
                "x",
                1.0,
                Chunk(("x", 1.0))
            )).failure.exists(_.getMessage.contains("expected a noul answer, got choice")))
        }
        "a choice decodes the option back from its key with the distribution in caller order" in {
            val q      = Query.choice("Which?", Seq(Tool.Shell, Tool.Database, Tool.None))
            val answer = Answer.Choice("Database", 0.93, Chunk(("None", 0.0), ("Database", 0.95), ("Shell", 0.05)))
            assert(q.decode(answer) == Result.succeed(Decision(
                Tool.Database,
                0.93,
                Chunk((Tool.Shell, 0.05), (Tool.Database, 0.95), (Tool.None, 0.0))
            )))
        }
        "a choice rejects an unknown key" in {
            val q = Query.choice("Which?", Seq(Tool.Shell))
            assert(q.decode(Answer.Choice("Browser", 1.0, Chunk(("Browser", 1.0)))).failure.exists(
                _.getMessage.contains("unknown option 'Browser'")
            ))
        }
        "a choice rejects a distribution missing an option's probability" in {
            val q      = Query.choice("Which?", Seq(Tool.Shell, Tool.Database, Tool.None))
            val answer = Answer.Choice("Database", 0.93, Chunk(("Database", 0.95), ("Shell", 0.05)))
            assert(q.decode(answer).failure.exists(_.getMessage.contains("no probability for 'None'")))
        }
        "a score decodes the value, the nearest level and the distribution" in {
            val q      = Query.score("How?", Seq("Healthy", "Degraded", "Corrupt"))
            val answer = Answer.Score(1.3, 0.9, Chunk(0.0, 0.7, 0.3))
            assert(q.decode(answer) == Result.succeed(Score(
                1.3,
                "Degraded",
                0.9,
                Chunk(("Healthy", 0.0), ("Degraded", 0.7), ("Corrupt", 0.3))
            )))
        }
        "a score picks the level nearest the mean, a tie rounding up, clamped to the levels" in {
            val q = Query.score("How?", Seq("a", "b", "c"))
            assert(q.decode(Answer.Score(1.5, 1.0, Chunk(0.0, 0.5, 0.5))).map(_.level) == Result.succeed("c"))
            assert(q.decode(Answer.Score(0.4, 1.0, Chunk(0.6, 0.4, 0.0))).map(_.level) == Result.succeed("a"))
            assert(q.decode(Answer.Score(7.0, 1.0, Chunk(0.0, 0.0, 1.0))).map(_.level) == Result.succeed("c"))
        }
        "a score rejects a distribution of the wrong size" in {
            val q = Query.score("How?", Seq("a", "b"))
            assert(q.decode(Answer.Score(0.5, 1.0, Chunk(0.5, 0.5, 0.0))).failure.exists(
                _.getMessage.contains("3 probabilities for 2 levels")
            ))
        }
    }

    "plans" - {
        "checkPlan carries the threshold on the question and thresholds the probability" in {
            val p = checkPlan(str("Done?"), 0.9)
            assert(p.questions == Chunk(Question.Noul(str("Done?"), Absent, Absent, Present(0.9))))
            assert(p.decode(Chunk(Answer.Noul(0.87))) == Result.succeed(false))
            assert(p.decode(Chunk(Answer.Noul(0.9))) == Result.succeed(true))
        }
        "the homogeneous batch plan asks every question and decodes the answers in order" in {
            val p = batchPlan(Seq(Query.noul("a"), Query.noul("b"), Query.noul("c")))
            assert(p.questions.map(_.kind) == Chunk("noul", "noul", "noul"))
            val answers = Chunk(Answer.Noul(0.9), Answer.Noul(0.1), Answer.Noul(0.5))
            assert(p.decode(answers) == Result.succeed(Chunk(0.9, 0.1, 0.5)))
            assert(p.decode(Chunk(Answer.Noul(0.9), Answer.Choice("x", 1.0, Chunk(("x", 1.0))), Answer.Noul(0.5)))
                .failure.exists(_.getMessage.contains("expected a noul answer, got choice")))
        }
    }

    "recorded messages" - {
        "the question message carries every question in wire shape, never the threshold" in {
            val questions = Chunk(
                Question.Noul(str("Done?"), Absent, Absent, Present(0.7)),
                Question.Noul(str("Broken?"), Present(str("t")), Present(str("f")), Absent),
                Question.Choice(
                    str("Which?"),
                    Chunk(("Shell", Structure.Value.Null), ("c1", Structure.Value.Record(Chunk(("name", str("db"))))))
                ),
                Question.Score(str("How?"), Chunk(str("low"), str("high")))
            )
            val expected =
                """{"questions":[{"type":"noul","instructions":"Done?"},""" +
                    """{"type":"noul","instructions":"Broken?","criteria":{"true":"t","false":"f"}},""" +
                    """{"type":"choice","instructions":"Which?","criteria":{"Shell":null,"c1":{"name":"db"}}},""" +
                    """{"type":"score","instructions":"How?","criteria":["low","high"]}]}"""
            assert(questionMessage(questions) == UserMessage(expected, Absent))
        }
        "the answer message carries every answer with its distribution, in the endpoint's wire shape" in {
            val answers = Chunk(
                Answer.Noul(0.96),
                Answer.Choice("Shell", 0.93, Chunk(("Shell", 0.95), ("None", 0.05))),
                Answer.Score(1.3, 0.97, Chunk(0.0, 0.7, 0.3))
            )
            val expected =
                """{"answers":[{"type":"noul","noul":0.96},""" +
                    """{"type":"choice","choice":"Shell","confidence":0.93,"probabilities":{"Shell":0.95,"None":0.05}},""" +
                    """{"type":"score","score":1.3,"confidence":0.97,"probabilities":{"0":0.0,"1":0.7,"2":0.3}}]}"""
            assert(answerMessage(answers) == AssistantMessage(expected))
        }
    }

end DeciderTest
