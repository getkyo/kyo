package kyo

/** Tagged wrapper for pre-encoded JSON text bound against `jsonb` (PostgreSQL) or `JSON` (MySQL) columns.
  *
  * `kyo.Json` is a serializer singleton (not a value type), so it cannot be used directly as a column type. Wrap a JSON-text string in
  * [[JsonText]] to bind it against a JSON-typed column without colliding with the default [[SqlSchema[String]]] mapping (which targets
  * PostgreSQL `text` / MySQL `VARCHAR`).
  *
  * Element shape is not validated at the wrapper boundary, callers should produce well-formed JSON text via [[kyo.Json.encode]] before
  * wrapping. Malformed text will surface as a server-side parse error at execute time.
  *
  * Read-side decoding strips the PostgreSQL `jsonb` version prefix (0x01) and returns the raw UTF-8 JSON text; MySQL returns the
  * length-prefixed UTF-8 payload unchanged.
  */
opaque type JsonText = String

object JsonText:
    def apply(text: String): JsonText = text

    extension (self: JsonText)
        /** Returns the underlying JSON-text string. */
        def value: String = self
end JsonText
