package kyo.internal.tasty.binary

import scala.scalajs.js
import scala.scalajs.js.typedarray.Int8Array

/** JS platform implementation of UTF-8 decode.
  *
  * Uses the TextDecoder Web API (available in Node.js 11+ and all modern browsers). The slice is copied into a typed Int8Array
  * (JavaScript's Int8Array / Uint8Array), then decoded by TextDecoder. Using Int8Array here is safe: TextDecoder treats the underlying
  * ArrayBuffer as unsigned bytes regardless of the view type.
  *
  * Note on 4-byte sequences: JS strings are UTF-16. U+1F600 (encoded as 4 bytes in UTF-8) becomes a single code point in a JS string
  * (String.length == 1), unlike JVM which uses a surrogate pair (String.length == 2).
  */
object Utf8 extends Utf8Impl:

    def decode(bytes: Array[Byte], offset: Int, length: Int): String =
        val arr = new Int8Array(length)
        var i   = 0
        while i < length do
            arr(i) = bytes(offset + i)
            i += 1
        val decoder = js.Dynamic.newInstance(js.Dynamic.global.TextDecoder)("utf-8")
        decoder.decode(arr).asInstanceOf[String]
    end decode
end Utf8
