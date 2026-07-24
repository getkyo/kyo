package kyo.build

/** Version-aware scalac option helpers for build.sbt. Each option carries the Scala version range
  * that supports it; `ScalacOptions.tokensForVersion` filters a proposed set down to the tokens
  * valid for the version being compiled, so cross-built modules (Scala 2.13 and 3 LTS rows) get
  * only the flags their compiler understands. Only the options build.sbt references are modeled.
  */
final case class ScalacOption(
    option: String,
    args: List[String],
    isSupported: ScalaVersion => Boolean
)

object ScalacOption {
    def apply(option: String, isSupported: ScalaVersion => Boolean): ScalacOption =
        ScalacOption(option, Nil, isSupported)
}

final case class ScalaVersion(major: Long, minor: Long, patch: Long) extends Ordered[ScalaVersion] {
    def compare(that: ScalaVersion): Int = {
        val majorCmp = major.compare(that.major)
        if (majorCmp != 0) majorCmp
        else {
            val minorCmp = minor.compare(that.minor)
            if (minorCmp != 0) minorCmp
            else patch.compare(that.patch)
        }
    }

    def isAtLeast(addedVersion: ScalaVersion): Boolean = this >= addedVersion

    def isBetween(addedVersion: ScalaVersion, removedVersion: ScalaVersion): Boolean =
        this >= addedVersion && this < removedVersion
}

object ScalaVersion {
    val V2_12_2 = ScalaVersion(2, 12, 2)
    val V2_12_5 = ScalaVersion(2, 12, 5)
    val V2_13_0 = ScalaVersion(2, 13, 0)
    val V2_13_9 = ScalaVersion(2, 13, 9)
    val V3_0_0  = ScalaVersion(3, 0, 0)
    val V3_3_0  = ScalaVersion(3, 3, 0)
    val V3_3_1  = ScalaVersion(3, 3, 1)
    val V3_5_0  = ScalaVersion(3, 5, 0)

    private val VersionPattern = """(\d+)\.(\d+)\.(\d+).*""".r

    def fromString(version: String): Either[IllegalArgumentException, ScalaVersion] =
        version match {
            case VersionPattern(major, minor, patch) =>
                Right(ScalaVersion(major.toLong, minor.toLong, patch.toLong))
            case _ =>
                Left(new IllegalArgumentException(s"Scala version $version cannot be parsed"))
        }
}

object ScalacOptions {
    import ScalaVersion.*

    // `-release` needs a JDK 9+ javac backend; read the running JDK's major version once.
    private val javaMajorVersion: Long = {
        val raw   = sys.props.getOrElse("java.version", "0")
        val major = raw.stripPrefix("1.").takeWhile(_.isDigit)
        if (major.isEmpty) 0L else major.toLong
    }

    def encoding(enc: String): ScalacOption = ScalacOption("-encoding", List(enc), _ => true)

    val feature: ScalacOption = ScalacOption("-feature", _ => true)

    val unchecked: ScalacOption = ScalacOption("-unchecked", _ => true)

    // Scala 2.13 turns individual deprecation warnings on by default; the flag is only needed
    // outside the 2.13 series.
    val deprecation: ScalacOption =
        ScalacOption("-deprecation", version => version < V2_13_0 || version >= V3_0_0)

    val warnValueDiscard: ScalacOption =
        ScalacOption(
            "-Wvalue-discard",
            version => version.isBetween(V2_13_0, V3_0_0) || version.isAtLeast(V3_3_0)
        )

    val warnNonUnitStatement: ScalacOption =
        ScalacOption(
            "-Wnonunit-statement",
            version => version.isBetween(V2_13_9, V3_0_0) || version.isAtLeast(V3_3_1)
        )

    val languageStrictEquality: ScalacOption =
        ScalacOption("-language:strictEquality", _ >= V3_0_0)

    val advancedKindProjector: ScalacOption =
        ScalacOption("-Xkind-projector", _ >= V3_5_0)

    val source3: ScalacOption =
        ScalacOption("-Xsource:3", _.isBetween(V2_12_2, V3_0_0))

    def release(version: String): ScalacOption =
        ScalacOption(
            "-release",
            List(version),
            scalaVersion => javaMajorVersion >= 9 && scalaVersion >= V2_12_5
        )

    def tokensForVersion(
        scalaVersion: ScalaVersion,
        proposedScalacOptions: Set[ScalacOption]
    ): Seq[String] =
        proposedScalacOptions.toList
            .filter(_.isSupported(scalaVersion))
            .flatMap(opt => opt.option :: opt.args)
}
