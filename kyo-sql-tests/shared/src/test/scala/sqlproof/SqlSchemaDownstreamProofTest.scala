package sqlproof
import com.example.stub.StubDialect
import com.example.stub.StubWriter
import kyo.*
import kyo.SqlCodec
import kyo.db.Idiom

/** A single-column value type, the shape [[kyo.SqlSchema.of]] exists for. */
final case class Sku(value: String)

/** A single-column value type that also declares a cast target, the shape [[kyo.SqlType.of]] exists for. */
final case class Ean(value: String)

/** A two-column value type, the shape [[kyo.SqlSchema.ofMulti]] exists for. */
final case class Pair(id: Long, name: String)

/** A value type PostgreSQL owns the wire form of, the shape `PostgresTypes.custom` exists for. */
final case class Point(x: Double, y: Double)

/** An ordinary product whose every field is a single column, declaring its instance the way a user writes it. */
final case class Item(id: Long, sku: String, price: BigDecimal) derives SqlSchema

/** The same shape with no `derives` clause, for the ambient-derivation leaf. */
final case class BareItem(id: Long, sku: String)

/** Proof that defining a column type, and having an ordinary case class be a row, need nothing from `kyo.internal`.
  *
  * The factories a user reaches for name [[kyo.SqlCodec.Writer]] and [[kyo.SqlCodec.Reader]] in their parameter types, and the payload
  * vehicle [[kyo.SqlCodec.Writer.Payload]] plus the read carrier [[kyo.SqlCodec.Reader.Extension]] and the wire-format enum
  * [[kyo.SqlCodec.Format]] in the extension channel. While those types lived in `kyo.internal`, every custom column type obliged its author
  * to import an internal package to spell the lambda it was handing over. `kyo.internal` is an ordinary package rather than an
  * access-restricted one, so the defect was never that the import failed: it was that the import was required at all, and no test inside `kyo`
  * can observe it, because in-package code names both packages without importing either.
  *
  * The compile of this file is therefore the guard, and it is the only mechanism that can be one. Every declaration below is real rather than
  * the contents of a `typeChecks` string, so the real compiler accepts them in the real file, and the only kyo import is `import kyo.*`.
  * Moving either type back under `kyo.internal` stops this file compiling.
  *
  * What a downstream test can OBSERVE is the other half. `width`, `fieldNames`, `write` and `read` are `private[kyo]`, so nothing here reads
  * a codec's state directly: the assertions go through the two surfaces a user actually has, the render (which placeholder count and which
  * codec each bind carries) and the compiler (which types resolve at which tier). Codec behaviour is covered in-package by
  * `SqlSchemaOfFactoriesTest` and `PostgresTypesTest`.
  */
class SqlSchemaDownstreamProofTest extends kyo.test.Test[Any]:

    private val postgres = Idiom.Id("postgres")

    private def encodePoint(p: Point): Span[Byte] =
        Span.from(s"${p.x},${p.y}".getBytes(java.nio.charset.StandardCharsets.UTF_8))

    private def decodePoint(bytes: Span[Byte]): Point =
        val parts = new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8).split(',')
        Point(parts(0).toDouble, parts(1).toDouble)
    end decodePoint

    /** The `given` form the README teaches, so the proof covers the spelling a user copies. */
    private given skuColumn: SqlSchema.Column[Sku] = SqlSchema.of[Sku](
        write = (v, w) => w.string(v.value),
        read = r => Sku(r.string())
    )

    /** The cast target is a separate declaration from the codec, so the proof covers both spellings for one type. */
    private given eanColumn: SqlSchema.Column[Ean] = SqlSchema.of[Ean](
        write = (v, w) => w.string(v.value),
        read = r => Ean(r.string())
    )

    private given SqlType[Ean] = SqlType.of(SqlType.Type.Text)

    private val pair: SqlSchema[Pair] = SqlSchema.ofMulti[Pair](Seq("id", "name"))(
        write = (v, w) =>
            w.long(v.id)
            w.string(v.name)
    )(read = r => Pair(r.long(), r.string()))

    /** Goes out through the extension channel, so it names [[kyo.SqlCodec.Writer.Payload]], [[kyo.SqlCodec.Reader.Extension]] and
      * [[kyo.SqlCodec.Format]] as well as the two nested codec types.
      */
    private val point: SqlSchema.Column[Point] = PostgresTypes.custom[Point] { (p, w) =>
        w.extension(SqlCodec.Writer.Payload(postgres, "point", SqlCodec.Format.Text, encodePoint(p)))
    } { r =>
        val ext = r.nextExtension(postgres, "point")
        ext.format match
            case SqlCodec.Format.Text => decodePoint(ext.bytes)
            // A `point` column in the binary form is two float8s rather than the `x,y` rendering this codec writes, so the branch a
            // downstream author writes here is a refusal rather than a second parser. Writing it at all is what the carrier makes possible.
            case SqlCodec.Format.Binary =>
                throw SqlUnsupportedDialectFeatureException(
                    "reading a point value in the binary wire format",
                    postgres,
                    Maybe.Absent,
                    Maybe.Absent
                )(using r.frame)
        end match
    }

    private def rendered(ast: Sql[?]): Sql.Rendered = ast.render(StubDialect)

    "SqlSchema.of is callable with no kyo.internal import, and its result binds" in {
        val r = rendered(sql"SELECT 1 WHERE sku = ${Sku("abc")}")
        assert(r.onlySql == Maybe("SELECT 1 WHERE sku = ?"))
        assert(r.params.size == 1, "a custom column occupies one placeholder")
        given CanEqual[Any, Sku] = CanEqual.derived
        assert((r.params.head.value: Any) == Sku("abc"))
        assert(r.params.head.schema.asInstanceOf[AnyRef] eq skuColumn.asInstanceOf[AnyRef], "the bind carries the installed codec")
    }

    "SqlType.of declares the cast target a custom column renders as" in {
        assert(summon[SqlType[Ean]].columnType == SqlType.Type.Text)
        assert(rendered(Sql.literal(Ean("x")).cast[Ean]).onlySql == Maybe("CAST(? AS TEXT)"))
    }

    // The tiers are the load-bearing distinction downstream: `of` yields a bind-legal column, `ofMulti` yields row evidence that a bind
    // position does not accept, and the refusal is a compile error rather than something a caller discovers at execution.
    "SqlSchema.ofMulti is callable with no kyo.internal import, and is not bind-legal" in {
        val row: SqlSchema[Pair] = pair
        assert(row ne null)
        typeCheckFailure("""summon[SqlSchema.Column[Pair]]""")("cannot occupy a single SQL column")
    }

    "PostgresTypes.custom is callable with no kyo.internal import, and yields a column" in {
        // Installed locally rather than at the file level so the ambient-derivation leaves below stay unaffected by it.
        given SqlSchema.Column[Point] = point
        val r                         = rendered(sql"SELECT 1 WHERE loc = ${Point(1.5, -2.5)}")
        assert(r.onlySql == Maybe("SELECT 1 WHERE loc = ?"))
        assert(r.params.size == 1, "a custom column occupies one placeholder")
        given CanEqual[Any, Point] = CanEqual.derived
        assert((r.params.head.value: Any) == Point(1.5, -2.5))
        assert(r.params.head.schema.asInstanceOf[AnyRef] eq point.asInstanceOf[AnyRef], "the bind carries the custom codec")
    }

    // The other side of the writer contract. The leaves above hand a `w => ...` lambda to a factory, so they only prove the type is
    // NAMEABLE downstream. Subclassing it is the backend author's job, and a downstream subclass is constructible and its members are
    // callable: the stub's refusal is thrown by the member this leaf calls, so the message pins that the call landed there.
    "a downstream SqlCodec.Writer is constructible and its primitives are callable" in {
        val writer = new StubWriter(summon[Frame])
        interceptThrownMessage[UnsupportedOperationException](
            "StubWriter.string: the stub engine encodes no parameters"
        )(writer.string("x"))
    }

    // The row half of the same proof. A downstream case class whose fields are all single columns IS a row: `derives SqlSchema` resolves,
    // the DSL takes the type, and the render names every column.
    "a downstream case class with derives SqlSchema is a row the DSL accepts" in {
        val instance: SqlSchema[Item] = summon[SqlSchema[Item]]
        assert(instance ne null)
        assert(
            rendered(Sql.from[Item]("i")).onlySql ==
                Maybe("""SELECT "i"."id", "i"."sku", "i"."price" FROM "item" "i"""")
        )
    }

    // The derivation is ambient, so the `derives` clause above is a declaration of intent rather than a requirement: the same case class
    // with no clause at all resolves in a downstream package too.
    "a downstream case class with no derives clause resolves through the ambient derivation" in {
        val instance: SqlSchema[BareItem] = summon[SqlSchema[BareItem]]
        assert(instance ne null)
        assert(
            rendered(Sql.from[BareItem]("b")).onlySql ==
                Maybe("""SELECT "b"."id", "b"."sku" FROM "bareitem" "b"""")
        )
    }

    // A field with no column codec is a compile error naming the field, downstream exactly as in-package: support IS the instance, so there
    // is no runtime path where an unsupported field is discovered.
    "a downstream case class with an unsupported field is a compile error naming the field" in {
        typeCheckFailure(
            """case class WithThread(id: Long, worker: java.lang.Thread)
               summon[SqlSchema[WithThread]]"""
        )("worker")
    }

end SqlSchemaDownstreamProofTest
