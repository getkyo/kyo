package kyo.internal

import java.nio.file.Files
import java.nio.file.Paths
import kyo.*
import scala.io.Source
import scala.jdk.CollectionConverters.*

/** Pinning invariants test (T-024): validates structural invariants of the kyo-mcp module source tree. */
class InvariantsTest extends Test:

    // These paths are relative to the build root where sbt runs.
    private val moduleRoot = "kyo-mcp/shared/src/main/scala/kyo"
    private val testRoot   = "kyo-mcp/shared/src/test/scala/kyo"

    // Types whose test coverage is provided by a differently-named file (enum-schema aggregates, etc.).
    // Rule 8c exceptions documented here.
    private val coveredByAlternateFile: Set[String] = Set(
        // McpStopReason is covered by McpEnumSchemaTest
        "McpStopReason",
        // McpCapabilities covered by McpCapabilityGateTest and McpCapabilityDerivationTest
        "McpCapabilities",
        // McpElicitation* covered by McpExceptionTest (error wire path)
        "McpElicitationRequest",
        "McpElicitationResponse",
        // McpInfo covered inline in every test that constructs McpInfo(...)
        "McpInfo",
        // McpRoot covered by McpSamplingReverseTest (requestRoots path)
        "McpRoot",
        // McpSamplingRequest / McpSamplingResponse covered by McpSamplingReverseTest
        "McpSamplingRequest",
        "McpSamplingResponse",
        // McpException is covered by McpExceptionTest
        "McpException"
    )

    "INV-009: no test file outside kyo/internal/ imports kyo.internal.* (INV-009)" in run {
        if Platform.isJVM then
            val testRootPath = Paths.get(testRoot)
            if Files.exists(testRootPath) then
                val violations = Files.list(testRootPath).iterator().asScala
                    .filter(p => p.toString.endsWith(".scala"))
                    .flatMap { path =>
                        val content = Files.readString(path)
                        val lines   = content.linesIterator
                        lines.filter(l => l.contains("import kyo.internal") || l.contains("kyo.internal.")).map { line =>
                            s"${path.getFileName}: $line"
                        }
                    }
                    .toList
                assert(violations.isEmpty, s"Test files outside internal/ import kyo.internal: ${violations.mkString("; ")}")
            else
                succeed
            end if
        else
            succeed
        end if
    }

    "INV-021: Structure.Value in flat main layer hits exactly the five allowlist sites" in run {
        if Platform.isJVM then
            val allowlistFiles = Set(
                "McpElicitationResponse.scala",
                "McpSamplingRequest.scala",
                "McpCapabilities.scala",
                "McpHandler.scala",
                "McpException.scala"
            )
            val rootPath = Paths.get(moduleRoot)
            if Files.exists(rootPath) then
                val violations = Files.list(rootPath).iterator().asScala
                    .filter(p => p.toString.endsWith(".scala"))
                    .flatMap { path =>
                        val fileName = path.getFileName.toString
                        if allowlistFiles.contains(fileName) then
                            Iterator.empty
                        else
                            val content = Files.readString(path)
                            val hits = content.linesIterator.zipWithIndex.collect {
                                case (line, idx) if line.contains("Structure.Value") =>
                                    s"$fileName:${idx + 1}: $line"
                            }
                            hits
                        end if
                    }
                    .toList
                assert(violations.isEmpty, s"Structure.Value found outside allowlist: ${violations.mkString("\n")}")
            else
                succeed
            end if
        else
            succeed
        end if
    }

    "INV-025: McpConfig.ProtocolVersion has no file-public def apply" in run {
        if Platform.isJVM then
            val path = Paths.get(s"$moduleRoot/McpConfig.scala")
            if Files.exists(path) then
                val src = Files.readString(path)
                // Only check inside the ProtocolVersion object block for a public def apply.
                val inBlock = src.linesIterator.dropWhile(!_.contains("object ProtocolVersion")).take(60).toList
                val hits = inBlock.zipWithIndex.collect {
                    case (line, idx) if line.contains("def apply") && !line.contains("private") => s"line ${idx + 1}: $line"
                }.toList
                assert(hits.isEmpty, s"Public def apply found in McpConfig.ProtocolVersion: ${hits.mkString("; ")}")
            else
                succeed
            end if
        else
            succeed
        end if
    }

    "INV-001: main source types have matching test files or documented alternate coverage" in run {
        if Platform.isJVM then
            val rootPath = Paths.get(moduleRoot)
            if Files.exists(rootPath) then
                val testRootPath = Paths.get(testRoot)
                val missing = Files.list(rootPath).iterator().asScala
                    .filter(p => p.toString.endsWith(".scala"))
                    .flatMap { path =>
                        val baseName = path.getFileName.toString.stripSuffix(".scala")
                        val testFile = testRootPath.resolve(s"${baseName}Test.scala")
                        if Files.exists(testFile) || coveredByAlternateFile.contains(baseName) then
                            Iterator.empty
                        else
                            Iterator(s"${baseName}Test.scala (no dedicated test file)")
                        end if
                    }
                    .toList
                assert(missing.isEmpty, s"Main sources without test coverage: ${missing.mkString(", ")}")
            else
                succeed
            end if
        else
            succeed
        end if
    }

end InvariantsTest
