package kyo.test.runner.internal

import java.util.regex.Pattern

/** Glob pattern matching for test path strings.
  *
  * Paths are dot-joined leaf paths (e.g., `"outer.inner.leaf"`). The separator is `.`.
  *
  * Supported metacharacters:
  *   - `**`: matches any sequence of characters across segments (including `.`)
  *   - `*`: matches any sequence of characters within a single segment (no `.`)
  *   - `?`: matches exactly one non-separator character (no `.`)
  *
  * Patterns are anchored: the pattern must match the entire path string.
  */
object Glob:

    // Cache is intentionally unbounded; the key space is bounded by the set of distinct glob patterns supplied to a single test run, which is small in practice.
    private val cache = new java.util.concurrent.ConcurrentHashMap[String, Pattern]()

    /** Returns true if `path` matches `pattern`.
      *
      * The separator between path segments is always `.`. Single `*` matches any non-empty sequence of characters within one segment (no
      * `.` allowed in the matched substring). Double `**` matches any sequence of characters across one or more segments (`.` included). A
      * pattern must match the entire path string.
      *
      * Examples:
      *   - `Glob.matches("kyo.*", "kyo.Foo")` → `true` (`*` matches `Foo`, a single segment)
      *   - `Glob.matches("kyo.*", "kyo.sub.Foo")` → `false` (`*` cannot cross the `.` between `sub` and `Foo`)
      *   - `Glob.matches("kyo.**", "kyo.sub.Foo")` → `true` (`**` matches `sub.Foo` across segments)
      *
      * @param pattern
      *   a glob pattern using `.` as the path separator; supports `*` (one segment), `**` (multiple segments), and `?` (one non-separator
      *   character)
      * @param path
      *   a dot-joined leaf path string (e.g., `"outer.inner"`)
      */
    def matches(pattern: String, path: String): Boolean =
        val compiled = cache.computeIfAbsent(pattern, p => Pattern.compile(toRegex(p)))
        compiled.matcher(path).matches()

    private def toRegex(pattern: String): String =
        val sb = new StringBuilder("^")
        var i  = 0
        while i < pattern.length do
            val ch = pattern.charAt(i)
            if ch == '*' && i + 1 < pattern.length && pattern.charAt(i + 1) == '*' then
                sb.append(".*")
                i += 2
            else if ch == '*' then
                sb.append("[^.]+")
                i += 1
            else if ch == '?' then
                sb.append("[^.]")
                i += 1
            else
                sb.append(Pattern.quote(ch.toString))
                i += 1
            end if
        end while
        sb.append("$")
        sb.toString
    end toRegex

end Glob
