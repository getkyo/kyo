package kyo.ai.compactor

import kyo.*
import kyo.Compactor
import kyo.Compactor.Mechanism
import kyo.Compactor.Tuning
import kyo.ai.*
import kyo.ai.Compaction.*
import kyo.ai.Context.*
import scala.annotation.tailrec

/** Liveness ranking: which regions the conversation is still using. Builds the edge graph over regions (adjacency, structural and content references, the analysis layer's dependency and relatedness edges), seeds it with the shares that say where attention starts, and spreads that mass with Personalized PageRank. A score is a SHARE of one unit of liveness, which is why the keep floor is relative to the uniform share rather than absolute.
  */
private[kyo] trait Ranking:
    self: Compactor.internal.Default =>

    import Model.*
    // The same unqualified access to the policy and the measured constants the rest of the default has.
    import calibration.*
    import tuning.*

    // ---- key supersession (the tool's typed Supersession key, read through the Info closure, no cast) ----
    def superKeys(units: Chunk[Region], raw: Chunk[Message])(using Frame): Dict[Int, (String, Tool.Kind)] < LLM =
        Tool.internal.infos.map(infos => superKeysFrom(units, raw, infos))

    def supersession(units: Chunk[Region], keys: Dict[Int, (String, Tool.Kind)]): Dict[Int, Int] =
        if !mechanisms.contains(Mechanism.KeyedSupersession) then Dict.empty
        else keyedSupersession(units, keys)

    private def keyedSupersession(units: Chunk[Region], keys: Dict[Int, (String, Tool.Kind)]): Dict[Int, Int] =
        val (result, _) =
            units.sortBy(_.id).foldLeft((Dict.empty[Int, Int], Dict.empty[String, (Int, Tool.Kind)])) {
                case ((sup, last), u) =>
                    keys.get(u.id) match
                        case Absent => (sup, last)
                        case Present((k, curKind)) =>
                            last.get(k) match
                                case Present((prevId, prevKind)) =>
                                    val supersedes = curKind == Tool.Kind.Write || prevKind == Tool.Kind.Read
                                    val sup2       = if supersedes then sup.update(prevId, u.id) else sup
                                    (sup2, last.update(k, (u.id, curKind)))
                                case Absent => (sup, last.update(k, (u.id, curKind)))
            }
        result
    end keyedSupersession

    // ---- graph: structural Adjacency + Reference edges; the analysis pass's Dependency and
    // Relatedness edges merge in via `analyzed`, empty until then. Reference and analyzed edges
    // repoint through supersession so liveness accrues to current content. The identifier extractor
    // (extractTokens) requires interior signal, so sentence-initial capitalized words never mint
    // identifiers; the hub damping is the document-frequency cutoff.

    def tokenIndex(units: Chunk[Region], raw: Chunk[Message]): TokenIndex =
        val ordered = units.toList.sortBy(_.id)
        val perUnit: List[(Int, Set[String])] =
            ordered.map(u => (u.id, extractTokens(unitContent(u, raw)).toSet))
        val introducer = perUnit.foldLeft(Dict.empty[String, Int]) { case (idx, (id, toks)) =>
            toks.foldLeft(idx)((ix, t) => if ix.contains(t) then ix else ix.update(t, id))
        }
        val mentions = perUnit.foldLeft(Map.empty[String, Int]) { case (mc, (_, toks)) =>
            toks.foldLeft(mc)((m, t) => m.updated(t, m.getOrElse(t, 0) + 1))
        }
        val adj: List[(Int, Edge)] =
            ordered.sliding(2).toList.collect { case prev :: cur :: Nil =>
                (cur.id, Edge(prev.id, EdgeKind.Adjacency, adjacencyWeight))
            }
        val perUnitContent: List[(Int, Set[String])] =
            ordered.map(u => (u.id, contentTokens(unitContent(u, raw)).toSet))
        val contentDf = perUnitContent.foldLeft(Map.empty[String, Int]) { case (mc, (_, toks)) =>
            toks.foldLeft(mc)((m, t) => m.updated(t, m.getOrElse(t, 0) + 1))
        }
        val contentIntro = perUnitContent.foldLeft(Dict.empty[String, Int]) { case (idx, (id, toks)) =>
            toks.foldLeft(idx)((ix, t) => if ix.contains(t) then ix else ix.update(t, id))
        }
        // The cutoff is a FRACTION of the conversation, but it must also stay strictly below the
        // region count: a term present in EVERY region discriminates nothing (it is the
        // conversation's connective tissue, "the"/"is"), and at small region counts the fraction
        // alone rounds up to the whole conversation and would admit exactly those stopwords.
        val dfMax = math.min(ordered.size - 1, math.max(contentMinRegions, (contentDfCutoffFraction * ordered.size).toInt))
        TokenIndex(ordered, perUnit, introducer, mentions, adj, perUnitContent, contentDf, contentIntro, dfMax)
    end tokenIndex

    // The convenience form, extracting fresh. Every caller that derives ONE graph from a transcript
    // uses it; the boundary uses the canonical form below so its two derivations share one extraction.
    def deriveGraph(
        units: Chunk[Region],
        raw: Chunk[Message],
        superseded: Dict[Int, Int],
        analyzed: Chunk[(Int, Int, EdgeKind)] = Chunk.empty
    ): Graph =
        deriveGraph(tokenIndex(units, raw), superseded, analyzed)

    def deriveGraph(
        index: TokenIndex,
        superseded: Dict[Int, Int],
        analyzed: Chunk[(Int, Int, EdgeKind)]
    ): Graph =
        if index.ordered.isEmpty then Graph.empty
        else
            val ordered    = index.ordered
            val perUnit    = index.perUnit
            val introducer = index.introducer
            val mentions   = index.mentions
            val adj        = index.adjacency
            val ref: List[(Int, Edge)] =
                perUnit.flatMap { case (id, toks) =>
                    toks.toList.flatMap { t =>
                        introducer.get(t) match
                            case Present(intro) if intro != id =>
                                val target = repoint(intro, superseded)
                                if target == id then Nil
                                else
                                    val hub = 1.0 + math.log(1.0 + mentions.getOrElse(t, 1).toDouble)
                                    List((id, Edge(target, EdgeKind.Reference, referenceWeight / hub)))
                                end if
                            case _ => Nil
                    }
                }
            // second reference tier: bare-word / bigram co-references the identifier extractor misses.
            // A term is admitted only if it RECURS in >= contentMinRegions regions (a reference, not a
            // one-off) AND its document frequency is <= dfMax (not a ubiquitous common word: the design's
            // blessed-but-unimplemented DF cutoff). Edges are minted at contentReferenceWeight (< an
            // identifier's) and capped per region, so admitting bare words cannot flood or out-vote the
            // structural signal. A bigram survives even when its individual words are too common (high DF),
            // which is exactly how "retry budget"-class references get caught.
            val perUnitContent = index.perUnitContent
            val contentDf      = index.contentDf
            val contentIntro   = index.contentIntro
            val dfMax          = index.dfMax
            // Off, the whole admission scan is skipped rather than its edges being discounted to
            // nothing: zeroing the weight left the document frequencies, the rarity test and the
            // per-region cap all running, so the arm paid for production and silenced consumption.
            val contentRef: List[(Int, Edge)] =
                if !mechanisms.contains(Mechanism.ContentReferences) then Nil
                else
                    perUnitContent.flatMap { case (id, toks) =>
                        toks.toList.flatMap { t =>
                            val df = contentDf.getOrElse(t, 0)
                            if df < contentMinRegions || df > dfMax || !isDistinctive(t) then Nil
                            else
                                contentIntro.get(t) match
                                    case Present(intro) if intro != id =>
                                        val target = repoint(intro, superseded)
                                        if target == id then Nil
                                        else
                                            val hub = 1.0 + math.log(1.0 + df.toDouble)
                                            List((id, Edge(target, EdgeKind.Reference, contentReferenceWeight / hub)))
                                        end if
                                    case _ => Nil
                            end if
                        }.take(contentRefPerRegionCap)
                    }
            val semantic: List[(Int, Edge)] =
                analyzed.toList.collect {
                    case (from, target, EdgeKind.Dependency) =>
                        (from, Edge(repoint(target, superseded), EdgeKind.Dependency, dependencyWeight))
                    case (from, target, EdgeKind.Relatedness) =>
                        (from, Edge(repoint(target, superseded), EdgeKind.Relatedness, relatednessWeight))
                }
            val edges = (adj ++ ref ++ contentRef ++ semantic).foldLeft(Map.empty[Int, Chunk[Edge]]) { case (m, (from, e)) =>
                m.updated(from, m.getOrElse(from, Chunk.empty).append(e))
            }
            Graph(Dict.from(edges))
    end deriveGraph

    // ---- scoring (one-shot Personalized PageRank; supersession penalty applied outside) ----
    def score(units: Chunk[Region], graph: Graph, superseded: Dict[Int, Int], seed: Dict[Int, Double]): Dict[Int, Double] =
        if units.isEmpty then Dict.empty
        else
            val ids   = units.toList.map(_.id)
            val alpha = restartWeight
            val normEdges: List[(Int, Int, Double)] =
                ids.flatMap { id =>
                    val es  = graph.edges.get(id).getOrElse(Chunk.empty)
                    val sum = es.foldLeft(0.0)((a, e) => a + e.weight)
                    if sum <= 0.0 then Nil else es.toList.map(e => (id, e.target, e.weight / sum))
                }
            def seedOf(id: Int): Double = seed.get(id).getOrElse(0.0)
            @tailrec def iterate(r: Map[Int, Double], n: Int): Map[Int, Double] =
                if n <= 0 then r
                else
                    val base = ids.map(id => id -> alpha * seedOf(id)).toMap
                    val next = normEdges.foldLeft(base) { case (acc, (from, to, w)) =>
                        acc.updated(to, acc.getOrElse(to, 0.0) + (1.0 - alpha) * w * r.getOrElse(from, 0.0))
                    }
                    iterate(next, n - 1)
            val ranked    = iterate(ids.map(id => id -> seedOf(id)).toMap, pprIterations)
            val penalized = ranked.map { case (id, v) => id -> (if superseded.contains(id) then v * supersessionPenalty else v) }
            Dict.from(penalized)
    end score

    def seedVector(units: Chunk[Region], raw: Chunk[Message], state: Compaction.State): Dict[Int, Double] =
        if units.isEmpty then Dict.empty
        else
            val ordered  = units.toList.sortBy(_.id)
            val systemId = ordered.headOption.filter(u => isSystemHead(u, raw)).map(_.id)
            val userIds  = ordered.filter(u => hasUser(u, raw)).map(_.id)
            val taskId   = userIds.headOption
            val objId    = userIds.lastOption
            val unresIds = ordered.filter(_.unresolved).map(_.id)
            val tailIds  = tailUnits(units).toList.sorted
            val singles: List[(List[Int], Double)] =
                List(
                    (objId.toList, seedObjective),
                    (taskId.toList, seedTask),
                    (unresIds, seedUnresolved),
                    (systemId.toList, seedSystem)
                )
            val folded         = singles.foldLeft(0.0) { case (acc, (t, w)) => if t.isEmpty then acc + w else acc }
            val tailShare      = seedTail + folded
            val singleContribs = singles.flatMap { case (t, w) => if t.isEmpty then Nil else t.map(id => (id, w / t.size)) }
            val tailOrder      = tailIds.reverse
            val geo            = tailOrder.zipWithIndex.map { case (id, k) => (id, math.pow(0.5, k.toDouble)) }
            val geoSum         = geo.foldLeft(0.0)((a, g) => a + g._2)
            val tailContribs   = if geoSum <= 0.0 then Nil else geo.map { case (id, g) => (id, tailShare * g / geoSum) }
            // recall as a decaying seed: each recall record contributes to its region's seed
            // entry, decaying geometrically per boundary since the recall. The record lives in state,
            // never inferred from the view, so clearing the recall exchange never drops the signal.
            // Off, an already-recorded recall contributes nothing either: the tool is gone, so a
            // record can only be a leftover from a session that ran with Recall enabled, and reading
            // it would let a disabled mechanism keep steering liveness.
            val recallContribs =
                if !mechanisms.contains(Mechanism.Recall) then Chunk.empty
                else
                    state.recalls.map { r =>
                        (
                            r.region,
                            recallSeedWeight * math.pow(recallDecay, (state.boundaryCounter - r.boundaryStamp).toDouble)
                        )
                    }
            // automatic recall as a DAMPED decaying seed, sharing recall's decay constant and clock but
            // entering at a fraction of its weight, because a framework guess is weaker evidence than a
            // model's ask. Damping a geometrically decaying seed has an exact meaning: PRE-AGING. A record
            // at fraction f is indistinguishable, in seed space, from a tool record log2(1/f) boundaries
            // older, at every region count. So an identifier fire enters as an ask one boundary old and a
            // content-only fire as one two boundaries old.
            //
            // Gated on Expansion and NOT on Recall, which matters: recall contributions are emitted only
            // when the tool is enabled, so riding that gate would silently kill the framework's own seed
            // wherever a deployment removed the tool, which is precisely the deployment that needs it.
            //
            // And the AGGREGATE is bounded, not only the per-region accumulation. The design derived a
            // per-region ceiling (sustained guessing on one region saturates at one fresh explicit ask)
            // and stopped there, which left the total unbounded: the fire caps admit up to a few regions
            // per turn and records live for a prune horizon of boundaries, so dozens can hold a live
            // record at once and their sum can rival the entire designed seed budget, which sums to 1.0.
            // Measured, that is not theoretical: on a long tool-heavy session it kept enough regions above
            // the keep floor to hold the served view near the trigger indefinitely.
            //
            // So the same ceiling the design argues for one region is applied to all of them together:
            // everything the mechanism injects, across every region, is worth at most ONE fresh ask from
            // the model. Scaling rather than truncating keeps the relative weighting the evidence classes
            // and the decay clock established, so the bound changes how loud the mechanism is and never
            // which regions it favours.
            val expansionRaw =
                if !mechanisms.contains(Mechanism.Expansion) then Chunk.empty
                else
                    state.expansions.map { e =>
                        val fraction = e.evidence match
                            case Evidence.Identifier => expansionSeedFractionIdentifier
                            case Evidence.Content    => expansionSeedFractionContent
                        (
                            e.region,
                            fraction * recallSeedWeight * math.pow(recallDecay, (state.boundaryCounter - e.boundaryStamp).toDouble)
                        )
                    }
            val expansionTotal = expansionRaw.foldLeft(0.0)((a, c) => a + c._2)
            val expansionContribs =
                if expansionTotal <= recallSeedWeight then expansionRaw
                else expansionRaw.map((id, v) => (id, v * recallSeedWeight / expansionTotal))
            val merged = (singleContribs ++ tailContribs ++ recallContribs ++ expansionContribs).foldLeft(Map.empty[Int, Double]) {
                case (m, (id, v)) => m.updated(id, m.getOrElse(id, 0.0) + v)
            }
            Dict.from(merged)
    end seedVector

    // ---- the seed's decayed tail set: the most recent regions carrying the geometric tail
    // seed. v4 has no separate roots-pin or co-pin machinery: the keep threshold plus span pinning
    // subsume it, and unresolved-turn regions are excluded from spans (never demotable).
    def tailUnits(units: Chunk[Region]): Set[Int] =
        val ordered = units.toList.sortBy(_.id).reverse
        @tailrec def loop(rem: List[Region], count: Int, tokens: Int, acc: Set[Int]): Set[Int] =
            rem match
                case Nil => acc
                case u :: rest =>
                    if count >= seedTailTurns then acc
                    else if tokens + u.tokens > seedTailTokens && acc.nonEmpty then acc
                    else loop(rest, count + 1, tokens + u.tokens, acc + u.id)
        loop(ordered, 0, 0, Set.empty)
    end tailUnits

    // The retention working-set tail band, trimmed at eviction time so the head and tail bands
    // together leave room under the low watermark, letting eviction of the frozen demoted middle
    // reach the target. A runtime guard, not a config default, since the band is dynamic. It
    // only shrinks the POSITIONAL tail protection; the evictable filter still forgets nothing that is
    // not currently demoted, so live content is never forgotten.
    def retentionTail(units: Chunk[Region], raw: Chunk[Message], headTokens: Int, low: Int): Set[Int] =
        val ordered                     = units.toList.sortBy(_.id).reverse
        def regionStamp(u: Region): Int = u.indices.foldLeft(0)((n, i) => n + stampedTokens(raw(i)))
        @tailrec def loop(rem: List[Region], count: Int, tokens: Int, acc: Set[Int]): Set[Int] =
            rem match
                case Nil => acc
                case u :: rest =>
                    val ut = regionStamp(u)
                    if count >= seedTailTurns then acc
                    else if tokens + ut > seedTailTokens && acc.nonEmpty then acc
                    else if headTokens + tokens + ut > low && acc.nonEmpty then acc
                    else loop(rest, count + 1, tokens + ut, acc + u.id)
                    end if
        loop(ordered, 0, 0, Set.empty)
    end retentionTail
end Ranking
