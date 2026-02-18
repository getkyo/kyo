package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import scala.annotation.tailrec

/** Shared multipart parsing utilities used by both buffered (HttpRequest) and streaming (MultipartStreamDecoder) parsers. */
private[kyo] object MultipartUtil:

    /** Find the first occurrence of `pattern` in `data` starting at `from`. Returns -1 if not found. */
    def indexOf(data: Array[Byte], pattern: Array[Byte], from: Int): Int =
        val dataLen    = data.length
        val patternLen = pattern.length
        if patternLen == 0 || from + patternLen > dataLen then -1
        else
            @tailrec def matchAt(i: Int, j: Int): Boolean =
                if j >= patternLen then true
                else if data(i + j) != pattern(j) then false
                else matchAt(i, j + 1)

            @tailrec def search(i: Int): Int =
                if i > dataLen - patternLen then -1
                else if matchAt(i, 0) then i
                else search(i + 1)

            search(from)
        end if
    end indexOf

    /** Extract a quoted parameter value from a Content-Disposition header. */
    def extractDispositionParam(disposition: String, param: String): Maybe[String] =
        val search = param + "=\""
        val idx    = disposition.indexOf(search)
        if idx < 0 then Absent
        else
            val start  = idx + search.length
            val endIdx = disposition.indexOf('"', start)
            if endIdx < 0 then Absent
            else Present(disposition.substring(start, endIdx))
        end if
    end extractDispositionParam

    /** Extract the boundary string from a multipart/form-data Content-Type header. */
    def extractBoundary(contentType: String): Maybe[String] =
        val boundaryPrefix = "boundary="
        val idx            = contentType.indexOf(boundaryPrefix)
        if idx < 0 then Absent
        else
            val start = idx + boundaryPrefix.length
            val value = contentType.substring(start).trim
            if value.length >= 2 && value.charAt(0) == '"' && value.charAt(value.length - 1) == '"' then
                Present(value.substring(1, value.length - 1))
            else
                val semiIdx = value.indexOf(';')
                Present(if semiIdx >= 0 then value.substring(0, semiIdx).trim else value)
            end if
        end if
    end extractBoundary

end MultipartUtil
