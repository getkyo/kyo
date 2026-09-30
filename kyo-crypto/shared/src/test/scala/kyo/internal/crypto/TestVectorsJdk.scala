package kyo.internal.crypto

/** The `jdk-differential` set [[TestVectors]] serves: what the JDK computed for each primitive, written by
  * `project/CryptoDifferentialGen.scala`. A line is a kind followed by `field=value` pairs; a line of an unknown kind fails the suite that
  * reads it rather than being skipped.
  */
object TestVectorsJdk:

    val set = "jdk-differential"

    final case class Case(kind: String, fields: Map[String, String]):
        def bytes(field: String): Array[Byte] = TestVectorsCavp.bytes(value(field))
        def big(field: String): BigInt        = BigInt(value(field), 16)
        def int(field: String): Int           = value(field).toInt
        def flag(field: String): Boolean      = value(field).toBoolean
        def value(field: String): String      =
            fields.getOrElse(field, throw new IllegalStateException(s"a $kind case has no field $field"))
    end Case

    private val kinds = Set("sha1", "sha256", "sha512", "md5", "hmac", "pbkdf2", "rsa-key", "rs256", "oaep", "ed25519")

    lazy val cases: Seq[Case] =
        TestVectors.text(set, "cases.txt").linesIterator.filter(_.nonEmpty).map { line =>
            val parts = line.split(' ').toSeq
            if !kinds.contains(parts.head) then throw new IllegalStateException(s"unknown jdk-differential kind: ${parts.head}")
            Case(
                parts.head,
                parts.tail.map { pair =>
                    val at = pair.indexOf('=')
                    pair.substring(0, at) -> pair.substring(at + 1)
                }.toMap
            )
        }.toSeq

    def of(kind: String): Seq[Case] = cases.filter(_.kind == kind)

    /** The 2048-bit key pair the RS256 and OAEP cases were made under. */
    lazy val rsaKey: Case = of("rsa-key").head

end TestVectorsJdk
