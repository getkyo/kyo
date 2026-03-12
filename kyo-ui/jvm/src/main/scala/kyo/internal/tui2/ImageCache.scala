package kyo.internal.tui2

import kyo.*
import kyo.Maybe.*
import scala.annotation.tailrec

/** Pre-allocated LRU image cache. Stores encoded escape sequences keyed by (path, protocol, width, height). File I/O errors return Absent
  * (via Result), never throw.
  *
  * Callers use `get.orElse { loadRaw(...).flatMap(encode).map(putEncoded) }` — all Maybe ops are inline, so zero allocation on cache hit
  * AND miss (only the unavoidable file read and encode byte arrays allocate on miss).
  */
final private[kyo] class ImageCache(maxEntries: Int = 32):
    private val paths     = new Array[String](maxEntries)
    private val protocols = new Array[Int](maxEntries)
    private val widths    = new Array[Int](maxEntries)
    private val heights   = new Array[Int](maxEntries)
    private val encoded   = new Array[Array[Byte]](maxEntries)
    private var count     = 0

    /** Look up cached encoded sequence for exact (path, protocol, w, h) match. */
    def get(path: String, protocol: Int, w: Int, h: Int): Maybe[Array[Byte]] =
        @tailrec def loop(i: Int): Maybe[Array[Byte]] =
            if i >= count then Absent
            else if paths(i) == path && protocols(i) == protocol &&
                widths(i) == w && heights(i) == h
            then
                Present(encoded(i))
            else loop(i + 1)
        loop(0)
    end get

    /** Load raw file bytes. Uses Result to catch IOException — no throw. Cold path only (called on cache miss).
      */
    def loadRaw(path: String): Maybe[Array[Byte]] =
        Result.apply[Array[Byte]] {
            java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path))
        }.toMaybe

    /** Store an encoded sequence in the cache. */
    def putEncoded(path: String, protocol: Int, w: Int, h: Int, data: Array[Byte]): Unit =
        if count >= maxEntries then
            java.lang.System.arraycopy(paths, 1, paths, 0, maxEntries - 1)
            java.lang.System.arraycopy(protocols, 1, protocols, 0, maxEntries - 1)
            java.lang.System.arraycopy(widths, 1, widths, 0, maxEntries - 1)
            java.lang.System.arraycopy(heights, 1, heights, 0, maxEntries - 1)
            java.lang.System.arraycopy(encoded, 1, encoded, 0, maxEntries - 1)
            count = maxEntries - 1
        end if
        paths(count) = path
        protocols(count) = protocol
        widths(count) = w
        heights(count) = h
        encoded(count) = data
        count += 1
    end putEncoded

end ImageCache
