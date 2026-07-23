package kyo.internal.msgpack

/** MessagePack format-byte constants and small classification helpers.
  *
  * Centralizes the wire-format prefix bytes shared by [[MsgPackWriter]] and [[MsgPackReader]] so the two stay in sync. Values follow the
  * MessagePack specification (https://github.com/msgpack/msgpack/blob/master/spec.md). Ranges encoded in a single byte (fixint, fixstr,
  * fixarray, fixmap) are expressed as base markers plus masks rather than enumerated. The `inline val`s carry no type ascription so each
  * keeps its singleton literal type, which `inline` requires.
  */
private[msgpack] object MsgPackFormat:

    // Single-byte-encoded ranges
    inline val PositiveFixIntMax = 0x7f // 0x00..0x7f; 0xe0..0xff encode negative fixints -32..-1
    inline val FixMapPrefix      = 0x80 // 0x80..0x8f, low nibble = size
    inline val FixArrayPrefix    = 0x90 // 0x90..0x9f, low nibble = size
    inline val FixStrPrefix      = 0xa0 // 0xa0..0xbf, low 5 bits = length
    inline val FixMax            = 0x0f // max size/length for the fix* map/array forms; fixstr uses FixStrMax
    inline val FixStrMax         = 0x1f

    // Constant single-byte values
    inline val Nil   = 0xc0
    inline val False = 0xc2
    inline val True  = 0xc3

    // Length-prefixed binary
    inline val Bin8  = 0xc4
    inline val Bin16 = 0xc5
    inline val Bin32 = 0xc6

    // Extension types
    inline val Ext8     = 0xc7
    inline val Ext16    = 0xc8
    inline val Ext32    = 0xc9
    inline val FixExt1  = 0xd4
    inline val FixExt2  = 0xd5
    inline val FixExt4  = 0xd6
    inline val FixExt8  = 0xd7
    inline val FixExt16 = 0xd8

    // Floating point
    inline val Float32 = 0xca
    inline val Float64 = 0xcb

    // Unsigned integers
    inline val UInt8  = 0xcc
    inline val UInt16 = 0xcd
    inline val UInt32 = 0xce
    inline val UInt64 = 0xcf

    // Signed integers
    inline val Int8  = 0xd0
    inline val Int16 = 0xd1
    inline val Int32 = 0xd2
    inline val Int64 = 0xd3

    // Length-prefixed string
    inline val Str8  = 0xd9
    inline val Str16 = 0xda
    inline val Str32 = 0xdb

    // Length-prefixed array / map
    inline val Array16 = 0xdc
    inline val Array32 = 0xdd
    inline val Map16   = 0xde
    inline val Map32   = 0xdf

    /** MessagePack reserved extension type for timestamps (`-1`). */
    inline val ExtTypeTimestamp = -1

    // Open-container kinds tracked by the writer to decide whether `field` counts toward an object header.
    inline val KindObject = 0
    inline val KindArray  = 1
    inline val KindMap    = 2

    def isPositiveFixInt(b: Int): Boolean = (b & 0x80) == 0x00         // 0x00..0x7f
    def isNegativeFixInt(b: Int): Boolean = (b & 0xe0) == 0xe0         // 0xe0..0xff
    def isFixMap(b: Int): Boolean         = (b & 0xf0) == FixMapPrefix // 0x80..0x8f
    def isFixArray(b: Int): Boolean       = (b & 0xf0) == FixArrayPrefix
    def isFixStr(b: Int): Boolean         = (b & 0xe0) == FixStrPrefix // 0xa0..0xbf

    def isStr(b: Int): Boolean   = isFixStr(b) || b == Str8 || b == Str16 || b == Str32
    def isMap(b: Int): Boolean   = isFixMap(b) || b == Map16 || b == Map32
    def isArray(b: Int): Boolean = isFixArray(b) || b == Array16 || b == Array32
    def isBin(b: Int): Boolean   = b == Bin8 || b == Bin16 || b == Bin32
    def isExt(b: Int): Boolean =
        b == Ext8 || b == Ext16 || b == Ext32 ||
            b == FixExt1 || b == FixExt2 || b == FixExt4 || b == FixExt8 || b == FixExt16

    def isInt(b: Int): Boolean =
        isPositiveFixInt(b) || isNegativeFixInt(b) ||
            b == UInt8 || b == UInt16 || b == UInt32 || b == UInt64 ||
            b == Int8 || b == Int16 || b == Int32 || b == Int64

    def isFloat(b: Int): Boolean = b == Float32 || b == Float64

end MsgPackFormat
