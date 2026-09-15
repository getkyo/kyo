package kyo.internal

import kyo.discard

/** Path syntax on Scala.js: normalization and absoluteness for `Path` construction, `parts` and `isAbsolute`.
  *
  * None of these touch a file system, so on a POSIX host, and on a host with no OS paths at all such as a browser, they are computed here
  * with no host module: [[posixNormalize]] and [[posixIsAbsolute]] are ports of Node's `path.posix`, which have been stable across Node
  * releases, and give the string Node gives.
  *
  * On Windows the rules are Node's `path.win32`, reached through `process.getBuiltinModule`. They are not ported: Node revises them
  * between releases (device roots, reserved names, and a `.\` prefix for relative paths containing `:`, CVE-2024-36139), and a copy would
  * disagree with the Node it runs on. A Windows host without the module fails with an `UnsupportedOperationException` naming the host.
  */
private[kyo] object PathSyntaxJs:

    /** Normalizes `path`: Node's `path.win32` rules on Windows, [[posixNormalize]] elsewhere. */
    def normalize(path: String): String =
        if Platform.isWindows then win32.normalize(path).asInstanceOf[String] else posixNormalize(path)

    /** Whether `path` is absolute: Node's `path.win32` rules on Windows, [[posixIsAbsolute]] elsewhere. */
    def isAbsolute(path: String): Boolean =
        if Platform.isWindows then win32.isAbsolute(path).asInstanceOf[Boolean] else posixIsAbsolute(path)

    /** Node's `path.posix.normalize`: resolves `.` and `..`, collapses repeated separators, keeps a trailing separator. */
    def posixNormalize(path: String): String =
        if path.isEmpty then "."
        else
            val absolute          = path.charAt(0) == '/'
            val trailingSeparator = path.charAt(path.length - 1) == '/'
            val resolved          = resolveDots(path, allowAboveRoot = !absolute)
            if resolved.isEmpty then
                if absolute then "/" else if trailingSeparator then "./" else "."
            else
                val withTrailing = if trailingSeparator then resolved + "/" else resolved
                if absolute then "/" + withTrailing else withTrailing
            end if

    /** Node's `path.posix.isAbsolute`. */
    def posixIsAbsolute(path: String): Boolean =
        path.nonEmpty && path.charAt(0) == '/'

    private def win32: scala.scalajs.js.Dynamic =
        PlatformJs.nodeBuiltin("node:path").fold(
            throw new UnsupportedOperationException(
                s"Path syntax on Windows needs Node's node:path module (Node, Bun or Deno); this host is ${Platform.host}"
            )
        )(_.win32)

    /** Node's `normalizeString` for `/`: the segments with `.` dropped and each `..` removing the segment before it, joined by `/`. A `..`
      * with nothing before it to remove is kept when `allowAboveRoot` (a relative path) and dropped otherwise (an absolute one).
      */
    private def resolveDots(path: String, allowAboveRoot: Boolean): String =
        val result            = new java.lang.StringBuilder
        var lastSegmentLength = 0
        var lastSlash         = -1
        var dots              = 0
        var i                 = 0
        while i <= path.length do
            // One past the end reads as a separator, closing the last segment; a path ending in one closes it already.
            if i == path.length && path.charAt(i - 1) == '/' then i = path.length + 1
            else
                val code = if i < path.length then path.charAt(i) else '/'
                if code == '/' then
                    if lastSlash == i - 1 || dots == 1 then ()
                    else if dots == 2 then
                        val endsWithDotDot =
                            result.length >= 2 && lastSegmentLength == 2 &&
                                result.charAt(result.length - 1) == '.' && result.charAt(result.length - 2) == '.'
                        if !endsWithDotDot && result.length > 0 then
                            val lastSeparator = result.lastIndexOf("/")
                            if lastSeparator == -1 then
                                result.setLength(0)
                                lastSegmentLength = 0
                            else
                                result.setLength(lastSeparator)
                                lastSegmentLength = result.length - 1 - result.lastIndexOf("/")
                            end if
                        else if allowAboveRoot then
                            discard(if result.length > 0 then result.append("/..") else result.append(".."))
                            lastSegmentLength = 2
                        end if
                    else
                        if result.length > 0 then discard(result.append('/'))
                        discard(result.append(path, lastSlash + 1, i))
                        lastSegmentLength = i - lastSlash - 1
                    end if
                    lastSlash = i
                    dots = 0
                else if code == '.' && dots != -1 then dots += 1
                else dots = -1
                end if
                i += 1
            end if
        end while
        result.toString
    end resolveDots

end PathSyntaxJs
