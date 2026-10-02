package kyo

import kyo.EmailMessageReference.*
import kyo.internal.email.mime.EmbeddedMime4jModelDifferencesTsv

class EmailMessageReferenceTest extends kyo.test.Test[Any]:

    private lazy val listed: Seq[Row] = parse(EmbeddedMime4jModelDifferencesTsv.text)

    private lazy val comparison: (Seq[Row], Seq[Unexplained]) = compared

    "the four corpora: 45 CPython, 10 Stalwart, 31 mime4j and 89 Thunderbird messages" in {
        assert(messages.size == 175)
    }
    "every difference from the mime4j reference has a reason" in {
        val unexplained = comparison._2
        assert(
            unexplained.isEmpty,
            unexplained.map(u => s"${u.message} ${u.aspect}: module [${u.module}] reference [${u.reference}]").mkString("\n")
        )
    }
    "every difference is listed" in {
        val unlisted = comparison._1.filterNot(listed.contains)
        assert(unlisted.isEmpty, s"${unlisted.size} differences are not listed:\n${render(unlisted)}")
    }
    "every listed difference still occurs" in {
        val stale = listed.filterNot(comparison._1.contains)
        assert(stale.isEmpty, s"${stale.size} listed differences no longer occur:\n${render(stale)}")
    }
    "the list has no duplicate rows" in {
        assert(listed.distinct.size == listed.size)
    }

end EmailMessageReferenceTest
