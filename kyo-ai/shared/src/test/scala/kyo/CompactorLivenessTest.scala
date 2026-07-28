package kyo

import Compactor.internal.*
import kyo.ai.*
import kyo.ai.Compaction.*
import kyo.ai.Context.*

/** Does being REFERENCED change what survives compaction?
  *
  * Named for LIVENESS, the ranking property it measures, and deliberately not for retention: `raw`
  * retention is the eviction backstop in `kyo.ai.compactor.Retention`, a different mechanism entirely, and a test
  * file named after it would point a reader at the wrong source.
  *
  * The deterministic half of a published probe: a research agent's compaction retained 3 of 3 high-level
  * facts and 0 of 3 obscure specifics. That number grades a summarizer. The half that is ours to grade
  * happens BEFORE any summarizer runs, in the tier the compactor assigns, and it is the claim the whole
  * ranking design rests on: content other turns refer back to stays available, content nobody mentions
  * again does not.
  *
  * The fixture is a MATCHED PAIR. Two journal turns at the same depth, byte-equal filler, equal token
  * stamps, each introducing one distinct identifier. Later turns name one of them three times and never
  * name the other. Position, size, recency and span grain are therefore all controlled, and the only
  * surviving difference between the two is the reference edge. A test that planted them at different
  * depths would be measuring recency and would pass on a compactor that has no notion of a reference at
  * all.
  */
class CompactorLivenessTest extends kyo.test.Test[Any]:

    def um(s: String): UserMessage       = UserMessage(s, Absent)
    def sm(s: String): SystemMessage     = SystemMessage(s)
    def am(s: String): AssistantMessage  = AssistantMessage(s)
    def tok(m: Message, n: Int): Message = stamp(m, TokenStamp("t", n))
    def toks(v: Chunk[Message]): Int     = v.foldLeft(0)((a, m) => a + stampedTokens(m))

    val window      = 16384
    def cfg: Config = Config.OpenAI.default.apiKey("k").model(Config.OpenAI, "m", window)

    val referenced = "vault-nine"
    val orphan     = "harbor-four"

    /** The matched pair, with `hot` named by later turns and `cold` never named again.
      *
      * `hot` and `cold` are passed in rather than fixed so the symmetry leg can swap them: if separation
      * follows the identifier's POSITION rather than the references to it, the swap exposes that.
      */
    def conversation(hot: String, cold: String): Chunk[Message] =
        val filler = "the team records context for each decision so a later reader can reconstruct it. " * 8
        val head   = Chunk[Message](tok(sm("you are a systems assistant"), 40), tok(um("we are designing a storage layer"), 60))
        // Adjacent, byte-equal but for the identifier, and identically stamped.
        val pair = Chunk[Message](
            tok(um(s"journal entry\n$filler\nthe archive index shard is $hot.\n$filler"), 900),
            tok(am(s"entry acknowledged\n$filler"), 700),
            tok(um(s"journal entry\n$filler\nthe archive index shard is $cold.\n$filler"), 900),
            tok(am(s"entry acknowledged\n$filler"), 700)
        )
        // Later turns name ONE of the pair, three times, and never the other.
        val referencing = Chunk.from((0 until 3).flatMap(i =>
            List(
                tok(um(s"follow-up $i: what did we decide about $hot?"), 300),
                tok(am(s"answer $i about $hot\n$filler"), 700)
            )
        ))
        val bulk = Chunk.from((0 until 12).flatMap(i =>
            List(tok(um(s"unrelated question $i about system design"), 300), tok(am(s"answer $i\n$filler"), 700))
        ))
        head.concat(pair).concat(referencing).concat(bulk)
    end conversation

    /** The raw index of the message carrying `id`, which is the region id the ladder assigns a level to. */
    def indexOf(raw: Chunk[Message], id: String): Int =
        raw.zipWithIndex.collectFirst { case (m, i) if m.content.contains(s"index shard is $id") => i }.getOrElse(-1)

    /** The served level of the fact at `idx`, read from the VIEW alone.
      *
      * Deliberately mechanism-agnostic: Verbatim is "the text is still there", Pointer is "a stand-in
      * covers it and carries no body", and anything between is Summary or Terse. Reading a level off
      * `Compactor.internal` instead would grade the implementation's bookkeeping rather than what the
      * model actually receives.
      */
    enum Served derives CanEqual:
        case Verbatim, Reduced, Pointer, Gone

    def servedLevel(view: Chunk[Message], raw: Chunk[Message], idx: Int, id: String): Served =
        val text     = raw(idx).content
        val standIn  = view.filter(_.origin.exists(o => idx >= o.start && idx < o.end))
        val verbatim = view.exists(_.content == text)
        if verbatim then Served.Verbatim
        else if standIn.isEmpty then Served.Gone
        else if standIn.exists(_.content.trim.nonEmpty) then Served.Reduced
        else Served.Pointer
        end if
    end servedLevel

    def rank(s: Served): Int = s match
        case Served.Verbatim => 3
        case Served.Reduced  => 2
        case Served.Pointer  => 1
        case Served.Gone     => 0

    def genBody(v: String): String =
        val envelope = Json.encode(s"""{"resultValue":${Json.encode(v)}}""")
        s"""{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"r1","type":"function","function":{"name":"result_tool","arguments":$envelope}}]}}]}"""

    /** Drives the real serving seam, because the levels a boundary assigns depend on preparation having
      * run; calling render in isolation grades a harness with nothing staged.
      */
    def servedVia(c: Compactor[Any], raw: Chunk[Message])(using Frame): Chunk[Message] < (Async & Abort[Any] & Scope) =
        TestCompletionServer.run { server =>
            Kyo.foreachDiscard(0 until 60)(i => server.enqueueBody(genBody(s"summary $i: decisions and open threads preserved")))
                .andThen {
                    LLM.run(cfg.apiUrl(server.baseUrl)) {
                        AI.init.map(_.enable(c)).map { ai =>
                            ai.setContext(Context(raw)).andThen(ai.userMessage("continue"))
                                .andThen(ai.gen[String]).handle(Abort.run[Any]).andThen(ai.context).map(_.compacted)
                        }
                    }
                }
        }

    "a referenced fact outranks an identical unreferenced one at the same depth" in {
        val raw = conversation(referenced, orphan)
        assert(toks(raw) > Compactor.internal.axis(Compactor.Tuning(), cfg).high, "REGIME: the fixture must cross the trigger")
        servedVia(Compactor.init, raw).map { view =>
            // REGIME: something was actually demoted. Without this the test passes on a compactor that
            // pins everything, which separates nothing and would look identical to success.
            assert(view.exists(_.origin.isDefined), "REGIME: at least one span must be demoted, or there is no ladder to read")
            assert(toks(view) < toks(raw), s"REGIME: the view must be smaller than raw, ${toks(view)} vs ${toks(raw)}")

            val hotLevel  = servedLevel(view, raw, indexOf(raw, referenced), referenced)
            val coldLevel = servedLevel(view, raw, indexOf(raw, orphan), orphan)
            println(s"[retention] referenced=$hotLevel orphan=$coldLevel")
            assert(
                rank(hotLevel) > rank(coldLevel),
                s"the referenced fact must be served at a higher level than its matched twin: " +
                    s"$referenced=$hotLevel $orphan=$coldLevel"
            )
        }
    }

    "the separation follows the reference and not the position" in {
        // The symmetry leg. Swapping which identifier later turns name must invert the answer; if it does
        // not, the first test was measuring where the pair sits, and both facts sit at the same depth
        // precisely so that this can be told apart.
        val raw = conversation(orphan, referenced)
        servedVia(Compactor.init, raw).map { view =>
            assert(view.exists(_.origin.isDefined), "REGIME: at least one span must be demoted")
            val nowHot  = servedLevel(view, raw, indexOf(raw, orphan), orphan)
            val nowCold = servedLevel(view, raw, indexOf(raw, referenced), referenced)
            assert(
                rank(nowHot) > rank(nowCold),
                s"swapping which identifier is referenced must swap the levels: $orphan=$nowHot $referenced=$nowCold"
            )
        }
    }

    "with the reference edge silenced the pair is NOT separated, which is what proves references did it" in {
        // The negative calibration, and the one that decides whether this file measures anything. An
        // implementation that separated the pair through SPAN FORMATION, the two turns happening to fall
        // into different spans for a structural reason, would pass every assertion above. Zeroing the
        // reference edge classes removes the only channel by which being named can matter; if the
        // separation survives that, it never came from references.
        val raw = conversation(referenced, orphan)
        val blind = Compactor.internal.Default(
            Compactor.Tuning(),
            Calibration(referenceWeight = 0.0, contentReferenceWeight = 0.0)
        )
        servedVia(blind, raw).map { view =>
            val hotLevel  = servedLevel(view, raw, indexOf(raw, referenced), referenced)
            val coldLevel = servedLevel(view, raw, indexOf(raw, orphan), orphan)
            println(s"[retention-blind] referenced=$hotLevel orphan=$coldLevel")
            assert(
                rank(hotLevel) == rank(coldLevel),
                s"with references silenced the matched pair must land on the SAME level, or the separation " +
                    s"measured above came from something other than liveness: $referenced=$hotLevel $orphan=$coldLevel"
            )
        }
    }

end CompactorLivenessTest
