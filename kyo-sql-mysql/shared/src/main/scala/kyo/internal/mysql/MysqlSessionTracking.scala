package kyo.internal.mysql

import java.nio.charset.StandardCharsets
import kyo.*

/** Reads the session-state-change block the server attaches to an OK packet when a tracked variable changes.
  *
  * `CLIENT_SESSION_TRACK` is negotiated at handshake, and the default tracked set is exactly the variables whose value decides what the
  * driver's bytes mean: `time_zone` and the three `character_set_*`. The block rides on the OK packet of the statement that changed one.
  *
  * Wire form, one or more entries: a type byte, a length-encoded length, then that many bytes of payload. For the system-variables type the
  * payload is a length-encoded name followed by a length-encoded value.
  *
  * Parsed from raw bytes rather than a decoded string, because the lengths are binary: a length of 128 or more is not a valid UTF-8 sequence,
  * so decoding first would replace it and lose the framing.
  */
private[mysql] object MysqlSessionTracking:

    /** SESSION_TRACK_SYSTEM_VARIABLES, the only entry type this driver acts on. */
    private val SystemVariables: Int = 0x00

    /** One reported change: the variable's name and the value it now holds. */
    final case class Change(name: String, value: String) derives CanEqual

    /** Reads every system-variable change in `block`.
      *
      * Answers what it could read rather than failing: a malformed block must not fail a statement that already succeeded. An unrecognised
      * entry is skipped by its declared length, and a block ending mid-entry stops there.
      */
    def read(block: Span[Byte]): Chunk[Change] =
        val bytes   = block.toArray
        var pos     = 0
        var changes = Chunk.empty[Change]

        def readLenencInt(): Long =
            if pos >= bytes.length then -1L
            else
                val first = bytes(pos) & 0xff
                pos += 1
                if first < 0xfb then first.toLong
                else if first == 0xfc && pos + 2 <= bytes.length then
                    val v = (bytes(pos) & 0xff) | ((bytes(pos + 1) & 0xff) << 8)
                    pos += 2
                    v.toLong
                else if first == 0xfd && pos + 3 <= bytes.length then
                    val v = (bytes(pos) & 0xff) | ((bytes(pos + 1) & 0xff) << 8) | ((bytes(pos + 2) & 0xff) << 16)
                    pos += 3
                    v.toLong
                else if first == 0xfe && pos + 8 <= bytes.length then
                    var v = 0L
                    var i = 0
                    while i < 8 do
                        v |= (bytes(pos + i) & 0xffL) << (8 * i)
                        i += 1
                    end while
                    pos += 8
                    v
                else -1L
                end if
            end if
        end readLenencInt

        def readLenencString(): Maybe[String] =
            val len = readLenencInt()
            if len < 0 || pos + len > bytes.length then Absent
            else
                val s = new String(bytes, pos, len.toInt, StandardCharsets.UTF_8)
                pos += len.toInt
                Present(s)
            end if
        end readLenencString

        while pos < bytes.length do
            val entryType = bytes(pos) & 0xff
            pos += 1
            val entryLen = readLenencInt()
            if entryLen < 0 || pos + entryLen > bytes.length then pos = bytes.length
            else
                val entryEnd = pos + entryLen.toInt
                if entryType == SystemVariables then
                    val name  = readLenencString()
                    val value = readLenencString()
                    (name, value) match
                        case (Present(n), Present(v)) => changes = changes.append(Change(n, v))
                        case _                        => ()
                    end match
                end if
                // Skip to the entry's declared end whatever was read, so one unrecognised or short entry cannot
                // desynchronise the entries after it.
                pos = entryEnd
            end if
        end while
        changes
    end read

end MysqlSessionTracking
