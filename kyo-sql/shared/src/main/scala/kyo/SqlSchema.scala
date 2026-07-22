package kyo

import kyo.internal.SqlReader
import kyo.internal.SqlWriter
import kyo.internal.client.TypeRegistry
import kyo.internal.mysql.BoundMysqlParam
import kyo.internal.mysql.MysqlParamWriter
import kyo.internal.mysql.MysqlRowReader
import kyo.internal.postgres.BoundParam
import kyo.internal.postgres.PostgresParamWriter
import kyo.internal.postgres.PostgresRowReader
import scala.compiletime.constValue
import scala.compiletime.erasedValue
import scala.compiletime.summonFrom
import scala.compiletime.summonInline
import scala.deriving.Mirror

/** Public typeclass for SQL schema derivation.
  *
  * Opaque wrapper around a [[kyo.Schema]] plus SQL-specific naming state ([[SqlSchema.Naming]], table-name override, renamed fields). Users
  * register case class schemas via `given SqlSchema[A] = SqlSchema.derived` in the companion object. Primitive schemas are provided
  * automatically.
  *
  * Both Postgres and MySQL backends share the same write/read lambdas, delegating through backend-specific [[SqlWriter]] / [[SqlReader]]
  * subclasses.
  */
opaque type SqlSchema[A] = SqlSchema.State[A]

object SqlSchema:

    /** Internal storage backing every `SqlSchema[A]`. Holds the underlying [[Schema]] and the SQL-specific naming state that
      * [[SqlNameResolver]] and [[internal.SqlSchemaInfo]] consult when resolving table and column names.
      */
    final private[kyo] case class State[A](
        schema: Schema[A],
        namingStrategy: Maybe[SqlSchema.Naming] = Maybe.Absent,
        tableNameOverride: Maybe[String] = Maybe.Absent,
        renamedFields: Chunk[(String, String)] = Chunk.empty
    )

    /** Wraps a bare [[Schema]] into a [[SqlSchema]] with no naming overrides attached. Every internal factory that builds a schema (derived,
      * of, ofMulti, tuple givens, primitive givens) funnels through this helper so the wrapping shape lives in exactly one place.
      */
    private[kyo] def wrap[A](schema: Schema[A]): SqlSchema[A] = State(schema)

    /** Lifts an existing [[Schema]] into a [[SqlSchema]]. */
    def apply[A](using s: SqlSchema[A]): SqlSchema[A] = s

    /** Alias accessor for [[SqlSchema.Naming]] companion. Enables `SqlSchema.naming.snakeCase` at the call site. */
    inline def naming: SqlSchema.Naming.type = SqlSchema.Naming

    /** Derives a [[SqlSchema]] for a case class or sealed trait / enum `A`.
      *
      * For product types (case classes, tuples): every field type of `A` must have a [[Schema]] in implicit scope. Field names from
      * [[Mirror.ProductOf.MirroredElemLabels]] are stored as [[Field]] descriptors so that [[fieldCount]] and [[fieldNames]] return correct
      * values.
      *
      * For sum types (sealed traits, Scala 3 enums): the schema occupies a single SQL column. Two strategies are auto-selected at derive
      * time based on the variants' shape:
      *
      *   - **String discriminator** when every variant is a singleton (case object / no-arg enum case). The column holds the variant
      *     label as `TEXT` on PostgreSQL and MySQL. Smallest storage, queryable with standard `=` / `IN` predicates.
      *   - **JSON encoding** when any variant carries data fields. The column holds the [[kyo.Json]]-encoded value as `jsonb` on
      *     PostgreSQL and `JSON` on MySQL. Handles arbitrary variant shapes uniformly.
      *
      * Invoke as `given SqlSchema[Foo] = SqlSchema.derived` in the companion object. Recursive sum types and sum types with case-class
      * variants both use the JSON path; pure-enum sum types use the cheaper string path automatically.
      */
    inline def derived[A]: SqlSchema[A] =
        summonFrom {
            case m: Mirror.ProductOf[A] => derivedProduct[A](using m)
            case m: Mirror.SumOf[A]     => derivedSum[A](using m)
        }

    /** Product-type derivation (case classes, tuples). See [[derived]] for usage. */
    private inline def derivedProduct[A](using m: Mirror.ProductOf[A]): SqlSchema[A] =
        val inner = Schema.derived[A]
        // Populate sourceFields from Mirror labels so fieldCount / fieldNames work correctly.
        // Schema.derived produces sourceFields = Nil by design; we rebuild it here.
        val fields = buildSourceFields[m.MirroredElemLabels, m.MirroredElemTypes]
        wrap(Schema.init[A](
            writeFn = inner.serializeWrite(_, _),
            readFn = inner.serializeRead(_),
            getterFn = _ => Maybe.empty,
            setterFn = (a, _) => a,
            sourceFields = fields
        ))
    end derivedProduct

    /** Sum-type derivation (sealed traits, enums). Auto-selects between a single-TEXT-column string discriminator (when every variant is a
      * singleton case object / no-arg enum case) and a single-jsonb-column JSON encoding (when any variant carries data fields).
      */
    private inline def derivedSum[A](using m: Mirror.SumOf[A]): SqlSchema[A] =
        inline if allSingletonVariants[m.MirroredElemTypes] then
            derivedSumStringDiscriminator[A](using m)
        else
            derivedSumJson[A]
    end derivedSum

    /** Compile-time check: every type in `Types` has a [[ValueOf]] given, i.e. every sum variant is a singleton (case object / no-arg enum
      * case). Returns false if any variant is a case class with data fields.
      */
    private inline def allSingletonVariants[Types <: Tuple]: Boolean =
        inline erasedValue[Types] match
            case _: EmptyTuple => true
            case _: (h *: t) =>
                summonFrom {
                    case _: ValueOf[`h`] => allSingletonVariants[t]
                    case _               => false
                }

    /** Collects the [[ValueOf]]-materialised singleton instance for each variant type into an array, ordered by
      * [[Mirror.SumOf.MirroredElemTypes]] ordinal. Requires every variant to have a [[ValueOf]] given.
      */
    private inline def collectSingletons[Types <: Tuple]: List[Any] =
        inline erasedValue[Types] match
            case _: EmptyTuple => Nil
            case _: (h *: t)   => summonInline[ValueOf[h]].value :: collectSingletons[t]

    /** Collects the compile-time string literal at each position of `Labels` into an ordered list. */
    private inline def collectLabels[Labels <: Tuple]: List[String] =
        inline erasedValue[Labels] match
            case _: EmptyTuple => Nil
            case _: (h *: t)   => constValue[h & String] :: collectLabels[t]

    /** Single-TEXT-column string-discriminator schema for sum types whose variants are all singletons. */
    private inline def derivedSumStringDiscriminator[A](using m: Mirror.SumOf[A]): SqlSchema[A] =
        val labels     = collectLabels[m.MirroredElemLabels].toArray
        val singletons = collectSingletons[m.MirroredElemTypes].toArray
        // Mirror's contract guarantees singletons(m.ordinal(a)) is the singleton matching `a`,
        // and that every Singleton in MirroredElemTypes is structurally an A. The asInstanceOf here
        // is unavoidable: tuple-typed inline traversal loses the variant-subtype information that
        // the Mirror.SumOf contract carries.
        SqlSchema.of[A](
            write = (v, w) => w.string(labels(m.ordinal(v))),
            read = r =>
                val s   = r.string()
                val ord = labels.indexOf(s)
                if ord < 0 then
                    throw SqlDecodeSumTypeUnknownLabelException(s, Chunk.from(labels))(using r.frame)
                end if
                singletons(ord).asInstanceOf[A]
        )
    end derivedSumStringDiscriminator

    /** Single-jsonb-column JSON-encoded schema for sum types with case-class variants. Delegates to [[kyo.Json]] for variant encoding /
      * decoding using the user-provided [[kyo.Schema]] for the sum type.
      */
    private inline def derivedSumJson[A]: SqlSchema[A] =
        val inner = Schema.derived[A]
        SqlSchema.of[A](
            write = (v, w) =>
                given Frame     = w.frame
                given Schema[A] = inner
                val text        = kyo.Json.encode[A](v)
                w match
                    case pw: PostgresParamWriter =>
                        val buf = new kyo.internal.postgres.PostgresBufferWriter()
                        kyo.internal.postgres.types.PostgresEncoder.jsonbBinary.write(text, buf)
                        pw.custom("jsonb", buf.toSpan, kyo.internal.postgres.types.Format.Binary)
                    case mw: MysqlParamWriter =>
                        mw.custom(
                            "json",
                            Span.from(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                            kyo.internal.postgres.types.Format.Binary
                        )
                    case _ =>
                        bug(s"sum-type JSON SqlSchema cannot write to ${w.getClass}")
                end match
            ,
            read = r =>
                given Frame     = r.frame
                given Schema[A] = inner
                val text = r match
                    case pr: PostgresRowReader =>
                        val bytes = pr.custom("jsonb")
                        kyo.internal.postgres.types.PostgresDecoder.jsonDecoder.read(
                            kyo.internal.postgres.types.Format.Binary,
                            bytes
                        )(using pr.frame)
                    case mr: MysqlRowReader => mr.string()
                    case _ =>
                        bug(s"sum-type JSON SqlSchema cannot read from ${r.getClass}")
                kyo.Json.decode[A](text) match
                    case kyo.Result.Success(a) => a
                    case kyo.Result.Failure(e) =>
                        throw SqlDecodeJsonException(e.getMessage.take(100), new Exception(e.getMessage))(using r.frame)
                    case kyo.Result.Panic(t) => throw t
                end match
        )
    end derivedSumJson

    /** Returns the number of SQL columns that type `A` occupies, as a compile-time constant.
      *
      * For product types (case classes, tuples) this is the number of constructor fields as determined from the [[Mirror.ProductOf]]
      * evidence. For scalar types (primitives, [[Maybe]], [[SqlSchema.of]] scalars) there is no product mirror in scope and this returns 1.
      *
      * This value is used by the `sql"..."` macro at compile time to emit the correct number of `?` / `$$N` placeholder slots for a
      * multi-field record interpolation.
      */
    transparent inline def fieldCountOf[A]: Int =
        summonFrom {
            case m: Mirror.ProductOf[A] => constValue[Tuple.Size[m.MirroredElemTypes]]
            case _                      => 1
        }

    /** Builds a list of [[Field]] descriptors from the compile-time Mirror element labels and types. */
    private inline def buildSourceFields[Labels <: Tuple, Types <: Tuple]: List[Field[?, ?]] =
        inline erasedValue[(Labels, Types)] match
            case _: (EmptyTuple, EmptyTuple) => Nil
            case _: ((lh *: lt), (th *: tt)) =>
                val name = constValue[lh & String]
                val tag  = summonInline[Tag[th]]
                Field[lh & String, th](name, tag) :: buildSourceFields[lt, tt]

    /** Multi-column escape hatch for custom encodings that span more than one SQL column.
      *
      * Use this for opt-in alternatives like [[offsetDateTimePreserving]] / [[zonedDateTimePreserving]] (each occupying two columns:
      * instant + offset/zone). The provided `fieldNames` determine the reported [[fieldCount]] and [[fieldNames]] of the resulting schema;
      * `write` must emit exactly `fieldNames.size` primitive values, and `read` must consume exactly that many.
      *
      * @param fieldNames
      *   logical column names in declaration order (used for `fieldCount` / `fieldNames`)
      * @param write
      *   writes the value's columns to a [[SqlWriter]] in declaration order
      * @param read
      *   reads the value's columns from a [[SqlReader]] in declaration order
      */
    def ofMulti[A](
        fieldNames: Seq[String],
        write: (A, SqlWriter) => Unit,
        read: SqlReader => A
    ): SqlSchema[A] =
        val sourceFields = fieldNames.map(n => Field[String, Any](n, Tag[Any]))
        wrap(Schema.init[A](
            writeFn = (v, w) =>
                w match
                    case sw: SqlWriter => write(v, sw)
                    case _             => bug(s"SqlSchema.ofMulti requires a SqlWriter; got ${w.getClass}")
            ,
            readFn = (r: Codec.Reader) =>
                r match
                    case sr: SqlReader => read(sr)
                    case _             => bug(s"SqlSchema.ofMulti requires a SqlReader; got ${r.getClass}")
            ,
            getterFn = _ => Maybe.empty,
            setterFn = (a, _) => a,
            sourceFields = sourceFields
        ))
    end ofMulti

    /** Manual escape hatch for extension types (geometry, JSONB, hstore, pgvector) that need custom write/read logic.
      *
      * The returned schema occupies exactly one SQL column and always reports `fieldCount == 1`. Use `SqlSchema.derived` for multi-field
      * record types; `of(...)` is for single-column custom encodings only.
      *
      * @param write
      *   writes the value to a [[SqlWriter]] (called once per bind execution)
      * @param read
      *   reads the value from a [[SqlReader]] (called once per result row)
      */
    def of[A](
        write: (A, SqlWriter) => Unit,
        read: SqlReader => A
    ): SqlSchema[A] =
        // getterFn/setterFn are no-ops: SqlSchema never needs the focus/Changeset machinery.
        // sourceFields is left at its default (Seq.empty); fieldCount maps 0 → 1.
        //
        // Schema.init expects (A, Codec.Writer) => Unit. SqlWriter IS-A Codec.Writer, but
        // function types are contravariant in parameters: (A, SqlWriter) => Unit is a supertype,
        // not a subtype, of (A, Codec.Writer) => Unit. We bridge with a wrapper lambda that
        // pattern-matches (the match always succeeds, only SqlWriter instances reach this path).
        wrap(Schema.init[A](
            writeFn = (v, w) =>
                w match
                    case sw: SqlWriter => write(v, sw)
                    case _             => bug(s"SqlSchema.of requires a SqlWriter; got ${w.getClass}")
            ,
            readFn = (r: Codec.Reader) =>
                r match
                    case sr: SqlReader => read(sr)
                    case _             => bug(s"SqlSchema.of requires a SqlReader; got ${r.getClass}")
            ,
            getterFn = _ => Maybe.empty,
            setterFn = (a, _) => a
        ))

    // --- Primitive given instances (opacification of kyo-schema primitives) ---
    // Reference Schema companion givens directly to avoid triggering the ambient
    // `derived` inline given during implicit search for Schema[Long], etc.

    given SqlSchema[Long]        = wrap(Schema.longSchema)
    given SqlSchema[Int]         = wrap(Schema.intSchema)
    given SqlSchema[Short]       = wrap(Schema.shortSchema)
    given SqlSchema[Byte]        = wrap(Schema.byteSchema)
    given SqlSchema[String]      = wrap(Schema.stringSchema)
    given SqlSchema[Boolean]     = wrap(Schema.booleanSchema)
    given SqlSchema[Float]       = wrap(Schema.floatSchema)
    given SqlSchema[Double]      = wrap(Schema.doubleSchema)
    given SqlSchema[BigDecimal]  = wrap(Schema.bigDecimalSchema)
    given SqlSchema[Span[Byte]]  = wrap(Schema.spanByteSchema)
    given SqlSchema[kyo.Instant] = wrap(Schema.kyoInstantSchema)

    /** java.time.Duration → PG INTERVAL (binary 16-byte struct, µs/days/months) or MySQL TIME. Delegates to [[Schema.durationSchema]],
      * which calls `writer.duration(v)`, `PostgresParamWriter.duration` lands an `intervalBinary` BoundParam; `MysqlParamWriter.duration`
      * lands an `intervalTime` BoundMysqlParam.
      */
    given SqlSchema[java.time.Duration] = wrap(Schema.durationSchema)

    given SqlSchema[java.time.LocalDate] = SqlSchema.of[java.time.LocalDate](
        write = (v, w) =>
            w match
                case pw: PostgresParamWriter =>
                    val buf = new kyo.internal.postgres.PostgresBufferWriter()
                    kyo.internal.postgres.types.PostgresEncoder.dateBinary.write(v, buf)
                    pw.custom("date", buf.toSpan, kyo.internal.postgres.types.Format.Binary)
                case mw: MysqlParamWriter =>
                    val buf = new kyo.internal.mysql.MysqlBufferWriter()
                    kyo.internal.mysql.types.MysqlEncoder.localDateEncoder.write(v, buf)
                    mw.custom("date", buf.toSpan, kyo.internal.postgres.types.Format.Binary)
                case _ =>
                    bug(s"SqlSchema[LocalDate] cannot write to ${w.getClass}")
        ,
        read = r =>
            r match
                case pr: PostgresRowReader =>
                    val bytes = pr.custom("date")
                    kyo.internal.postgres.types.PostgresDecoder.date.read(
                        kyo.internal.postgres.types.Format.Binary,
                        bytes
                    )(using pr.frame)
                case mr: MysqlRowReader =>
                    val bytes = mr.custom("date")
                    kyo.internal.mysql.MysqlRowReader.decodeDateBytes(bytes)(using mr.frame)
                case _ =>
                    bug(s"SqlSchema[LocalDate] cannot read from ${r.getClass}")
    )

    given SqlSchema[java.time.LocalDateTime] = SqlSchema.of[java.time.LocalDateTime](
        write = (v, w) =>
            w match
                case pw: PostgresParamWriter =>
                    val buf = new kyo.internal.postgres.PostgresBufferWriter()
                    kyo.internal.postgres.types.PostgresEncoder.timestampBinary.write(v, buf)
                    pw.custom("timestamp", buf.toSpan, kyo.internal.postgres.types.Format.Binary)
                case mw: MysqlParamWriter =>
                    val buf = new kyo.internal.mysql.MysqlBufferWriter()
                    kyo.internal.mysql.types.MysqlEncoder.localDateTimeEncoder.write(v, buf)
                    mw.custom("datetime", buf.toSpan, kyo.internal.postgres.types.Format.Binary)
                case _ =>
                    bug(s"SqlSchema[LocalDateTime] cannot write to ${w.getClass}")
        ,
        read = r =>
            r match
                case pr: PostgresRowReader =>
                    val bytes = pr.custom("timestamp")
                    kyo.internal.postgres.types.PostgresDecoder.timestamp.read(
                        kyo.internal.postgres.types.Format.Binary,
                        bytes
                    )(using pr.frame)
                case mr: MysqlRowReader =>
                    val bytes = mr.custom("datetime")
                    kyo.internal.mysql.MysqlRowReader.decodeDatetimeBytes(bytes)(using mr.frame)
                case _ =>
                    bug(s"SqlSchema[LocalDateTime] cannot read from ${r.getClass}")
    )

    given SqlSchema[java.time.LocalTime] = wrap(Schema.localTimeSchema)

    /** OffsetTime schema: Postgres uses the native `timetz` binary wire codec (OID 1266), encoding 8 bytes of microseconds-of-day followed
      * by 4 bytes of negated UTC-offset seconds. MySQL has no native timetz type; falls back to ISO-8601 text via OffsetTime.toString /
      * OffsetTime.parse.
      *
      * Design note, offset preservation: unlike `timestamptz`, which normalises to UTC and loses the original offset on round-trip,
      * `timetz` encodes the offset in the wire payload. PostgreSQL preserves the original offset faithfully, so a round-trip returns the
      * exact same OffsetTime (local time + offset).
      */
    given SqlSchema[java.time.OffsetTime] = SqlSchema.of[java.time.OffsetTime](
        write = (v, w) =>
            w match
                case pw: PostgresParamWriter =>
                    val buf = new kyo.internal.postgres.PostgresBufferWriter()
                    kyo.internal.postgres.types.PostgresEncoder.timetzBinary.write(v, buf)
                    pw.custom("timetz", buf.toSpan, kyo.internal.postgres.types.Format.Binary)
                case mw: MysqlParamWriter =>
                    mw.string(v.toString)
                case _ =>
                    bug(s"SqlSchema[OffsetTime] cannot write to ${w.getClass}")
        ,
        read = r =>
            r match
                case pr: PostgresRowReader =>
                    val bytes = pr.custom("timetz")
                    kyo.internal.postgres.types.PostgresDecoder.timetz.read(
                        kyo.internal.postgres.types.Format.Binary,
                        bytes
                    )(using pr.frame)
                case mr: MysqlRowReader =>
                    java.time.OffsetTime.parse(mr.string())
                case _ =>
                    bug(s"SqlSchema[OffsetTime] cannot read from ${r.getClass}")
    )

    /** Period schema: Postgres uses the native INTERVAL binary wire codec (OID 1186) encoding only months and days (microseconds=0); MySQL
      * falls back to ISO-8601 text via Period.toString / Period.parse.
      *
      * Design note, months field: Period.toTotalMonths accounts for both the years and months fields (years * 12 + months). On decode the
      * months field is passed directly to Period.of(0, months, days).normalized() which reconstructs years and months correctly.
      */
    given SqlSchema[java.time.Period] = SqlSchema.of[java.time.Period](
        write = (v, w) =>
            w match
                case pw: PostgresParamWriter =>
                    val buf = new kyo.internal.postgres.PostgresBufferWriter()
                    kyo.internal.postgres.types.PostgresEncoder.intervalPeriodBinary.write(v, buf)
                    pw.custom("interval", buf.toSpan, kyo.internal.postgres.types.Format.Binary)
                case mw: MysqlParamWriter =>
                    mw.string(v.toString)
                case _ =>
                    bug(s"SqlSchema[Period] cannot write to ${w.getClass}")
        ,
        read = r =>
            r match
                case pr: PostgresRowReader =>
                    val bytes = pr.custom("interval")
                    kyo.internal.postgres.types.PostgresDecoder.intervalPeriod.read(
                        kyo.internal.postgres.types.Format.Binary,
                        bytes
                    )(using pr.frame)
                case mr: MysqlRowReader =>
                    java.time.Period.parse(mr.string())
                case _ =>
                    bug(s"SqlSchema[Period] cannot read from ${r.getClass}")
    )

    /** OffsetDateTime schema: encodes via the `timestamptz` / `DATETIME` instant wire path.
      *
      * Design note, offset loss on round-trip: PostgreSQL `timestamptz` (OID 1184) stores the value normalised to UTC; the original
      * UTC-offset carried by the `OffsetDateTime` is NOT persisted on the wire. On decode the instant is reconstructed with
      * `ZoneOffset.UTC`, so a round-trip always yields `+00:00` regardless of the original offset. This is the documented, intentional
      * behaviour: the offset is application-level metadata and must be stored separately if preservation is required.
      *
      * MySQL `DATETIME` likewise has no native offset column; the instant is written as a local-time binary packet without offset, and
      * decoded back as UTC.
      */
    given SqlSchema[java.time.OffsetDateTime] = SqlSchema.of[java.time.OffsetDateTime](
        write = (v, w) => w.instant(v.toInstant),
        read = r =>
            val jInstant = r.instant()
            java.time.OffsetDateTime.ofInstant(jInstant, java.time.ZoneOffset.UTC)
    )

    /** ZonedDateTime schema: encodes via the `timestamptz` / `DATETIME` instant wire path.
      *
      * Design note, zone loss on round-trip: PostgreSQL `timestamptz` (OID 1184) stores the value normalised to UTC; the original IANA
      * zone ID carried by the `ZonedDateTime` is NOT persisted on the wire. On decode the instant is reconstructed using `ZoneOffset.UTC`,
      * so a round-trip always yields a UTC-zoned value regardless of the original zone. This is the documented, intentional behaviour: the
      * zone ID is application-level metadata and must be stored separately if preservation is required.
      *
      * MySQL `DATETIME` likewise has no native zone column; the instant is written as a local-time binary packet without zone, and decoded
      * back as UTC.
      */
    given SqlSchema[java.time.ZonedDateTime] = SqlSchema.of[java.time.ZonedDateTime](
        write = (v, w) => w.instant(v.toInstant),
        read = r =>
            val jInstant = r.instant()
            java.time.ZonedDateTime.ofInstant(jInstant, java.time.ZoneOffset.UTC)
    )

    /** Two-column [[SqlSchema]] for [[java.time.OffsetDateTime]] that preserves the original UTC offset across round-trip.
      *
      * Columns (in declaration order):
      *   1. `instant`, `timestamptz` (PG) / `DATETIME` (MySQL), the value's instant
      *   2. `offset_seconds`, `INTEGER`, `OffsetDateTime#getOffset.getTotalSeconds` (range ±18*3600)
      *
      * Opt-in alternative to the default single-column `given SqlSchema[OffsetDateTime]` (which discards the offset on round-trip per
      * PostgreSQL `timestamptz` semantics). To enable, shadow the given in a local scope:
      * {{{
      *   given SqlSchema[OffsetDateTime] = SqlSchema.offsetDateTimePreserving
      * }}}
      */
    def offsetDateTimePreserving: SqlSchema[java.time.OffsetDateTime] =
        SqlSchema.ofMulti[java.time.OffsetDateTime](
            fieldNames = Seq("instant", "offset_seconds"),
            write = (v, w) =>
                w.instant(v.toInstant)
                w.int(v.getOffset.getTotalSeconds)
            ,
            read = r =>
                val jInstant = r.instant()
                val secs     = r.int()
                java.time.OffsetDateTime.ofInstant(jInstant, java.time.ZoneOffset.ofTotalSeconds(secs))
        )

    /** Two-column [[SqlSchema]] for [[java.time.ZonedDateTime]] that preserves the original IANA zone across round-trip.
      *
      * Columns (in declaration order):
      *   1. `instant`, `timestamptz` (PG) / `DATETIME` (MySQL), the value's instant
      *   2. `zone_id`, `TEXT`, `ZonedDateTime#getZone.getId` (IANA zone ID, e.g. "Europe/Paris")
      *
      * Opt-in alternative to the default single-column `given SqlSchema[ZonedDateTime]` (which discards the zone on round-trip per
      * PostgreSQL `timestamptz` semantics). To enable, shadow the given in a local scope:
      * {{{
      *   given SqlSchema[ZonedDateTime] = SqlSchema.zonedDateTimePreserving
      * }}}
      *
      * Unknown / unparsable zone IDs surface as [[java.time.zone.ZoneRulesException]] on decode.
      */
    def zonedDateTimePreserving: SqlSchema[java.time.ZonedDateTime] =
        SqlSchema.ofMulti[java.time.ZonedDateTime](
            fieldNames = Seq("instant", "zone_id"),
            write = (v, w) =>
                w.instant(v.toInstant)
                w.string(v.getZone.getId)
            ,
            read = r =>
                val jInstant = r.instant()
                val zoneId   = r.string()
                java.time.ZonedDateTime.ofInstant(jInstant, java.time.ZoneId.of(zoneId))
        )

    /** URI schema: encodes via [[java.net.URI#toString]] / decodes via [[java.net.URI#create]].
      *
      * Occupies a single TEXT column on both Postgres and MySQL. Any string that is not a valid URI raises a [[SqlDecodeException]] on
      * round-trip.
      */
    given SqlSchema[java.net.URI] = SqlSchema.of[java.net.URI](
        write = (v, w) => w.string(v.toString),
        read = r => java.net.URI.create(r.string())
    )

    /** URL schema: encodes via [[java.net.URL#toString]] / decodes via [[java.net.URI#create]] followed by `toURL`.
      *
      * Occupies a single TEXT column on both Postgres and MySQL.
      *
      * Design note, DNS resolution caveat: [[java.net.URL#equals]] and [[java.net.URL#hashCode]] may perform DNS resolution. Use `URI`
      * instead of `URL` when that behaviour is undesirable. The string round-trip is always byte-identical regardless of DNS resolution.
      *
      * Design note, deprecation: [[java.net.URL]] (String) constructor is deprecated since JDK 20. Decoding goes via
      * `URI.create(s).toURL()` to avoid the deprecated constructor and to validate the URI syntax first.
      */
    given SqlSchema[java.net.URL] = SqlSchema.of[java.net.URL](
        write = (v, w) => w.string(v.toString),
        read = r => java.net.URI.create(r.string()).toURL()
    )

    /** Locale schema: encodes via [[java.util.Locale#toLanguageTag]] / decodes via [[java.util.Locale#forLanguageTag]].
      *
      * Occupies a single TEXT column on both Postgres and MySQL. The BCP 47 language tag is used (e.g. "en-US", "pt-BR"). Note that
      * [[java.util.Locale#forLanguageTag]] is lenient and will return a non-null Locale for any well-formed tag; malformed tags yield the
      * root locale rather than an error.
      */
    given SqlSchema[java.util.Locale] = SqlSchema.of[java.util.Locale](
        write = (v, w) => w.string(v.toLanguageTag),
        read = r => java.util.Locale.forLanguageTag(r.string())
    )

    /** Currency schema: encodes via [[java.util.Currency#getCurrencyCode]] / decodes via [[java.util.Currency#getInstance]].
      *
      * Occupies a single TEXT column on both Postgres and MySQL. The ISO 4217 currency code is stored (e.g. "USD", "BRL", "JPY"). An
      * invalid code raises [[java.lang.IllegalArgumentException]] on decode, which is surfaced as a [[SqlDecodeException]] failure via the
      * schema's `readPostgres` / `readMysql` helpers.
      */
    given SqlSchema[java.util.Currency] = SqlSchema.of[java.util.Currency](
        write = (v, w) => w.string(v.getCurrencyCode),
        read = r => java.util.Currency.getInstance(r.string())
    )

    /** InetAddress schema: Postgres uses the native inet binary wire codec (OID 869); MySQL falls back to text (dotted-decimal /
      * colon-hex).
      */
    given SqlSchema[java.net.InetAddress] = SqlSchema.of[java.net.InetAddress](
        write = (v, w) =>
            w match
                case pw: PostgresParamWriter =>
                    val buf = new kyo.internal.postgres.PostgresBufferWriter()
                    kyo.internal.postgres.types.PostgresEncoder.inetBinary.write(v, buf)
                    pw.custom("inet", buf.toSpan, kyo.internal.postgres.types.Format.Binary)
                case mw: MysqlParamWriter =>
                    mw.string(v.getHostAddress)
                case _ =>
                    bug(s"SqlSchema[InetAddress] cannot write to ${w.getClass}")
        ,
        read = r =>
            r match
                case pr: PostgresRowReader =>
                    val bytes = pr.custom("inet")
                    kyo.internal.postgres.types.PostgresDecoder.inet.read(
                        kyo.internal.postgres.types.Format.Binary,
                        bytes
                    )(using pr.frame)
                case mr: MysqlRowReader =>
                    java.net.InetAddress.getByName(mr.string())
                case _ =>
                    bug(s"SqlSchema[InetAddress] cannot read from ${r.getClass}")
    )

    /** `Chunk[Int]` schema: Postgres uses the native binary `int4[]` array wire codec (OID 1007); MySQL falls back to a JSON array encoded
      * / decoded via [[kyo.Json]] (which honours quoting, escaping, and DoS limits).
      */
    given chunkIntSchema: SqlSchema[Chunk[Int]] = SqlSchema.of[Chunk[Int]](
        write = (v, w) =>
            w match
                case pw: PostgresParamWriter =>
                    val buf = new kyo.internal.postgres.PostgresBufferWriter()
                    kyo.internal.postgres.types.PostgresEncoder.int4ArrayBinary.write(v, buf)
                    pw.custom("_int4", buf.toSpan, kyo.internal.postgres.types.Format.Binary)
                case mw: MysqlParamWriter =>
                    // MySQL has no native array type; encode via kyo.Json (handles quoting / escaping / numeric formatting).
                    // Pass the plain Schema explicitly so implicit search doesn't resolve back to this SQL-only schema
                    // and re-enter the bug guard inside SqlSchema.of.
                    given kyo.Frame          = mw.frame
                    given Schema[Chunk[Int]] = Schema.chunkSchema(using Schema.intSchema)
                    mw.string(kyo.Json.encode[Chunk[Int]](v))
                case _ =>
                    bug(s"SqlSchema[Chunk[Int]] cannot write to ${w.getClass}")
        ,
        read = r =>
            r match
                case pr: PostgresRowReader =>
                    val bytes = pr.custom("_int4")
                    kyo.internal.postgres.types.PostgresDecoder.int4Array.read(
                        kyo.internal.postgres.types.Format.Binary,
                        bytes
                    )(using pr.frame)
                case mr: MysqlRowReader =>
                    // MySQL stores as JSON array text; decode via kyo.Json.
                    given kyo.Frame          = mr.frame
                    given Schema[Chunk[Int]] = Schema.chunkSchema(using Schema.intSchema)
                    kyo.Json.decode[Chunk[Int]](mr.string()) match
                        case kyo.Result.Success(c) => c
                        case kyo.Result.Failure(e) =>
                            throw SqlDecodeJsonException(e.getMessage.take(100), new Exception(e.getMessage))(using mr.frame)
                        case kyo.Result.Panic(t) => throw t
                    end match
                case _ =>
                    bug(s"SqlSchema[Chunk[Int]] cannot read from ${r.getClass}")
    )

    /** `Chunk[String]` schema: Postgres uses the native binary `text[]` array wire codec (OID 1009); MySQL falls back to a JSON array
      * encoded / decoded via [[kyo.Json]] (handles quoting, escaping, and Unicode correctly).
      */
    given chunkStringSchema: SqlSchema[Chunk[String]] = SqlSchema.of[Chunk[String]](
        write = (v, w) =>
            w match
                case pw: PostgresParamWriter =>
                    val buf = new kyo.internal.postgres.PostgresBufferWriter()
                    kyo.internal.postgres.types.PostgresEncoder.textArrayBinary.write(v, buf)
                    pw.custom("_text", buf.toSpan, kyo.internal.postgres.types.Format.Binary)
                case mw: MysqlParamWriter =>
                    // MySQL has no native array type; encode via kyo.Json (handles quoting / escaping / Unicode).
                    // Pass the plain Schema explicitly so implicit search doesn't resolve back to this SQL-only schema
                    // and re-enter the bug guard inside SqlSchema.of.
                    given kyo.Frame             = mw.frame
                    given Schema[Chunk[String]] = Schema.chunkSchema(using Schema.stringSchema)
                    mw.string(kyo.Json.encode[Chunk[String]](v))
                case _ =>
                    bug(s"SqlSchema[Chunk[String]] cannot write to ${w.getClass}")
        ,
        read = r =>
            r match
                case pr: PostgresRowReader =>
                    val bytes = pr.custom("_text")
                    kyo.internal.postgres.types.PostgresDecoder.textArray.read(
                        kyo.internal.postgres.types.Format.Binary,
                        bytes
                    )(using pr.frame)
                case mr: MysqlRowReader =>
                    // MySQL stores as JSON array text; decode via kyo.Json.
                    given kyo.Frame             = mr.frame
                    given Schema[Chunk[String]] = Schema.chunkSchema(using Schema.stringSchema)
                    kyo.Json.decode[Chunk[String]](mr.string()) match
                        case kyo.Result.Success(c) => c
                        case kyo.Result.Failure(e) =>
                            throw SqlDecodeJsonException(e.getMessage.take(100), new Exception(e.getMessage))(using mr.frame)
                        case kyo.Result.Panic(t) => throw t
                    end match
                case _ =>
                    bug(s"SqlSchema[Chunk[String]] cannot read from ${r.getClass}")
    )

    /** `Structure.Value` schema: Postgres uses the native binary `jsonb` wire codec (OID 3802, version byte 0x01 + UTF-8 text); MySQL uses
      * the native `TYPE_JSON` (0xf5) wire path. On write, [[kyo.Json.encode]] serialises the [[Structure.Value]] to JSON text; on read,
      * [[kyo.Json.decode]] parses the column payload back into a [[Structure.Value]]. `Structure.Value` variants that lack a direct JSON
      * counterpart (`Bytes`, `Instant`, `Duration`, `MapEntries` with non-string keys) inherit the representation chosen by
      * [[kyo.Json]]'s Structure serializer.
      */
    given structureValueSchema: SqlSchema[Structure.Value] = SqlSchema.of[Structure.Value](
        write = (v, w) =>
            w match
                case pw: PostgresParamWriter =>
                    given kyo.Frame = pw.frame
                    val jsonText    = kyo.Json.encode[Structure.Value](v)
                    val buf         = new kyo.internal.postgres.PostgresBufferWriter()
                    kyo.internal.postgres.types.PostgresEncoder.jsonbBinary.write(jsonText, buf)
                    pw.custom("jsonb", buf.toSpan, kyo.internal.postgres.types.Format.Binary)
                case mw: MysqlParamWriter =>
                    given kyo.Frame = mw.frame
                    val jsonText    = kyo.Json.encode[Structure.Value](v)
                    mw.custom(
                        "json",
                        Span.from(jsonText.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        kyo.internal.postgres.types.Format.Binary
                    )
                case _ =>
                    bug(s"SqlSchema[Structure.Value] cannot write to ${w.getClass}")
        ,
        read = r =>
            r match
                case pr: PostgresRowReader =>
                    val bytes = pr.custom("jsonb")
                    val jsonText = kyo.internal.postgres.types.PostgresDecoder.jsonDecoder.read(
                        kyo.internal.postgres.types.Format.Binary,
                        bytes
                    )(using pr.frame)
                    decodeStructureValue(jsonText, pr.frame)
                case mr: MysqlRowReader =>
                    decodeStructureValue(mr.string(), mr.frame)
                case _ =>
                    bug(s"SqlSchema[Structure.Value] cannot read from ${r.getClass}")
    )

    /** `Chunk[Structure.Value]` schema: Postgres uses the native binary `jsonb[]` wire codec (OID 3807) with per-element `jsonb`
      * payloads; MySQL stores the array as a JSON array of documents. Both paths delegate to [[kyo.Json]] for element
      * encoding/decoding.
      */
    given chunkStructureValueSchema: SqlSchema[Chunk[Structure.Value]] = SqlSchema.of[Chunk[Structure.Value]](
        write = (v, w) =>
            w match
                case pw: PostgresParamWriter =>
                    given kyo.Frame  = pw.frame
                    val jsonElements = v.map(kyo.Json.encode[Structure.Value])
                    val buf          = new kyo.internal.postgres.PostgresBufferWriter()
                    kyo.internal.postgres.types.PostgresEncoder.jsonbArrayBinary.write(jsonElements, buf)
                    pw.custom("_jsonb", buf.toSpan, kyo.internal.postgres.types.Format.Binary)
                case mw: MysqlParamWriter =>
                    given kyo.Frame = mw.frame
                    mw.string(kyo.Json.encode[Chunk[Structure.Value]](v))
                case _ =>
                    bug(s"SqlSchema[Chunk[Structure.Value]] cannot write to ${w.getClass}")
        ,
        read = r =>
            r match
                case pr: PostgresRowReader =>
                    val bytes = pr.custom("_jsonb")
                    val elements = kyo.internal.postgres.types.PostgresDecoder.jsonbArray.read(
                        kyo.internal.postgres.types.Format.Binary,
                        bytes
                    )(using pr.frame)
                    elements.map(txt => decodeStructureValue(txt, pr.frame))
                case mr: MysqlRowReader =>
                    given kyo.Frame = mr.frame
                    kyo.Json.decode[Chunk[Structure.Value]](mr.string()) match
                        case kyo.Result.Success(c) => c
                        case kyo.Result.Failure(e) =>
                            throw SqlDecodeJsonException(e.getMessage.take(100), new Exception(e.getMessage))(using mr.frame)
                        case kyo.Result.Panic(t) => throw t
                    end match
                case _ =>
                    bug(s"SqlSchema[Chunk[Structure.Value]] cannot read from ${r.getClass}")
    )

    /** Parses JSON text into a [[Structure.Value]] or throws a positioned [[SqlDecodeException]] on failure. Factored out because both
      * scalar and array reads share the same parse-or-fail path.
      */
    private def decodeStructureValue(jsonText: String, frame: Frame): Structure.Value =
        given Frame = frame
        kyo.Json.decode[Structure.Value](jsonText) match
            case kyo.Result.Success(v) => v
            case kyo.Result.Failure(e) =>
                throw SqlDecodeJsonException(e.getMessage.take(100), new Exception(e.getMessage))
            case kyo.Result.Panic(t) => throw t
        end match
    end decodeStructureValue

    /** UUID schema: Postgres uses the native 16-byte binary UUID wire codec (OID 2950); MySQL falls back to text (36-char canonical). */
    given SqlSchema[java.util.UUID] = SqlSchema.of[java.util.UUID](
        write = (v, w) =>
            w match
                case pw: PostgresParamWriter =>
                    val buf = new kyo.internal.postgres.PostgresBufferWriter()
                    kyo.internal.postgres.types.PostgresEncoder.uuidBinary.write(v, buf)
                    pw.custom("uuid", buf.toSpan, kyo.internal.postgres.types.Format.Binary)
                case mw: MysqlParamWriter =>
                    mw.string(v.toString)
                case _ =>
                    bug(s"SqlSchema[UUID] cannot write to ${w.getClass}")
        ,
        read = r =>
            r match
                case pr: PostgresRowReader =>
                    val bytes = pr.custom("uuid")
                    kyo.internal.postgres.types.PostgresDecoder.uuid.read(
                        kyo.internal.postgres.types.Format.Binary,
                        bytes
                    )(using pr.frame)
                case mr: MysqlRowReader =>
                    java.util.UUID.fromString(mr.string())
                case _ =>
                    bug(s"SqlSchema[UUID] cannot read from ${r.getClass}")
    )

    /** Nullable wrapper: [[Maybe.Absent]] encodes as SQL NULL. */
    given nullable[A](using inner: SqlSchema[A]): SqlSchema[Maybe[A]] =
        // Invoke Schema.maybeSchema on the underlying Schema directly to avoid triggering the ambient `derived` inline given.
        wrap(Schema.maybeSchema(using inner.schema))

    // --- Tuple given instances ---
    // SQL tuples correspond to multi-column positional result sets.
    // Delegate to Schema's tuple schemas and then inject sentinel sourceFields
    // so that fieldCount returns the correct column count.
    //
    // The tuple bodies unwrap each `SqlSchema[X]` context bound to a local `given Schema[X] = summon[SqlSchema[X]].schema`
    // so `Schema.tupleNSchema` / `Schema.derived[TupleN]` can find them. This local unwrap is deliberately kept out of the
    // enclosing implicit scope, a package-level `given [A](using SqlSchema[A]): Schema[A]` would otherwise be an ambient
    // fallback for every `Schema[X]` lookup, forcing every failed summon to detour through the SqlSchema path and derailing
    // unrelated macros (e.g. kyo-test's AssertMacro) that summon `Schema[X]` for local types.

    given tuple2[A: SqlSchema, B: SqlSchema]: SqlSchema[(A, B)] =
        given Schema[A] = summon[SqlSchema[A]].schema
        given Schema[B] = summon[SqlSchema[B]].schema
        withFieldCount(Schema.tuple2Schema, 2)
    end tuple2

    given tuple3[A: SqlSchema, B: SqlSchema, C: SqlSchema]: SqlSchema[(A, B, C)] =
        given Schema[A] = summon[SqlSchema[A]].schema
        given Schema[B] = summon[SqlSchema[B]].schema
        given Schema[C] = summon[SqlSchema[C]].schema
        withFieldCount(Schema.tuple3Schema, 3)
    end tuple3

    given tuple4[A: SqlSchema, B: SqlSchema, C: SqlSchema, D: SqlSchema]: SqlSchema[(A, B, C, D)] =
        given Schema[A] = summon[SqlSchema[A]].schema
        given Schema[B] = summon[SqlSchema[B]].schema
        given Schema[C] = summon[SqlSchema[C]].schema
        given Schema[D] = summon[SqlSchema[D]].schema
        withFieldCount(Schema.tuple4Schema, 4)
    end tuple4

    given tuple5[A: SqlSchema, B: SqlSchema, C: SqlSchema, D: SqlSchema, E: SqlSchema]: SqlSchema[(A, B, C, D, E)] =
        given Schema[A] = summon[SqlSchema[A]].schema
        given Schema[B] = summon[SqlSchema[B]].schema
        given Schema[C] = summon[SqlSchema[C]].schema
        given Schema[D] = summon[SqlSchema[D]].schema
        given Schema[E] = summon[SqlSchema[E]].schema
        withFieldCount(Schema.tuple5Schema, 5)
    end tuple5

    given tuple6[A: SqlSchema, B: SqlSchema, C: SqlSchema, D: SqlSchema, E: SqlSchema, F: SqlSchema]
        : SqlSchema[(A, B, C, D, E, F)] =
        given Schema[A] = summon[SqlSchema[A]].schema
        given Schema[B] = summon[SqlSchema[B]].schema
        given Schema[C] = summon[SqlSchema[C]].schema
        given Schema[D] = summon[SqlSchema[D]].schema
        given Schema[E] = summon[SqlSchema[E]].schema
        given Schema[F] = summon[SqlSchema[F]].schema
        withFieldCount(Schema.derived[(A, B, C, D, E, F)], 6)
    end tuple6

    given tuple7[A: SqlSchema, B: SqlSchema, C: SqlSchema, D: SqlSchema, E: SqlSchema, F: SqlSchema, G: SqlSchema]
        : SqlSchema[(A, B, C, D, E, F, G)] =
        given Schema[A] = summon[SqlSchema[A]].schema
        given Schema[B] = summon[SqlSchema[B]].schema
        given Schema[C] = summon[SqlSchema[C]].schema
        given Schema[D] = summon[SqlSchema[D]].schema
        given Schema[E] = summon[SqlSchema[E]].schema
        given Schema[F] = summon[SqlSchema[F]].schema
        given Schema[G] = summon[SqlSchema[G]].schema
        withFieldCount(Schema.derived[(A, B, C, D, E, F, G)], 7)
    end tuple7

    given tuple8[
        A: SqlSchema,
        B: SqlSchema,
        C: SqlSchema,
        D: SqlSchema,
        E: SqlSchema,
        F: SqlSchema,
        G: SqlSchema,
        H: SqlSchema
    ]: SqlSchema[(A, B, C, D, E, F, G, H)] =
        given Schema[A] = summon[SqlSchema[A]].schema
        given Schema[B] = summon[SqlSchema[B]].schema
        given Schema[C] = summon[SqlSchema[C]].schema
        given Schema[D] = summon[SqlSchema[D]].schema
        given Schema[E] = summon[SqlSchema[E]].schema
        given Schema[F] = summon[SqlSchema[F]].schema
        given Schema[G] = summon[SqlSchema[G]].schema
        given Schema[H] = summon[SqlSchema[H]].schema
        withFieldCount(Schema.derived[(A, B, C, D, E, F, G, H)], 8)
    end tuple8

    given tuple9[
        A: SqlSchema,
        B: SqlSchema,
        C: SqlSchema,
        D: SqlSchema,
        E: SqlSchema,
        F: SqlSchema,
        G: SqlSchema,
        H: SqlSchema,
        I: SqlSchema
    ]: SqlSchema[(A, B, C, D, E, F, G, H, I)] =
        given Schema[A] = summon[SqlSchema[A]].schema
        given Schema[B] = summon[SqlSchema[B]].schema
        given Schema[C] = summon[SqlSchema[C]].schema
        given Schema[D] = summon[SqlSchema[D]].schema
        given Schema[E] = summon[SqlSchema[E]].schema
        given Schema[F] = summon[SqlSchema[F]].schema
        given Schema[G] = summon[SqlSchema[G]].schema
        given Schema[H] = summon[SqlSchema[H]].schema
        given Schema[I] = summon[SqlSchema[I]].schema
        withFieldCount(Schema.derived[(A, B, C, D, E, F, G, H, I)], 9)
    end tuple9

    given tuple10[
        A: SqlSchema,
        B: SqlSchema,
        C: SqlSchema,
        D: SqlSchema,
        E: SqlSchema,
        F: SqlSchema,
        G: SqlSchema,
        H: SqlSchema,
        I: SqlSchema,
        J: SqlSchema
    ]: SqlSchema[(A, B, C, D, E, F, G, H, I, J)] =
        given Schema[A] = summon[SqlSchema[A]].schema
        given Schema[B] = summon[SqlSchema[B]].schema
        given Schema[C] = summon[SqlSchema[C]].schema
        given Schema[D] = summon[SqlSchema[D]].schema
        given Schema[E] = summon[SqlSchema[E]].schema
        given Schema[F] = summon[SqlSchema[F]].schema
        given Schema[G] = summon[SqlSchema[G]].schema
        given Schema[H] = summon[SqlSchema[H]].schema
        given Schema[I] = summon[SqlSchema[I]].schema
        given Schema[J] = summon[SqlSchema[J]].schema
        withFieldCount(Schema.derived[(A, B, C, D, E, F, G, H, I, J)], 10)
    end tuple10

    given tuple11[
        A: SqlSchema,
        B: SqlSchema,
        C: SqlSchema,
        D: SqlSchema,
        E: SqlSchema,
        F: SqlSchema,
        G: SqlSchema,
        H: SqlSchema,
        I: SqlSchema,
        J: SqlSchema,
        K: SqlSchema
    ]: SqlSchema[(A, B, C, D, E, F, G, H, I, J, K)] =
        given Schema[A] = summon[SqlSchema[A]].schema
        given Schema[B] = summon[SqlSchema[B]].schema
        given Schema[C] = summon[SqlSchema[C]].schema
        given Schema[D] = summon[SqlSchema[D]].schema
        given Schema[E] = summon[SqlSchema[E]].schema
        given Schema[F] = summon[SqlSchema[F]].schema
        given Schema[G] = summon[SqlSchema[G]].schema
        given Schema[H] = summon[SqlSchema[H]].schema
        given Schema[I] = summon[SqlSchema[I]].schema
        given Schema[J] = summon[SqlSchema[J]].schema
        given Schema[K] = summon[SqlSchema[K]].schema
        withFieldCount(Schema.derived[(A, B, C, D, E, F, G, H, I, J, K)], 11)
    end tuple11

    given tuple12[
        A: SqlSchema,
        B: SqlSchema,
        C: SqlSchema,
        D: SqlSchema,
        E: SqlSchema,
        F: SqlSchema,
        G: SqlSchema,
        H: SqlSchema,
        I: SqlSchema,
        J: SqlSchema,
        K: SqlSchema,
        L: SqlSchema
    ]: SqlSchema[(A, B, C, D, E, F, G, H, I, J, K, L)] =
        given Schema[A] = summon[SqlSchema[A]].schema
        given Schema[B] = summon[SqlSchema[B]].schema
        given Schema[C] = summon[SqlSchema[C]].schema
        given Schema[D] = summon[SqlSchema[D]].schema
        given Schema[E] = summon[SqlSchema[E]].schema
        given Schema[F] = summon[SqlSchema[F]].schema
        given Schema[G] = summon[SqlSchema[G]].schema
        given Schema[H] = summon[SqlSchema[H]].schema
        given Schema[I] = summon[SqlSchema[I]].schema
        given Schema[J] = summon[SqlSchema[J]].schema
        given Schema[K] = summon[SqlSchema[K]].schema
        given Schema[L] = summon[SqlSchema[L]].schema
        withFieldCount(Schema.derived[(A, B, C, D, E, F, G, H, I, J, K, L)], 12)
    end tuple12

    given tuple13[
        A: SqlSchema,
        B: SqlSchema,
        C: SqlSchema,
        D: SqlSchema,
        E: SqlSchema,
        F: SqlSchema,
        G: SqlSchema,
        H: SqlSchema,
        I: SqlSchema,
        J: SqlSchema,
        K: SqlSchema,
        L: SqlSchema,
        M: SqlSchema
    ]: SqlSchema[(A, B, C, D, E, F, G, H, I, J, K, L, M)] =
        given Schema[A] = summon[SqlSchema[A]].schema
        given Schema[B] = summon[SqlSchema[B]].schema
        given Schema[C] = summon[SqlSchema[C]].schema
        given Schema[D] = summon[SqlSchema[D]].schema
        given Schema[E] = summon[SqlSchema[E]].schema
        given Schema[F] = summon[SqlSchema[F]].schema
        given Schema[G] = summon[SqlSchema[G]].schema
        given Schema[H] = summon[SqlSchema[H]].schema
        given Schema[I] = summon[SqlSchema[I]].schema
        given Schema[J] = summon[SqlSchema[J]].schema
        given Schema[K] = summon[SqlSchema[K]].schema
        given Schema[L] = summon[SqlSchema[L]].schema
        given Schema[M] = summon[SqlSchema[M]].schema
        withFieldCount(Schema.derived[(A, B, C, D, E, F, G, H, I, J, K, L, M)], 13)
    end tuple13

    given tuple14[
        A: SqlSchema,
        B: SqlSchema,
        C: SqlSchema,
        D: SqlSchema,
        E: SqlSchema,
        F: SqlSchema,
        G: SqlSchema,
        H: SqlSchema,
        I: SqlSchema,
        J: SqlSchema,
        K: SqlSchema,
        L: SqlSchema,
        M: SqlSchema,
        N: SqlSchema
    ]: SqlSchema[(A, B, C, D, E, F, G, H, I, J, K, L, M, N)] =
        given Schema[A] = summon[SqlSchema[A]].schema
        given Schema[B] = summon[SqlSchema[B]].schema
        given Schema[C] = summon[SqlSchema[C]].schema
        given Schema[D] = summon[SqlSchema[D]].schema
        given Schema[E] = summon[SqlSchema[E]].schema
        given Schema[F] = summon[SqlSchema[F]].schema
        given Schema[G] = summon[SqlSchema[G]].schema
        given Schema[H] = summon[SqlSchema[H]].schema
        given Schema[I] = summon[SqlSchema[I]].schema
        given Schema[J] = summon[SqlSchema[J]].schema
        given Schema[K] = summon[SqlSchema[K]].schema
        given Schema[L] = summon[SqlSchema[L]].schema
        given Schema[M] = summon[SqlSchema[M]].schema
        given Schema[N] = summon[SqlSchema[N]].schema
        withFieldCount(Schema.derived[(A, B, C, D, E, F, G, H, I, J, K, L, M, N)], 14)
    end tuple14

    given tuple15[
        A: SqlSchema,
        B: SqlSchema,
        C: SqlSchema,
        D: SqlSchema,
        E: SqlSchema,
        F: SqlSchema,
        G: SqlSchema,
        H: SqlSchema,
        I: SqlSchema,
        J: SqlSchema,
        K: SqlSchema,
        L: SqlSchema,
        M: SqlSchema,
        N: SqlSchema,
        O: SqlSchema
    ]: SqlSchema[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O)] =
        given Schema[A] = summon[SqlSchema[A]].schema
        given Schema[B] = summon[SqlSchema[B]].schema
        given Schema[C] = summon[SqlSchema[C]].schema
        given Schema[D] = summon[SqlSchema[D]].schema
        given Schema[E] = summon[SqlSchema[E]].schema
        given Schema[F] = summon[SqlSchema[F]].schema
        given Schema[G] = summon[SqlSchema[G]].schema
        given Schema[H] = summon[SqlSchema[H]].schema
        given Schema[I] = summon[SqlSchema[I]].schema
        given Schema[J] = summon[SqlSchema[J]].schema
        given Schema[K] = summon[SqlSchema[K]].schema
        given Schema[L] = summon[SqlSchema[L]].schema
        given Schema[M] = summon[SqlSchema[M]].schema
        given Schema[N] = summon[SqlSchema[N]].schema
        given Schema[O] = summon[SqlSchema[O]].schema
        withFieldCount(Schema.derived[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O)], 15)
    end tuple15

    given tuple16[
        A: SqlSchema,
        B: SqlSchema,
        C: SqlSchema,
        D: SqlSchema,
        E: SqlSchema,
        F: SqlSchema,
        G: SqlSchema,
        H: SqlSchema,
        I: SqlSchema,
        J: SqlSchema,
        K: SqlSchema,
        L: SqlSchema,
        M: SqlSchema,
        N: SqlSchema,
        O: SqlSchema,
        P: SqlSchema
    ]: SqlSchema[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P)] =
        given Schema[A] = summon[SqlSchema[A]].schema
        given Schema[B] = summon[SqlSchema[B]].schema
        given Schema[C] = summon[SqlSchema[C]].schema
        given Schema[D] = summon[SqlSchema[D]].schema
        given Schema[E] = summon[SqlSchema[E]].schema
        given Schema[F] = summon[SqlSchema[F]].schema
        given Schema[G] = summon[SqlSchema[G]].schema
        given Schema[H] = summon[SqlSchema[H]].schema
        given Schema[I] = summon[SqlSchema[I]].schema
        given Schema[J] = summon[SqlSchema[J]].schema
        given Schema[K] = summon[SqlSchema[K]].schema
        given Schema[L] = summon[SqlSchema[L]].schema
        given Schema[M] = summon[SqlSchema[M]].schema
        given Schema[N] = summon[SqlSchema[N]].schema
        given Schema[O] = summon[SqlSchema[O]].schema
        given Schema[P] = summon[SqlSchema[P]].schema
        withFieldCount(Schema.derived[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P)], 16)
    end tuple16

    given tuple17[
        A: SqlSchema,
        B: SqlSchema,
        C: SqlSchema,
        D: SqlSchema,
        E: SqlSchema,
        F: SqlSchema,
        G: SqlSchema,
        H: SqlSchema,
        I: SqlSchema,
        J: SqlSchema,
        K: SqlSchema,
        L: SqlSchema,
        M: SqlSchema,
        N: SqlSchema,
        O: SqlSchema,
        P: SqlSchema,
        Q: SqlSchema
    ]: SqlSchema[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q)] =
        given Schema[A] = summon[SqlSchema[A]].schema
        given Schema[B] = summon[SqlSchema[B]].schema
        given Schema[C] = summon[SqlSchema[C]].schema
        given Schema[D] = summon[SqlSchema[D]].schema
        given Schema[E] = summon[SqlSchema[E]].schema
        given Schema[F] = summon[SqlSchema[F]].schema
        given Schema[G] = summon[SqlSchema[G]].schema
        given Schema[H] = summon[SqlSchema[H]].schema
        given Schema[I] = summon[SqlSchema[I]].schema
        given Schema[J] = summon[SqlSchema[J]].schema
        given Schema[K] = summon[SqlSchema[K]].schema
        given Schema[L] = summon[SqlSchema[L]].schema
        given Schema[M] = summon[SqlSchema[M]].schema
        given Schema[N] = summon[SqlSchema[N]].schema
        given Schema[O] = summon[SqlSchema[O]].schema
        given Schema[P] = summon[SqlSchema[P]].schema
        given Schema[Q] = summon[SqlSchema[Q]].schema
        withFieldCount(Schema.derived[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q)], 17)
    end tuple17

    given tuple18[
        A: SqlSchema,
        B: SqlSchema,
        C: SqlSchema,
        D: SqlSchema,
        E: SqlSchema,
        F: SqlSchema,
        G: SqlSchema,
        H: SqlSchema,
        I: SqlSchema,
        J: SqlSchema,
        K: SqlSchema,
        L: SqlSchema,
        M: SqlSchema,
        N: SqlSchema,
        O: SqlSchema,
        P: SqlSchema,
        Q: SqlSchema,
        R: SqlSchema
    ]: SqlSchema[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R)] =
        given Schema[A] = summon[SqlSchema[A]].schema
        given Schema[B] = summon[SqlSchema[B]].schema
        given Schema[C] = summon[SqlSchema[C]].schema
        given Schema[D] = summon[SqlSchema[D]].schema
        given Schema[E] = summon[SqlSchema[E]].schema
        given Schema[F] = summon[SqlSchema[F]].schema
        given Schema[G] = summon[SqlSchema[G]].schema
        given Schema[H] = summon[SqlSchema[H]].schema
        given Schema[I] = summon[SqlSchema[I]].schema
        given Schema[J] = summon[SqlSchema[J]].schema
        given Schema[K] = summon[SqlSchema[K]].schema
        given Schema[L] = summon[SqlSchema[L]].schema
        given Schema[M] = summon[SqlSchema[M]].schema
        given Schema[N] = summon[SqlSchema[N]].schema
        given Schema[O] = summon[SqlSchema[O]].schema
        given Schema[P] = summon[SqlSchema[P]].schema
        given Schema[Q] = summon[SqlSchema[Q]].schema
        given Schema[R] = summon[SqlSchema[R]].schema
        withFieldCount(Schema.derived[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R)], 18)
    end tuple18

    given tuple19[
        A: SqlSchema,
        B: SqlSchema,
        C: SqlSchema,
        D: SqlSchema,
        E: SqlSchema,
        F: SqlSchema,
        G: SqlSchema,
        H: SqlSchema,
        I: SqlSchema,
        J: SqlSchema,
        K: SqlSchema,
        L: SqlSchema,
        M: SqlSchema,
        N: SqlSchema,
        O: SqlSchema,
        P: SqlSchema,
        Q: SqlSchema,
        R: SqlSchema,
        S: SqlSchema
    ]: SqlSchema[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S)] =
        given Schema[A] = summon[SqlSchema[A]].schema
        given Schema[B] = summon[SqlSchema[B]].schema
        given Schema[C] = summon[SqlSchema[C]].schema
        given Schema[D] = summon[SqlSchema[D]].schema
        given Schema[E] = summon[SqlSchema[E]].schema
        given Schema[F] = summon[SqlSchema[F]].schema
        given Schema[G] = summon[SqlSchema[G]].schema
        given Schema[H] = summon[SqlSchema[H]].schema
        given Schema[I] = summon[SqlSchema[I]].schema
        given Schema[J] = summon[SqlSchema[J]].schema
        given Schema[K] = summon[SqlSchema[K]].schema
        given Schema[L] = summon[SqlSchema[L]].schema
        given Schema[M] = summon[SqlSchema[M]].schema
        given Schema[N] = summon[SqlSchema[N]].schema
        given Schema[O] = summon[SqlSchema[O]].schema
        given Schema[P] = summon[SqlSchema[P]].schema
        given Schema[Q] = summon[SqlSchema[Q]].schema
        given Schema[R] = summon[SqlSchema[R]].schema
        given Schema[S] = summon[SqlSchema[S]].schema
        withFieldCount(Schema.derived[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S)], 19)
    end tuple19

    given tuple20[
        A: SqlSchema,
        B: SqlSchema,
        C: SqlSchema,
        D: SqlSchema,
        E: SqlSchema,
        F: SqlSchema,
        G: SqlSchema,
        H: SqlSchema,
        I: SqlSchema,
        J: SqlSchema,
        K: SqlSchema,
        L: SqlSchema,
        M: SqlSchema,
        N: SqlSchema,
        O: SqlSchema,
        P: SqlSchema,
        Q: SqlSchema,
        R: SqlSchema,
        S: SqlSchema,
        T: SqlSchema
    ]: SqlSchema[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T)] =
        given Schema[A] = summon[SqlSchema[A]].schema
        given Schema[B] = summon[SqlSchema[B]].schema
        given Schema[C] = summon[SqlSchema[C]].schema
        given Schema[D] = summon[SqlSchema[D]].schema
        given Schema[E] = summon[SqlSchema[E]].schema
        given Schema[F] = summon[SqlSchema[F]].schema
        given Schema[G] = summon[SqlSchema[G]].schema
        given Schema[H] = summon[SqlSchema[H]].schema
        given Schema[I] = summon[SqlSchema[I]].schema
        given Schema[J] = summon[SqlSchema[J]].schema
        given Schema[K] = summon[SqlSchema[K]].schema
        given Schema[L] = summon[SqlSchema[L]].schema
        given Schema[M] = summon[SqlSchema[M]].schema
        given Schema[N] = summon[SqlSchema[N]].schema
        given Schema[O] = summon[SqlSchema[O]].schema
        given Schema[P] = summon[SqlSchema[P]].schema
        given Schema[Q] = summon[SqlSchema[Q]].schema
        given Schema[R] = summon[SqlSchema[R]].schema
        given Schema[S] = summon[SqlSchema[S]].schema
        given Schema[T] = summon[SqlSchema[T]].schema
        withFieldCount(Schema.derived[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T)], 20)
    end tuple20

    given tuple21[
        A: SqlSchema,
        B: SqlSchema,
        C: SqlSchema,
        D: SqlSchema,
        E: SqlSchema,
        F: SqlSchema,
        G: SqlSchema,
        H: SqlSchema,
        I: SqlSchema,
        J: SqlSchema,
        K: SqlSchema,
        L: SqlSchema,
        M: SqlSchema,
        N: SqlSchema,
        O: SqlSchema,
        P: SqlSchema,
        Q: SqlSchema,
        R: SqlSchema,
        S: SqlSchema,
        T: SqlSchema,
        U: SqlSchema
    ]: SqlSchema[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U)] =
        given Schema[A] = summon[SqlSchema[A]].schema
        given Schema[B] = summon[SqlSchema[B]].schema
        given Schema[C] = summon[SqlSchema[C]].schema
        given Schema[D] = summon[SqlSchema[D]].schema
        given Schema[E] = summon[SqlSchema[E]].schema
        given Schema[F] = summon[SqlSchema[F]].schema
        given Schema[G] = summon[SqlSchema[G]].schema
        given Schema[H] = summon[SqlSchema[H]].schema
        given Schema[I] = summon[SqlSchema[I]].schema
        given Schema[J] = summon[SqlSchema[J]].schema
        given Schema[K] = summon[SqlSchema[K]].schema
        given Schema[L] = summon[SqlSchema[L]].schema
        given Schema[M] = summon[SqlSchema[M]].schema
        given Schema[N] = summon[SqlSchema[N]].schema
        given Schema[O] = summon[SqlSchema[O]].schema
        given Schema[P] = summon[SqlSchema[P]].schema
        given Schema[Q] = summon[SqlSchema[Q]].schema
        given Schema[R] = summon[SqlSchema[R]].schema
        given Schema[S] = summon[SqlSchema[S]].schema
        given Schema[T] = summon[SqlSchema[T]].schema
        given Schema[U] = summon[SqlSchema[U]].schema
        withFieldCount(Schema.derived[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U)], 21)
    end tuple21

    given tuple22[
        A: SqlSchema,
        B: SqlSchema,
        C: SqlSchema,
        D: SqlSchema,
        E: SqlSchema,
        F: SqlSchema,
        G: SqlSchema,
        H: SqlSchema,
        I: SqlSchema,
        J: SqlSchema,
        K: SqlSchema,
        L: SqlSchema,
        M: SqlSchema,
        N: SqlSchema,
        O: SqlSchema,
        P: SqlSchema,
        Q: SqlSchema,
        R: SqlSchema,
        S: SqlSchema,
        T: SqlSchema,
        U: SqlSchema,
        V: SqlSchema
    ]: SqlSchema[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V)] =
        given Schema[A] = summon[SqlSchema[A]].schema
        given Schema[B] = summon[SqlSchema[B]].schema
        given Schema[C] = summon[SqlSchema[C]].schema
        given Schema[D] = summon[SqlSchema[D]].schema
        given Schema[E] = summon[SqlSchema[E]].schema
        given Schema[F] = summon[SqlSchema[F]].schema
        given Schema[G] = summon[SqlSchema[G]].schema
        given Schema[H] = summon[SqlSchema[H]].schema
        given Schema[I] = summon[SqlSchema[I]].schema
        given Schema[J] = summon[SqlSchema[J]].schema
        given Schema[K] = summon[SqlSchema[K]].schema
        given Schema[L] = summon[SqlSchema[L]].schema
        given Schema[M] = summon[SqlSchema[M]].schema
        given Schema[N] = summon[SqlSchema[N]].schema
        given Schema[O] = summon[SqlSchema[O]].schema
        given Schema[P] = summon[SqlSchema[P]].schema
        given Schema[Q] = summon[SqlSchema[Q]].schema
        given Schema[R] = summon[SqlSchema[R]].schema
        given Schema[S] = summon[SqlSchema[S]].schema
        given Schema[T] = summon[SqlSchema[T]].schema
        given Schema[U] = summon[SqlSchema[U]].schema
        given Schema[V] = summon[SqlSchema[V]].schema
        withFieldCount(Schema.derived[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V)], 22)
    end tuple22

    /** Wraps a Schema and sets the number of source fields (used for fieldCount). */
    private def withFieldCount[A](base: Schema[A], n: Int): SqlSchema[A] =
        val sentinels = (0 until n).map(i => Field[String, Any](s"_${i + 1}", Tag[Any])).toSeq
        wrap(Schema.init[A](
            writeFn = base.serializeWrite(_, _),
            readFn = base.serializeRead(_),
            getterFn = _ => Maybe.empty,
            setterFn = (a, _) => a,
            sourceFields = sentinels
        ))
    end withFieldCount

    // --- Extension methods ---

    extension [A](self: SqlSchema[A])

        /** Number of SQL columns this schema occupies.
          *
          * For derived case classes: the count is read from `sourceFields`, which is populated by [[derived]] using Mirror element labels.
          * For primitives and [[of]] scalars: `sourceFields` is empty (Schema.init default), so returns 1.
          */
        def fieldCount: Int =
            val n = self.schema.sourceFields.size
            if n == 0 then 1 else n

        /** Field names in declaration order.
          *
          * Returns an empty [[Chunk]] for primitives (which have no `sourceFields`). For derived case classes, returns the field names in
          * the order declared in the case class constructor.
          */
        def fieldNames: Chunk[String] =
            Chunk.from(self.schema.sourceFields.map(f => f.name: String))

        /** Encodes `value` as a sequence of Postgres bind parameters.
          *
          * One [[BoundParam]] per scalar field. Structural framing methods in [[PostgresParamWriter]] are no-ops, so case class fields
          * flatten correctly into a positional parameter list.
          */
        def writePostgres(value: A)(using Frame): Chunk[BoundParam[?]] =
            val w = PostgresParamWriter(TypeRegistry.empty)
            self.schema.serializeWrite(value, w)
            w.params
        end writePostgres

        /** Encodes `value` as a sequence of MySQL bind parameters.
          *
          * One [[BoundMysqlParam]] per scalar field. Structural framing methods in [[MysqlParamWriter]] are no-ops, so case class fields
          * flatten correctly into a positional parameter list.
          */
        def writeMysql(value: A)(using Frame): Chunk[BoundMysqlParam[?]] =
            val w = MysqlParamWriter(Map.empty)
            self.schema.serializeWrite(value, w)
            w.params
        end writeMysql

        /** SQL-canonical type name for the underlying primitive schema. Returns "TEXT" for any schema that is not one of the documented
          * primitive `given val`s in `Schema` companion. Used by `Cast` rendering to produce stable `CAST(... AS <TYPE>)` SQL.
          */
        def sqlTypeName: String =
            val s = self.schema
            if s eq Schema.intSchema then "INTEGER"
            else if s eq Schema.longSchema then "BIGINT"
            else if s eq Schema.shortSchema then "SMALLINT"
            else if s eq Schema.byteSchema then "TINYINT"
            else if s eq Schema.stringSchema then "TEXT"
            else if s eq Schema.booleanSchema then "BOOLEAN"
            else if s eq Schema.floatSchema then "REAL"
            else if s eq Schema.doubleSchema then "DOUBLE PRECISION"
            else if s eq Schema.bigDecimalSchema then "DECIMAL"
            else "TEXT"
            end if
        end sqlTypeName

        /** Reference-equality check on the underlying schema instance. Used by tests that verify a re-summoned schema is the same `given`
          * instance as the original. Compares the wrapping [[State]] identities.
          */
        private[kyo] def eqRef[B](other: SqlSchema[B]): Boolean = self eq other

        /** Decodes `row` into an `A` using the Postgres binary column format.
          *
          * Columns are consumed positionally starting at index 0. Column names in the [[SqlRow]] must match the field names of `A` for case
          * class decoding (Schema uses field-name matching internally). On decode failure, the thrown exception is caught via
          * `Result.catching[Exception]` and surfaced as `Abort[SqlDecodeException]`.
          */
        def readPostgres(row: SqlRow)(using Frame): A < Abort[SqlDecodeException] =
            val reader = PostgresRowReader(row)
            // serializeRead throws a JVM exception directly on failure, use Result.catching,
            // not Abort.run, to intercept the throw before converting to Abort.
            (Result.catching[Throwable](self.schema.serializeRead(reader)): Result[Throwable, A]) match
                case Result.Success(a) => a
                case Result.Failure(e) =>
                    e match
                        case decode: SqlDecodeException => Abort.fail(decode)
                        case other                      => Abort.fail(SqlDecodeColumnDecodeException(-1, other))
                case Result.Panic(t) =>
                    java.lang.System.err.println(
                        s"[kyo-sql] readPostgres: unexpected decode panic: ${t.getMessage}"
                    )
                    t match
                        case decode: SqlDecodeException => Abort.fail(decode)
                        case other                      => Abort.fail(SqlDecodeColumnDecodeException(-1, other))
            end match
        end readPostgres

        /** Decodes `row` into an `A` using the MySQL binary column format.
          *
          * Columns are consumed positionally starting at index 0. On decode failure, the thrown exception is caught via
          * `Result.catching[Throwable]` and surfaced as `Abort[SqlDecodeException]`.
          */
        def readMysql(row: SqlRow)(using Frame): A < Abort[SqlDecodeException] =
            val reader = MysqlRowReader(row)
            // serializeRead throws a JVM exception directly on failure, use Result.catching,
            // not Abort.run, to intercept the throw before converting to Abort.
            (Result.catching[Throwable](self.schema.serializeRead(reader)): Result[Throwable, A]) match
                case Result.Success(a) => a
                case Result.Failure(e) =>
                    e match
                        case decode: SqlDecodeException => Abort.fail(decode)
                        case other                      => Abort.fail(SqlDecodeColumnDecodeException(-1, other))
                case Result.Panic(t) =>
                    java.lang.System.err.println(
                        s"[kyo-sql] readMysql: unexpected decode panic: ${t.getMessage}"
                    )
                    t match
                        case decode: SqlDecodeException => Abort.fail(decode)
                        case other                      => Abort.fail(SqlDecodeColumnDecodeException(-1, other))
            end match
        end readMysql

        /** Returns a new [[SqlSchema]] with the given [[SqlSchema.Naming]] attached.
          *
          * The strategy is stored on the [[SqlSchema.State]] wrapper. The static-SQL macros read it via [[SqlSchema.readNaming]] to
          * convert Scala field names to SQL column names at expansion time. Transforms compose: calling `.withNaming` after
          * `.withTableName` preserves the table-name override and vice versa.
          *
          * @param strategy
          *   the naming convention to attach (e.g. `SqlSchema.Naming.snakeCase`)
          */
        transparent inline def withNaming(strategy: SqlSchema.Naming): SqlSchema[A] =
            SqlSchema.applyNaming(self, strategy)

        /** Returns a new [[SqlSchema]] with the given SQL table name attached.
          *
          * The name is stored on the [[SqlSchema.State]] wrapper. The static-SQL macros read it via [[SqlSchema.readTableNameOverride]] and
          * emit it as the table name literal rather than deriving from the Scala type name. Transforms compose: calling `.withTableName`
          * after `.withNaming` preserves the naming strategy and vice versa.
          *
          * @param name
          *   the SQL table name to use (e.g. `"countries"`)
          */
        transparent inline def withTableName(inline name: String): SqlSchema[A] =
            SqlSchema.applyTableNameOverride(self, name)

        /** Returns a new [[SqlSchema]] with a single field renamed for SQL output.
          *
          * Appends `(from, to)` to the schema's `renamedFields` list on the [[SqlSchema.State]] wrapper. The rename is consulted at
          * `Column`-construction time by `resolveSqlName` (via [[kyo.internal.SqlNameResolver.columnName]]): the Scala field named `from`
          * will be emitted in rendered SQL as `to`. Composable: calling `.rename` multiple times accumulates all pairs in order.
          *
          * @param from
          *   the Scala field name to match
          * @param to
          *   the SQL column name to emit in its place
          */
        transparent inline def rename(inline from: String, inline to: String): SqlSchema[A] =
            SqlSchema.applyRenamedField(self, from, to)

        /** Reads the [[SqlSchema.Naming]] attached to this schema. Returns [[Maybe.Absent]] when no strategy has been set. */
        private[kyo] def namingStrategy: Maybe[SqlSchema.Naming] =
            SqlSchema.readNaming(self)

        /** Reads the SQL table name override attached to this schema. Returns [[Maybe.Absent]] when no override has been set. */
        private[kyo] def tableNameOverride: Maybe[String] =
            SqlSchema.readTableNameOverride(self)
    end extension

    given fromExprSqlSchema[A: scala.quoted.Type](using scala.quoted.Quotes): scala.quoted.FromExpr[SqlSchema[A]] =
        new scala.quoted.FromExpr[SqlSchema[A]]:
            def unapply(x: scala.quoted.Expr[SqlSchema[A]])(using qctx: scala.quoted.Quotes): Option[SqlSchema[A]] =
                // Resolve the schema from the supplied expression `x`, it is the actual `SqlSchema[…]`
                // given reference at the use site. Re-summoning `SqlSchema[A]` fails when `A` was
                // saturated to `Any` by the recursion guard (a `Literal[Any]` field reached via the
                // `Term` sum), so resolve `x` directly via JVM reflection on its stable given symbol.
                kyo.internal.FromExprDerived.resolveStableGiven[SqlSchema[A]](x)

    // --- ToExpr instances for the SQL bind-value types ---
    //
    // The static-SQL macro lifts each rendered bind value back to an `Expr`. The Scala stdlib
    // provides `ToExpr` for the primitive types (Int/Long/String/Boolean/Double/Float/Short/Byte/
    // BigDecimal); these five cover the remaining SQL value types. Co-located with the corresponding
    // `SqlSchema[…]` givens so the FromExpr + ToExpr roundtrip stays in one place.

    import scala.quoted.Expr
    import scala.quoted.Quotes
    import scala.quoted.ToExpr

    given toExprSpanByte(using Quotes): ToExpr[kyo.Span[Byte]] =
        new ToExpr[kyo.Span[Byte]]:
            def apply(sp: kyo.Span[Byte])(using Quotes): Expr[kyo.Span[Byte]] =
                val bytes: Expr[Array[Byte]] = Expr(sp.toArray)
                '{ kyo.Span.from($bytes) }

    given toExprKyoInstant(using Quotes): ToExpr[kyo.Instant] =
        new ToExpr[kyo.Instant]:
            def apply(i: kyo.Instant)(using Quotes): Expr[kyo.Instant] =
                val ms: Expr[Long] = Expr(i.toJava.toEpochMilli)
                '{ kyo.Instant.fromJava(java.time.Instant.ofEpochMilli($ms)) }

    given toExprLocalDate(using Quotes): ToExpr[java.time.LocalDate] =
        new ToExpr[java.time.LocalDate]:
            def apply(d: java.time.LocalDate)(using Quotes): Expr[java.time.LocalDate] =
                val y   = Expr(d.getYear)
                val m   = Expr(d.getMonthValue)
                val day = Expr(d.getDayOfMonth)
                '{ java.time.LocalDate.of($y, $m, $day) }
            end apply

    given toExprLocalDateTime(using Quotes): ToExpr[java.time.LocalDateTime] =
        new ToExpr[java.time.LocalDateTime]:
            def apply(dt: java.time.LocalDateTime)(using Quotes): Expr[java.time.LocalDateTime] =
                val y  = Expr(dt.getYear)
                val mo = Expr(dt.getMonthValue)
                val d  = Expr(dt.getDayOfMonth)
                val h  = Expr(dt.getHour)
                val mi = Expr(dt.getMinute)
                val s  = Expr(dt.getSecond)
                val n  = Expr(dt.getNano)
                '{ java.time.LocalDateTime.of($y, $mo, $d, $h, $mi, $s, $n) }
            end apply

    given toExprLocalTime(using Quotes): ToExpr[java.time.LocalTime] =
        new ToExpr[java.time.LocalTime]:
            def apply(t: java.time.LocalTime)(using Quotes): Expr[java.time.LocalTime] =
                val h = Expr(t.getHour)
                val m = Expr(t.getMinute)
                val s = Expr(t.getSecond)
                val n = Expr(t.getNano)
                '{ java.time.LocalTime.of($h, $m, $s, $n) }
            end apply

    // --- State accessors for the transform extensions above and macro-side extractors ---
    // These are private[kyo] so [[SqlSchemaInfo]] (in the kyo.internal package) can call them.
    // Every naming override lives on the SqlSchema wrapper's State; the underlying Schema stays untouched.

    private[kyo] def applyNaming[A](s: SqlSchema[A], strategy: SqlSchema.Naming): SqlSchema[A] =
        s.copy(namingStrategy = Maybe(strategy))

    private[kyo] def applyTableNameOverride[A](s: SqlSchema[A], name: String): SqlSchema[A] =
        s.copy(tableNameOverride = Maybe(name))

    private[kyo] def readNaming[A](s: SqlSchema[A]): Maybe[SqlSchema.Naming] =
        s.namingStrategy

    private[kyo] def readTableNameOverride[A](s: SqlSchema[A]): Maybe[String] =
        s.tableNameOverride

    private[kyo] def readRenamedFields[A](s: SqlSchema[A]): Chunk[(String, String)] =
        s.renamedFields

    private[kyo] def applyRenamedField[A](s: SqlSchema[A], from: String, to: String): SqlSchema[A] =
        s.copy(renamedFields = s.renamedFields :+ (from, to))

    /** Reference-equality check between two [[SqlSchema]] instances, wildcard-friendly. Mirror of the `.eqRef` extension for call sites
      * that hold `SqlSchema[?]` values (the extension form cannot infer its type parameter through the wildcard).
      */
    @scala.annotation.targetName("eqRefStatic")
    private[kyo] def eqRef[A, B](a: SqlSchema[A], b: SqlSchema[B]): Boolean = a eq b

    /** Reference-equality check between the [[BoundValue.schema]] field and another [[SqlSchema]]. Used by static-SQL tests that would
      * otherwise trip kyo-test's AssertMacro instrumentation on the path-dependent `bv.schema` type. Passing the [[BoundValue]] itself as
      * the argument keeps the macro's `record` path on a stable term with no member projection to trip over.
      */
    private[kyo] def boundSchemaEqRef[A](bv: BoundValue[?], other: SqlSchema[A]): Boolean =
        bv.schema.asInstanceOf[AnyRef] eq other.asInstanceOf[AnyRef]

    /** A bind value paired with its [[SqlSchema]] for backend encoding.
      *
      * The construction site is type-checked: `SqlSchema.BoundValue[A](v: A, s: SqlSchema[A])` enforces that the value and schema refer
      * to the same type `A`. At storage positions the type parameter is hidden via `SqlSchema.BoundValue[?]`; the backend recovers the
      * encoder by pattern-matching the schema's concrete type.
      *
      * @tparam A
      *   the type of the bound value; must have a [[SqlSchema]] instance
      */
    final case class BoundValue[A](value: A, schema: SqlSchema[A])

    /** Pluggable naming convention for mapping Scala type and field names to SQL table and column names.
      *
      * Two built-in implementations are provided: [[SqlSchema.Naming.identity]] and [[SqlSchema.Naming.snakeCase]]. Custom implementations
      * may be defined inline as `new SqlSchema.Naming { ... }` or as objects extending the trait.
      */
    trait Naming:
        def tableName(typeName: String): String
        def columnName(fieldName: String): String

    object Naming:

        /** Pass-through. Type and field names are emitted verbatim. */
        case object identity extends Naming:
            def tableName(s: String): String  = s
            def columnName(s: String): String = s

        /** `Country` becomes `country`; `countryCode` becomes `country_code`; `topLevelCategoryId` becomes `top_level_category_id`. */
        case object snakeCase extends Naming:
            def tableName(s: String): String  = camelToSnake(s)
            def columnName(s: String): String = camelToSnake(s)

        private def camelToSnake(s: String): String =
            s.foldLeft(new StringBuilder) { (acc, c) =>
                if c.isUpper && acc.nonEmpty then acc.append('_')
                acc.append(c.toLower)
            }.toString

    end Naming

end SqlSchema
