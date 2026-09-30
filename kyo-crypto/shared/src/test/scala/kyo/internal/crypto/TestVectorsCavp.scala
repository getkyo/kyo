package kyo.internal.crypto

/** The NIST CAVP Secure Hash Standard byte-oriented files [[TestVectors]] serves: `Len`, `Msg`, `MD` records of the short and long
  * message files, and the seed and 100 checkpoints of a Monte Carlo file. A record the reader does not understand fails the suite reading
  * it instead of being skipped.
  */
object TestVectorsCavp:

    val set = "nist-cavp-shs"

    final case class MessageVector(bits: Int, message: Array[Byte], digest: String)

    final case class MonteCarlo(seed: Array[Byte], checkpoints: Seq[String])

    def bytes(hex: String): Array[Byte] =
        Array.tabulate(hex.length / 2)(i => Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16).toByte)

    def hex(bytes: Array[Byte]): String =
        bytes.map(b => f"${b & 0xff}%02x").mkString

    /** The `name = value` lines of a `.rsp` file, comments and section headers dropped, in order. */
    def fields(text: String): Seq[(String, String)] =
        text.linesIterator
            .map(_.trim)
            .filter(line => line.contains(" = ") && !line.startsWith("#") && !line.startsWith("["))
            .map { line =>
                val at = line.indexOf(" = ")
                line.substring(0, at) -> line.substring(at + 3)
            }
            .toSeq

    /** The message vectors of a short or long message file; a `Len` of 0 has a `Msg` of `00` that stands for no bytes. */
    def messageVectors(file: String): Seq[MessageVector] =
        fields(TestVectors.text(set, file)).grouped(3).map {
            case Seq(("Len", len), ("Msg", msg), ("MD", md)) =>
                MessageVector(len.toInt, bytes(msg).take(len.toInt / 8), md)
            case other => throw new IllegalStateException(s"unexpected CAVP record in $file: $other")
        }.toSeq

    def monteCarlo(file: String): MonteCarlo =
        val records = fields(TestVectors.text(set, file))
        val seed    = records.collectFirst { case ("Seed", value) => value }
        MonteCarlo(
            bytes(seed.getOrElse(throw new IllegalStateException(s"no Seed in $file"))),
            records.collect { case ("MD", value) => value }
        )
    end monteCarlo

    /** The 100 checkpoints the Monte Carlo procedure of the SHA validation system produces from `seed` with `digest`: each is the last of
      * 1000 digests of the three previous values chained.
      */
    def monteCarloCheckpoints(seed: Array[Byte], count: Int, digest: kyo.Chunk[kyo.Span[Byte]] => kyo.Span[Byte]): Seq[Array[Byte]] =
        (0 until count).scanLeft(kyo.Span.from(seed)) { (previous, _) =>
            val last = (3 to 1002).foldLeft((previous, previous, previous)) { case ((md0, md1, md2), _) =>
                (md1, md2, digest(kyo.Chunk(md0, md1, md2)))
            }
            last._3
        }.tail.map(_.toArray)

end TestVectorsCavp
