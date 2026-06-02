package kyo.website

import kyo.*

/** One rendered documentation version: the git tag it came from, the label shown in the version
  * dropdown, and whether this version is served as `latest`.
  */
final case class WebsiteVersion(tag: String, label: String, latest: Boolean) derives CanEqual

object WebsiteVersion:

    /** A git tag parsed into its semantic-version components.
      *
      * @param major
      *   the leading numeric component (`v0.19.0` parses to `major = 0`).
      * @param minor
      *   the second numeric component, defaulting to `0` when the tag omits it (`v1.0-RC1`).
      * @param patch
      *   the third numeric component, defaulting to `0` when the tag omits it (`v1.0-RC1`).
      * @param preRelease
      *   the pre-release suffix after the first `-` (e.g. `RC1`), `Absent` for a stable release.
      */
    final case class Parsed(major: Int, minor: Int, patch: Int, preRelease: Maybe[String]) derives CanEqual

    /** Parse a `vMAJOR[.MINOR[.PATCH]]` tag with an optional pre-release suffix (`-RC1`, `-rc1`,
      * `-M1`, `-alpha`, ...) into its numeric components. Total: returns `Absent` for any tag that
      * does not start with `v` followed by at least one numeric component, and never throws (each
      * numeric component is parsed via [[parseInt]], not `String.toInt`). Missing minor/patch
      * components default to `0`, so the real `v1.0-RC1` tag parses to `(1, 0, 0, Present("RC1"))`.
      */
    def parse(tag: String): Maybe[Parsed] =
        if !tag.startsWith("v") then Absent
        else
            val body             = tag.substring(1)
            val dash             = body.indexOf('-')
            val numericPart      = if dash >= 0 then body.substring(0, dash) else body
            val preReleasePart   = if dash >= 0 then body.substring(dash + 1) else ""
            val preRelease       = if dash >= 0 && preReleasePart.nonEmpty then Present(preReleasePart) else Absent
            val components       = numericPart.split('.').toList
            val parsedComponents = components.map(parseInt)
            if parsedComponents.isEmpty || parsedComponents.exists(_.isEmpty) then Absent
            else
                val nums  = parsedComponents.flatMap(_.toList)
                val major = nums.headOption.getOrElse(0)
                val minor = nums.lift(1).getOrElse(0)
                val patch = nums.lift(2).getOrElse(0)
                Present(Parsed(major, minor, patch, preRelease))
            end if
        end if
    end parse

    /** Parse a non-empty run of ASCII digits into a non-negative `Int`, totally. Returns `Absent`
      * for an empty string or any non-digit character, and never throws (no `String.toInt`).
      */
    private def parseInt(s: String): Maybe[Int] =
        if s.isEmpty || !s.forall(c => c >= '0' && c <= '9') then Absent
        else
            var acc = 0L
            var i   = 0
            while i < s.length do
                acc = acc * 10 + (s.charAt(i) - '0')
                i += 1
            if acc > Int.MaxValue then Absent else Present(acc.toInt)
        end if
    end parseInt

    /** Ordering of git tags by semantic version, ascending (oldest first), matching `sort -V`
      * semantics for the kyo tag scheme.
      *
      *   - Parseable tags order by `(major, minor, patch)` numerically.
      *   - A pre-release sorts BEFORE the stable release of the same `major.minor.patch`
      *     (`v0.19.0-RC1` < `v0.19.0`); two pre-releases of the same triple order by their suffix
      *     lexicographically (a deterministic tiebreak).
      *   - Tags that do not parse sort BEFORE all parseable tags (so they land oldest/first and are
      *     never selected as latest), ordered among themselves lexicographically for determinism.
      *
      * Pure and total: no exceptions, no partial functions. `Frame`-free so it stays a plain data
      * accessor usable from any position.
      */
    val tagOrdering: Ordering[String] =
        (a: String, b: String) =>
            parse(a) match
                case Present(pa) =>
                    parse(b) match
                        case Present(pb) => compareParsed(pa, pb)
                        case Absent      => 1 // a parses, b does not: a is newer
                case Absent =>
                    parse(b) match
                        case Present(_) => -1 // b parses, a does not: b is newer
                        case Absent     => a.compareTo(b)

    private def compareParsed(a: Parsed, b: Parsed): Int =
        val byMajor = a.major.compareTo(b.major)
        if byMajor != 0 then byMajor
        else
            val byMinor = a.minor.compareTo(b.minor)
            if byMinor != 0 then byMinor
            else
                val byPatch = a.patch.compareTo(b.patch)
                if byPatch != 0 then byPatch
                else comparePreRelease(a.preRelease, b.preRelease)
            end if
        end if
    end compareParsed

    /** Same `major.minor.patch`: a pre-release precedes the stable release of that triple, and two
      * pre-releases order by their suffix lexicographically (a deterministic tiebreak).
      */
    private def comparePreRelease(a: Maybe[String], b: Maybe[String]): Int =
        a match
            case Absent =>
                b match
                    case Absent     => 0
                    case Present(_) => 1 // a is stable, b is pre-release: a newer
            case Present(sa) =>
                b match
                    case Absent      => -1 // a is pre-release, b is stable: a older
                    case Present(sb) => sa.compareTo(sb)
    end comparePreRelease

end WebsiteVersion
