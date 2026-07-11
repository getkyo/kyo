package kyo.stats.machine

import kyo.*

/** Total decoders for the Linux text files, parsing over a borrowed byte span decoded to `Text`. Every
  * field routes to `Absent` on a malformed, truncated, non-numeric, or missing value, never a throw.
  */
private[machine] object LinuxDecoders:

    /** Parses /proc/stat aggregate cpu line: `cpu user nice system idle iowait ...` in jiffies, scaling
      * each mode to nanoseconds via `scale`. total = sum of the present modes.
      */
    def cpu(bytes: Span[Byte], len: Int, scale: Long): Maybe[Machine.CpuReading] =
        val text = Text.fromSpan(bytes, len)
        text.lineFields("cpu ") match
            case Present(f) if f.length >= 5 =>
                def ns(i: Int): Maybe[Long] = field(f, i).map(_ * scale)
                val user                    = ns(1); val nice = ns(2); val system = ns(3); val idle = ns(4)
                val iowait                  = if f.length >= 6 then ns(5) else Absent
                Present(Machine.CpuReading(sumPresent(user, nice, system, idle, iowait), user, system, idle, iowait))
            case _ => Absent
        end match
    end cpu

    def memory(bytes: Span[Byte], len: Int): Maybe[Machine.MemoryReading] =
        val text = Text.fromSpan(bytes, len)
        Present(Machine.MemoryReading(
            total = text.kbField("MemTotal:").map(_ * 1024L),
            available = text.kbField("MemAvailable:").map(_ * 1024L),
            free = text.kbField("MemFree:").map(_ * 1024L)
        ))
    end memory

    def swap(bytes: Span[Byte], len: Int): Maybe[Machine.SwapReading] =
        val text = Text.fromSpan(bytes, len)
        Present(Machine.SwapReading(
            total = text.kbField("SwapTotal:").map(_ * 1024L),
            free = text.kbField("SwapFree:").map(_ * 1024L)
        ))
    end swap

    def load(bytes: Span[Byte], len: Int): Maybe[Machine.LoadReading] =
        val ts = Text.fromSpan(bytes, len).tokens
        if ts.length >= 3 then
            Present(Machine.LoadReading(parseDouble(ts, 0), parseDouble(ts, 1), parseDouble(ts, 2)))
        else Absent
    end load

    private def field(fields: IndexedSeq[String], i: Int): Maybe[Long] =
        if i < fields.length then fields(i).toLongOption.fold(Absent)(Present(_)) else Absent

    private def parseDouble(fields: IndexedSeq[String], i: Int): Maybe[Double] =
        if i < fields.length then fields(i).toDoubleOption.fold(Absent)(Present(_)) else Absent

    private def sumPresent(vs: Maybe[Long]*): Maybe[Long] =
        val present = vs.collect { case Present(v) => v }
        if present.isEmpty then Absent else Present(present.sum)

end LinuxDecoders
