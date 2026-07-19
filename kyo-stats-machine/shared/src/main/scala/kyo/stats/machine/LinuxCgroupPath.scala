package kyo.stats.machine

import kyo.*

/** Parses the two proc files that resolve the cgroup layout: `/proc/self/mountinfo` for where the cgroup
  * filesystem is actually mounted, and `/proc/self/cgroup` for where THIS process sits inside it. Both are
  * read once, at construction, so this parsing is off the zero-allocation tick path.
  */
private[machine] object LinuxCgroupPath:

    /** Mount options that appear in a mountinfo super-options field alongside the controller names, filtered
      * out so only genuine v1 controllers are keyed.
      */
    private val GenericMountFlags =
        Set("rw", "ro", "nosuid", "nodev", "noexec", "relatime", "noatime", "nodiratime", "seclabel", "sync")

    /** The cgroup2 (unified) mount point from `/proc/self/mountinfo`, or absent when no cgroup2 filesystem is
      * mounted. A mountinfo line is `<id> <parent> <maj:min> <root> <mount-point> <opts> [tags...] - <fstype>
      * <source> <super-options>`, so after the standalone `-` separator the fstype is the first token and the
      * mount point is field 4. Absent routes the caller to the conventional fallback root.
      */
    def mountRootV2(bytes: Span[Byte], len: Int): Maybe[String] =
        Maybe.fromOption(
            LinuxText.lines(bytes, len).flatMap(mountEntry).collectFirst {
                case (fstype, mount, _) if fstype == "cgroup2" => mount
            }
        )

    /** Each v1 cgroup controller mapped to its ACTUAL mount point from `/proc/self/mountinfo`, keyed by the
      * INDIVIDUAL controller name found in the mount's super-options (never the compound `cpu,cpuacct`
      * string), so a controller mounted at a path that does not match its name still resolves. Only `cgroup`
      * (v1) fstype lines contribute, and the generic mount flags in the super-options are filtered out.
      */
    def v1MountRoots(bytes: Span[Byte], len: Int): Map[String, String] =
        LinuxText.lines(bytes, len).flatMap(mountEntry).flatMap {
            case (fstype, mount, opts) if fstype == "cgroup" =>
                opts.split(",").iterator.filter(isController).map(c => c -> mount)
            case _ => Iterator.empty
        }.toMap

    /** Each v1 controller mapped to THIS process's cgroup-relative path from `/proc/self/cgroup`
      * (`N:<controllers>:<path>`); a namespace-root `/` path contributes no suffix. Joined with the
      * mountinfo mount points by `reconcileV1` to build each controller's directory.
      */
    def v1Rel(bytes: Span[Byte], len: Int): Map[String, String] =
        LinuxText.lines(bytes, len).flatMap { l =>
            l.split(":", 3) match
                case Array(_, ctrls, path) if ctrls.nonEmpty =>
                    val rel = if path == "/" then "" else path
                    ctrls.split(",").iterator.map(c => c -> rel)
                case _ => Iterator.empty
        }.toMap

    /** Joins each controller's real mountinfo mount point with this process's relative path. A controller
      * present in `/proc/self/cgroup` but absent from mountinfo falls back to the conventional
      * `<root>/<controller>` layout, so a partial mountinfo never drops a controller.
      */
    def reconcileV1(mountRoots: Map[String, String], rels: Map[String, String], root: String): Map[String, String] =
        rels.map { case (ctrl, rel) => ctrl -> (mountRoots.getOrElse(ctrl, root + "/" + ctrl) + rel) }

    /** v2: the single `0::<path>` line; the resolved dir is `<root><path>`, or `<root>` when path is `/`. */
    def v2Dir(bytes: Span[Byte], len: Int, root: String): String =
        LinuxText.lines(bytes, len).collectFirst {
            case l if l.startsWith("0::") =>
                val p = l.stripPrefix("0::")
                if p == "/" || p.isEmpty then root else root + p
        }.getOrElse(root)

    /** v1: each `N:<controllers>:<path>` line maps every controller to `<root>/<controllers><path>`. This is
      * the conventional-layout fallback, used only when mountinfo carries no v1 cgroup mount at all; the
      * mountinfo reconciliation above is the primary path.
      */
    def v1Dirs(bytes: Span[Byte], len: Int, root: String): Map[String, String] =
        LinuxText.lines(bytes, len).flatMap { l =>
            l.split(":", 3) match
                case Array(_, ctrls, path) if ctrls.nonEmpty =>
                    val dir = if path == "/" then "" else path
                    ctrls.split(",").iterator.map(c => c -> (root + "/" + ctrls + dir))
                case _ => Iterator.empty
        }.toMap

    /** Splits a mountinfo line into `(fstype, mount-point, super-options)` after the standalone `-`
      * separator, or nothing when the line has no separator or too few fields to be a mount entry.
      */
    private def mountEntry(line: String): Iterator[(String, String, String)] =
        val parts = line.split(" ")
        val dash  = parts.indexOf("-")
        if dash >= 0 && parts.length > 4 && dash + 3 < parts.length then
            Iterator.single((parts(dash + 1), parts(4), parts(dash + 3)))
        else Iterator.empty
    end mountEntry

    private def isController(token: String): Boolean =
        token.nonEmpty && !GenericMountFlags.contains(token)

end LinuxCgroupPath
