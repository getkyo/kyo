package kyo.doctest.sbt

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files => NioFiles, Path => NioPath, Paths}
import org.scalafmt.interfaces.Scalafmt
import sbt.util.Logger

/** Rewrites the scala code blocks in Markdown files in place using scalafmt and the project's `.scalafmt.conf`.
  *
  * A doctest block is a statement-sequence fragment, not a standalone compilation unit, so each block is wrapped in a synthetic
  * `object` before formatting and unwrapped afterward (handling both the brace form and the `removeOptionalBraces` colon form scalafmt
  * may emit). Blocks that fail to parse (intentionally broken examples, pseudo-code) are left untouched, as are blocks tagged with a bare
  * `noformat` token on the fence info string (for example `scala noformat`).
  *
  * The formatter lives on the sbt (Scala 2.12) side rather than in the Scala 3 runner so that scalafmt's classpath never mixes with the
  * forked dotty driver's.
  */
private[sbt] object Formatter {

    private val WrapperObject = "KyoDoctestFormatWrapper"
    private val Fence         = "```"
    private val ScalaInfo     = "scala"
    private val WrapperHeader = "object " + WrapperObject
    private val OpenBrace     = WrapperHeader + " {"
    private val EndMarker     = "end " + WrapperObject

    /** Per-file outcome: how many blocks were reformatted, left unchanged, or skipped (un-parseable or `noformat`). */
    final case class FileResult(file: File, reformatted: Int, unchanged: Int, skipped: Int)

    /** Reformats every existing source in place using the supplied `.scalafmt.conf`. */
    def run(sources: Seq[File], scalafmtConf: File, log: Logger): Seq[FileResult] =
        if (!scalafmtConf.exists()) {
            log.warn(s"doctest-format: scalafmt config not found at $scalafmtConf; skipping")
            Seq.empty
        } else {
            val scalafmt = Scalafmt.create(getClass.getClassLoader)
            try {
                val confPath = scalafmtConf.toPath
                sources.filter(_.exists()).map { file =>
                    val result = formatFile(scalafmt, confPath, file)
                    val detail = s"${result.reformatted} reformatted, ${result.unchanged} unchanged, ${result.skipped} skipped"
                    log.info(s"doctest-format: $file ($detail)")
                    result
                }
            } finally scalafmt.clear()
        }

    private def formatFile(scalafmt: Scalafmt, confPath: NioPath, file: File): FileResult = {
        val original                                     = new String(NioFiles.readAllBytes(file.toPath), StandardCharsets.UTF_8)
        val (rewritten, reformatted, unchanged, skipped) = rewrite(scalafmt, confPath, original)
        if (rewritten != original) NioFiles.write(file.toPath, rewritten.getBytes(StandardCharsets.UTF_8))
        FileResult(file, reformatted, unchanged, skipped)
    }

    /** Pure transform: scans `content` for ` ```scala ` fences, reformats each block body, and returns the rewritten content plus counts.
      * Package-private so it can be exercised directly without touching disk.
      */
    private[sbt] def rewrite(scalafmt: Scalafmt, confPath: NioPath, content: String): (String, Int, Int, Int) = {
        val lines   = content.split("\n", -1).toVector
        val out     = Vector.newBuilder[String]
        val fmtFile = Paths.get("DoctestBlock.scala")
        var i           = 0
        var reformatted = 0
        var unchanged   = 0
        var skipped     = 0
        while (i < lines.length) {
            val line = lines(i)
            scalaFenceInfo(line) match {
                case Some(info) =>
                    out += line // opening fence, preserved verbatim (directives included)
                    val bodyStart = i + 1
                    var j         = bodyStart
                    while (j < lines.length && !isClosingFence(lines(j))) j += 1
                    val bodyLines = lines.slice(bodyStart, j)
                    val body      = bodyLines.mkString("\n")
                    val replacement: Vector[String] =
                        if (j >= lines.length || hasNoFormat(info)) { skipped += 1; bodyLines }
                        else
                            formatBlock(scalafmt, confPath, fmtFile, body) match {
                                case Some(formatted) if formatted != body => reformatted += 1; formatted.split("\n", -1).toVector
                                case Some(_)                               => unchanged += 1; bodyLines
                                case None                                  => skipped += 1; bodyLines
                            }
                    out ++= replacement
                    if (j < lines.length) out += lines(j) // closing fence
                    i = j + 1
                case None =>
                    out += line
                    i += 1
            }
        }
        (out.result().mkString("\n"), reformatted, unchanged, skipped)
    }

    // Some("") for ```scala, Some("<info>") for ```scala <info>, None otherwise.
    private def scalaFenceInfo(line: String): Option[String] = {
        val t = line.trim
        if (t == Fence + ScalaInfo) Some("")
        else if (t.startsWith(Fence + ScalaInfo + " ")) Some(t.substring((Fence + ScalaInfo).length).trim)
        else None
    }

    private def isClosingFence(line: String): Boolean = line.trim == Fence

    private def hasNoFormat(info: String): Boolean = info.split("\\s+").contains("noformat")

    private def formatBlock(scalafmt: Scalafmt, confPath: NioPath, fmtFile: NioPath, body: String): Option[String] = {
        if (body.trim.isEmpty) return Some(body)
        // Indent only STRUCTURAL lines (those outside a multi-line `"""` string) by one level so scalafmt
        // sees a well-formed object body. Multi-line string CONTENT is left at its original column, so the
        // literal's value is preserved exactly. scalafmt then normalises the structural indentation, which
        // unwrap strips back off. Indenting every line (including string interiors) would change a string's
        // value; indenting none confuses scalafmt's Scala-3 significant-indentation parse.
        val wrapped = OpenBrace + "\n" + indentStructural(body, "  ") + "\n}\n"
        val formatted =
            try scalafmt.format(confPath, fmtFile, wrapped)
            catch { case _: Throwable => return None }
        unwrap(formatted)
    }

    // Strips the synthetic wrapper object and dedents the body. Handles both `object W { ... }` and the
    // `removeOptionalBraces` form `object W:` (optionally followed by `end W`).
    private def unwrap(formatted: String): Option[String] = {
        val ls = formatted.split("\n", -1).toVector.dropWhile(_.trim.isEmpty)
        if (ls.isEmpty) return None
        val head = ls.head.trim
        if (!head.startsWith(WrapperHeader)) return None
        val brace = head.endsWith("{")
        val colon = head.endsWith(":")
        if (!brace && !colon) return None
        var bodyLines = dropTrailingBlank(ls.drop(1))
        if (brace) {
            if (bodyLines.nonEmpty && bodyLines.last.trim == "}") bodyLines = bodyLines.init
        } else if (bodyLines.nonEmpty && bodyLines.last.trim == EndMarker) bodyLines = bodyLines.init
        bodyLines = dropTrailingBlank(bodyLines)
        Some(dropTrailingBlank(dedentStructural(bodyLines).dropWhile(_.trim.isEmpty)).mkString("\n"))
    }

    // Add `prefix` to every structural line (one outside a multi-line `"""` string); leave string-interior
    // and blank lines untouched so string literals keep their exact content.
    private def indentStructural(body: String, prefix: String): String = {
        val lines      = body.split("\n", -1).toVector
        val structural = structuralFlags(lines)
        lines
            .zip(structural)
            .map {
                case (l, true) if l.nonEmpty => prefix + l
                case (l, _)                  => l
            }
            .mkString("\n")
    }

    // Dedent only "structural" lines (those that begin outside a multi-line `"""` string) by their common
    // leading-space prefix. Lines inside a triple-quoted string keep their exact indentation, since that
    // whitespace is part of the literal. scalafmt does not re-indent string content, so a naive
    // min-over-all-lines dedent would under-dedent the code whenever a block contains a multi-line string.
    private def dedentStructural(lines: Vector[String]): Vector[String] = {
        val structural = structuralFlags(lines)
        val indents =
            lines.zip(structural).collect { case (l, true) if l.trim.nonEmpty => l.takeWhile(_ == ' ').length }
        val dedent = if (indents.isEmpty) 0 else indents.min
        lines.zip(structural).map {
            case (l, true) if l.length >= dedent => l.substring(dedent)
            case (l, true)                       => l.dropWhile(_ == ' ')
            case (l, _)                          => l
        }
    }

    // For each line, true if the line BEGINS outside a multi-line string literal. An odd number of `"""`
    // on a line flips the in-string state for the line that follows.
    private def structuralFlags(lines: Vector[String]): Vector[Boolean] = {
        val flags    = Array.ofDim[Boolean](lines.length)
        var inString = false
        var i        = 0
        while (i < lines.length) {
            flags(i) = !inString
            val triples = lines(i).split("\"\"\"", -1).length - 1
            if (triples % 2 == 1) inString = !inString
            i += 1
        }
        flags.toVector
    }

    private def dropTrailingBlank(v: Vector[String]): Vector[String] = {
        var k = v.length
        while (k > 0 && v(k - 1).trim.isEmpty) k -= 1
        v.take(k)
    }
}
