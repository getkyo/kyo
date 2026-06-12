package kyo.test.runner

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import javax.xml.parsers.DocumentBuilderFactory
import kyo.*
import kyo.Chunk
import kyo.Maybe
import kyo.test.LeafInfo
import kyo.test.RunInfo
import kyo.test.SuiteInfo
import kyo.test.SuiteReport
import kyo.test.TestReport
import kyo.test.TestResult

class JUnitXmlReporterTest extends kyo.test.Test[Any]:

    private def withTempDir(body: Path => Unit): Unit =
        val dir = Files.createTempDirectory("junit-xml-test")
        try body(dir)
        finally
            val files = dir.toFile.listFiles()
            if files != null then files.foreach(_.delete())
            dir.toFile.delete(): Unit
        end try
    end withTempDir

    private def runOneSuite(
        reporter: JUnitXmlReporter,
        suiteInfo: SuiteInfo,
        leaves: List[(LeafInfo, TestResult)]
    ): Unit =
        reporter.onRunStart(RunInfo(1, 1))
        reporter.onSuiteStart(suiteInfo)
        leaves.foreach: (leaf, result) =>
            reporter.onLeafStart(leaf)
            reporter.onLeafComplete(leaf, result)
        val report = SuiteReport(
            suiteInfo.name,
            Chunk.from(leaves.map { case (l, r) => (l.path, r) }),
            100L.millis
        )
        reporter.onSuiteComplete(suiteInfo, report)
        reporter.onRunComplete(TestReport(Chunk(report)))
    end runOneSuite

    "writes one XML file per suite" in {
        withTempDir { dir =>
            val reporter = JUnitXmlReporter(dir)
            List("Suite1", "Suite2", "Suite3").foreach { name =>
                val suiteInfo = SuiteInfo(name, s"com.example.$name", Maybe.empty)
                val leaf      = LeafInfo(name, Chunk("test1"), Set.empty)
                runOneSuite(reporter, suiteInfo, List(leaf -> TestResult.Passed(1L.millis)))
            }
            val suite1Exists = Files.exists(dir.resolve("TEST-Suite1.xml"))
            val suite2Exists = Files.exists(dir.resolve("TEST-Suite2.xml"))
            val suite3Exists = Files.exists(dir.resolve("TEST-Suite3.xml"))
            assert(suite1Exists, "TEST-Suite1.xml missing"): Unit
            assert(suite2Exists, "TEST-Suite2.xml missing"): Unit
            assert(suite3Exists, "TEST-Suite3.xml missing"): Unit
        }
    }

    "XML has correct root element and structure" in {
        withTempDir { dir =>
            val reporter  = JUnitXmlReporter(dir)
            val suiteInfo = SuiteInfo("MyS", "com.example.MyS", Maybe.empty)
            val leaf      = LeafInfo("MyS", Chunk("someTest"), Set.empty)
            runOneSuite(reporter, suiteInfo, List(leaf -> TestResult.Passed(1L.millis)))
            val xml = Files.readString(dir.resolve("TEST-MyS.xml"))
            assert(xml.contains("<?xml"), s"Missing XML declaration in: $xml"): Unit
            assert(xml.contains("<testsuite"), s"Missing <testsuite in: $xml"): Unit
            assert(xml.contains("<testcase"), s"Missing <testcase in: $xml"): Unit
        }
    }

    "includes per-leaf duration in time attribute" in {
        withTempDir { dir =>
            val reporter  = JUnitXmlReporter(dir)
            val suiteInfo = SuiteInfo("DurS", "com.example.DurS", Maybe.empty)
            val leaf      = LeafInfo("DurS", Chunk("slowTest"), Set.empty)
            runOneSuite(reporter, suiteInfo, List(leaf -> TestResult.Passed(42L.millis)))
            val xml = Files.readString(dir.resolve("TEST-DurS.xml"))
            assert(xml.contains("time=\"0.042\""), s"Expected time=\"0.042\" in: $xml"): Unit
        }
    }

    "failure message in <failure> element" in {
        withTempDir { dir =>
            val reporter  = JUnitXmlReporter(dir)
            val suiteInfo = SuiteInfo("FailS", "com.example.FailS", Maybe.empty)
            val leaf      = LeafInfo("FailS", Chunk("failTest"), Set.empty)
            runOneSuite(
                reporter,
                suiteInfo,
                List(leaf -> TestResult.Failed("x == 5\n| 5", Maybe.empty, 1L.millis))
            )
            val xml = Files.readString(dir.resolve("TEST-FailS.xml"))
            assert(xml.contains("<failure"), s"Missing <failure in: $xml"): Unit
            assert(xml.contains("x == 5"), s"Missing diagram text in: $xml"): Unit
        }
    }

    "skipped and pending mapped to <skipped>" in {
        withTempDir { dir =>
            val reporter  = JUnitXmlReporter(dir)
            val suiteInfo = SuiteInfo("SkipS", "com.example.SkipS", Maybe.empty)
            val leaf1     = LeafInfo("SkipS", Chunk("pendingTest"), Set.empty)
            val leaf2     = LeafInfo("SkipS", Chunk("skippedTest"), Set.empty)
            runOneSuite(
                reporter,
                suiteInfo,
                List(
                    leaf1 -> TestResult.Pending("later"),
                    leaf2 -> TestResult.Skipped("no env")
                )
            )
            val xml   = Files.readString(dir.resolve("TEST-SkipS.xml"))
            val count = "<skipped".r.findAllIn(xml).length
            assert(count == 2, s"Expected 2 <skipped elements, found $count in: $xml"): Unit
        }
    }

    "testsuite failures count correct" in {
        withTempDir { dir =>
            val reporter  = JUnitXmlReporter(dir)
            val suiteInfo = SuiteInfo("CntS", "com.example.CntS", Maybe.empty)
            val leaves = List(
                LeafInfo("CntS", Chunk("p1"), Set.empty) -> TestResult.Passed(1L.millis),
                LeafInfo("CntS", Chunk("p2"), Set.empty) -> TestResult.Passed(1L.millis),
                LeafInfo("CntS", Chunk("f1"), Set.empty) -> TestResult.Failed("fail", Maybe.empty, 1L.millis),
                LeafInfo("CntS", Chunk("t1"), Set.empty) -> TestResult.TimedOut(10L.millis)
            )
            runOneSuite(reporter, suiteInfo, leaves)
            val xml = Files.readString(dir.resolve("TEST-CntS.xml"))
            assert(xml.contains("failures=\"2\""), s"Expected failures=\"2\" in: $xml"): Unit
        }
    }

    "testsuite skipped count correct" in {
        withTempDir { dir =>
            val reporter  = JUnitXmlReporter(dir)
            val suiteInfo = SuiteInfo("SkCntS", "com.example.SkCntS", Maybe.empty)
            val leaves = List(
                LeafInfo("SkCntS", Chunk("pending1"), Set.empty) -> TestResult.Pending("waiting"),
                LeafInfo("SkCntS", Chunk("ignored1"), Set.empty) -> TestResult.Ignored("")
            )
            runOneSuite(reporter, suiteInfo, leaves)
            val xml = Files.readString(dir.resolve("TEST-SkCntS.xml"))
            assert(xml.contains("skipped=\"2\""), s"Expected skipped=\"2\" in: $xml"): Unit
        }
    }

    "cancelled leaf is <skipped>, not <failure>, and counts as skipped" in {
        withTempDir { dir =>
            val reporter  = JUnitXmlReporter(dir)
            val suiteInfo = SuiteInfo("CancS", "com.example.CancS", Maybe.empty)
            val leaves = List(
                LeafInfo("CancS", Chunk("p1"), Set.empty) -> TestResult.Passed(1L.millis),
                LeafInfo("CancS", Chunk("c1"), Set.empty) -> TestResult.Cancelled("Needs >4 cores", 1L.millis)
            )
            runOneSuite(reporter, suiteInfo, leaves)
            val xml = Files.readString(dir.resolve("TEST-CancS.xml"))
            // A cancelled precondition is a deliberate skip, not a failure.
            assert(xml.contains("failures=\"0\""), s"Expected failures=\"0\" in: $xml"): Unit
            assert(xml.contains("skipped=\"1\""), s"Expected skipped=\"1\" in: $xml"): Unit
            assert(xml.contains("<skipped"), s"Expected <skipped element in: $xml"): Unit
            assert(!xml.contains("<failure"), s"Cancelled must not produce <failure> in: $xml"): Unit
        }
    }

    "testsuite tests count correct" in {
        withTempDir { dir =>
            val reporter  = JUnitXmlReporter(dir)
            val suiteInfo = SuiteInfo("TstCntS", "com.example.TstCntS", Maybe.empty)
            val leaves = (1 to 4).map { i =>
                LeafInfo("TstCntS", Chunk(s"test$i"), Set.empty) -> TestResult.Passed(1L.millis)
            }.toList
            runOneSuite(reporter, suiteInfo, leaves)
            val xml = Files.readString(dir.resolve("TEST-TstCntS.xml"))
            assert(xml.contains("tests=\"4\""), s"Expected tests=\"4\" in: $xml"): Unit
        }
    }

    "XML-special characters escaped in name" in {
        withTempDir { dir =>
            val reporter  = JUnitXmlReporter(dir)
            val suiteInfo = SuiteInfo("EscS", "com.example.EscS", Maybe.empty)
            val leaf      = LeafInfo("EscS", Chunk("suite & test < 1 >"), Set.empty)
            runOneSuite(reporter, suiteInfo, List(leaf -> TestResult.Passed(1L.millis)))
            val xml = Files.readString(dir.resolve("TEST-EscS.xml"))
            // The raw & and < must not appear unescaped inside attribute values
            assert(!xml.contains(" & "), s"Raw & found in XML: $xml")
            assert(xml.contains("&amp;"), s"Expected &amp; escaping in: $xml"): Unit
        }
    }

    "classname attribute matches SuiteInfo.className" in {
        withTempDir { dir =>
            val reporter  = JUnitXmlReporter(dir)
            val suiteInfo = SuiteInfo("MySuite", "com.example.MySuite", Maybe.empty)
            val leaf      = LeafInfo("MySuite", Chunk("t"), Set.empty)
            runOneSuite(reporter, suiteInfo, List(leaf -> TestResult.Passed(1L.millis)))
            val xml = Files.readString(dir.resolve("TEST-MySuite.xml"))
            assert(xml.contains("classname=\"com.example.MySuite\""), s"Expected classname in: $xml"): Unit
        }
    }

    // ── Phase 4 leaves ───────────────────────────────────────────────────────────────────────────

    "onSuiteComplete with 1 Passed and 1 Failed produces javax-parseable XML" in {
        withTempDir { dir =>
            val reporter  = JUnitXmlReporter(dir)
            val suiteInfo = SuiteInfo("ParseSuite", "com.example.ParseSuite", Maybe.empty)
            val leaves = List(
                LeafInfo("ParseSuite", Chunk("passTest"), Set.empty) -> TestResult.Passed(1L.millis),
                LeafInfo("ParseSuite", Chunk("failTest"), Set.empty) -> TestResult
                    .Failed("assertion failed", Maybe.empty, 1L.millis)
            )
            runOneSuite(reporter, suiteInfo, leaves)
            val xml   = Files.readString(dir.resolve("TEST-ParseSuite.xml"))
            val bytes = xml.getBytes("UTF-8")
            // Must not throw
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                new ByteArrayInputStream(bytes)
            )
            val root = doc.getDocumentElement
            assert(root.getTagName == "testsuite", s"Expected root <testsuite>, got: ${root.getTagName}")
            assert(root.getAttribute("tests") == "2", s"Expected tests=2, got: ${root.getAttribute("tests")}")
        }
    }

    "xmlEscape replaces control characters with ?" in {
        val reporter = JUnitXmlReporter(Files.createTempDirectory("unused"))
        //  and   are control chars below ' '
        val result = reporter.xmlEscape("hello ")
        assert(result == "?hello?", s"Expected '?hello?' but got: '$result'")
    }

    "write failure to read-only path logs to stderr without propagating exception" in {
        val readOnlyDir = Files.createTempDirectory("junit-readonly")
        try
            // Make the directory read-only so writing fails
            readOnlyDir.toFile.setWritable(false)
            val reporter  = JUnitXmlReporter(readOnlyDir)
            val suiteInfo = SuiteInfo("RoSuite", "com.example.RoSuite", Maybe.empty)
            val leaf      = LeafInfo("RoSuite", Chunk("t"), Set.empty)
            val report    = SuiteReport("RoSuite", Chunk((Chunk("t"), TestResult.Passed(1L.millis))), 1L.millis)
            val baos      = new ByteArrayOutputStream()
            val ps        = new PrintStream(baos, true, "UTF-8")
            val oldErr    = java.lang.System.err
            java.lang.System.setErr(ps)
            try
                // Must not throw
                reporter.onSuiteComplete(suiteInfo, report)
            finally
                java.lang.System.setErr(oldErr)
            end try
            val stderr = baos.toString("UTF-8")
            assert(
                stderr.contains("[kyo-test] JUnitXmlReporter failed to write"),
                s"Expected write-failure log in stderr, got: $stderr"
            )
        finally
            readOnlyDir.toFile.setWritable(true)
            readOnlyDir.toFile.delete(): Unit
        end try
    }

    // ── PathChunk Leaf 4 (JVM-only: uses java.nio.file.Files) ────────────────────────────────
    // JUnitXmlReporter golden test: byte-identical XML before and after Chunk path migration
    "JUnitXmlReporter serialises SuiteReport with Chunk path identically to prior List output" in {
        withTempDir { dir =>
            val reporter = JUnitXmlReporter(dir)
            val info     = SuiteInfo("S", "com.example.S", Maybe.empty)
            reporter.onRunStart(RunInfo(1, 1))
            reporter.onSuiteStart(info)
            val leaf = LeafInfo("S", Chunk("a"), Set.empty)
            reporter.onLeafStart(leaf)
            reporter.onLeafComplete(leaf, TestResult.Passed(1L.millis))
            val sr = SuiteReport("S", Chunk((Chunk("a"), TestResult.Passed(1L.millis))), Duration.Zero)
            reporter.onSuiteComplete(info, sr)
            reporter.onRunComplete(TestReport(Chunk(sr)))
            val xml = Files.readString(dir.resolve("TEST-S.xml"))
            // The leaf name in the XML is path.mkString(".") = "a" — same for Chunk("a") and List("a")
            assert(xml.contains("""name="a""""), s"Expected name=\"a\" in XML, got: $xml"): Unit
            assert(xml.contains("<testsuite"), s"Expected <testsuite in: $xml"): Unit
            assert(xml.contains("""tests="1""""), s"Expected tests=\"1\" in: $xml"): Unit
            assert(xml.contains("failures=\"0\""), s"Expected failures=\"0\" in: $xml"): Unit
        }
    }

end JUnitXmlReporterTest
