package kyo.stats.machine

import kyo.*

/** Parses `/proc/self/cgroup` into the resolved cgroup directory (v2) or per-controller dirs (v1). */
private[machine] object LinuxCgroupPath:

    /** v2: the single `0::<path>` line; the resolved dir is `<root><path>`, or `<root>` when path is `/`. */
    def v2Dir(bytes: Span[Byte], len: Int, root: String): String =
        Text.fromSpan(bytes, len).lines.collectFirst {
            case l if l.startsWith("0::") =>
                val p = l.stripPrefix("0::")
                if p == "/" || p.isEmpty then root else root + p
        }.getOrElse(root)

    /** v1: each `N:<controllers>:<path>` line maps every controller to `<root>/<controllers><path>`. */
    def v1Dirs(bytes: Span[Byte], len: Int, root: String): Map[String, String] =
        Text.fromSpan(bytes, len).lines.flatMap { l =>
            l.split(":", 3) match
                case Array(_, ctrls, path) if ctrls.nonEmpty =>
                    val dir = if path == "/" then "" else path
                    ctrls.split(",").iterator.map(c => c -> (root + "/" + ctrls + dir))
                case _ => Iterator.empty
        }.toMap

end LinuxCgroupPath
