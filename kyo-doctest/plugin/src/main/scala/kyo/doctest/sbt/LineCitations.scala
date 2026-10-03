package kyo.doctest.sbt

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files => NioFiles}
import sbt.MessageOnlyException
import sbt.util.Logger
import scala.util.matching.Regex

/** A source line-number citation in Markdown: `Foo.scala:12`, `build.sbt:10-20`, a code span holding only `:40-52`, `#L85`, `~L85-95`,
  * "line 42".
  *
  * A line number goes stale on the next edit of the cited file, and nothing reports it: the citation keeps pointing somewhere, usually at
  * a blank line or an unrelated statement. A doc cites the symbol instead (`Tasty.computeFullName`), which a rename breaks loudly. This is
  * why the check fails the build rather than warning.
  *
  * Fenced blocks are code or program output, where `File.scala:12` is data (a `Frame` position in a log line, a compiler diagnostic) that
  * the doc must render verbatim, so their content is exempt except for a `//` or `#` comment made only of citations, which is a citation
  * annotating the code. A file:line match requires a source-like extension, which is what keeps `localhost:5432`, `12:30`, `3:1` and
  * `postgres:16` out.
  */
private[sbt] object LineCitations {

    /** One citation: the 1-indexed line in the Markdown file and the matched text. */
    final case class Citation(line: Int, text: String)

    private val extensions =
        "scala|sbt|java|kt|c|h|cc|cpp|hpp|js|mjs|cjs|ts|tsx|jsx|py|rs|go|zig|sh|yml|yaml|toml|conf|json|md|proto|sql|html|css|xml|properties"

    private val fileLine: Regex = ("""(?<![\w.-])[\w./-]*\.(?:""" + extensions + """):\d+(?:\s*[-,]\s*\d+(?![\w.]))*""").r
    private val bareLine: Regex = """(?<=`):\d+(?:\s*[-,]\s*\d+)*(?=`)""".r
    private val anchor: Regex   = """#L\d+""".r
    private val lRange: Regex   = """(?<![\w#])~?L\d+-\d+\b|~L\d+\b""".r
    private val prose: Regex    = """(?i)\blines?\s+~?\d+""".r

    private val patterns = Seq(fileLine, bareLine, anchor, lRange, prose)

    private val fenceOpen: Regex = """^\s*(`{3,}|~{3,})""".r

    /** Every citation in `markdown`, in document order. */
    def scan(markdown: String): Seq[Citation] = {
        val lines                 = markdown.split("\n", -1)
        val out                   = Vector.newBuilder[Citation]
        var fence: Option[String] = None
        var i                     = 0
        while (i < lines.length) {
            val line = lines(i).stripSuffix("\r")
            fence match {
                case None =>
                    fenceOpen.findFirstMatchIn(line) match {
                        case Some(m) => fence = Some(m.group(1))
                        case None    => out ++= matches(line).map(Citation(i + 1, _))
                    }
                case Some(open) =>
                    if (closes(open, line)) fence = None
                    else out ++= citationComment(line).map(Citation(i + 1, _))
            }
            i += 1
        }
        out.result()
    }

    /** One `<path relative to root>:<md line>: <citation>` entry per citation across `files`, in file then line order. */
    def check(files: Seq[File], root: File): Seq[String] = {
        val rootPath = root.getCanonicalFile.toPath
        files.filter(_.isFile).flatMap { file =>
            val content = new String(NioFiles.readAllBytes(file.toPath), StandardCharsets.UTF_8)
            val label   = rootPath.relativize(file.getCanonicalFile.toPath).toString.replace(File.separatorChar, '/')
            scan(content).map(c => s"$label:${c.line}: ${c.text}")
        }
    }

    /** Fails with every citation in `files` listed, or returns when there is none. */
    def enforce(files: Seq[File], root: File, log: Logger): Unit = {
        val found = check(files, root)
        if (found.nonEmpty) {
            found.foreach(f => log.error(s"doctest: source line-number citation: $f"))
            throw new MessageOnlyException(
                s"doctest: ${found.size} source line-number citation(s) in Markdown. Cite the symbol (a type, method or setting name) " +
                    "with its file path instead; a line number goes stale on the next edit."
            )
        }
    }

    /** The build's git-tracked Markdown files, or None when `root` is not inside a git work tree. */
    def trackedMarkdown(root: File): Option[Seq[File]] = {
        val out  = new StringBuilder
        val code = scala.sys.process.Process(Seq("git", "ls-files", "-z", "--", "*.md"), root).!(
            scala.sys.process.ProcessLogger(line => { out.append(line).append('\n'); () }, _ => ())
        )
        if (code != 0) None
        else Some(out.toString.split('\u0000').map(_.trim).filter(_.nonEmpty).map(new File(root, _)).toSeq)
    }

    private def matches(text: String): Seq[String] =
        patterns.flatMap(p => p.findAllMatchIn(text).map(m => (m.start, m.matched))).sortBy(_._1).map(_._2)

    private def closes(open: String, line: String): Boolean = {
        val t = line.trim
        t.length >= open.length && t.forall(_ == open.charAt(0))
    }

    private def citationComment(line: String): Seq[String] = {
        val idx = commentStart(line)
        if (idx < 0) Seq.empty
        else {
            val comment = line.substring(idx).dropWhile(c => c == '/' || c == '#')
            val found   = fileLine.findAllMatchIn(comment).map(_.matched).toSeq
            if (found.nonEmpty && fileLine.replaceAllIn(comment, "").forall(c => c.isWhitespace || c == ',' || c == ';')) found
            else Seq.empty
        }
    }

    private def commentStart(line: String): Int = {
        val slashes = line.indexOf("//")
        if (slashes >= 0) slashes
        else if (line.trim.startsWith("#")) line.indexOf('#')
        else -1
    }
}
