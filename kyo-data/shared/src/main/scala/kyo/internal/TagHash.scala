package kyo.internal

import kyo.bug
import scala.annotation.tailrec

/** Shared hashing of static Tag literals, including literals emitted before hashes were embedded.
  *
  * New literals are !1<flag><two UTF-16 hash code units>:<entry body>. The stored hash describes
  * the original flag and body, so both dispatch and public hashes remain compatible with old tags.
  * Reading a new hash does not allocate, inspect the type graph, or initialize platform objects.
  */
private[kyo] object TagHash:

    private inline val HeaderSize = 6

    def pack(legacy: String): String =
        if legacy.isEmpty || (legacy.charAt(0) != '*' && legacy.charAt(0) != '.') then
            bug("Invalid legacy tag prefix")
        else
            val hash = XXHashPlatform.stringHash(legacy)
            val packed = "!1" + legacy.charAt(0) + (hash >>> 16).toChar + hash.toChar + ":" + legacy.substring(1)
            // JVM constants use modified UTF-8, including two bytes for NUL and three per surrogate.
            if fitsConstant(packed, 65535) then packed else legacy

    def of(tag: Any): Int =
        tag match
            case tag: String =>
                if isPacked(tag) then packedHash(tag)
                else XXHashPlatform.stringHash(tag)
            case tag => tag.hashCode

    def concrete(tag: String): Boolean = flag(tag) == '*'

    def same(a: String, b: String): Boolean =
        if of(a) != of(b) then false
        else
            val aStart = bodyOffset(a)
            val bStart = bodyOffset(b)
            val length = a.length - aStart
            flag(a) == flag(b) && length == b.length - bStart && a.regionMatches(aStart, b, bStart, length)

    def bodyOffset(tag: String): Int = if isPacked(tag) then HeaderSize else 1

    def appendLegacy(tag: String, builder: java.lang.StringBuilder): Unit =
        builder.append(flag(tag)).append(tag, bodyOffset(tag), tag.length)
        ()

    def validate(tag: String): Unit =
        if isPacked(tag) then
            val expected = packedHash(tag)
            @tailrec def hash(index: Int, value: Int): Int =
                if index == tag.length then value
                else hash(index + 1, value * 31 + tag.charAt(index).toInt)
            if hash(HeaderSize, flag(tag).toInt) != expected then bug("Invalid tag hash")
        else if tag.isEmpty || (tag.charAt(0) != '*' && tag.charAt(0) != '.') then
            bug("Invalid tag prefix")

    private def isPacked(tag: String): Boolean = tag.nonEmpty && tag.charAt(0) == '!'

    private def flag(tag: String): Char = tag.charAt(if isPacked(tag) then 2 else 0)

    private def packedHash(tag: String): Int =
        if tag.length < HeaderSize || tag.charAt(1) != '1' ||
            (tag.charAt(2) != '*' && tag.charAt(2) != '.') || tag.charAt(5) != ':'
        then bug("Invalid tag header")
        (tag.charAt(3).toInt << 16) | tag.charAt(4).toInt

    private def fitsConstant(value: String, limit: Int): Boolean =
        @tailrec def loop(index: Int, size: Int): Boolean =
            if size > limit then false
            else if index == value.length then true
            else
                val char = value.charAt(index)
                val width = if char >= 1 && char <= 0x7f then 1 else if char <= 0x7ff then 2 else 3
                loop(index + 1, size + width)
        loop(0, 0)

end TagHash
