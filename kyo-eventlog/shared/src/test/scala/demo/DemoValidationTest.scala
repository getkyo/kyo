package demo

import kyo.*
import kyo.AllowUnsafe.embrace.danger

/** Validates the kyo-eventlog demo against the real file journal and EventLog pipeline.
  *
  * Drives the SAME `flow` a fleet-service author reads (no re-implemented copy) through
  * `FleetLedgerDemo.flow` and asserts `validate` returns `Absent`.
  */
class DemoValidationTest extends kyo.test.Test[Any]:

    "FleetLedgerDemo: flow drives Journal.Backend.file and validate returns Absent" in {
        Abort.run[FileException | JournalError | EventLog.PreparationFailure](FleetLedgerDemo.flow).map {
            case Result.Success(snapshot) =>
                val verdict = FleetLedgerDemo.validate(snapshot)
                assert(verdict == Absent, s"demo validate must return Absent; got: $verdict")
            case other =>
                assert(false, s"demo flow must not abort; got: $other")
        }
    }

    // Unsafe: JVM-only self-audit of the shipped docs on disk (a dev-time grep, not a runtime
    // capability); mirrors JournalEventTest's repo-root walk and file-read helpers. These leaves
    // are `.onlyJvm` because `user.dir` and the synchronous file reads are JVM-only.
    private def repoRoot(): Path =
        @scala.annotation.tailrec
        def loop(dir: Path): Path =
            if (dir / "build.sbt").unsafe.exists() then dir
            else
                dir.parent match
                    case Maybe.Present(parent) => loop(parent)
                    case Maybe.Absent          => throw new RuntimeException("repo root with build.sbt not found")
        loop(Path(java.lang.System.getProperty("user.dir").nn))
    end repoRoot

    private def docText(segments: String*): String =
        segments.foldLeft(repoRoot() / "kyo-eventlog")((acc, seg) => acc / seg).unsafe.read().getOrThrow

    private def readmeText(): String = docText("README.md")
    private def basicText(): String  = docText("docs", "tutorials", "basic-eventlog.md")
    private def rawText(): String    = docText("docs", "tutorials", "raw-journal.md")
    private def customText(): String = docText("docs", "tutorials", "custom-storage.md")

    // A banned token that is one whole word not immediately preceded by '.' (so `Event.StreamId`
    // never trips the bare-`StreamId` ban) and not the prefix of a longer identifier (so
    // `FileJournal.Configuration` never trips the `FileJournal.Config` ban).
    private def bannedHits(text: String, token: String): List[String] =
        val escaped = java.util.regex.Pattern.quote(token)
        val pattern = s"(?<![.\\w])$escaped(?![\\w])".r
        text.linesIterator.filter(line => pattern.findFirstIn(line).isDefined).toList
    end bannedHits

    private val bannedSymbols = List(
        "appendBatch",
        "EventLog.from",
        "EventLog.Typed",
        "EventEnvelope",
        "RecordedEvent",
        "SegmentFormat",
        "FileJournal.Config",
        "EventPayloadCodec",
        "EventMetadataCodec",
        "MetadataValue",
        "MetadataKey",
        "EventId",
        "EventType",
        "StreamId",
        "EventMetadata"
    )

    "QuestParty is the first teaching domain and Fleet is secondary".onlyJvm in {
        val readme   = readmeText().toLowerCase
        val questIdx = readme.indexOf("quest")
        val fleetIdx = readme.indexOf("fleet")
        assert(questIdx >= 0, "the README must teach the QuestParty domain")
        assert(fleetIdx < 0 || questIdx < fleetIdx, s"QuestParty (idx $questIdx) must precede Fleet (idx $fleetIdx)")
        val fleetPrimary = readmeText().linesIterator.exists(l =>
            l.toLowerCase.contains("fleet") && l.toLowerCase.contains("primary")
        )
        assert(!fleetPrimary, "Fleet must never be labeled the primary teaching domain")
    }

    "no banned surface symbol, spelling drift, or URI path leakage in docs/examples".onlyJvm in {
        val docs = List(
            "README.md"      -> readmeText(),
            "basic-eventlog" -> basicText(),
            "raw-journal"    -> rawText(),
            "custom-storage" -> customText()
        )
        docs.foreach { case (name, text) =>
            bannedSymbols.foreach { symbol =>
                val hits = bannedHits(text, symbol)
                assert(hits.isEmpty, s"$name carries the banned symbol '$symbol': $hits")
            }
            assert(!text.contains("Pippin"), s"$name uses the banned Pippin spelling (source parity is Pippen)")
            assert(!text.contains("file://"), s"$name leaks a physical file:// URI")
        }
    }

    "the QuestParty tutorial fenced blocks demonstrate direct append with no membership plumbing".onlyJvm in {
        val basic = basicText()
        assert(basic.contains("log.append("), "the basic-eventlog tutorial must show the plain log.append form")
        assert(!basic.contains("Frame.internal"), "the tutorial must not expose Frame.internal at the call site")
        assert(!basic.contains("summon[<:<"), "the tutorial must not summon a <:< membership witness")
        assert(!basic.contains("EventDefinition"), "the tutorial must not pass an explicit EventDefinition at the call site")
    }

end DemoValidationTest
