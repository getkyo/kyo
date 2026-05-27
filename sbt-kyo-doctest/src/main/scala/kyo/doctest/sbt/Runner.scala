package kyo.doctest.sbt

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files => NioFiles}
import sbt.util.Logger

/** Forks a JVM running kyo.doctest.internal.cli.Main, passing it a JSON config file and reading back a JSON result file.
  *
  * IPC pattern: plugin writes config JSON to a temp file, forks the JVM, reads result JSON from another temp file.
  *
  * The forked JVM's classpath includes all entries from `classpath` (which must include kyo-doctest and its transitive deps: kyo-core,
  * scala3-compiler, etc.) since the plugin resolves them via sbt's dependency-resolution surface (`Test/fullClasspath`).
  */
object Runner {

    /** Runs kyo-doctest by forking a JVM and reading back the result.
      *
      * @param sources
      *   Markdown files to validate.
      * @param classpath
      *   Full classpath passed to the forked JVM (must include kyo-doctest, kyo-core, scala3-compiler, etc.).
      * @param scalacOpts
      *   Scalac options forwarded to the compiler inside the forked JVM.
      * @param cacheDir
      *   Cache directory; will be created by the forked JVM if absent.
      * @param parallel
      *   Maximum number of blocks compiled concurrently inside the forked JVM.
      * @param writeCache
      *   If false the forked JVM is passed a temp cache dir that is discarded after the run (dry-run mode).
      * @param log
      *   sbt logger for status output.
      */
    def run(
        sources: Seq[File],
        classpath: Seq[File],
        scalacOpts: Seq[String],
        cacheDir: File,
        parallel: Int,
        predef: Seq[String],
        freshDriver: Boolean,
        writeCache: Boolean,
        forkJavaOptions: Seq[String],
        log: Logger
    ): Unit = {
        if (sources.isEmpty) {
            log.info("doctest: no sources to validate")
            return
        }

        // Effective cache: use a temp dir when writeCache=false so the run doesn't pollute the real cache.
        val effectiveCacheDir: File =
            if (writeCache) cacheDir
            else {
                val tmp = NioFiles.createTempDirectory("doctest-nowrite").toFile
                tmp.deleteOnExit()
                tmp
            }

        val configFile = NioFiles.createTempFile("doctest-config-", ".json").toFile
        val resultFile = NioFiles.createTempFile("doctest-result-", ".json").toFile
        configFile.deleteOnExit()
        resultFile.deleteOnExit()

        try {
            val configJson = ConfigJson.encodeConfig(
                sources     = sources,
                classpath   = classpath,
                scalacOpts  = scalacOpts,
                cacheDir    = effectiveCacheDir,
                parallel    = parallel,
                predef      = predef,
                freshDriver = freshDriver
            )
            NioFiles.writeString(configFile.toPath, configJson, StandardCharsets.UTF_8)

            val cpString = classpath.map(_.getAbsolutePath).mkString(File.pathSeparator)
            val javaHome = System.getProperty("java.home")
            val javaBin  = s"$javaHome${File.separator}bin${File.separator}java"

            val command = Seq(javaBin) ++ forkJavaOptions ++ Seq(
                "-cp", cpString,
                "kyo.doctest.internal.cli.Main",
                configFile.getAbsolutePath,
                resultFile.getAbsolutePath
            )

            log.info(s"doctest: validating ${sources.size} file(s) with ${classpath.size} classpath entries")
            log.debug(s"doctest: fork command: ${command.mkString(" ")}")

            val process = new ProcessBuilder(command: _*)
                .inheritIO()
                .start()

            val exitCode = process.waitFor()

            // Parse the result file if it was written.
            val report: Option[ParsedReport] =
                if (resultFile.exists() && resultFile.length() > 0) {
                    val resultJson = new String(NioFiles.readAllBytes(resultFile.toPath), StandardCharsets.UTF_8)
                    ConfigJson.decodeReport(resultJson)
                } else {
                    None
                }

            report.foreach { r =>
                log.info(
                    s"doctest: total=${r.totalBlocks} compiled=${r.compiled} cacheHits=${r.cacheHits} failures=${r.failureCount}"
                )
                val summary = s"total=${r.totalBlocks} compiled=${r.compiled} cacheHits=${r.cacheHits} failures=${r.failureCount}"
                val summaryFile = new File(effectiveCacheDir, "last-summary.txt")
                NioFiles.writeString(summaryFile.toPath, summary, StandardCharsets.UTF_8)
            }

            if (exitCode != 0) {
                throw new sbt.MessageOnlyException(
                    s"doctest: validation failed (exit code $exitCode). See output above for details."
                )
            }
        } finally {
            configFile.delete()
            resultFile.delete()
            if (!writeCache) {
                deleteRecursive(effectiveCacheDir)
            }
        }
    }

    private def deleteRecursive(f: File): Unit = {
        if (f.isDirectory) {
            f.listFiles().foreach(deleteRecursive)
        }
        f.delete()
        ()
    }
}

/** Summary of the fields parsed from a result JSON file. Full Doctest.Failure round-trip is not needed by the plugin. */
private[sbt] case class ParsedReport(
    totalBlocks: Int,
    cacheHits: Int,
    compiled: Int,
    failureCount: Int
)

/** Minimal JSON encode/decode for the plugin side (Scala 2.12). Does not depend on kyo-doctest classes. */
private[sbt] object ConfigJson {

    def encodeConfig(
        sources: Seq[File],
        classpath: Seq[File],
        scalacOpts: Seq[String],
        cacheDir: File,
        parallel: Int,
        predef: Seq[String],
        freshDriver: Boolean
    ): String = {
        val srcArr    = sources.map(f => quoteJson(f.getAbsolutePath))
        val cpArr     = classpath.map(f => quoteJson(f.getAbsolutePath))
        val optsArr   = scalacOpts.map(quoteJson)
        val predefArr = predef.map(quoteJson)
        s"""{
  "sources": [${srcArr.mkString(", ")}],
  "classpath": [${cpArr.mkString(", ")}],
  "scalaOpts": [${optsArr.mkString(", ")}],
  "cache": ${quoteJson(cacheDir.getAbsolutePath)},
  "parallel": $parallel,
  "predef": [${predefArr.mkString(", ")}],
  "freshDriver": $freshDriver
}"""
    }

    def decodeReport(json: String): Option[ParsedReport] = {
        try {
            val total    = extractInt(json, "totalBlocks").getOrElse(0)
            val hits     = extractInt(json, "cacheHits").getOrElse(0)
            val compiled = extractInt(json, "compiled").getOrElse(0)
            val failures = extractArrayLength(json, "failures")
            Some(ParsedReport(total, hits, compiled, failures))
        } catch {
            case _: Exception => None
        }
    }

    private def extractArrayLength(json: String, key: String): Int = {
        val keyMarker = s""""$key""""
        val keyIdx    = json.indexOf(keyMarker)
        if (keyIdx < 0) return 0
        val openBracket = json.indexOf('[', keyIdx + keyMarker.length)
        if (openBracket < 0) return 0
        // String-aware scan: skip characters inside JSON strings (honoring \" escapes) so that
        // a ']' inside a quoted value (e.g. "List[Int]") does not prematurely close the array.
        var count       = 0
        var objectDepth = 0
        var inString    = false
        var i           = openBracket + 1
        var done        = false
        while (i < json.length && !done) {
            val c = json(i)
            if (inString) {
                if (c == '\\') {
                    i += 1 // skip the escaped character
                } else if (c == '"') {
                    inString = false
                }
            } else {
                c match {
                    case '"' =>
                        inString = true
                    case '{' =>
                        if (objectDepth == 0) count += 1
                        objectDepth += 1
                    case '}' =>
                        objectDepth -= 1
                    case ']' if objectDepth == 0 =>
                        done = true
                    case _ => ()
                }
            }
            i += 1
        }
        count
    }

    private def quoteJson(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private def extractInt(json: String, key: String): Option[Int] = {
        val keyMarker = s""""$key""""
        val keyIdx    = json.indexOf(keyMarker)
        if (keyIdx < 0) return None
        val colonIdx = json.indexOf(':', keyIdx + keyMarker.length)
        if (colonIdx < 0) return None
        val rest   = json.substring(colonIdx + 1).dropWhile(c => c == ' ' || c == '\n' || c == '\r' || c == '\t')
        val numStr = rest.takeWhile(_.isDigit)
        if (numStr.isEmpty) None
        else {
            try Some(numStr.toInt)
            catch {
                case _: NumberFormatException => None
            }
        }
    }
}
