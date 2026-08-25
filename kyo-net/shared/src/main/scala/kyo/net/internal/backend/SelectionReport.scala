package kyo.net.internal.backend

import kyo.*

/** What a selection saw: every candidate it considered, the outcome each candidate's probe produced, and which one won.
  *
  * It has two consumers and no public surface. It is logged once at init as a single `info` line, so a run that quietly landed on the floor
  * says so in the log rather than only in the absence of a fast backend; and it is rendered into the `cause` of the terminal
  * `NetBackendUnavailableException` / `NetTlsProviderUnavailableException`, so the failure a caller catches carries the whole picture
  * instead of just the name that was missing.
  *
  * Rendered into a `cause` only where that channel is free. A terminal whose cause already holds a throwable (a propagated build failure)
  * keeps it: the report augments the diagnosis, it never displaces a real underlying error.
  */
final private[net] case class SelectionReport(chosen: Maybe[String], entries: Chunk[SelectionReport.Entry]):

    /** The single line the log carries and the terminal exception embeds. */
    def render: String =
        val head = chosen match
            case Present(name) => s"selected '$name'"
            case Absent        => "no candidate selected"
        s"$head; candidates: ${entries.map(_.render).mkString(", ")}"
    end render

end SelectionReport

private[net] object SelectionReport:

    /** One line of the report. */
    enum Entry derives CanEqual:

        /** A registered candidate and what its probe reported. */
        case Probed(name: String, priority: Int, outcome: CapabilityOutcome)

        /** A pinned name that matches no registered candidate, so there is no descriptor to read a priority or an outcome from. Synthesized
          * by the pinned TLS path, which otherwise would contribute nothing to the report at all, and kept structurally distinct from a
          * registered candidate whose probe reported it unavailable: those two are different operator mistakes (a typo in the pin versus a
          * library that is not staged) and the diagnosis must not conflate them.
          */
        case NotRegistered(name: String)

        def render: String = this match
            case Probed(name, priority, outcome) => s"$name[$priority] ${outcome.describe}"
            case NotRegistered(name)             => s"$name not registered"

    end Entry

end SelectionReport
