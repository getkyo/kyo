package kyo.doctest.internal

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kyo.*
import kyo.doctest.*

/** On-disk content-hash cache for compiled block results.
  *
  * Layout: each cache entry is a single file at `<root>/<sha256>.ok` (for successful compiles) or `<root>/<sha256>.fail` (for failed
  * compiles). The cache key is SHA-256 of all inputs (block body, scope-closure bodies, classpath fingerprint, scalaVersion, sorted scalac
  * options) fed with length-prefix delimiters to prevent preimage collisions.
  *
  * File contents: `.ok` files contain a newline-separated list of serialized warning diagnostics (empty if no warnings). `.fail` files
  * contain a newline-separated list of serialized error diagnostics followed by warnings.
  *
  * Serialized diagnostic format (one per line): `SEVERITY|FILE|LINE|COL|MESSAGE` where MESSAGE has newlines replaced with `\n` literal.
  */
final private[kyo] class BlockCache private (root: kyo.Path):

    /** Looks up a cached result for a block.
      *
      * @param block
      *   The block whose result is being requested.
      * @param scopeClosure
      *   The bodies of any prior blocks whose bindings are in scope (for inherited/env scopes). Empty for isolated blocks.
      * @param classpathFingerprint
      *   The fingerprint from ClasspathFingerprint.compute.
      * @param scalaVersion
      *   The Scala version string (e.g. "3.8.3").
      * @param scalacOpts
      *   Scalac options in effect for this run.
      * @return
      *   Present(Entry) if a cached result exists, Absent if not.
      */
    def lookup(
        block: Block,
        scopeClosure: Chunk[String],
        classpathFingerprint: String,
        scalaVersion: String,
        scalacOpts: Chunk[String]
    )(using Frame): Maybe[BlockCache.Entry] < Sync =
        val key      = cacheKey(block, scopeClosure, classpathFingerprint, scalaVersion, scalacOpts)
        val okFile   = root / s"$key.ok"
        val failFile = root / s"$key.fail"
        // Cache files may be truncated or corrupted by a crashed prior run; treat any IO error as a cache miss so
        // the block re-runs rather than serving stale or corrupt data. A single boundary runner with one outer
        // Abort.run captures all four ops (exists/read for ok and fail files); all errors route to Maybe.empty,
        // so no per-op IoError label is needed here.
        Abort.run[FileException] {
            Path.runReadOnly {
                okFile.exists.flatMap {
                    case true =>
                        okFile.read.map { content =>
                            try Maybe(BlockCache.Entry(Driver.Outcome.Ok(deserializeDiagnostics(content))))
                            catch case _: Throwable => Maybe.empty
                        }
                    case false =>
                        failFile.exists.flatMap {
                            case true =>
                                failFile.read.map { content =>
                                    try
                                        val (errors, warnings) = deserializeErrorsAndWarnings(content)
                                        Maybe(BlockCache.Entry(Driver.Outcome.Failed(errors, warnings)))
                                    catch case _: Throwable => Maybe.empty
                                }
                            case false =>
                                Maybe.empty
                        }
                }
            }
        }.map {
            case Result.Success(v) => v
            case _                 => Maybe.empty
        }
    end lookup

    /** Records a compile result in the cache.
      *
      * @param block
      *   The block that was compiled.
      * @param scopeClosure
      *   The bodies of any prior blocks in scope.
      * @param classpathFingerprint
      *   The classpath fingerprint used for this compile.
      * @param scalaVersion
      *   The Scala version string.
      * @param scalacOpts
      *   Scalac options in effect.
      * @param result
      *   The compile result to store.
      */
    def record(
        block: Block,
        scopeClosure: Chunk[String],
        classpathFingerprint: String,
        scalaVersion: String,
        scalacOpts: Chunk[String],
        result: Driver.Outcome
    )(using Frame): Unit < Sync =
        val key = cacheKey(block, scopeClosure, classpathFingerprint, scalaVersion, scalacOpts)
        val (filePath, content) = result match
            case Driver.Outcome.Ok(warnings) =>
                (root / s"$key.ok", serializeDiagnostics(warnings))
            case Driver.Outcome.Failed(errors, warnings) =>
                (root / s"$key.fail", serializeErrorsAndWarnings(errors, warnings))
        // Swallow write errors: a failed cache write means the next run recompiles. Not fatal.
        Abort.run[FileException](Path.run(filePath.write(content))).unit
    end record

    /** Cache key: SHA-256 of all components fed with length-prefix delimiters to prevent preimage collisions. */
    private def cacheKey(
        block: Block,
        scopeClosure: Chunk[String],
        classpathFingerprint: String,
        scalaVersion: String,
        scalacOpts: Chunk[String]
    ): String =
        val md = MessageDigest.getInstance("SHA-256")
        def feed(s: String): Unit =
            val bytes = s.getBytes(StandardCharsets.UTF_8)
            md.update((bytes.length >>> 24).toByte)
            md.update((bytes.length >>> 16).toByte)
            md.update((bytes.length >>> 8).toByte)
            md.update((bytes.length).toByte)
            md.update(bytes)
        end feed
        feed(block.body)
        feed(classpathFingerprint)
        feed(scalaVersion)
        feed(scopeClosure.size.toString)
        scopeClosure.foreach(feed)
        val sortedOpts = scalacOpts.toSeq.sorted
        feed(sortedOpts.size.toString)
        sortedOpts.foreach(feed)
        md.digest().map(b => f"${b & 0xff}%02x").mkString
    end cacheKey

    // Serialize a list of diagnostics to a multi-line string, one diagnostic per line.
    private def serializeDiagnostics(diags: Chunk[Driver.Diagnostic]): String =
        diags.toSeq.map(serializeDiagnostic).mkString("\n")

    // Serialize errors followed by warnings, separated by a marker line.
    private def serializeErrorsAndWarnings(
        errors: Chunk[Driver.Diagnostic],
        warnings: Chunk[Driver.Diagnostic]
    ): String =
        val errorPart   = errors.toSeq.map(serializeDiagnostic).mkString("\n")
        val warningPart = warnings.toSeq.map(serializeDiagnostic).mkString("\n")
        if warnings.isEmpty then errorPart
        else if errors.isEmpty then s"---WARNINGS---\n$warningPart"
        else s"$errorPart\n---WARNINGS---\n$warningPart"
    end serializeErrorsAndWarnings

    // Parse a `.ok` file: all lines are warnings (or empty if no warnings).
    private def deserializeDiagnostics(content: String): Chunk[Driver.Diagnostic] =
        if content.trim.isEmpty then Chunk.empty
        else Chunk.from(content.split("\n", -1).filter(_.nonEmpty).map(deserializeDiagnostic))

    // Parse a `.fail` file: error diagnostics before the marker, warnings after.
    private def deserializeErrorsAndWarnings(
        content: String
    ): (Chunk[Driver.Diagnostic], Chunk[Driver.Diagnostic]) =
        val markerIdx = content.indexOf("---WARNINGS---")
        if markerIdx < 0 then
            val errors = Chunk.from(content.split("\n", -1).filter(_.nonEmpty).map(deserializeDiagnostic))
            (errors, Chunk.empty)
        else
            val errorPart   = content.substring(0, markerIdx)
            val warningPart = content.substring(markerIdx + "---WARNINGS---".length)
            val errors      = Chunk.from(errorPart.split("\n", -1).filter(_.nonEmpty).map(deserializeDiagnostic))
            val warnings    = Chunk.from(warningPart.split("\n", -1).filter(_.nonEmpty).map(deserializeDiagnostic))
            (errors, warnings)
        end if
    end deserializeErrorsAndWarnings

    // Format: SEVERITY|FILE|LINE|COL|MESSAGE  (MESSAGE has literal \n for embedded newlines)
    private def serializeDiagnostic(d: Driver.Diagnostic): String =
        val msg = d.message.replace("\\", "\\\\").replace("\n", "\\n")
        s"${d.severity}|${d.file}|${d.line}|${d.col}|$msg"

    private def deserializeDiagnostic(line: String): Driver.Diagnostic =
        val parts = line.split("\\|", 5)
        val severity = parts(0) match
            case "Error"   => Driver.Diagnostic.Severity.Error
            case "Warning" => Driver.Diagnostic.Severity.Warning
            case _         => Driver.Diagnostic.Severity.Info
        val file    = kyo.Path(parts(1))
        val lineNum = parts(2).toInt
        val col     = parts(3).toInt
        val msg     = parts(4).replace("\\n", "\n").replace("\\\\", "\\")
        Driver.Diagnostic(severity, file, lineNum, col, msg, Chunk.empty)
    end deserializeDiagnostic

end BlockCache

private[kyo] object BlockCache:

    /** A cached compile result entry. */
    case class Entry(result: Driver.Outcome) derives CanEqual

    /** Opens (or creates) a BlockCache rooted at the supplied path.
      *
      * The directory is created if it does not already exist.
      *
      * @param path
      *   Root directory for the cache.
      * @return
      *   A ready-to-use BlockCache.
      */
    def init(path: kyo.Path)(using Frame): BlockCache < (Sync & Abort[Doctest.Error]) =
        // Single boundary runner covering both ops (exists + mkDir); Path.run subsumes runReadOnly.
        // Both ops fail for the same reason (cache-dir setup failure), so a combined error label is accurate.
        Abort.recover[FileException](e => Abort.fail(Doctest.Error.IoError(path, "exists/mkDir", e))) {
            Path.run {
                path.exists.flatMap { exists =>
                    if !exists then path.mkDir.andThen(new BlockCache(path))
                    else new BlockCache(path)
                }
            }
        }

end BlockCache
