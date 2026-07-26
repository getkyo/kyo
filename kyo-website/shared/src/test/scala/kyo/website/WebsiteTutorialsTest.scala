package kyo.website

import kyo.*

class WebsiteTutorialsTest extends WebsiteTest:

    "the registry returns the three registered kyo-eventlog tutorial declarations" in {
        val declarations = WebsiteTutorials.forModule("kyo-eventlog")
        assert(declarations.size == 3, s"expected 3 declarations, got ${declarations.size}")
        assert(
            declarations.map(_.slug) == Chunk("basic-eventlog", "raw-journal", "custom-storage"),
            s"slugs: ${declarations.map(_.slug)}"
        )
        assert(
            declarations.map(_.title) == Chunk("Basic EventLog", "Raw Journal", "Custom storage"),
            s"titles: ${declarations.map(_.title)}"
        )
        assert(
            declarations.map(_.source) == Chunk(
                Path("kyo-eventlog/docs/tutorials/basic-eventlog.md"),
                Path("kyo-eventlog/docs/tutorials/raw-journal.md"),
                Path("kyo-eventlog/docs/tutorials/custom-storage.md")
            ),
            s"sources: ${declarations.map(_.source)}"
        )
    }

    "the registry returns an empty rail for an unregistered module slug" in {
        val declarations = WebsiteTutorials.forModule("kyo-core")
        assert(declarations.isEmpty, s"expected an empty rail for kyo-core, got $declarations")
    }

end WebsiteTutorialsTest
