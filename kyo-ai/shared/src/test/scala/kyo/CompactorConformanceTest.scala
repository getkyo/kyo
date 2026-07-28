package kyo

import Compactor.internal.stamp
import kyo.ai.*
import kyo.ai.Context.*

/** A mechanism-agnostic conformance battery for ANY `Compactor`.
  *
  * Everything here is expressed over the pair (raw conversation, served view). Nothing reaches into
  * spans, levels, markers, scores or staging, so the same battery grades the shipped compactor, a
  * whole-conversation summarizer, a truncator, or a user's own implementation. A compactor passes or
  * fails on what it EMITS, which is the only thing a caller can observe.
  *
  * It encodes what the live comparison experiments measured, minus the model. A fact that never reaches
  * the served view cannot be used by any model, so retention of the view is a necessary condition and it
  * is deterministic and free. Whether the model then USES what survived is the live half, and belongs to
  * the integration suites; this battery is the part that can run on every commit.
  *
  * The battery is CALIBRATED rather than asserted in a vacuum: it grades a deliberately lossy reference
  * (tail truncation) alongside the real one, so a metric that cannot separate them is a broken metric
  * rather than a passing grade.
  */
class CompactorConformanceTest extends kyo.test.Test[Any]:

    private def axisOf(c: Config): Compactor.internal.Axis =
        Compactor.internal.axis(Compactor.Tuning(), c)

    def um(s: String): UserMessage                    = UserMessage(s, Absent)
    def sm(s: String): SystemMessage                  = SystemMessage(s)
    def am(s: String, calls: Call*): AssistantMessage = AssistantMessage(s, Chunk.from(calls))
    def tok(m: Message, n: Int): Message              = stamp(m, TokenStamp("t", n))
    def tm(id: String, s: String): ToolMessage        = ToolMessage(CallId(id), s)
    def call(id: String, fn: String, a: String): Call = Call(CallId(id), fn, a)
    def toks(v: Chunk[Message]): Int                  = v.foldLeft(0)((a, m) => a + Compactor.internal.stampedTokens(m))

    val window      = 16384
    def cfg: Config = Config.OpenAI.default.apiKey("k").model(Config.OpenAI, "m", window)

    /** The planted facts, one per kind, mirroring the live experiment's grid. Each sits PAST the first
      * line of its turn so a descriptor cannot carry it for free.
      */
    val facts: List[(String, String)] = List(
        "verbatim-value"      -> "the ingest batch size is 384 records",
        "standing-constraint" -> "we must never write directly to the prod bucket",
        "decision-rationale"  -> "we chose quorum reads because tail latency matters more than storage cost",
        "needle-in-bulk"      -> "the archive index shard is vault-nine"
    )

    /** Raw index of the message carrying each planted fact, by construction of `conversation`. */
    def factIndices: List[Int] = facts.indices.toList.map(i => 2 + 2 * i)

    /** A session that grows past the window, with the facts planted at three depths. */
    def conversation: Chunk[Message] =
        val bulk = "the team records context for each decision so a later reader can reconstruct it. " * 8
        val head = Chunk[Message](tok(sm("you are a systems assistant"), 40), tok(um("we are designing a storage layer"), 60))
        val planted = facts.zipWithIndex.flatMap { case ((kind, text), i) =>
            List(
                tok(um(s"journal entry $i\n$bulk\n$text.\n$bulk"), 900),
                tok(am(s"entry $i acknowledged\n$bulk"), 700)
            )
        }
        val filler =
            (0 until 14).flatMap(i => List(tok(um(s"unrelated question $i about system design"), 300), tok(am(s"answer $i\n$bulk"), 700)))
        head.concat(Chunk.from(planted)).concat(Chunk.from(filler))
    end conversation

    /** The observable contract, computed only from (raw, view).
      *
      * Deliberately NOT "did the fact survive". Whether a summary preserved a fact is a property of the
      * MODEL that wrote the summary, so no deterministic battery can grade it for any summarizing
      * compactor: with scripted summaries every such compactor scores zero, which grades the script. That
      * question belongs to the live integration arm.
      *
      * What is observable without a model is ACCOUNTABILITY: a compactor may drop content, but the caller
      * must be able to tell that something was dropped and what stood in its place. Silent disappearance
      * is the failure mode a caller cannot defend against, and it is mechanism-agnostic.
      */
    case class Grade(
        accounted: Int,
        dropped: Int,
        ofTotal: Int,
        servedTokens: Int,
        fitsLimit: Boolean,
        noInvention: Boolean
    ):
        def accountability: Double = if ofTotal == 0 then 1.0 else accounted.toDouble / ofTotal
    end Grade

    def grade(raw: Chunk[Message], view: Chunk[Message], limit: Int): Grade =
        gradeWith(facts, factIndices, raw, view, limit)

    /** [[grade]] over an explicitly given planting, because the facts and their raw indices are properties
      * of a FIXTURE, not of the battery. Hard-wiring the prose fixture's plantings meant grading any other
      * corpus against facts it never contained, which reads as a compaction defect and is not one.
      */
    def gradeWith(
        facts: List[(String, String)],
        factIndices: List[Int],
        raw: Chunk[Message],
        view: Chunk[Message],
        limit: Int
    ): Grade =
        val joined  = view.foldLeft("")((a, m) => a + m.content + "\n")
        val rawText = raw.foldLeft("")((a, m) => a + m.content + "\n")
        // A fact is ACCOUNTED FOR when its text is still served, or when the region that carried it is
        // represented in the view by a synthetic stand-in (any message the compactor generated rather
        // than copied). It is DROPPED when neither holds: the content is gone and nothing marks its place.
        // A stand-in is a synthetic entry the compactor put in place of content it removed. `origin` is the
        // framework's own marker for exactly that, and it is public on Message, so this stays
        // mechanism-agnostic. An earlier version also counted "any message not present in raw", which
        // swept in the turns appended AFTER the render and graded every compactor as perfectly accountable.
        // PER FACT, not per view. An earlier version credited every fact as soon as ANY stand-in appeared
        // anywhere, so a compactor that discarded the whole conversation and prepended one marker scored
        // perfect accountability and passed this battery, calibration included: the contract test was
        // trivially satisfiable by exactly the silent-loss compactor it exists to reject.
        //
        // A fact is accounted for when its text is still served, OR when some stand-in's origin range
        // covers the raw index of the message that carried it. Both are read off the public Context.
        val covered: Int => Boolean = idx =>
            view.exists(_.origin.exists(o => idx >= o.start && idx < o.end))
        val perFact = facts.zip(factIndices).map { case ((_, text), idx) =>
            joined.contains(text) || covered(idx)
        }
        val present   = facts.count((_, text) => joined.contains(text))
        val accounted = perFact.count(identity)
        // Identifiers that were NEVER said, but are plausible neighbours of ones that were. An earlier
        // version listed the planted values themselves, which are always present in raw, so the predicate
        // could never fire and the assertion was decoration.
        val identifiers = List("vault-seven", "harbor-nine", "512 records", "lantern-five")
        val invented    = identifiers.exists(id => joined.contains(id))
        Grade(accounted, facts.size - present, facts.size, toks(view), toks(view) <= limit, !invented)
    end gradeWith

    /** Reference compactors, defined here so the battery is self-contained and calibrated.
      *
      * They are also the honest measure of what the boundary asks of an implementer: two lines of state
      * declaration for a compactor that needs none, and one `Decision` to say whether anything changed.
      */
    final class TailOnly(budget: Int) extends Compactor[Any]:
        type State = Unit
        def initState(using Frame): Unit < Sync = Kyo.unit

        def compact(ctx: Context, state: Unit)(using Frame): Compactor.Decision < (LLM & Async & Abort[AIGenException]) =
            val raw = ctx.raw
            @annotation.tailrec
            def back(i: Int, acc: Int): Int =
                if i <= 1 then i
                else
                    val t = Compactor.internal.stampedTokens(raw(i - 1))
                    if acc + t > budget then i else back(i - 1, acc + t)
            val cut = back(raw.size, 0)
            // Owning the trigger is the price of owning the policy: it reports Unchanged when the tail
            // already fits, which is what keeps the served bytes stable between its own boundaries.
            if cut <= 1 then Kyo.lift(Compactor.Decision.Unchanged)
            else Kyo.lift(Compactor.Decision.Compacted(ctx.copy(compacted = raw.take(1).concat(raw.drop(cut)))))
            end if
        end compact
    end TailOnly

    /** Drops everything and leaves ONE marker. The battery must reject it; if it passes, the contract is
      * satisfiable by discarding the conversation, which is the failure this whole file exists to catch.
      */
    final class SilentLoss extends Compactor[Any]:
        type State = Unit
        def initState(using Frame): Unit < Sync = Kyo.unit

        def compact(ctx: Context, state: Unit)(using Frame): Compactor.Decision < (LLM & Async & Abort[AIGenException]) =
            Kyo.lift(Compactor.Decision.Compacted(ctx.copy(
                compacted = Chunk(SystemMessage("[earlier conversation compacted]", origin = Present(Context.Origin(0, 1, 1))))
            )))
    end SilentLoss

    def genBody(resultValue: String): String =
        val envelope = Json.encode(s"""{"resultValue":$resultValue}""")
        s"""{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"r1","type":"function","function":{"name":"result_tool","arguments":$envelope}}]}}]}"""

    /** Drives the compactor through the REAL serving path, not `render` in isolation.
      *
      * Calling `render` directly is what a first version of this battery did, and it graded the shipped
      * compactor 0 out of 4: with no preparation having run there are no summary bytes staged, so every
      * demoted span renders as an elision and every planted fact disappears. That measured the harness,
      * not the compactor. The seam runs preparation BEFORE render, so the battery has to go through a
      * generation, with summary replies scripted so it stays deterministic and free.
      *
      * This keeps the battery mechanism-agnostic: the compactor decides what to emit, and the battery
      * observes only the served view.
      */
    def servedVia(c: Compactor[Any], raw: Chunk[Message])(using Frame): Chunk[Message] < (Async & Abort[Any] & Scope) =
        TestCompletionServer.run { server =>
            val scripted = cfg.apiUrl(server.baseUrl)
            Kyo.foreachDiscard(0 until 40)(i =>
                server.enqueueBody(genBody(Json.encode(s"summary $i: decisions, values and open threads preserved")))
            ).andThen {
                LLM.run(scripted) {
                    AI.init.map(_.enable(c)).map { ai =>
                        ai.setContext(Context(raw)).andThen(ai.userMessage("continue")).andThen(ai.gen[String])
                            .handle(Abort.run[Any]).andThen(ai.context).map(_.compacted)
                    }
                }
            }
        }

    "the contract holds for the shipped compactor" in {
        val raw = conversation
        assert(toks(raw) > window / 2, s"REGIME: the fixture must be large enough to compact, got ${toks(raw)}")
        servedVia(Compactor.init, raw).map { view =>
            val g = grade(raw, view, axisOf(cfg).hard)
            assert(g.fitsLimit, s"the served view must fit the hard limit: ${g.servedTokens} > ${axisOf(cfg).hard}")
            assert(g.noInvention, "a compactor may drop content but must never invent an identifier")
            assert(
                g.accountability == 1.0,
                s"every dropped region must be accounted for by a stand-in, got ${g.accounted}/${g.ofTotal} accounted"
            )
        }
    }

    /** The same session shape, but composed the way the real workload is: assistant messages carrying
      * tool calls, each answered by a `ToolMessage` with a large payload.
      *
      * Anthropic's published breakdown of a research agent's context is 96.3% file-read tool results
      * against 1.7% agent reasoning. Every seam-level fixture in this suite is prose, so the ranking, the
      * ladder, the cost readings and the cadence figures have all been measured on the composition the
      * workload has least of. That is not a missing assertion, it is a corpus that does not represent
      * what is being graded, which quietly weakens every other number here.
      */
    val toolFacts: List[(String, String)] = List(
        "verbatim-value"      -> "quorum_min=7",
        "standing-constraint" -> "replica-03 is quarantined and must not be written",
        "decision-rationale"  -> "checksum 8f2a marks the last known-good snapshot",
        "needle-in-bulk"      -> "shard_owner=vault-nine"
    )

    /** Raw index of the TOOL RESULT carrying each planted fact, by construction of the fixture below. */
    def toolFactIndices: List[Int] = toolFacts.indices.toList.map(i => 3 + 3 * i)

    def toolHeavyConversation: Chunk[Message] =
        def payload(i: Int) = s"path=/srv/ledger/replica-$i.yaml\nstatus=ok\nchecksum=8f2a\nbytes=40960\n" * 40
        val head            = Chunk[Message](tok(sm("you are a systems assistant"), 40), tok(um("audit the ledger replicas"), 60))
        val body = (0 until 16).flatMap { i =>
            List(
                tok(am(s"reading replica $i", call(s"c$i", "read_file", s"""{"path":"/srv/ledger/replica-$i.yaml"}""")), 90),
                // The facts are planted INSIDE tool payloads, which is where facts live in this workload.
                tok(
                    tm(s"c$i", payload(i) + toolFacts.zipWithIndex.collectFirst { case ((_, t), k) if k == i => s"\n$t\n" }.getOrElse("")),
                    1400
                ),
                tok(um(s"and replica ${i + 1}?"), 40)
            )
        }
        head.concat(Chunk.from(body))
    end toolHeavyConversation

    "the contract holds on a TOOL-RESULT-HEAVY corpus, which is what the workload actually looks like" in {
        val raw       = toolHeavyConversation
        val toolToks  = raw.filter(_.isInstanceOf[ToolMessage]).foldLeft(0)((a, m) => a + toks(Chunk(m)))
        val totalToks = toks(raw)
        // (1) REGIME: the corpus must actually BE tool-heavy. Sized-down payloads would make this prose
        // wearing a ToolMessage costume, and every assertion below would be measuring prose again.
        assert(
            toolToks * 10 > totalToks * 8,
            s"REGIME: tool results must dominate the corpus, got $toolToks of $totalToks tokens"
        )
        servedVia(Compactor.init, raw).map { view =>
            val g = gradeWith(toolFacts, toolFactIndices, raw, view, axisOf(cfg).hard)
            // (2) The existing contract, unchanged. Same assertions as the prose case, no exemptions.
            assert(g.fitsLimit, s"the served view must fit the hard limit: ${g.servedTokens} > ${axisOf(cfg).hard}")
            assert(g.noInvention, "a compactor may drop content but must never invent an identifier")
            assert(
                g.accountability == 1.0,
                s"every dropped region must be accounted for by a stand-in, got ${g.accounted}/${g.ofTotal}"
            )
            // (3) FUSION INTEGRITY, the assertion unique to this corpus and the one with teeth: `group`
            // fuses an assistant message with its answering tool results at REGION grain, while `project`
            // renders per MESSAGE, so the two can disagree. A view that kept a call while demoting its
            // answer would show the model a question with no reply, and the reverse would show a reply
            // with nothing it answers.
            val servedCallIds = view.collect { case AssistantMessage(_, calls, _, _) => calls.map(_.id) }.flatten.toSet
            val servedToolIds = view.collect { case ToolMessage(id, _, _, _) => id }.toSet
            assert(
                servedCallIds == servedToolIds,
                s"an assistant call and its answering tool result must share a level: calls served without " +
                    s"answers ${servedCallIds -- servedToolIds}, answers served without calls ${servedToolIds -- servedCallIds}"
            )
        }
    }

    "the pass-through compactor retains everything, which calibrates the upper bound" in {
        val raw = conversation
        servedVia(Compactor.none, raw).map { view =>
            val g = grade(raw, view, Int.MaxValue)
            assert(g.dropped == 0, s"serving raw must drop nothing, got ${g.dropped} dropped")
        }
    }

    "the metric SEPARATES a lossy compactor from the shipped one, or it is not a metric" in {
        // The calibration that makes the battery meaningful. Tail-only truncation drops the oldest content,
        // where the facts are planted, so it must score strictly worse. A battery that grades them the same
        // is measuring nothing, and would pass a compactor that throws the conversation away.
        val raw = conversation
        servedVia(Compactor.init, raw).map { shipped =>
            servedVia(TailOnly(axisOf(cfg).low), raw).map { lossy =>
                val gs = grade(raw, shipped, axisOf(cfg).hard)
                val gl = grade(raw, lossy, axisOf(cfg).hard)
                println(
                    s"[diag] raw=${raw.size}msgs/${toks(raw)}tok shipped=${shipped.size}msgs/${toks(shipped)}tok lossy=${lossy.size}msgs/${toks(lossy)}tok"
                )
                println(s"[diag] shipped present=${facts.count((_, t) =>
                        shipped.exists(_.content.contains(t))
                    )} lossy present=${facts.count((_, t) => lossy.exists(_.content.contains(t)))}")
                // Tail-only truncation drops the oldest content and leaves NOTHING in its place: the
                // caller cannot tell anything is missing. The shipped compactor drops content too, but
                // every drop leaves a stand-in. That difference is the contract, and it is observable
                // without a model.
                assert(gl.dropped > 0, s"REGIME: the lossy reference must actually drop facts, got ${gl.dropped}")
                assert(
                    gs.accountability > gl.accountability,
                    s"the battery must separate them on accountability: shipped=${gs.accountability} tail-only=${gl.accountability}"
                )
            }
        }
    }

    "the battery REJECTS a compactor that discards the conversation behind one marker" in {
        // The negative calibration. Without it the accountability metric can drift back to a form that
        // credits everything on the presence of a single stand-in, which is how it was first written.
        val raw = conversation
        servedVia(SilentLoss(), raw).map { view =>
            val g = grade(raw, view, axisOf(cfg).hard)
            assert(
                g.accountability < 1.0,
                s"a compactor that drops everything behind one marker must NOT be fully accountable, got ${g.accountability}"
            )
        }
    }

    /** A corpus where LIVENESS HAS SOMETHING TO FOLLOW, which the prose fixture above does not provide.
      *
      * `conversation` plants each fact once and never mentions it again, so under the design's own theory
      * ("which regions the conversation is still using", Ranking.scala) all its facts are equally dead and
      * all of them should demote. Whatever survives there survives for a positional reason, and measuring
      * retention on it grades the fixture. It is worse than neutral: every message there is built around
      * one identical boilerplate string, so 22 of 39 regions share the same content terms, every content
      * reference edge targets the term's FIRST occurrence, and the region carrying it collects an
      * enormous in-degree it did nothing to earn.
      *
      * So this corpus does two things differently. Every filler message draws a distinct vocabulary and a
      * distinct sentence frame, which SHRINKS the funnel without eliminating it: three attempts measured
      * 243, then 195, then about 20 edges on the watched region. (Varying only interpolated numbers left
      * the lexical skeleton shared across 32 of 36 regions; varying every distinctive word but keeping
      * one frame still shared that frame's bigrams, which the tier admits.) Residual structure remains
      * and is load-bearing: the frames recur about three times each, the un-varied scaffolds
      * ("decision record", "record acknowledged", "answer") recur throughout, and `extractTokens` treats
      * a trailing period as structural, so every sentence-final frame word mints an identifier. Do not
      * read this corpus as funnel-free; read it as funnel-reduced. And two of
      * the three planted facts are REFERENCED by later turns through the camelCase
      * identifier they introduced, while the third is a CONTROL that is never mentioned again. That makes
      * verbatim retention a test of the design's actual claim (liveness follows use) instead of a test of
      * where a fact happened to sit: the referenced facts have a reason to be kept, the control has none,
      * and a policy that cannot tell them apart is not doing what this one says it does.
      *
      * The later turns cite the identifier WITHOUT repeating the fact's sentence, so a reference turn
      * sitting in the tail can never satisfy the grader on the fact's behalf.
      */
    val usedFacts: List[(String, String)] = List(
        "referenced-value"  -> "the ingestBatchSize is 384 records",
        "referenced-policy" -> "writes to prodBucket are forbidden in every environment",
        "control-unused"    -> "the archive index shard is vaultNine"
    )

    /** Raw index of the message carrying each fact in [[usedConversation]], by construction. */
    def usedFactIndices: List[Int] = List(2, 4, 6)

    /** Distinct vocabulary per message, so no term recurs across regions and the content tier finds no
      * recurring vocabulary to funnel. Ordinary connectives are shared, as they are in any prose, but
      * they sit far below the rarity floor and are never admitted, which is exactly how real
      * conversations behave: the distinctive words differ turn to turn.
      */
    /** Distinct sentence FRAMES, so no bigram recurs across every message either. */
    private val fillerFrames: Vector[(String, String, String) => String] = Vector(
        (s0, v, o) => s"the $s0 $v its $o. ",
        (s0, v, o) => s"whenever a $s0 $v, its $o follow immediately. ",
        (s0, v, o) => s"our $o were $v by that $s0 during review. ",
        (s0, v, o) => s"nobody expected a $s0 to $v so many $o. ",
        (s0, v, o) => s"$o accumulate once the $s0 $v without supervision. ",
        (s0, v, o) => s"after the $s0 $v, we counted the remaining $o. ",
        (s0, v, o) => s"a $s0 that $v leaves its $o inconsistent. ",
        (s0, v, o) => s"we documented how the $s0 $v and where its $o land. ",
        (s0, v, o) => s"$o outlive any $s0 which $v carelessly. ",
        (s0, v, o) => s"should a $s0 $v, expect its $o to drift. ",
        (s0, v, o) => s"the review noted that this $s0 $v its $o twice. ",
        (s0, v, o) => s"between runs a $s0 $v whatever $o remain. "
    )

    private val fillerSubjects: Vector[String] = Vector(
        "quorum",
        "ledger",
        "beacon",
        "harvester",
        "spindle",
        "trellis",
        "cistern",
        "lantern",
        "foundry",
        "kestrel",
        "marlin",
        "pelican",
        "obsidian",
        "juniper",
        "cypress",
        "sequoia",
        "meridian",
        "zenith",
        "estuary",
        "alcove",
        "brambles",
        "cobalt",
        "dovetail",
        "ember",
        "fathom",
        "granite",
        "hollow",
        "inlet",
        "jetty",
        "kiln",
        "lattice",
        "mosaic",
        "nectar",
        "orchard",
        "pumice",
        "quiver",
        "ravine",
        "saffron",
        "thicket",
        "umber",
        "verdant",
        "wharf",
        "yarrow",
        "zephyr"
    )
    private val fillerVerbs: Vector[String] = Vector(
        "throttles",
        "reconciles",
        "partitions",
        "escalates",
        "quiesces",
        "amortises",
        "coalesces",
        "serialises",
        "rebalances",
        "annotates",
        "deduplicates",
        "backfills",
        "hydrates",
        "prunes",
        "shards",
        "vacuums",
        "compacts",
        "replicates",
        "quarantines",
        "arbitrates",
        "interleaves",
        "materialises",
        "checkpoints",
        "gossips",
        "leases",
        "preempts",
        "requeues",
        "stripes",
        "transposes",
        "unwinds",
        "vectorises",
        "watermarks",
        "yields",
        "zips",
        "buffers",
        "collates",
        "dispatches",
        "encodes",
        "filters",
        "gathers",
        "hashes",
        "indexes",
        "joins",
        "keys"
    )
    private val fillerObjects: Vector[String] = Vector(
        "manifests",
        "checkpoints",
        "digests",
        "envelopes",
        "fragments",
        "gauges",
        "headers",
        "intervals",
        "journals",
        "keyrings",
        "lattices",
        "matrices",
        "notaries",
        "octets",
        "payloads",
        "quotas",
        "registers",
        "segments",
        "tallies",
        "unions",
        "vectors",
        "windows",
        "xrefs",
        "yardsticks",
        "zones",
        "anchors",
        "buckets",
        "cursors",
        "deltas",
        "epochs",
        "frames",
        "grids",
        "handles",
        "ingots",
        "jars",
        "kernels",
        "lanes",
        "monoids",
        "nodes",
        "offsets",
        "prisms",
        "queues",
        "rails",
        "sinks"
    )

    def usedConversation: Chunk[Message] =
        // Each message draws a distinct subject/verb/object triple, so its distinctive terms appear in
        // exactly one region, which shrinks but does not remove the funnel (the frames themselves recur,
        // and sentence-final words mint period identifiers). No number is interpolated into the
        // text (an earlier version's `reviewer ${i * 3}` minted tokens like "300" that collided with
        // other messages' indices and produced identifier edges the fixture never intended).
        var next = -1
        def filler(): String =
            next += 1
            val i = next % fillerSubjects.size
            // The FRAME varies too, not only the words in it. A fixed frame repeated across messages
            // shares its bigrams, and the content tier admits bigrams, so a constant frame funnels just
            // as a constant vocabulary does: the second attempt at this fixture varied every distinctive
            // word and still left region 2 with 195 reference edges.
            val f = fillerFrames(next % fillerFrames.size)
            f(fillerSubjects(i), fillerVerbs(i), fillerObjects(i)) * 3
        end filler
        val head = Chunk[Message](tok(sm("you are a systems assistant"), 40), tok(um("we are designing a storage layer"), 60))
        val planted = usedFacts.zipWithIndex.flatMap { case ((_, text), i) =>
            List(
                tok(um(s"decision record $i\n${filler()}\n$text.\n${filler()}"), 900),
                tok(am(s"record $i acknowledged\n${filler()}"), 700)
            )
        }
        val bulk = (0 until 12).flatMap(i =>
            List(tok(um(s"question $i about ${filler()}"), 300), tok(am(s"answer $i\n${filler()}"), 700))
        )
        // The use signal: recent turns that cite the identifiers introduced at indices 2 and 4, never the
        // control at index 6, and never the fact sentences themselves.
        val refs = Chunk[Message](
            tok(um("raise ingestBatchSize before the next ingest run and tell me what breaks"), 300),
            tok(am(s"raising ingestBatchSize affects the ingest pipeline\n${filler()}"), 700),
            tok(um("does prodBucket still reject direct writes from the batch job"), 300),
            tok(am(s"prodBucket rejects them at the policy layer\n${filler()}"), 700)
        )
        head.concat(Chunk.from(planted)).concat(Chunk.from(bulk)).concat(refs)
    end usedConversation

    "the introducer funnel, measured on the workload's own shape" in {
        // The ranking's content-reference tier mints an edge from every later use of a term to the term's
        // FIRST occurrence (`contentIntro` in the graph derivation). That is a deliberate choice, and on
        // prose it is mild. On this suite's own tool-heavy fixture it is not mild, and the fixture is the
        // one the file itself argues is representative: the note above it records that a research agent's
        // context is 96.3% tool results against 1.7% reasoning, and that every other fixture here is prose.
        //
        // Repeated-format payloads are what tool results ARE. Each read here returns the same key set
        // (`path=`, `status=`, `checksum=`, `bytes=`), so those terms recur in nearly every region, clear
        // the rarity floor, sit under the document-frequency cutoff, and every one of them points at
        // whichever region read first. The result is a hub with an in-degree far beyond anything the prose
        // corpora produce, on a region whose only distinction is being early.
        //
        // This is measured rather than argued because a number in a document that no test can
        // reproduce is worth nothing. The assertions below are deliberately loose on magnitude and strict
        // on shape: a hub of at least a hundred edges, landing in the first handful of regions, and an
        // order of magnitude above what the frame-varied prose corpus produces. Exact counts move with any
        // fixture edit; the shape is the finding.
        def hubOf(raw0: Chunk[Message]): (Int, Int, Int) =
            // Scored at the boundary's real input: the serving seam appends its own turn before rendering,
            // and leaving it out changes the seed allocation enough to explain the wrong render.
            val raw             = raw0.append(tok(um("continue"), 20))
            val d               = Compactor.internal.Default(Compactor.Tuning(), Compactor.internal.Calibration())
            val units           = d.group(raw)
            val graph           = d.deriveGraph(d.tokenIndex(units, raw), Dict.empty[Int, Int], Chunk.empty)
            val ids             = units.toList.map(_.id)
            val deg             = ids.map(id => id -> ids.map(f => graph.edges.get(f).getOrElse(Chunk.empty).count(_.target == id)).sum)
            val (hubId, hubDeg) = deg.maxBy(_._2)
            (hubId, hubDeg, units.size)
        end hubOf

        val (toolHub, toolDeg, toolRegions)    = hubOf(toolHeavyConversation)
        val (proseHub, proseDeg, proseRegions) = hubOf(usedConversation)
        println(
            s"[funnel] toolHeavy regions=$toolRegions hub=region$toolHub inEdges=$toolDeg | " +
                s"prose regions=$proseRegions hub=region$proseHub inEdges=$proseDeg"
        )

        assert(toolRegions > 10, s"REGIME: the tool fixture must have enough regions to concentrate, got $toolRegions")
        assert(
            toolDeg >= 100,
            s"RECORDED: repeated-format tool payloads concentrate the content tier onto one early region, " +
                s"got $toolDeg in-edges on region $toolHub"
        )
        assert(
            toolHub <= 4,
            s"RECORDED: and the hub is whichever region read FIRST, not one that earned it, got region $toolHub"
        )
        assert(
            toolDeg > 4 * proseDeg,
            s"RECORDED: the workload-representative shape funnels far harder than the prose corpora that " +
                s"every other number here is measured on, $toolDeg against $proseDeg"
        )
    }

    "the floor measurement: the apparatus against brutal truncation, where liveness has something to follow" in {
        // THE MEASUREMENT NOBODY HAD RUN. `CompactorBaselines` was written to carry it ("the two reference
        // strategies the default compactor is measured against", and of the floor: "Any mechanism that
        // cannot beat brutal truncation on quality has no reason to exist"), and then no test ever
        // referenced either arm. The floor existed as a class and never as a number.
        //
        // The first version of this measurement ran on `conversation` and read 1/4 against 0/4 as a win
        // for the apparatus. It was not one. The single survivor was the region carrying the FIRST
        // occurrence of that fixture's repeated boilerplate, which collected 174 incoming reference edges
        // against 1 for its peers; changing only the filler text, leaving every fact and position alone,
        // moved the score to 2/4 through a different accident. A metric that moves when you edit text it
        // does not measure was measuring the fixture. Hence [[usedConversation]].
        //
        // WHAT IS AND IS NOT ASSERTED HERE. Accountability is deliberately NOT asserted as a difference
        // between the arms: the shipped compactor emits an origin on every marker by construction and
        // Truncation omits one by declared design, so "1.00 against 0.00" restates the two class
        // definitions rather than discovering anything, and the same separation is already asserted
        // against `TailOnly` in the calibration test below. It is printed, not graded. What IS graded is
        // the design's own claim: content the conversation is still using stays verbatim, and a policy
        // that keeps only the recent tail loses it.
        //
        // The floor is given exactly the served size the shipped arm chose, so neither arm can win by
        // serving more.
        val raw = usedConversation
        assert(toks(raw) > window / 2, s"REGIME: the fixture must be large enough to compact, got ${toks(raw)}")
        servedVia(Compactor.init, raw).map { shippedView =>
            val shipped = gradeWith(usedFacts, usedFactIndices, raw, shippedView, axisOf(cfg).hard)
            servedVia(CompactorBaselines.Truncation(shipped.servedTokens), raw).map { floorView =>
                val floor                  = gradeWith(usedFacts, usedFactIndices, raw, floorView, axisOf(cfg).hard)
                def present(g: Grade): Int = g.ofTotal - g.dropped
                val joined                 = shippedView.foldLeft("")((a, m) => a + m.content + "\n")
                val keptReferenced         = usedFacts.take(2).count((_, t) => joined.contains(t))
                val keptControl            = if joined.contains(usedFacts(2)._2) then 1 else 0

                println(
                    s"[floor] shipped served=${shipped.servedTokens} present=${present(shipped)}/${shipped.ofTotal} " +
                        f"accounted=${shipped.accounted}/${shipped.ofTotal} (${shipped.accountability}%.2f) " +
                        s"referenced=$keptReferenced/2 control=$keptControl/1 | " +
                        s"truncation served=${floor.servedTokens} present=${present(floor)}/${floor.ofTotal} " +
                        f"accounted=${floor.accounted}/${floor.ofTotal} (${floor.accountability}%.2f)"
                )

                assert(
                    floor.servedTokens < toks(raw),
                    s"REGIME: the floor must have dropped content, served ${floor.servedTokens} of ${toks(raw)}"
                )
                assert(shipped.fitsLimit && floor.fitsLimit, "REGIME: both arms must fit the hard limit to be comparable")
                assert(shipped.noInvention && floor.noInvention, "neither arm may invent an identifier")

                // THE VOID RULE, in the only form that can fail. The first version tested retention OR
                // accountability, and since the accountability difference is entailed by the two class
                // definitions, the disjunction was unfalsifiable: it could never fire whatever the
                // retention numbers did. `CompactorBaselines` states the rule about QUALITY, so the tie
                // test is on retention alone.
                assert(
                    present(shipped) != present(floor),
                    s"VOID: truncation tied the apparatus on verbatim retention (${present(floor)}/${floor.ofTotal} each). " +
                        "Per CompactorBaselines, a scenario truncation ties never exercised memory, so this is " +
                        "not a win and the fixture must be strengthened before it is read as one"
                )
                // WHAT THE APPARATUS DID, and what the mechanism is NOT. Three explanations were tried
                // here and two were refuted by measurement; what follows is only the part that survived.
                //
                // It keeps ONE of the two facts later turns still reference, drops the control, and the
                // floor keeps none. The casualty is span [4,6), carrying `prodBucket`. It is not a budget
                // casualty: pass two iterates only spans already below the floor (Projection.scala), so a
                // floor-clearing span is unreachable by it, and the counterfactual pinned view sits far
                // under the hard limit and would simply have been served. It is a pass-one relevance
                // decision, the span having scored under the keep floor.
                //
                // It is also NOT citation dilution, which was the second wrong answer. Out-edge
                // normalization in `score` is real (a region's liveness leaves it through out-edges
                // divided by their total weight, so admitting content edges shrinks every co-resident
                // edge's share, adjacency and identifiers alike), and that coupling is structural: the
                // tier can only REDISTRIBUTE retention, and redistribution has losers. But decomposed on
                // this corpus it is the minority term, and at the nearer citing region the tier
                // CONCENTRATES toward the cited span rather than diluting it. Removing only the
                // citing-turn content edges leaves the span above the floor.
                //
                // What actually moves it is transit mass rerouted at NON-CITING regions. The residual
                // template scaffold ("decision record", "record acknowledged", "answer") mints content
                // edges on the regions along the adjacency spine, so the cascade that would have reached
                // span [4,6) is bled at successive hops and lands elsewhere.
                //
                // The exhibit that settles it, measured at the boundary's real input (37 regions, keep
                // floor 0.03649), is a span nobody was watching:
                //
                //   span [8,10), pure filler, never referenced   ON 0.03669/0.04001 PINNED   OFF demoted
                //   span [4,6),  cited by a recent turn          ON 0.02918 demoted          OFF 0.05486 PINNED
                //
                // With the tier on, this corpus pins filler and demotes a citation. That is funnel
                // redistribution, not dilution: the largest in-degree here belongs to region 9 (27 edges),
                // ahead of both watched spans.
                //
                // Asserted below is only what is deterministic: dead content demotes, the floor loses what
                // it drops, the apparatus keeps at least one live fact, and the two arms differ. The
                // ablation is a REGRESSION PIN on a measured difference, not a verdict on the tier: the
                // arms are not size-matched, and the ablated arm's own retention leans on the same
                // residual template structure, so this measures the coupling's existence on a synthetic
                // corpus and not the tier's cost on production-shaped input.
                assert(
                    keptControl == 0,
                    s"the control fact is never referenced again, so it must NOT be retained verbatim, got $keptControl"
                )
                assert(
                    keptReferenced >= 1,
                    s"the apparatus must keep at least one still-referenced fact verbatim, kept $keptReferenced/2"
                )
                assert(
                    present(floor) == 0,
                    s"REGIME: the floor must lose them, or the two policies are not being told apart, got ${present(floor)}"
                )
                // THE ABLATION, which is the finding rather than a footnote: with the content-reference
                // tier off and nothing else changed, BOTH still-referenced facts stay verbatim. If this
                // ever stops holding, either the dilution was fixed (good, and this assertion is how you
                // find out) or the corpus drifted; either way it must be re-read, not adjusted.
                val ablated = Compactor.Tuning(mechanisms = Compactor.Mechanism.all - Compactor.Mechanism.ContentReferences)
                servedVia(Compactor.init(ablated), raw).map { ablatedView =>
                    val joinedAblated = ablatedView.foldLeft("")((a, m) => a + m.content + "\n")
                    val ablatedKept   = usedFacts.take(2).count((_, t) => joinedAblated.contains(t))
                    println(s"[floor] contentRefsOFF referenced=$ablatedKept/2")
                    assert(
                        ablatedKept == 2,
                        s"RECORDED: the two arms differ on this corpus, the ablated one keeping both " +
                            s"still-referenced facts; kept $ablatedKept/2. This pins a measured difference, " +
                            "not a verdict on the tier: see the mechanism note above for why the corpus's " +
                            "residual template structure is load-bearing in BOTH arms"
                    )
                    assert(
                        ablatedKept > keptReferenced,
                        s"RECORDED: the shipped arm retains fewer here, $keptReferenced against $ablatedKept"
                    )
                }
            }
        }
    }

    "a compactor with EVERY mechanism disabled still satisfies the contract" in {
        // The safety property of the disable surface itself. Each lever is guarded individually elsewhere;
        // this asks the different question of whether the surface can be used to build something UNSAFE.
        // With every mechanism off there is no analysis, no summary fill, no speculative preparation, no
        // supersession from either source, no repointing, no plain-word reference tier and no recall, so
        // the compactor is reduced to structural ranking and presentation alone. It must still fit the
        // limit and still account for what it drops, or the surface hands a caller a broken compactor
        // through an ordinary combination of its own options.
        val raw = conversation
        servedVia(Compactor.init(Compactor.Tuning(mechanisms = Compactor.Mechanism.none)), raw).map { view =>
            val g = grade(raw, view, axisOf(cfg).hard)
            assert(g.fitsLimit, s"an all-off compactor must still fit the hard limit: ${g.servedTokens} > ${axisOf(cfg).hard}")
            assert(g.noInvention, "and must never invent an identifier")
            assert(
                g.accountability == 1.0,
                s"and must still account for every region it drops, got ${g.accounted}/${g.ofTotal}"
            )
        }
    }

    "cost is graded on prefix stability, which is what caching prices" in {
        // Prompt caching is present, functional and transparent, so the cost of a turn is the suffix after
        // the longest prefix it shares with the previous turn. This grades that WITHOUT knowing how any
        // compactor works: replay the session, diff consecutive views, charge the remainder.
        val raw   = conversation
        val steps = (6 to raw.size by 4).toList
        def replay(c: Compactor[Any])(using Frame): List[Chunk[Message]] < (Async & Abort[Any] & Scope) =
            Kyo.foreach(steps)(n => servedVia(c, raw.take(n)))
        def equivalents(views: List[Chunk[Message]]): Double =
            views match
                case Nil => 0.0
                case first :: rest =>
                    rest.foldLeft((toks(first).toDouble, first)) { case ((acc, prev), cur) =>
                        val shared = prev.toList.zip(cur.toList).takeWhile((a, b) => a.content == b.content && a.role == b.role).size
                        val cached = cur.take(shared).foldLeft(0)((a, m) => a + Compactor.internal.stampedTokens(m))
                        val fresh  = cur.drop(shared).foldLeft(0)((a, m) => a + Compactor.internal.stampedTokens(m))
                        (acc + fresh + 0.1 * cached, cur)
                    }._1
        replay(Compactor.init).map { shipped =>
            replay(Compactor.none).map { raw0 =>
                val cs = equivalents(shipped)
                val cn = equivalents(raw0)
                assert(cs > 0 && cn > 0, "both strategies must have a measurable cost")
                // Reported rather than asserted in a direction: which wins depends on the regime, and
                // pinning a direction here would bake in a conclusion this battery cannot justify.
                println(f"[conformance] equivalents: shipped=$cs%.0f pass-through=$cn%.0f ratio=${cs / cn}%.2f")
            }
        }
    }

end CompactorConformanceTest
