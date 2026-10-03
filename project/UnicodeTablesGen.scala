import java.nio.charset.StandardCharsets
import sbt.*
import scala.collection.mutable

/** Turns the vendored Unicode Character Database files and the RFC 3454 text into the main source `UnicodeTables`, the data SASLprep and
  * its NFKC normalization read.
  *
  * The inputs are the sets under the module's `shared/src/main/unicode/`, each with a `MANIFEST` in the test-vector shape and verified the
  * same way (see [[TestVectorsGen]]): `UnicodeData.txt` and `CompositionExclusions.txt` of one Unicode version, and `rfc3454.txt`. What
  * is emitted:
  *
  *   - the canonical combining class of every code point whose class is not 0;
  *   - the full compatibility decomposition of every code point that has one, recursively expanded so the normalizer never recurses
  *     (Hangul syllables are not in the table, they decompose arithmetically);
  *   - the primary composites: every pair of code points whose canonical composition is a code point that is not a composition exclusion
  *     (the listed exclusions, and the derived ones: a decomposition of one code point, or one beginning with a non-starter);
  *   - the six stringprep tables `pg_saslprep` reads, as sorted inclusive ranges: the non-ASCII spaces (C.1.2), the code points mapped to
  *     nothing (B.1), the prohibited output (the union of C.1.2, C.2.1, C.2.2, C.3, C.4, C.5, C.6, C.7, C.8 and C.9, overlapping and
  *     adjacent ranges merged), the code points unassigned in Unicode 3.2 (A.1), and the bidirectional classes R or AL (D.1) and L (D.2).
  *
  * Every `Int` array is emitted as string literals of two characters per value, base 32768 so no character is a surrogate, and decoded
  * once at class initialization: a literal array of this size would put its initializer past the JVM's 64 KB method limit.
  */
object UnicodeTablesGen {

    /** Values per string literal: 8192 values are 16384 characters, at most 49152 bytes of modified UTF-8, inside the 65535-byte constant. */
    private val ChunkValues = 8192

    private val HangulBase = 0xac00
    private val HangulEnd  = 0xd7a3

    final case class Ranges(name: String, table: String, ranges: Seq[(Int, Int)])

    /** The vendored Unicode set: its version (the set directory is `unicode-<version>`), the source URL its MANIFEST names, the licence
      * text beside the data files and the copyright line inside that text.
      */
    final case class Unicode(version: String, source: String, license: String, copyright: String)

    /** RFC 3454's Full Copyright Statement: the Internet Society copyright line and the paragraph that permits derivative works
      * assisting the RFC's implementation "provided that the above copyright notice and this paragraph are included on all such copies
      * and derivative works". The stringprep tables are such a derivative and ship in the jar, so both travel with them.
      */
    final case class Rfc(copyright: String, paragraph: Seq[String])

    def generate(inputsDir: File, outDir: File, pkg: String, objectName: String): Seq[File] = {
        val (unicode, entries) = inputs(inputsDir)
        val data               = parseUnicodeData(entries(s"unicode-${unicode.version}/UnicodeData.txt"))
        val exclusions         = parseExclusions(entries(s"unicode-${unicode.version}/CompositionExclusions.txt"))
        val rfc                = entries("ietf-rfc3454/rfc3454.txt")
        val source             = render(pkg, objectName, unicode, rfcStatement(rfc), data, exclusions, rfc)
        val target             = outDir / pkg.replace('.', '/') / s"$objectName.scala"
        if (!target.exists || IO.read(target, StandardCharsets.UTF_8) != source)
            IO.write(target, source, StandardCharsets.UTF_8)
        Seq(target)
    }

    /** The artifact's `META-INF/kyo-crypto/NOTICE` (under the module's own directory, so a shaded jar merging several artifacts'
      * `META-INF/NOTICE` files does not have to merge this one): the generated tables are a derived work of the Unicode Data Files and of RFC 3454's appendix
      * tables, so both notices travel with the jar and not only with the repository; the texts are the vendored files, never a second
      * copy.
      */
    def notice(inputsDir: File, outDir: File): Seq[File] = {
        val (unicode, entries) = inputs(inputsDir)
        val rfc                = rfcStatement(entries("ietf-rfc3454/rfc3454.txt"))
        val text               =
            s"""kyo-crypto ships Unicode tables (kyo.internal.crypto.UnicodeTables) generated from the Unicode Character Database,
               |version ${unicode.version} (${unicode.source}UnicodeData.txt and CompositionExclusions.txt), a derived work of the
               |Unicode Data Files, and stringprep tables generated from the appendix tables of RFC 3454
               |(https://www.rfc-editor.org/rfc/rfc3454.txt), a derivative work that assists in its implementation.
               |
               |RFC 3454: ${rfc.copyright}
               |
               |${rfc.paragraph.mkString("\n")}
               |
               |Unicode Character Database: ${unicode.copyright} The Data Files and the tables derived from them are provided under the
               |Unicode License v3 (https://www.unicode.org/license.txt), reproduced below.
               |
               |""".stripMargin + unicode.license
        val target = outDir / "META-INF" / "kyo-crypto" / "NOTICE"
        if (!target.exists || IO.read(target, StandardCharsets.UTF_8) != text)
            IO.write(target, text, StandardCharsets.UTF_8)
        Seq(target)
    }

    /** The copyright line under "Full Copyright Statement" and the paragraph after it, from the RFC text. */
    def rfcStatement(rfc: String): Rfc = {
        val start = rfc.lastIndexOf("Full Copyright Statement")
        if (start < 0) sys.error("unicode tables: RFC 3454 has no Full Copyright Statement")
        val lines     = rfc.substring(start).linesIterator.map(_.trim).toList.drop(1).dropWhile(_.isEmpty)
        val copyright = lines.headOption.filter(
            _.startsWith("Copyright")
        ).getOrElse(sys.error("unicode tables: RFC 3454 statement has no copyright line"))
        val paragraph = lines.drop(1).dropWhile(_.isEmpty).takeWhile(_.nonEmpty)
        if (!paragraph.exists(_.contains("derivative works"))) sys.error("unicode tables: RFC 3454 statement paragraph not found")
        Rfc(copyright, paragraph)
    }

    private def inputs(inputsDir: File): (Unicode, Map[String, String]) = {
        val sets        = TestVectorsGen.readSets(inputsDir)
        val entries     = sets.map(e => (e.set + "/" + e.name) -> e.text).toMap
        val unicodeSets = sets.map(_.set).distinct.filter(_.startsWith("unicode-"))
        if (unicodeSets.size != 1) sys.error(s"unicode tables: expected one unicode-<version> set under $inputsDir, found $unicodeSets")
        val set       = unicodeSets.head
        val license   = entries.getOrElse(s"$set/LICENSE", sys.error(s"unicode tables: $set has no LICENSE"))
        val copyright = license.linesIterator
            .map(_.trim)
            .find(_.startsWith("Copyright"))
            .getOrElse(sys.error(s"unicode tables: $set/LICENSE has no copyright line"))
        val source = IO
            .readLines(inputsDir / set / "MANIFEST", StandardCharsets.UTF_8)
            .map(_.trim)
            .collectFirst { case line if line.startsWith("source ") => line.substring(7).trim }
            .getOrElse(sys.error(s"unicode tables: $set/MANIFEST has no source line"))
        Seq("UnicodeData.txt", "CompositionExclusions.txt").foreach { name =>
            if (!entries.contains(s"$set/$name")) sys.error(s"unicode tables: $set/$name is not vendored under $inputsDir")
        }
        if (!entries.contains("ietf-rfc3454/rfc3454.txt"))
            sys.error(s"unicode tables: ietf-rfc3454/rfc3454.txt is not vendored under $inputsDir")
        (Unicode(set.substring("unicode-".length), source, license, copyright), entries)
    }

    final case class Data(
        combiningClass: Map[Int, Int],
        canonical: Map[Int, Seq[Int]],
        compatibility: Map[Int, Seq[Int]]
    )

    /** `UnicodeData.txt`: field 0 the code point, 3 the combining class, 5 the decomposition, tagged `<...>` when compatibility. A range
      * line (`<..., First>` / `<..., Last>`) carries neither a class nor a decomposition, so it needs no expansion here.
      */
    def parseUnicodeData(text: String): Data = {
        val ccc    = mutable.Map.empty[Int, Int]
        val canon  = mutable.Map.empty[Int, Seq[Int]]
        val compat = mutable.Map.empty[Int, Seq[Int]]
        text.linesIterator.filter(_.nonEmpty).foreach { line =>
            val fields = line.split(";", -1)
            if (fields.length < 6) sys.error(s"unicode tables: malformed UnicodeData line: $line")
            val code  = Integer.parseInt(fields(0), 16)
            val klass = fields(3).toInt
            if (klass != 0) ccc(code) = klass
            val decomposition = fields(5)
            if (decomposition.nonEmpty) {
                if (decomposition.startsWith("<")) {
                    val mapping = decomposition.substring(decomposition.indexOf('>') + 1).trim
                    compat(code) = mapping.split(" ").toSeq.map(Integer.parseInt(_, 16))
                } else
                    canon(code) = decomposition.split(" ").toSeq.map(Integer.parseInt(_, 16))
            }
        }
        Data(ccc.toMap, canon.toMap, compat.toMap)
    }

    /** The code points listed uncommented in `CompositionExclusions.txt`: the script-specific and post-composition-version groups. The
      * singleton and non-starter groups are commented out in the file and derived from the data instead.
      */
    def parseExclusions(text: String): Set[Int] =
        text.linesIterator
            .map(line => line.takeWhile(_ != '#').trim)
            .filter(_.nonEmpty)
            .map(Integer.parseInt(_, 16))
            .toSet

    /** The lines between `----- Start Table X -----` and `----- End Table X -----`: one code point or one inclusive range per line, a
      * `;` and a comment after it on some tables, page breaks in between.
      */
    def parseRfcTable(text: String, table: String): Seq[(Int, Int)] = {
        val start = text.indexOf(s"----- Start Table $table -----")
        val end   = text.indexOf(s"----- End Table $table -----", start)
        if (start < 0 || end < 0) sys.error(s"unicode tables: RFC 3454 table $table not found")
        val entry = "^([0-9A-F]{4,6})(?:-([0-9A-F]{4,6}))?$".r
        text.substring(start, end).linesIterator.drop(1).flatMap { raw =>
            val line = raw.takeWhile(_ != ';').trim
            line match {
                case entry(from, to) =>
                    val a = Integer.parseInt(from, 16)
                    val b = if (to == null) a else Integer.parseInt(to, 16)
                    Some((a, b))
                case _ => None
            }
        }.toSeq
    }

    def merged(ranges: Seq[(Int, Int)]): Seq[(Int, Int)] =
        ranges.sorted.foldLeft(List.empty[(Int, Int)]) {
            case ((a, b) :: rest, (c, d)) if c <= b + 1 => (a, math.max(b, d)) :: rest
            case (acc, r)                               => r :: acc
        }.reverse

    private def fullDecomposition(code: Int, data: Data): Seq[Int] =
        data.compatibility.get(code).orElse(data.canonical.get(code)) match {
            case Some(mapping) => mapping.flatMap(c => fullDecomposition(c, data))
            case None          => Seq(code)
        }

    private def render(
        pkg: String,
        objectName: String,
        unicode: Unicode,
        statement: Rfc,
        data: Data,
        listed: Set[Int],
        rfc: String
    ): String = {
        val classes = data.combiningClass.toSeq.sortBy(_._1)

        val decomposed = (data.canonical.keySet ++ data.compatibility.keySet).toSeq.sorted.map(c => c -> fullDecomposition(c, data))
        val starts     = decomposed.scanLeft(0)((offset, entry) => offset + entry._2.length)

        val excluded = (code: Int, mapping: Seq[Int]) =>
            listed.contains(code) || mapping.length == 1 || data.combiningClass.contains(mapping.head) || data.combiningClass.contains(code)
        val composites = data.canonical.toSeq.collect {
            case (code, mapping) if mapping.length == 2 && !excluded(code, mapping) && !(code >= HangulBase && code <= HangulEnd) =>
                (mapping(0), mapping(1), code)
        }.sorted

        val prohibited =
            merged(Seq("C.1.2", "C.2.1", "C.2.2", "C.3", "C.4", "C.5", "C.6", "C.7", "C.8", "C.9").flatMap(parseRfcTable(rfc, _)))
        val tables = Seq(
            Ranges("nonAsciiSpace", "C.1.2", merged(parseRfcTable(rfc, "C.1.2"))),
            Ranges("mappedToNothing", "B.1", merged(parseRfcTable(rfc, "B.1"))),
            Ranges("prohibitedOutput", "C.1.2 to C.9", prohibited),
            Ranges("unassigned", "A.1", merged(parseRfcTable(rfc, "A.1"))),
            Ranges("randALCat", "D.1", merged(parseRfcTable(rfc, "D.1"))),
            Ranges("lCat", "D.2", merged(parseRfcTable(rfc, "D.2")))
        )

        val sb = new StringBuilder
        sb.append(s"package $pkg\n\n")
        sb.append("// Generated by project/UnicodeTablesGen.scala from shared/src/main/unicode. Do not edit.\n")
        sb.append(s"// Derived from the Unicode Character Database, version ${unicode.version} (${unicode.source}UnicodeData.txt and\n")
        sb.append(s"// CompositionExclusions.txt). ${unicode.copyright} Provided under the Unicode License v3\n")
        sb.append("// (https://www.unicode.org/license.txt), reproduced in this artifact's META-INF/kyo-crypto/NOTICE.\n")
        sb.append("// The stringprep tables are RFC 3454's appendix tables (https://www.rfc-editor.org/rfc/rfc3454.txt), a derivative\n")
        sb.append(s"// work that assists in its implementation. ${statement.copyright}\n")
        statement.paragraph.foreach(line => sb.append(s"// $line\n"))
        sb.append(s"private[kyo] object $objectName {\n\n")
        sb.append(
            "    /** Sorted code points whose canonical combining class is not 0; `combiningClassValues` holds the classes by index. */\n"
        )
        array(sb, "combiningClassCodePoints", classes.map(_._1))
        array(sb, "combiningClassValues", classes.map(_._2))
        sb.append("    /** Sorted code points with a compatibility or canonical decomposition; entry i occupies `decompositionData` from\n")
        sb.append("      * `decompositionStarts(i)` to `decompositionStarts(i + 1)`, fully expanded.\n      */\n")
        array(sb, "decompositionCodePoints", decomposed.map(_._1))
        array(sb, "decompositionStarts", starts)
        array(sb, "decompositionData", decomposed.flatMap(_._2))
        sb.append(
            "    /** The primary composites, sorted by (first, second): `compositionFirst(i)` followed by `compositionSecond(i)` composes\n"
        )
        sb.append("      * to `compositionResult(i)`.\n      */\n")
        array(sb, "compositionFirst", composites.map(_._1))
        array(sb, "compositionSecond", composites.map(_._2))
        array(sb, "compositionResult", composites.map(_._3))
        tables.foreach { t =>
            sb.append(s"    /** RFC 3454 table ${t.table}: sorted inclusive ranges, `(from, to)` pairs flattened. */\n")
            array(sb, t.name, t.ranges.flatMap { case (a, b) => Seq(a, b) })
        }
        sb.append("""    private def ints(parts: String*): Array[Int] = {
                    |        var n = 0
                    |        parts.foreach(p => n += p.length / 2)
                    |        val out = new Array[Int](n)
                    |        var o   = 0
                    |        parts.foreach { p =>
                    |            var i = 0
                    |            while (i < p.length) {
                    |                out(o) = (p.charAt(i) << 15) | p.charAt(i + 1)
                    |                i += 2
                    |                o += 1
                    |            }
                    |        }
                    |        out
                    |    }
                    |}
                    |""".stripMargin)
        sb.toString
    }

    private def array(sb: StringBuilder, name: String, values: Seq[Int]): Unit = {
        sb.append(s"    val $name: Array[Int] = ints(")
        val chunks = values.grouped(ChunkValues).map(literal).toSeq
        if (chunks.isEmpty) sb.append("\"\"")
        else sb.append(chunks.mkString(",\n        "))
        sb.append(")\n\n")
    }

    private def literal(values: Seq[Int]): String = {
        val sb = new StringBuilder("\"")
        values.foreach { v =>
            if (v < 0 || v >= (1 << 30)) sys.error(s"unicode tables: value $v does not fit two base-32768 characters")
            sb.append(f"\\u${v >>> 15}%04x\\u${v & 0x7fff}%04x")
        }
        sb.append('"').toString
    }
}
