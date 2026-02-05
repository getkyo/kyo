package kyo.internal

import scala.annotation.tailrec

/** Shared utilities for HTTP path parsing. */
private[kyo] object PathUtil:

    /** Parse path into non-empty segments. "/api/users/123" → List("api", "users", "123") */
    def parseSegments(path: String): List[String] =
        val len = path.length
        @tailrec def loop(start: Int, acc: List[String]): List[String] =
            val segStart = skipSlashes(path, start, len)
            if segStart >= len then acc.reverse
            else
                val segEnd = findSegmentEnd(path, segStart, len)
                if segEnd > segStart then
                    loop(segEnd, path.substring(segStart, segEnd) :: acc)
                else
                    loop(segEnd, acc)
                end if
            end if
        end loop
        loop(0, Nil)
    end parseSegments

    /** Count non-empty segments without allocating. */
    def countSegments(path: String): Int =
        val len = path.length
        @tailrec def loop(start: Int, count: Int): Int =
            val segStart = skipSlashes(path, start, len)
            if segStart >= len then count
            else
                val segEnd = findSegmentEnd(path, segStart, len)
                if segEnd > segStart then loop(segEnd, count + 1)
                else loop(segEnd, count)
            end if
        end loop
        loop(0, 0)
    end countSegments

    /** Skip consecutive '/' characters. */
    @tailrec def skipSlashes(path: String, pos: Int, len: Int): Int =
        if pos < len && path.charAt(pos) == '/' then skipSlashes(path, pos + 1, len)
        else pos

    /** Find end of current segment (next '/' or end of string). */
    @tailrec def findSegmentEnd(path: String, pos: Int, len: Int): Int =
        if pos < len && path.charAt(pos) != '/' then findSegmentEnd(path, pos + 1, len)
        else pos

end PathUtil
