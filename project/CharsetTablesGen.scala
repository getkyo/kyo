import sbt.*
import sjsonnew.shaded.scalajson.ast.unsafe.*
import sjsonnew.support.scalajson.unsafe.Parser

/** Source generator for kyo-email's charset tables, from the WHATWG Encoding Standard files checked in verbatim under
  * `kyo-charset/data/whatwg-encoding/`, and for the test objects that embed those same files for the charset tests.
  *
  * Every file is verified against its `MANIFEST` by [[VendoredFiles]] before anything is generated. The main sources get one object
  * per index file, holding the index as ASCII string literals and decoding it in a `lazy val`, plus the WHATWG label table.
  *
  * Table format, parsed by `kyo.internal.charset.IndexTable`: a dense table lists every pointer from 0 in order, each as four
  * lowercase hex digits for a code point up to U+FFFF, `+` and six hex digits above it, or `~` for a pointer the index leaves unmapped.
  * A ranges table (gb18030-ranges) lists its pairs as six hex digits of pointer followed by six of code point.
  */
object CharsetTablesGen {

    private val Generator = "CharsetTablesGen"

    private val MainPackage = "kyo.internal.charset"

    // --- entry points ---

    /** Task body for `Compile / sourceGenerators`. The generated tables are BSD 3-Clause portions of the Encoding Standard, so each one's
      * header carries `notice`, the artifact's third-party notice with the copyright line, the conditions and the disclaimer.
      */
    def generateMain(dataDir: File, notice: File, outDir: File): Seq[File] = {
        val vendored    = VendoredFiles.verified(dataDir / "whatwg-encoding", Generator)
        val files       = vendored.texts
        val attribution = vendored.attribution ++ ("" +: IO.readLines(notice, java.nio.charset.StandardCharsets.UTF_8))
        val pkgDir      = outDir / "kyo" / "internal" / "charset"
        IO.createDirectory(pkgDir)
        val indexes = files.keys.toSeq.sorted.filter(_.startsWith("index-")).map { name =>
            val text = files(name)
            val body =
                if (name == "index-gb18030-ranges.txt") emitRangesTable(name, parseIndex(name, text), attribution)
                else emitDenseTable(name, parseIndex(name, text), attribution)
            VendoredFiles.writeIfChanged(pkgDir / s"${VendoredFiles.objectName(name)}.scala", body)
        }
        val groups = parseGroups(files("encodings.json"))
        val labels = VendoredFiles.writeIfChanged(pkgDir / "WhatwgLabels.scala", emitLabels(labelPairs(groups), attribution))
        indexes :+ labels
    }

    /** Task body for `Test / sourceGenerators`: every WHATWG file as text, one object each, in the charset package, since the charset tests
      * check the generated tables against the same files the tables come from.
      */
    def generateTest(dataDir: File, outDir: File): Seq[File] = {
        val pkgDir = outDir / "kyo" / "internal" / "charset"
        IO.createDirectory(pkgDir)
        val vendored = VendoredFiles.verified(dataDir / "whatwg-encoding", Generator)
        val files    = vendored.texts.toSeq.sortBy(_._1).map { case (name, text) =>
            VendoredFiles.writeIfChanged(
                pkgDir / s"Embedded${VendoredFiles.objectName(name)}.scala",
                VendoredFiles.emitEmbeddedText(
                    MainPackage,
                    Generator,
                    s"kyo-charset/data/whatwg-encoding/$name",
                    vendored.attribution,
                    name,
                    text
                )
            )
        }
        VendoredFiles.requireDistinctNames(Generator, files)
    }

    // --- parsing ---

    /** Pointer to code point, in file order. A data line is `pointer<TAB>0xCODEPOINT<TAB>comment`. */
    private def parseIndex(name: String, text: String): Seq[(Int, Int)] = {
        val entries = text.split("\n").toSeq.filterNot(l => l.startsWith("#") || l.trim.isEmpty).map { line =>
            line.split("\t") match {
                case Array(pointer, codePoint, _*) if codePoint.startsWith("0x") =>
                    (pointer.trim.toInt, Integer.parseInt(codePoint.drop(2), 16))
                case _ => sys.error(s"[$Generator] malformed line in $name: $line")
            }
        }
        val pointers = entries.map(_._1)
        if (pointers != pointers.sorted || pointers.distinct.size != pointers.size)
            sys.error(s"[$Generator] $name: pointers are not strictly increasing")
        entries
    }

    /** The groups of `encodings.json` in order: heading, then each encoding's name and labels. */
    private def parseGroups(text: String): Seq[(String, Seq[(String, Seq[String])])] = {
        def field(obj: JValue, key: String): JValue = obj match {
            case JObject(fields) => fields.find(_.field == key).map(_.value).getOrElse(sys.error(s"[$Generator] no '$key'"))
            case other           => sys.error(s"[$Generator] expected an object, got $other")
        }
        def array(value: JValue): Seq[JValue] = value match {
            case JArray(values) => values.toSeq
            case other          => sys.error(s"[$Generator] expected an array, got $other")
        }
        def string(value: JValue): String = value match {
            case JString(s) => s
            case other      => sys.error(s"[$Generator] expected a string, got $other")
        }
        array(Parser.parseFromString(text).get).map { group =>
            string(field(group, "heading")) -> array(field(group, "encodings")).map { encoding =>
                string(field(encoding, "name")) -> array(field(encoding, "labels")).map(string)
            }
        }
    }

    /** (label, encoding name) for every label in `encodings.json`. */
    private def labelPairs(groups: Seq[(String, Seq[(String, Seq[String])])]): Seq[(String, String)] = {
        val pairs = for {
            (_, encodings) <- groups
            (name, labels) <- encodings
            label          <- labels
        } yield label -> name
        pairs.foreach { case (label, _) =>
            if (label.exists(c => c < 0x21 || c > 0x7e))
                sys.error(s"[$Generator] label '$label' is not printable ASCII")
        }
        if (pairs.map(_._1).distinct.size != pairs.size) sys.error(s"[$Generator] a label appears twice in encodings.json")
        pairs
    }

    // --- emission ---

    private def emitDenseTable(file: String, entries: Seq[(Int, Int)], attribution: Seq[String]): String = {
        val byPointer = entries.toMap
        val size      = entries.last._1 + 1
        val tokens    = (0 until size).map { pointer =>
            byPointer.get(pointer) match {
                case None                     => "~"
                case Some(cp) if cp <= 0xffff => f"$cp%04x"
                case Some(cp)                 => f"+$cp%06x"
            }
        }
        emitTableObject(file, tokens, parts => s"IndexTable.dense($size, ${parts.mkString(", ")})", "IndexTable", attribution)
    }

    private def emitRangesTable(file: String, entries: Seq[(Int, Int)], attribution: Seq[String]): String = {
        val tokens = entries.map { case (pointer, cp) => f"$pointer%06x$cp%06x" }
        emitTableObject(file, tokens, parts => s"IndexTable.ranges(${parts.mkString(", ")})", "IndexTable.Ranges", attribution)
    }

    private def emitTableObject(
        file: String,
        tokens: Seq[String],
        build: Seq[String] => String,
        tableType: String,
        attribution: Seq[String]
    ): String = {
        val literals = VendoredFiles.chunkAscii(tokens)
        val name     = VendoredFiles.objectName(file)
        val sb       = new StringBuilder
        sb.append(VendoredFiles.header(Generator, s"kyo-charset/data/whatwg-encoding/$file", attribution))
        sb.append(s"package $MainPackage\n\n")
        sb.append(s"private[kyo] object $name:\n")
        literals.zipWithIndex.foreach { case (literal, i) => sb.append(VendoredFiles.part(i, literal)) }
        sb.append(s"    lazy val table: $tableType = ${build(literals.indices.map(i => s"part$i"))}\n")
        sb.append(s"end $name\n")
        sb.toString
    }

    private def emitLabels(pairs: Seq[(String, String)], attribution: Seq[String]): String = {
        val literals = VendoredFiles.chunkAscii(pairs.map { case (label, name) => s"$label\\t$name\\n" })
        val sb       = new StringBuilder
        sb.append(VendoredFiles.header(Generator, "kyo-charset/data/whatwg-encoding/encodings.json", attribution))
        sb.append(s"package $MainPackage\n\n")
        sb.append("private[kyo] object WhatwgLabels:\n")
        literals.zipWithIndex.foreach { case (literal, i) => sb.append(VendoredFiles.part(i, literal)) }
        sb.append(
            s"    lazy val byLabel: Map[String, String] = IndexTable.labelPairs(${literals.indices.map(i => s"part$i").mkString(", ")})\n"
        )
        sb.append("end WhatwgLabels\n")
        sb.toString
    }
}
