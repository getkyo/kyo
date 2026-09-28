package kyo.internal.charset

import java.nio.charset.Charset as JdkCharset
import kyo.*
import kyo.charset.Charset

/** Compares every `Charset` with the JDK's decoder for the nearest charset, over every valid sequence and a seeded set of generated
  * strings, on the JVM, where `java.nio.charset` has these charsets.
  *
  * Every input on which the two differ is a row of `jdk-25-differences.tsv`, with both outputs and the reason. The test fails on a
  * difference the file does not list and, on the JDK version the file was made on, on a listed difference that no longer occurs. On
  * another JDK version a listed difference that no longer occurs is not a failure, since the JDK's tables change between versions; an
  * unlisted one still is, so moving the build to another JDK version means regenerating the list on that version first. The file is
  * written by `CharsetJdkDifferences.main`, whose scaladoc says how to run it.
  */
class CharsetJdkDifferentialTest extends kyo.test.Test[Any]:

    import CharsetJdkDifferences.*
    import CharsetJdkDifferentialTest.*

    private val recordedVersion = "25"
    private val runningVersion  = java.lang.System.getProperty("java.specification.version")

    private lazy val listed: Seq[Row] =
        val stream = getClass.getResourceAsStream("jdk-25-differences.tsv")
        val text   = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
        stream.close()
        text.split("\n").toSeq.filterNot(l => l.startsWith("#") || l.isEmpty).map { line =>
            val fields = line.split("\t", -1)
            Row(fields(0), fields(1), fields(2), fields(3), Reason.valueOf(fields(4)))
        }
    end listed

    "every difference from the JDK is listed" in {
        val unlisted = observed.filterNot(listed.toSet.contains)
        assert(unlisted.isEmpty, s"${unlisted.size} differences are not listed, for example:\n${render(unlisted.take(20))}")
    }

    "every listed difference still occurs on the JDK version the list was made on" in {
        if runningVersion != recordedVersion then
            cancel(s"the list was made on JDK $recordedVersion and this is JDK $runningVersion, whose tables may differ")
        val stale = listed.filterNot(observed.toSet.contains)
        assert(stale.isEmpty, s"${stale.size} listed differences no longer occur, for example:\n${render(stale.take(20))}")
    }

    "the list has no duplicate rows" in {
        assert(listed.distinct.size == listed.size)
    }

    "each reason's rows have the property the reason states" - {
        "UTF-8: the module's output has more U+FFFD, and the same text once each run of U+FFFD is collapsed" in {
            val rows = listed.filter(_.reason == Reason.MaximalSubpart)
            assert(rows.nonEmpty)
            rows.foreach(r =>
                assert(collapse(r.module) == collapse(r.jdk) &&
                    r.module.split(" ").count(_ == "FFFD") > r.jdk.split(" ").count(_ == "FFFD"))
            )
            succeed
        }
        "UTF-16: the JDK's output is the module's with units after a U+FFFD missing" in {
            val rows = listed.filter(_.reason == Reason.Utf16UnpairedLead)
            assert(rows.nonEmpty)
            rows.foreach(r => assert(isSubsequence(r.jdk.split(" ").toSeq, r.module.split(" ").toSeq)))
            succeed
        }
        "generated inputs: one of the two outputs replaces something, or the input contains a listed valid sequence of that comparison" in {
            val validInputs =
                listed.filterNot(r => Set(Reason.Malformed, Reason.MaximalSubpart, Reason.Utf16UnpairedLead).contains(r.reason))
                    .groupBy(_.comparison).view.mapValues(_.map(_.input)).toMap
            val unexplained = listed.filter(_.reason == Reason.Malformed).filterNot { r =>
                r.module.contains("FFFD") || r.jdk.contains("FFFD") ||
                validInputs.getOrElse(r.comparison, Seq.empty).exists(v => s" ${r.input} ".contains(s" $v "))
            }
            assert(unexplained.isEmpty)
        }
        "C1 control rows are single bytes of a windows code page or Shift_JIS that the JDK replaces" in {
            val rows = listed.filter(_.reason == Reason.C1Control)
            assert(rows.nonEmpty)
            assert(rows.forall(r => (r.comparison.startsWith("windows-") || r.comparison == "Shift_JIS") && r.jdk == "FFFD"))
            assert(rows.forall(r => r.module == s"00${r.input}"))
        }
        "the iso-10646-ucs-2 rows are the three marked inputs, read little-endian by the module and big-endian by the JDK" in {
            val rows = listed.filter(_.reason == Reason.Ucs2Label).map(r => (r.input, r.module, r.jdk)).toSet
            assert(rows == Set(
                ("00 61 04 30", "6100 3004", "0061 0430"),
                ("FE FF 00 61 04 30", "FFFE 6100 3004", "FEFF 0061 0430"),
                ("FF FE 61 00 30 04", "FEFF 0061 0430", "FFFE 6100 3004")
            ))
        }
        "a bare UTF-16 reads a byte order mark as the JDK does, so the marked inputs give no row" in {
            val marked = CharsetJdkInputs.marked.map(hex).toSet
            assert(!listed.exists(r => r.comparison == "UTF-16" && marked.contains(r.input)))
        }
        "the gb2312 rows are A1A4 and A1AA with WHATWG's and GB 2312's code points" in {
            val rows = listed.filter(_.reason == Reason.Gb2312CodePoints).map(r => (r.input, r.module, r.jdk)).toSet
            assert(rows == Set(("A1 A4", "00B7", "30FB"), ("A1 AA", "2014", "2015")))
        }
    }

    "the JDK has no charset for ISO-8859-8-I, ISO-8859-10, ISO-8859-14, UTF-7 and HZ-GB-2312, so they are not compared" in {
        if runningVersion != recordedVersion then cancel(s"recorded on JDK $recordedVersion; this is JDK $runningVersion")
        assert(Charset.values.toSet.filterNot(c => JdkCharset.isSupported(jdkName(c))) == unavailable)
    }

end CharsetJdkDifferentialTest

object CharsetJdkDifferentialTest:

    def collapse(codePoints: String): String = codePoints.replaceAll("FFFD( FFFD)+", "FFFD")

    def isSubsequence(small: Seq[String], large: Seq[String]): Boolean =
        @scala.annotation.tailrec
        def loop(s: Seq[String], l: Seq[String]): Boolean =
            if s.isEmpty then true
            else if l.isEmpty then false
            else if s.head == l.head then loop(s.tail, l.tail)
            else loop(s, l.tail)
        loop(small, large)
    end isSubsequence

end CharsetJdkDifferentialTest
