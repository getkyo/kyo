package kyo.internal.postgres.types

/** Wire format code used in Bind/Describe/RowDescription messages.
  *
  * PostgreSQL allows each parameter and each result column to be sent in either text or binary format. Text is locale-independent ASCII
  * rendering; binary is type-specific and more compact.
  *
  * Reference: PostgreSQL §55.7 "Bind" and "RowDescription" message formats.
  */
enum Format derives CanEqual:
    case Text, Binary

    /** The Int16 format code used on the wire: 0 for Text, 1 for Binary. */
    inline def code: Short = this match
        case Text   => 0.toShort
        case Binary => 1.toShort
end Format

object Format:
    /** Decodes a wire format code. Unrecognised codes fall back to Text. */
    def fromCode(code: Short): Format =
        if code == 1 then Binary else Text
end Format
