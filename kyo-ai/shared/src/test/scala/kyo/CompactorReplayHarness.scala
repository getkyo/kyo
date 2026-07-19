package kyo

import Compactor.internal.*
import kyo.ai.*
import kyo.ai.Context.*

/** The validation-first replay harness: a test-only, model-free measurement of a dumb
  * keep-the-tail-plus-one-summary BASELINE against the full [[Compactor]] design over six synthetic
  * sessions. It embeds the sessions as literal `Chunk[Message]` data (no recorded corpus, no file I/O,
  * no network), replays both arms through the SAME driver, scores each on three deterministic metrics
  * (task-success predicates, integer token cost, re-fetch rate), and folds the per-session scoreboards
  * into a two-valued go/no-go [[GoNoGo]] verdict. It ships nothing into the compactor itself.
  *
  * The full arm drives the real demote/ladder/forced decision through the public seam
  * `Compactor.render` under `LLM.run`/`AI.initWith`; the baseline arm is a harness-local projector that
  * never touches `Compactor`'s render path. Both arms score through the identical metric functions, so
  * the comparison is not rigged toward the full design: session 6 is a short linear axis where recency
  * alone suffices and the baseline ties the full design, and [[decide]] returns NoGo whenever the full
  * design fails to clearly win.
  */
object CompactorReplayHarness:

    // ---- go/no-go value types (harness-local; NOT the compaction judge's Compactor.internal.Verdict) ----

    /** The two-valued go/no-go outcome. Go ships the full design; NoGo routes the design's
      * ship-baseline-plus-recall escape hatch to L0.
      */
    enum GoNoGo derives CanEqual:
        case Go, NoGo

    /** One arm's measured outcome on one session: whether the task-success predicates hold, the integer
      * token cost of the projected view, and the fraction of needed content the arm forces a re-fetch
      * for (content not present verbatim in the view).
      */
    case class Scoreboard(taskSuccess: Boolean, tokenCost: Int, refetchRate: Double) derives CanEqual

    /** Both arms' scoreboards for one session, plus the occupancy facts the frontier-drift axis reads
      * (the raw view occupancy and the two thresholds it must cross).
      */
    case class SessionScores(
        name: String,
        baseline: Scoreboard,
        full: Scoreboard,
        occupancy: Int,
        updateTrigger: Double,
        hardWindow: Double,
        window: Int,
        fullView: Context = Context.empty
    ) derives CanEqual

    // ---- construction helpers (reused verbatim from CompactorTest) ----

    def um(s: String): UserMessage                    = UserMessage(s, Absent)
    def sm(s: String): SystemMessage                  = SystemMessage(s)
    def am(s: String, calls: Call*): AssistantMessage = AssistantMessage(s, Chunk.from(calls))
    def tm(id: String, s: String): ToolMessage        = ToolMessage(CallId(id), s)
    def call(id: String, fn: String, args: String)    = Call(CallId(id), fn, args)
    def ctxOf(msgs: Message*): Context                = Context(Chunk.from(msgs))

    /** The fixed byte-to-token book both arms count with, so the token comparison is apples-to-apples. */
    val book0: Book = Book(0, 0.25, Set.empty, Set.empty, Set.empty)

    /** A config pointing the OpenAI backend at an unreachable dummy URL with a dummy key, and a fixed
      * model window. The dummy URL keeps `Compactor.render`'s detached background embed/judge dispatch
      * from attempting a real outbound connection: port 1 is unbound, so the swallowed attempt fails
      * fast and locally, never a real network egress.
      */
    def dummyConfig(window: Int): Config =
        Config.OpenAI.default.apiKey("test").model(Config.OpenAI, "gpt-4o", window).apiUrl("http://127.0.0.1:1")

    // ---- keyed tools for the supersession session (registered on the scope env so superKeys resolves) ----

    case class FileArg(path: String) derives Schema, CanEqual

    def readTool(using Frame): Tool[Any] =
        Tool.init[FileArg]("read", kind = Tool.Kind.Read, compactionKey = (a: FileArg) => Present(a.path)) { (_: FileArg) => "" }

    def writeTool(using Frame): Tool[Any] =
        Tool.init[FileArg]("write", kind = Tool.Kind.Write, compactionKey = (a: FileArg) => Present(a.path)) { (_: FileArg) => "" }

    // ---- the dumb baseline projector (harness-local; never touches Compactor's render path) ----

    /** Keep the last N grouped turns verbatim, replacing everything before them with ONE mechanically
      * assembled summary placeholder. N is fixed and documented, never tuned per session to win. The cut
      * lands on grouped-unit boundaries (reusing `Compactor.group`), so a tool_use/tool_result pair is
      * never severed.
      */
    val baselineTailTurns = 4

    def baselineProject(c: Compactor, transcript: Context): Context =
        val units = c.group(transcript, book0).toList.sortBy(_.id)
        if units.size <= baselineTailTurns then transcript
        else
            val kept         = units.takeRight(baselineTailTurns)
            val keptIdx      = kept.flatMap(_.messages).toSet
            val droppedCount = units.size - kept.size
            val summary      = SystemMessage(s"[baseline summary of $droppedCount earlier turns]")
            val keptMsgs     = transcript.messages.zipWithIndex.collect { case (m, i) if keptIdx.contains(i) => m }
            Context(Chunk(summary).concat(keptMsgs))
        end if
    end baselineProject

    // ---- the three deterministic metrics (identical functions for both arms) ----

    // Any compaction marker (mask or reference) carries "region <id>", so a region id present in the
    // view names content that is recall-recoverable from the immutable transcript.
    private val markerRegex = """region (\d+)""".r

    def viewText(view: Context): String = view.messages.map(_.content).mkString("\n")

    def verbatim(view: Context, needle: String): Boolean = viewText(view).contains(needle)

    def markerIds(view: Context): Set[Int] =
        markerRegex.findAllMatchIn(viewText(view)).map(_.group(1).toInt).toSet

    // Recall-recoverable: the view carries a region marker whose id, sliced from the live transcript,
    // still contains the needle. The metric is recoverability, not that the model actually called recall.
    def recoverable(c: Compactor, transcript: Context, view: Context, needle: String): Boolean =
        val units = c.group(transcript, book0)
        markerIds(view).exists { id =>
            units.filter(_.id == id).headMaybe match
                case Present(u) => c.unitContent(u, transcript).contains(needle)
                case Absent     => false
        }
    end recoverable

    def available(c: Compactor, transcript: Context, view: Context, needle: String): Boolean =
        verbatim(view, needle) || recoverable(c, transcript, view, needle)

    def scoreArm(c: Compactor, session: Session, view: Context): Scoreboard =
        val len = session.transcript.messages.size
        Scoreboard(
            taskSuccess = session.requiredNeedles.forall(n => available(c, session.transcript, view, n)),
            tokenCost = c.viewTokens(view, book0),
            refetchRate = session.neededNeedles.count(n => !verbatim(view, n)).toDouble / len.toDouble
        )
    end scoreArm

    // ---- the two-valued go/no-go decision (a real function of the numeric/boolean fields) ----

    /** The integer-token slack tolerated on the recency axis before a full-arm token regression counts
      * against a Go.
      */
    val tokenEpsilon = 4

    /** Go iff the full design clearly wins: it never loses task-success where the baseline succeeds, its
      * total token cost stays within epsilon of the baseline, its total re-fetch does not regress, and it
      * strictly wins on at least one axis somewhere. NoGo otherwise. Genuinely two-valued: a scoreboard
      * where the baseline beats the full design returns NoGo.
      */
    def decide(sessions: Seq[SessionScores]): GoNoGo =
        val losesTask   = sessions.exists(s => s.baseline.taskSuccess && !s.full.taskSuccess)
        val fullTokens  = sessions.map(_.full.tokenCost).sum
        val baseTokens  = sessions.map(_.baseline.tokenCost).sum
        val fullRefetch = sessions.map(_.full.refetchRate).sum
        val baseRefetch = sessions.map(_.baseline.refetchRate).sum
        val strictWin = sessions.exists(s =>
            (s.full.taskSuccess && !s.baseline.taskSuccess) ||
                s.full.tokenCost < s.baseline.tokenCost ||
                s.full.refetchRate < s.baseline.refetchRate
        )
        val clearWin =
            !losesTask &&
                fullTokens <= baseTokens + tokenEpsilon &&
                fullRefetch <= baseRefetch + 1e-9 &&
                strictWin
        if clearWin then GoNoGo.Go else GoNoGo.NoGo
    end decide

    // ---- the six synthetic sessions ----

    /** One synthetic session: its literal transcript, the LLM config and Compactor tuning that drive the
      * target render path, whether the keyed supersession tools are registered, its model window, and the
      * needles the task-success and re-fetch metrics read.
      */
    case class Session(
        name: String,
        transcript: Context,
        config: Config,
        tune: Compactor.Config => Compactor.Config,
        keyed: Boolean,
        window: Int,
        requiredNeedles: Seq[String],
        neededNeedles: Seq[String]
    )

    // Session 1: read, re-read, write of one key. Under occupancy pressure the full arm supersedes and
    // demotes the stale reads while keeping the latest write live; the baseline keeps the large reads
    // verbatim, so the full arm's view costs fewer tokens on this axis.
    val session1: Session =
        Session(
            name = "session 1 (supersession)",
            transcript = ctxOf(
                sm("sys"),
                um("task open config for KEY1"),
                am("reading", call("r1", "read", """{"path":"KEY1"}""")),
                tm("r1", "old contents " + ("x" * 400)),
                am("re-reading", call("r2", "read", """{"path":"KEY1"}""")),
                tm("r2", "stale contents " + ("y" * 400)),
                am("writing", call("w1", "write", """{"path":"KEY1"}""")),
                tm("w1", "LATEST_STATE_NEEDLE persisted"),
                um("latest confirm the write")
            ),
            config = dummyConfig(128000),
            tune = _.copy(effectiveCap = 50, windowFraction = 1.0, tailTurns = 2),
            keyed = true,
            window = 128000,
            requiredNeedles = Seq("LATEST_STATE_NEEDLE"),
            neededNeedles = Seq("LATEST_STATE_NEEDLE")
        )

    // Session 2 + 3: an oversized tool result the full arm elides (head/tail plus marker, keeping the
    // leading needle) and a short region it masks (recall-recoverable). The baseline drops both into its
    // summary, so it forces more re-fetches.
    private val oversizedResult =
        "ELIDE_HEAD_NEEDLE report begins here\n" +
            (1 to 20).map(i => s"data row $i " + ("z" * 15)).mkString("\n") +
            "\nfinal row TAILMARK"

    val session23: Session =
        Session(
            name = "session 2 (oversized result) + session 3 (recall)",
            transcript = ctxOf(
                sm("sys"),
                um("task analyze the report"),
                am("fetching", call("f1", "read", """{"path":"REPORT"}""")),
                tm("f1", oversizedResult),
                am("noting KEY_FACT_NEEDLE for later"),
                am("continuing work " + ("q" * 200)),
                am("more analysis " + ("r" * 200)),
                um("recall the earlier note please"),
                am("done " + ("s" * 30))
            ),
            config = dummyConfig(128000),
            tune = _.copy(effectiveCap = 40, windowFraction = 1.0, tailTurns = 1, elisionThreshold = 200),
            keyed = false,
            window = 128000,
            requiredNeedles = Seq("ELIDE_HEAD_NEEDLE", "KEY_FACT_NEEDLE"),
            neededNeedles = Seq("ELIDE_HEAD_NEEDLE", "KEY_FACT_NEEDLE")
        )

    // Session 4: occupancy crosses both H*E and H_hard against a small window; a mid-session objective
    // change references an earlier dependency via a shared identifier, so the forced path co-pins that
    // dependency (stays live) while masking the least-live units, and fits under the window with no
    // over-limit send.
    val session4: Session =
        Session(
            name = "session 4 (frontier drift)",
            transcript = ctxOf(
                sm("sys"),
                um("task build the PARSER_MODULE"),
                am("step 1 " + ("a" * 300)),
                am("step 2 " + ("b" * 300)),
                am("dependency note PARSER_MODULE needs LEXER_DEP OBJDEP_NEEDLE"),
                am("step 3 " + ("c" * 300)),
                am("step 4 " + ("d" * 300)),
                um("new objective finish LEXER_DEP now"),
                am("working on it " + ("e" * 50))
            ),
            config = dummyConfig(240),
            tune = _.copy(effectiveCap = 100000, windowFraction = 1.0, tailTurns = 1),
            keyed = false,
            window = 240,
            requiredNeedles = Seq("OBJDEP_NEEDLE"),
            neededNeedles = Seq("OBJDEP_NEEDLE")
        )

    // Session 5: the discriminator DISCRIMINATOR_XY lives ONLY in an EARLY NON-ROOT assistant note
    // (the "triage note" unit), never in a pinned root, so its survival is attributable to
    // graph-liveness scoring, not to root-pinning. The note also introduces the shared identifier
    // LEDGER_KEY; the last-user objective (a pinned root) references LEDGER_KEY, so deriveGraph draws a
    // Ref edge objective -> triage-note and coPinReferrers records the note as co-pinned by a live
    // root. Under real occupancy pressure (effectiveCap=50 like session 1: e=50, updateTrigger=35,
    // hardWindow=115200; the ~370-token transcript crosses the update trigger without reaching the hard
    // window, so the UPDATE path runs), the demote loop masks the least-live filler units but skips the
    // co-pinned triage note, whose nonzero PPR score (mass flowing from the seeded objective root along
    // that Ref edge) keeps it above pure filler. So DISCRIMINATOR_XY survives verbatim in the full view
    // by transitive liveness, while the baseline's unconditional keep-last-4 truncation folds the early
    // note into its opaque summary and drops the discriminator. The baseline outcome is MEASURED into
    // the scoreboard, never forced false a priori.
    val session5: Session =
        Session(
            name = "session 5 (discriminator)",
            transcript = ctxOf(
                sm("sys"),
                um("task investigate the failing deploy pipeline"),
                am("early triage note the LEDGER_KEY field is corrupt and DISCRIMINATOR_XY is the exact failing case " + ("a" * 40)),
                am("step one " + ("b" * 300)),
                am("step two " + ("c" * 300)),
                am("step three " + ("d" * 300)),
                am("step four " + ("e" * 300)),
                um("confirm the LEDGER_KEY fix is landing before we close out"),
                am("on it verifying now " + ("f" * 40))
            ),
            config = dummyConfig(128000),
            tune = _.copy(effectiveCap = 50, windowFraction = 1.0, tailTurns = 2),
            keyed = false,
            window = 128000,
            requiredNeedles = Seq("DISCRIMINATOR_XY"),
            neededNeedles = Seq("DISCRIMINATOR_XY")
        )

    // Session 6: a short linear session with no supersession, no oversized result, and no recall need.
    // Recency alone suffices, so the dumb baseline keeps everything verbatim and ties the full design:
    // the full arm's graph and PPR machinery earn no advantage here.
    val session6: Session =
        Session(
            name = "session 6 (short linear, recency wins)",
            transcript = ctxOf(
                um("task list the action items NEEDLE6"),
                am("here are the action items"),
                um("also add a deadline"),
                am("deadline added NEEDLE6B on Friday")
            ),
            config = dummyConfig(128000),
            tune = identity,
            keyed = false,
            window = 128000,
            requiredNeedles = Seq("NEEDLE6", "NEEDLE6B"),
            neededNeedles = Seq("NEEDLE6", "NEEDLE6B")
        )

    val sessions: List[Session] = List(session1, session23, session4, session5, session6)

    // ---- the shared replay driver (both arms) ----

    /** Replays one session through both arms and scores each identically. The full arm drives the real
      * render path (`Compactor.render` under `LLM.run`/`AI.initWith`); the baseline arm is the local
      * projector. Returns both scoreboards plus the occupancy facts the frontier-drift axis reads.
      */
    def replay(session: Session)(using Frame): SessionScores < (Async & Abort[AIGenException]) =
        LLM.run(session.config) {
            Compactor.init(session.tune).map { c =>
                val driven =
                    AI.initWith { ai =>
                        ai.setContext(session.transcript)
                            .andThen(c.render(ai, session.transcript))
                            .map { fullView =>
                                val baseView = baselineProject(c, session.transcript)
                                val e        = c.effectiveLength(c.config, session.window)
                                SessionScores(
                                    name = session.name,
                                    baseline = scoreArm(c, session, baseView),
                                    full = scoreArm(c, session, fullView),
                                    occupancy = c.viewTokens(session.transcript, book0),
                                    updateTrigger = c.config.updateTriggerFraction * e,
                                    hardWindow = c.config.hardWindowFraction * session.window,
                                    window = session.window,
                                    fullView = fullView
                                )
                            }
                    }
                if session.keyed then AI.enable(readTool, writeTool)(driven) else driven
            }
        }

end CompactorReplayHarness
