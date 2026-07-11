package kyo.stats.machine

import kyo.*

/** Total decoders for the cgroup resource files. Each routes a malformed/missing value to Absent. */
private[machine] object LinuxCgroupDecode:

    def singleLong(bytes: Span[Byte], len: Int): Maybe[Long] =
        Text.fromSpan(bytes, len).firstToken.flatMap(_.toLongOption.fold(Absent)(Present(_)))

    /** v2 memory.max: literal `max` => Absent, else the byte value (already bytes). */
    def v2Limit(bytes: Span[Byte], len: Int): Maybe[Long] =
        Text.fromSpan(bytes, len).firstToken match
            case Present("max") => Absent
            case Present(t)     => t.toLongOption.fold(Absent)(Present(_))
            case Absent         => Absent

    /** v2 cpu.max: `<quota> <period>`; quota `max` => Absent; both stored ns (microseconds x1000). */
    def v2Quota(bytes: Span[Byte], len: Int): Maybe[Long] =
        val ts = Text.fromSpan(bytes, len).tokens
        if ts.nonEmpty && ts(0) == "max" then Absent
        else if ts.nonEmpty then ts(0).toLongOption.map(_ * 1000L).fold(Absent)(Present(_))
        else Absent
    end v2Quota

    def v2Period(bytes: Span[Byte], len: Int): Maybe[Long] =
        val ts = Text.fromSpan(bytes, len).tokens
        if ts.length >= 2 then ts(1).toLongOption.map(_ * 1000L).fold(Absent)(Present(_)) else Absent

    /** cpu.stat `<key> <value>` lines. Reads `key` scaled by `scale`. Ignores nr_bursts/burst_usec. */
    def statField(bytes: Span[Byte], len: Int, key: String, scale: Long): Maybe[Long] =
        Text.fromSpan(bytes, len).lines.collectFirst {
            case l if l.startsWith(key + " ") || l.startsWith(key + "\t") =>
                l.trim.split("\\s+").lift(1).flatMap(_.toLongOption)
        }.flatten.map(_ * scale).fold(Absent)(Present(_))

end LinuxCgroupDecode
