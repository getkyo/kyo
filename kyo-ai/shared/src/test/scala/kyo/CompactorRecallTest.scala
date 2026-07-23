package kyo

import Compactor.internal.*
import Tool.internal.RunOutcome
import kyo.ai.*
import kyo.ai.Context.*

/** Recall aspect of the default Compactor: verbatim role-tagged restoration bound to the calling
  * instance, the decaying-seed reinstatement, its decay and re-demotion, and the conditional clearing of a
  * reinstated recall exchange.
  */
class CompactorRecallTest extends kyo.test.Test[Any]:

    def um(s: String): UserMessage                             = UserMessage(s, Absent)
    def sm(s: String): SystemMessage                           = SystemMessage(s)
    def am(s: String, calls: Call*): AssistantMessage          = AssistantMessage(s, Chunk.from(calls))
    def tm(id: String, s: String): ToolMessage                 = ToolMessage(CallId(id), s)
    def call(id: String, fn: String, args: String): Call       = Call(CallId(id), fn, args)
    def cfg: Config                                            = Config.OpenAI.default.apiKey("k").model(Config.OpenAI, "m", 200000)
    def eps(a: Double, b: Double, tol: Double = 1e-9): Boolean = math.abs(a - b) < tol

    "recall(id) restores the covered region verbatim, role-tagged, own instance only" in {
        // The view carries a pointer marker for region 14 (origin start=14, end=17); raw holds the 3 originals.
        val head   = Chunk.from((0 until 14).map(i => am(s"m$i")))
        val region = Chunk[Message](am("ASSISTANT PAYLOAD"), tm("c1", "TOOL PAYLOAD"), um("USER PAYLOAD"))
        val raw    = head.concat(region)
        val marker = SystemMessage("[regions 14-16 compacted]", origin = Present(Context.Origin(14, 17, 17)))
        val ctx    = Context(raw, Chunk(marker))
        LLM.run(cfg) {
            AI.init.map { ai =>
                ai.setContext(ctx).andThen {
                    Default.recallTool(ai).infos.head.decodeAndRun("""{"id":14}""").map { r =>
                        assert(
                            r match
                                case RunOutcome.Ran(Result.Success(o)) =>
                                    o.contains("assistant: ASSISTANT PAYLOAD") &&
                                    o.contains("tool: TOOL PAYLOAD") &&
                                    o.contains("user: USER PAYLOAD")
                                case _ => false
                            ,
                            s"recall(14) returns the 3 raw messages each role-prefixed, byte-exact, from THIS instance's raw, got: $r"
                        )
                    }
                }
            }
        }
    }

    "a fresh recall reinstates the region verbatim at the next boundary" in {
        val raw   = Chunk.from((0 until 16).map(i => am(s"region $i")))
        val units = Default.group(raw)
        // recall(14) recorded stamped with the current boundary counter (5): decay exponent is 0 at this boundary.
        val withRecall   = Default.seedVector(units, raw, CompactionState(boundaryCounter = 5).withRecall(14))
        val baseline     = Default.seedVector(units, raw, CompactionState(boundaryCounter = 5))
        val contribution = withRecall.get(14).getOrElse(0.0) - baseline.get(14).getOrElse(0.0)
        assert(
            eps(contribution, recallSeedWeight * math.pow(recallDecay, 0)),
            s"region 14's seed gains recallSeedWeight*decay^0, got $contribution"
        )
        assert(contribution > keepBase, "the recall seed lifts region 14's liveness above the floored keep, reinstating it verbatim")
    }

    "the recall boost decays and the region re-demotes after interest cools" in {
        val raw   = Chunk.from((0 until 16).map(i => am(s"region $i")))
        val units = Default.group(raw)
        def contribAt(n: Int): Double =
            val state   = CompactionState(boundaryCounter = 5 + n, recalls = Chunk(RecallRecord(14, 5)))
            val withR   = Default.seedVector(units, raw, state)
            val without = Default.seedVector(units, raw, CompactionState(boundaryCounter = 5 + n))
            withR.get(14).getOrElse(0.0) - without.get(14).getOrElse(0.0)
        end contribAt
        assert(eps(contribAt(0), recallSeedWeight), "at the recall boundary the contribution is recallSeedWeight*decay^0")
        assert(eps(contribAt(1), recallSeedWeight * recallDecay), "one boundary later it is recallSeedWeight*decay^1")
        assert(eps(contribAt(4), recallSeedWeight * math.pow(recallDecay, 4)), "n boundaries later it is recallSeedWeight*decay^n")
        assert(contribAt(0) > contribAt(1) && contribAt(1) > contribAt(4), "the contribution decays monotonically toward 0")
        assert(contribAt(4) < keepBase, "once it falls below the floored keep, the region re-demotes (the decay replaces a promotion flag)")
    }

    "the recall exchange is cleared when reinstated, kept when pressure prevents it" in {
        // raw holds region 14 plus a tail recall exchange: an assistant recall call fused with its tool result.
        val head       = Chunk.from((0 until 14).map(i => am(s"m$i")))
        val region14   = am("REGION 14 CONTENT")
        val recallCall = am("recalling", call("rc1", "recall", """{"id":14}"""))
        val toolResult = tm("rc1", "REGION 14 CONTENT")
        val raw        = head.append(region14).append(recallCall).append(toolResult) // indices 14, 15, 16
        val state      = CompactionState().withRecall(14)
        // (a) low pressure: region 14 reinstated (absent from any demoted span) -> the exchange is cleared.
        val clearedA = Default.reinstatedRecallIndices(raw, Set.empty[Int], state)
        assert(
            clearedA.contains(15) && clearedA.contains(16),
            s"the recall call (15) and its answering tool result (16) are both cleared, got $clearedA"
        )
        // (b) high pressure: region 14 stays demoted -> the exchange is kept so the model never loses what it asked for.
        val clearedB = Default.reinstatedRecallIndices(raw, Set(14), state)
        assert(clearedB.isEmpty, "when region 14 remains demoted the tail recall copy is retained")
        // the RecallRecord lives in state and is untouched by clearing, so the decaying seed survives.
        assert(state.recalls.map(_.region).toList == List(14), "the recall record in state is untouched by view clearing")
    }

end CompactorRecallTest
