import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import sbt.*

/** What the source generators over vendored data share: reading a directory of vendored files against its `MANIFEST`, and emitting a
  * file's content as a Scala object of string literals.
  *
  * A manifest has `#` comments and these line kinds only: `source <url>`, `source-sha256 <sha-256>`, `license <terms>`, and
  * `file <name> <sha-256> <path inside the source>`. Every listed file is verified against its SHA-256, and a malformed manifest, a
  * mismatch, a missing file or a file the manifest does not list fails the build, so generated sources always come from the published
  * bytes.
  *
  * Literals are the only way to carry this much data on all four platforms: an array literal compiles into one JVM method, which a few
  * thousand entries overflow at 64 KB. Each literal stays under 60000 bytes because the JVM caps a string constant at 65535 bytes of
  * modified UTF-8, and each is its own `val` because the compiler folds `"a" + "b"` into one constant, which would exceed the cap again.
  */
object VendoredFiles {

    val MaxLiteralBytes = 60000

    /** A verified set: its `source` and `license` lines, and each file's bytes. */
    final case class Vendored(source: String, license: String, files: Map[String, Array[Byte]]) {

        /** Each file decoded as UTF-8. */
        def texts: Map[String, String] = files.map { case (file, bytes) => file -> new String(bytes, StandardCharsets.UTF_8) }

        /** The header lines that name where a generated file's data comes from and under which terms. */
        def attribution: Seq[String] = Seq(s"Third-party data from $source, used under $license.")
    }

    /** Reads the directory's `MANIFEST`, checks every listed file's SHA-256, and returns the set. `generator` names the caller in the
      * build's error messages.
      */
    def verified(dir: File, generator: String): Vendored = {
        val manifest = dir / "MANIFEST"
        if (!manifest.exists) sys.error(s"[$generator] missing $manifest")
        def fail(detail: String): Nothing = sys.error(s"[$generator] $manifest: $detail")
        val lines = IO.readLines(manifest, StandardCharsets.UTF_8).filterNot(l => l.startsWith("#") || l.trim.isEmpty)
        val kinds = lines.map(_.takeWhile(_ != ' '))
        kinds.filterNot(Set("source", "source-sha256", "license", "file")).foreach(kind => fail(s"unknown line kind '$kind'"))
        Seq("source", "license").foreach(kind => if (kinds.count(_ == kind) != 1) fail(s"needs exactly one '$kind' line"))
        if (kinds.count(_ == "source-sha256") > 1) fail("has more than one 'source-sha256' line")
        val entries = lines.filter(_.startsWith("file ")).map { line =>
            line.split(" ") match {
                case Array("file", name, sha, _) if sha.matches("[0-9a-f]{64}") => name -> sha
                case _                                                          => fail(s"malformed line: $line")
            }
        }
        if (entries.map(_._1).distinct.size != entries.size) fail("lists a file twice")
        val listed   = entries.toMap
        val present  = (dir * "*").get.map(_.getName).filterNot(_ == "MANIFEST").toSet
        val unlisted = present -- listed.keySet
        if (unlisted.nonEmpty) fail(s"does not list ${unlisted.toSeq.sorted.mkString(", ")}")
        val files = listed.map { case (file, expected) =>
            val path = dir / file
            if (!path.exists) fail(s"lists $file, which is missing")
            val bytes  = IO.readBytes(path)
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString
            if (actual != expected) fail(s"SHA-256 of $file is $actual, the manifest says $expected")
            file -> bytes
        }
        def value(kind: String): String = lines.find(_.startsWith(kind + " ")).map(_.drop(kind.length + 1).trim).getOrElse("")
        Vendored(value("source"), value("license"), files)
    }

    /** An object holding `text` in a `text` value. */
    def emitEmbeddedText(pkg: String, generator: String, source: String, attribution: Seq[String], file: String, text: String): String = {
        val literals = chunkText(text)
        val name     = s"Embedded${objectName(file)}"
        val sb       = new StringBuilder
        sb.append(header(generator, source, attribution))
        sb.append(s"package $pkg\n\n")
        sb.append(s"object $name:\n")
        literals.zipWithIndex.foreach { case (literal, i) => sb.append(part(i, literal)) }
        sb.append(s"    lazy val text: String = ${literals.indices.map(i => s"part$i").mkString("Seq(", ", ", ").mkString")}\n")
        sb.append(s"end $name\n")
        sb.toString
    }

    /** An object holding the file's bytes, each as the char of the same value, so a file that is not UTF-8 survives; `text` reads them as
      * UTF-8 through the platform's decoder, never the module's.
      */
    def emitEmbeddedBytes(
        pkg: String,
        generator: String,
        source: String,
        attribution: Seq[String],
        file: String,
        bytes: Array[Byte]
    ): String = {
        val literals = chunkText(new String(bytes, StandardCharsets.ISO_8859_1))
        val name     = s"Embedded${objectName(file)}"
        val joined   = literals.indices.map(i => s"part$i").mkString("Seq(", ", ", ").mkString")
        val sb       = new StringBuilder
        sb.append(header(generator, source, attribution))
        sb.append(s"package $pkg\n\n")
        sb.append(s"object $name:\n")
        literals.zipWithIndex.foreach { case (literal, i) => sb.append(part(i, literal)) }
        sb.append(s"    lazy val bytes: kyo.Span[Byte] = kyo.Span.from($joined.map(_.toByte))\n")
        sb.append("    lazy val text: String = new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)\n")
        sb.append(s"end $name\n")
        sb.toString
    }

    def part(index: Int, literal: String): String =
        "    private val part" + index + ": String = \"" + literal + "\"\n"

    /** The generated file's first lines: the generator and input, then `attribution`, each line as a `//` comment. */
    def header(generator: String, source: String, attribution: Seq[String]): String =
        (s"Generated by project/$generator.scala from $source. Do not edit." +: attribution)
            .map(line => if (line.isEmpty) "//\n" else s"// $line\n")
            .mkString

    /** Joins ASCII tokens into literals of at most [[MaxLiteralBytes]] bytes, never splitting a token. */
    def chunkAscii(tokens: Seq[String]): Seq[String] = {
        val literals = Seq.newBuilder[String]
        val current  = new StringBuilder
        tokens.foreach { token =>
            if (current.length + token.length > MaxLiteralBytes) { literals += current.toString; current.clear() }
            current.append(token)
        }
        if (current.nonEmpty || tokens.isEmpty) literals += current.toString
        literals.result()
    }

    /** Escapes arbitrary text into ASCII literals whose values each take at most [[MaxLiteralBytes]] bytes of modified UTF-8, never
      * splitting a surrogate pair.
      */
    def chunkText(text: String): Seq[String] = {
        val literals = Seq.newBuilder[String]
        val current  = new StringBuilder
        var bytes    = 0
        var i        = 0
        while (i < text.length) {
            val c     = text.charAt(i)
            val width = if (Character.isHighSurrogate(c) && i + 1 < text.length) 2 else 1
            val cost  = text.substring(i, i + width).map(ch => if (ch >= 0x01 && ch <= 0x7f) 1 else if (ch <= 0x7ff) 2 else 3).sum
            if (bytes + cost > MaxLiteralBytes) { literals += current.toString; current.clear(); bytes = 0 }
            text.substring(i, i + width).foreach { ch =>
                if (ch >= 0x20 && ch <= 0x7e && ch != '"' && ch != '\\') current.append(ch)
                else current.append(f"\\u${ch.toInt}%04x")
            }
            bytes += cost
            i += width
        }
        literals += current.toString
        literals.result()
    }

    /** `index-iso-8859-15.txt` to `IndexIso8859_15`, `encodings.json` to `Encodings`, `big5_errors.html` to `Big5ErrorsHtml`: file-name
      * words capitalized, with an underscore between two words that meet digit to digit.
      */
    def objectName(file: String): String = {
        val words = file.stripSuffix(".txt").stripSuffix(".json").split("[-._]").toSeq.filter(_.nonEmpty)
        words.foldLeft("") { (acc, word) =>
            val sep = if (acc.nonEmpty && acc.last.isDigit && word.head.isDigit) "_" else ""
            acc + sep + word.head.toUpper + word.tail
        }
    }

    /** Writes `content` unless the file already holds it, so an unchanged generated source keeps its timestamp. */
    def writeIfChanged(file: File, content: String): File = {
        if (!file.exists || IO.read(file, StandardCharsets.UTF_8) != content) IO.write(file, content, StandardCharsets.UTF_8)
        file
    }

    /** Fails the build when two generated files would have the same name. */
    def requireDistinctNames(generator: String, files: Seq[File]): Seq[File] = {
        val clashes = files.groupBy(_.getName).collect { case (name, same) if same.size > 1 => name }
        if (clashes.nonEmpty) sys.error(s"[$generator] embedded files share an object name: ${clashes.mkString(", ")}")
        files
    }
}
