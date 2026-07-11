package kyo.stats.machine

import kyo.*

/** cgroup v1/v2 detection, process-cgroup resolution, and total decode with per-hierarchy unit scaling.
  *
  * v2 unified is chosen iff `/sys/fs/cgroup/cgroup.controllers` exists (Path.exists, Abort-free), else v1
  * legacy. Resource files are read at the resolved path from `/proc/self/cgroup`, not the hierarchy root
  * (v2 resource files exist only on non-root cgroups); the root is a fallback when the resolved dir is
  * the root itself or unreadable. Unit scaling stores ns: v2 throttled_usec/cpu.max microseconds x1000;
  * v1 throttled_time ns x1; v1 cfs_quota_us/cfs_period_us microseconds x1000. The v1 memory unlimited
  * sentinel (>= 1L<<62) routes to Absent; nr_bursts/burst_usec/burst_time are ignored.
  */
private[machine] object LinuxCgroup:

    private val root      = "/sys/fs/cgroup"
    private val v2Marker  = "/sys/fs/cgroup/cgroup.controllers"
    val unlimitedSentinel = 1L << 62

    def read(s: MachineSampler)(using AllowUnsafe): Maybe[Machine.CgroupReading] =
        if isV2(s) then readV2(s, resolvedV2Dir(s)) else readV1(s, resolveV1(s))

    def isV2(s: MachineSampler)(using AllowUnsafe): Boolean = exists(v2Marker)

    /** Resolves the v2 dir from the `0::<path>` line joined under the mount root; falls back to root. */
    def resolvedV2Dir(s: MachineSampler)(using AllowUnsafe): String =
        s.readScoped(Path("/proc/self/cgroup"), (b, n) => LinuxCgroupPath.v2Dir(b, n, root))
            .getOrElse(root)

    /** true when the resolved v2 cgroup dir carries the `*.pressure` PSI files. */
    def hasV2Pressure(s: MachineSampler)(using AllowUnsafe): Boolean =
        isV2(s) && exists(resolvedV2Dir(s) + "/cpu.pressure")

    private def resolveV1(s: MachineSampler)(using AllowUnsafe): Map[String, String] =
        s.readScoped(Path("/proc/self/cgroup"), (b, n) => LinuxCgroupPath.v1Dirs(b, n, root))
            .getOrElse(Map.empty)

    private def readV2(s: MachineSampler, dir: String)(using AllowUnsafe): Maybe[Machine.CgroupReading] =
        Present(Machine.CgroupReading(
            memoryUsage = readLong(s, dir + "/memory.current"),
            memoryLimit = readLimit(s, dir + "/memory.max"),
            cpuQuota = readV2Quota(s, dir + "/cpu.max"),
            cpuPeriod = readV2Period(s, dir + "/cpu.max"),
            periods = readStatField(s, dir + "/cpu.stat", "nr_periods", 1L),
            throttledPeriods = readStatField(s, dir + "/cpu.stat", "nr_throttled", 1L),
            throttledTime = readStatField(s, dir + "/cpu.stat", "throttled_usec", 1000L)
        ))

    private def readV1(s: MachineSampler, dirs: Map[String, String])(using
        AllowUnsafe
    ): Maybe[Machine.CgroupReading] =
        val mem = dirs.getOrElse("memory", root + "/memory")
        // v1Dirs keys the map by INDIVIDUAL controller ("cpu", "cpuacct"), never the compound "cpu,cpuacct"
        // mount name; look up either individual key and fall back to the compound mount-root path.
        val cpu = dirs.get("cpu").orElse(dirs.get("cpuacct")).getOrElse(root + "/cpu,cpuacct")
        Present(Machine.CgroupReading(
            memoryUsage = readLong(s, mem + "/memory.usage_in_bytes"),
            memoryLimit = readV1Limit(s, mem + "/memory.limit_in_bytes"),
            cpuQuota = readV1Quota(s, cpu + "/cpu.cfs_quota_us"),
            cpuPeriod = readLong(s, cpu + "/cpu.cfs_period_us").map(_ * 1000L),
            periods = readStatField(s, cpu + "/cpu.stat", "nr_periods", 1L),
            throttledPeriods = readStatField(s, cpu + "/cpu.stat", "nr_throttled", 1L),
            throttledTime = readStatField(s, cpu + "/cpu.stat", "throttled_time", 1L)
        ))
    end readV1

    private def readV1Limit(s: MachineSampler, path: String)(using AllowUnsafe): Maybe[Long] =
        readLong(s, path).flatMap(v => if v >= unlimitedSentinel then Absent else Present(v))

    private def exists(path: String)(using AllowUnsafe): Boolean =
        Path(path).unsafe.exists()

    private def readLong(s: MachineSampler, path: String)(using AllowUnsafe): Maybe[Long] =
        s.readScoped(Path(path), (b, n) => LinuxCgroupDecode.singleLong(b, n)).flatten

    private def readLimit(s: MachineSampler, path: String)(using AllowUnsafe): Maybe[Long] =
        s.readScoped(Path(path), (b, n) => LinuxCgroupDecode.v2Limit(b, n)).flatten

    private def readV2Quota(s: MachineSampler, path: String)(using AllowUnsafe): Maybe[Long] =
        s.readScoped(Path(path), (b, n) => LinuxCgroupDecode.v2Quota(b, n)).flatten

    private def readV2Period(s: MachineSampler, path: String)(using AllowUnsafe): Maybe[Long] =
        s.readScoped(Path(path), (b, n) => LinuxCgroupDecode.v2Period(b, n)).flatten

    private def readV1Quota(s: MachineSampler, path: String)(using AllowUnsafe): Maybe[Long] =
        readLong(s, path).flatMap(v => if v < 0 then Absent else Present(v * 1000L))

    private def readStatField(s: MachineSampler, path: String, key: String, scale: Long)(using
        AllowUnsafe
    ): Maybe[Long] =
        s.readScoped(Path(path), (b, n) => LinuxCgroupDecode.statField(b, n, key, scale)).flatten

end LinuxCgroup
