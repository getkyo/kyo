package kyo

/** Cast-target evidence: the portable SQL type a value of `A` occupies, summoned only at [[kyo.Sql.Term.cast]].
  *
  * `expr.cast[B]` renders `CAST(expr AS <B's SQL type>)`. The target column type is the one piece of SQL knowledge that operation needs, so
  * it is carried by this small sidecar rather than by the whole schema. A type with a portable SQL type has a `given SqlType`; a type that
  * has none (for example [[java.time.Duration]], whose fixed count of seconds is a type neither backend casts to) has no given, so
  * `.cast` to it is a compile error rather than a render-time failure.
  *
  * A user or factory declaring a column type of their own attaches a `SqlType` the same way they attach a schema, through [[SqlType.of]]:
  * {{{
  *   given SqlType[Geometry] = SqlType.of(SqlType.Type.Extension("geometry"))
  * }}}
  * A static render resolves the summoned `SqlType` by reference (the `Cast` node's custom lift reads it), so a plain `given` folds; no
  * `inline` is needed.
  *
  * The wrapped value is the portable [[SqlType.Type]] enum, read off the `Cast` node by `Idiom.cast` and spelled per dialect at render time.
  */
// The phantom `A` is the point of the wrapper: `.cast[B]` picks its target by summoning `SqlType[B]`, which needs a
// type-INDEXED given; the bare `SqlType.Type` enum cannot be summoned by target type. The opaque alias keeps that
// index with zero allocation: a `SqlType[A]` IS its `Type` at runtime.
opaque type SqlType[A] = SqlType.Type

object SqlType:

    /** The portable SQL type vocabulary every kyo-sql backend encodes and decodes against.
      *
      * A value of this enum names a SQL type independently of any one backend: [[SqlType.Type.Text]] is Postgres `TEXT` and MySQL `VARCHAR`,
      * [[SqlType.Type.Uuid]] is a Postgres `UUID` column and a 36-character MySQL string. Backends map each case onto their own wire and
      * column types, so a schema described with this vocabulary runs unchanged on either backend.
      *
      * The temporal cases are deliberately split three ways, because the distinction is not visible in the SQL column type alone:
      * [[SqlType.Type.DateTime]] carries no zone, [[SqlType.Type.Timestamp]] identifies a point on the timeline, and
      * [[SqlType.Type.CalendarInterval]] counts calendar units rather than a fixed number of seconds.
      *
      * Two cases take arguments. [[SqlType.Type.Array]] nests any other case as its element type, so `Array(Text)` describes a
      * one-dimensional array of text. [[SqlType.Type.Extension]] names a type only one backend owns, such as Postgres `HSTORE`; running an
      * extension type against the backend that does not own it fails with [[kyo.SqlUnsupportedTypeOnBackendException]].
      */
    enum Type derives CanEqual:
        case Text
        case SmallInt, Int, BigInt
        case Boolean
        case Float32, Float64

        /** Fixed-point decimal. `precision` is the total digit count and `scale` the digits after the point; both absent means the backend
          * default.
          */
        case Numeric(precision: Maybe[scala.Int] = Absent, scale: Maybe[scala.Int] = Absent)

        case Bytes
        case Uuid
        case Json
        case Date, Time, TimeWithOffset

        /** Wall-clock date and time with no zone attached. Postgres `TIMESTAMP`, MySQL `DATETIME`. */
        case DateTime

        /** A point on the timeline, zone-aware and normalised to UTC. Postgres `TIMESTAMPTZ`, MySQL `DATETIME` stored in UTC. */
        case Timestamp

        /** A calendar span counted in months and days rather than a fixed number of seconds, matching `java.time.Period`. */
        case CalendarInterval

        /** A one-dimensional array whose elements have the type `element`. */
        case Array(element: SqlType.Type)

        /** A type owned by a single dialect, named by the backend's own type name (for example `"hstore"`). */
        case Extension(typeName: String)
    end Type

    /** Builds cast-target evidence from a portable column type. Used to declare a `SqlType` for a user or factory column type, most often an
      * [[SqlType.Type.Extension]] one. The resulting `given` is read by reference at a `.cast[A]` static render (the `Cast` custom lift), so
      * a plain `given` folds and no `inline` is needed.
      */
    def of[A](columnType: Type): SqlType[A] = columnType

    extension [A](self: SqlType[A])
        /** The portable column type this evidence names. */
        inline def columnType: Type = self

    // Built-in cast targets, one per type whose default single-column schema declares a portable SQL type. Each maps to the same enum value
    // that type's schema uses. A `.cast[A]` static render resolves the summoned given by reference (the `Cast` custom lift in
    // `ColumnFromExpr`, via `resolveStableGiven`), so these are plain givens. Byte declares SmallInt because Postgres has no one-byte integer
    // type, and the narrowest cast target both flavors accept is the two-byte one. Duration is deliberately absent: it has no portable SQL
    // type, so `.cast[Duration]` is a compile error.

    given SqlType[Long]        = Type.BigInt
    given SqlType[Int]         = Type.Int
    given SqlType[Short]       = Type.SmallInt
    given SqlType[Byte]        = Type.SmallInt
    given SqlType[String]      = Type.Text
    given SqlType[Boolean]     = Type.Boolean
    given SqlType[Float]       = Type.Float32
    given SqlType[Double]      = Type.Float64
    given SqlType[BigDecimal]  = Type.Numeric(Absent, Absent)
    given SqlType[BigInt]      = Type.Numeric(Absent, Absent)
    given SqlType[Span[Byte]]  = Type.Bytes
    given SqlType[kyo.Instant] = Type.Timestamp

    given SqlType[java.time.LocalDate]      = Type.Date
    given SqlType[java.time.LocalDateTime]  = Type.DateTime
    given SqlType[java.time.LocalTime]      = Type.Time
    given SqlType[java.time.OffsetTime]     = Type.TimeWithOffset
    given SqlType[java.time.Period]         = Type.CalendarInterval
    given SqlType[java.time.OffsetDateTime] = Type.Timestamp
    given SqlType[java.time.ZonedDateTime]  = Type.Timestamp

    given SqlType[java.net.URI]       = Type.Text
    given SqlType[java.util.Locale]   = Type.Text
    given SqlType[java.util.Currency] = Type.Text
    given SqlType[java.util.UUID]     = Type.Uuid

    // Named because an anonymous given derives its synthesized name from the type head alone (`Chunk`), so the
    // three Chunk targets would collide; the same reason the parallel Schema givens carry names.
    given chunkIntType: SqlType[Chunk[Int]]           = Type.Array(Type.Int)
    given chunkStringType: SqlType[Chunk[String]]     = Type.Array(Type.Text)
    given jsonTextType: SqlType[JsonText]             = Type.Json
    given chunkJsonTextType: SqlType[Chunk[JsonText]] = Type.Array(Type.Json)

end SqlType
