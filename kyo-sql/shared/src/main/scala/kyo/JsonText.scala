package kyo

/** A JSON document as text, bound to and read from a SQL `json` column (`jsonb` on PostgreSQL, `JSON` on MySQL).
  *
  * The carrier for dynamic, runtime-shaped values in a single self-describing column, without coupling kyo-sql to any document library: a
  * plain `String` binds as text, while a `JsonText` reaches the native JSON wire, so the server indexes and queries it as a document.
  * Produce the text with any JSON encoder; with kyo-schema, `JsonText(Json.encodeString(value))` stores any `Schema`-encodable value,
  * including a runtime-built `Structure.Value`.
  *
  * `Chunk[JsonText]` is the array counterpart, one JSON document per element.
  */
opaque type JsonText = String

object JsonText:

    /** Wraps JSON text. The text is passed to the server as-is; the server validates it as JSON. */
    def apply(text: String): JsonText = text

    extension (self: JsonText)
        /** The JSON document text. */
        def text: String = self

    given column: SqlSchema.Column[JsonText] =
        new SqlSchema.Column((v, w) => w.json(v), r => r.nextJson())

    given chunkColumn: SqlSchema.Column[Chunk[JsonText]] =
        // The alias is transparent inside the companion: Chunk[JsonText] is Chunk[String] here.
        new SqlSchema.Column((v, w) => w.arrayOfJson(v), r => r.nextArrayOfJson())

    given CanEqual[JsonText, JsonText] = CanEqual.derived

end JsonText
