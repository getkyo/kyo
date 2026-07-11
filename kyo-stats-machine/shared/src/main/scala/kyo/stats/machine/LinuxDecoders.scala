package kyo.stats.machine

import kyo.*

/** Total decoders for the Linux text files, parsing over a borrowed byte span decoded to `Text`. Every
  * field routes to `Absent` on a malformed, truncated, non-numeric, or missing value, never a throw.
  */
private[machine] object LinuxDecoders:

    /** Parses /proc/stat aggregate cpu line: `cpu user nice system idle iowait irq softirq steal ...` in
      * jiffies, scaling each mode to nanoseconds via `scale`. Fields are present-guarded: an older kernel
      * with fewer columns contributes only the columns it exposes.
      *
      * The kernel folds guest into user and guest_nice into nice, so those are not summed again (double
      * count). The reported `system` mode includes irq + softirq (kernel-mode servicing time, the
      * node_exporter/common convention). `total` sums every present column (user, nice, system, idle,
      * iowait, irq, softirq, steal), so a `total - idle` utilization derivation on a virtualized host is
      * not under-reported by the omitted irq/softirq/steal time.
      */
    def cpu(bytes: Span[Byte], len: Int, scale: Long): Maybe[Machine.CpuReading] =
        val text = Text.fromSpan(bytes, len)
        text.lineFields("cpu ") match
            case Present(f) if f.length >= 5 =>
                def ns(i: Int): Maybe[Long] = if f.length > i then field(f, i).map(_ * scale) else Absent
                val user                    = ns(1); val nice    = ns(2); val systemRaw = ns(3); val idle = ns(4)
                val iowait                  = ns(5)
                val irq                     = ns(6); val softirq = ns(7); val steal     = ns(8)
                val system                  = sumPresent(systemRaw, irq, softirq)
                val total                   = sumPresent(user, nice, systemRaw, idle, iowait, irq, softirq, steal)
                Present(Machine.CpuReading(total, user, system, idle, iowait))
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
