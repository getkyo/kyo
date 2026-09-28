package kyo.internal.charset

import kyo.*
import kyo.charset.Charset

/** The single-byte encodings with the raw text of their WHATWG index file, embedded verbatim by the test source generator, and the table
  * the main source generator produced from the same file.
  *
  * The raw text is parsed here by code independent of the generator, so a test that compares the two checks the generator's encoding and
  * `IndexTable`'s decoding of it together, on every platform, without reading files at run time.
  */
object IndexTableFixtures:

    final case class SingleByte(charset: Charset, file: String, raw: String, table: IndexTable)

    val singleByte: Chunk[SingleByte] = Chunk(
        SingleByte(Charset.Ibm866, "index-ibm866.txt", EmbeddedIndexIbm866.text, IndexIbm866.table),
        SingleByte(Charset.Iso8859_2, "index-iso-8859-2.txt", EmbeddedIndexIso8859_2.text, IndexIso8859_2.table),
        SingleByte(Charset.Iso8859_3, "index-iso-8859-3.txt", EmbeddedIndexIso8859_3.text, IndexIso8859_3.table),
        SingleByte(Charset.Iso8859_4, "index-iso-8859-4.txt", EmbeddedIndexIso8859_4.text, IndexIso8859_4.table),
        SingleByte(Charset.Iso8859_5, "index-iso-8859-5.txt", EmbeddedIndexIso8859_5.text, IndexIso8859_5.table),
        SingleByte(Charset.Iso8859_6, "index-iso-8859-6.txt", EmbeddedIndexIso8859_6.text, IndexIso8859_6.table),
        SingleByte(Charset.Iso8859_7, "index-iso-8859-7.txt", EmbeddedIndexIso8859_7.text, IndexIso8859_7.table),
        SingleByte(Charset.Iso8859_8, "index-iso-8859-8.txt", EmbeddedIndexIso8859_8.text, IndexIso8859_8.table),
        SingleByte(Charset.Iso8859_8I, "index-iso-8859-8.txt", EmbeddedIndexIso8859_8.text, IndexIso8859_8.table),
        SingleByte(Charset.Iso8859_10, "index-iso-8859-10.txt", EmbeddedIndexIso8859_10.text, IndexIso8859_10.table),
        SingleByte(Charset.Iso8859_13, "index-iso-8859-13.txt", EmbeddedIndexIso8859_13.text, IndexIso8859_13.table),
        SingleByte(Charset.Iso8859_14, "index-iso-8859-14.txt", EmbeddedIndexIso8859_14.text, IndexIso8859_14.table),
        SingleByte(Charset.Iso8859_15, "index-iso-8859-15.txt", EmbeddedIndexIso8859_15.text, IndexIso8859_15.table),
        SingleByte(Charset.Iso8859_16, "index-iso-8859-16.txt", EmbeddedIndexIso8859_16.text, IndexIso8859_16.table),
        SingleByte(Charset.Koi8R, "index-koi8-r.txt", EmbeddedIndexKoi8R.text, IndexKoi8R.table),
        SingleByte(Charset.Koi8U, "index-koi8-u.txt", EmbeddedIndexKoi8U.text, IndexKoi8U.table),
        SingleByte(Charset.Macintosh, "index-macintosh.txt", EmbeddedIndexMacintosh.text, IndexMacintosh.table),
        SingleByte(Charset.Windows874, "index-windows-874.txt", EmbeddedIndexWindows874.text, IndexWindows874.table),
        SingleByte(Charset.Windows1250, "index-windows-1250.txt", EmbeddedIndexWindows1250.text, IndexWindows1250.table),
        SingleByte(Charset.Windows1251, "index-windows-1251.txt", EmbeddedIndexWindows1251.text, IndexWindows1251.table),
        SingleByte(Charset.Windows1252, "index-windows-1252.txt", EmbeddedIndexWindows1252.text, IndexWindows1252.table),
        SingleByte(Charset.Windows1253, "index-windows-1253.txt", EmbeddedIndexWindows1253.text, IndexWindows1253.table),
        SingleByte(Charset.Windows1254, "index-windows-1254.txt", EmbeddedIndexWindows1254.text, IndexWindows1254.table),
        SingleByte(Charset.Windows1255, "index-windows-1255.txt", EmbeddedIndexWindows1255.text, IndexWindows1255.table),
        SingleByte(Charset.Windows1256, "index-windows-1256.txt", EmbeddedIndexWindows1256.text, IndexWindows1256.table),
        SingleByte(Charset.Windows1257, "index-windows-1257.txt", EmbeddedIndexWindows1257.text, IndexWindows1257.table),
        SingleByte(Charset.Windows1258, "index-windows-1258.txt", EmbeddedIndexWindows1258.text, IndexWindows1258.table),
        SingleByte(Charset.XMacCyrillic, "index-x-mac-cyrillic.txt", EmbeddedIndexXMacCyrillic.text, IndexXMacCyrillic.table)
    )

    /** Pointer to code point for every data line of an index file: `pointer<TAB>0xCODEPOINT<TAB>comment`, skipping `#` comments and
      * blank lines.
      */
    def entries(raw: String): Map[Int, Int] =
        raw.split("\n").iterator.filterNot(line => line.startsWith("#") || line.trim.isEmpty).map { line =>
            val fields = line.split("\t")
            fields(0).trim.toInt -> Integer.parseInt(fields(1).stripPrefix("0x"), 16)
        }.toMap

    /** The number of lines of an index file that are neither `#` comments nor blank. */
    def dataLines(raw: String): Int = raw.split("\n").count(line => !line.startsWith("#") && line.trim.nonEmpty)

end IndexTableFixtures
