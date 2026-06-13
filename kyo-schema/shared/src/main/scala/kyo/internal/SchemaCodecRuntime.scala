package kyo.internal

import kyo.*
import kyo.Codec.Reader
import kyo.Codec.Writer

/** Runtime helper for the macro-generated `Schema` write/read paths.
  *
  * The Schema derivation macro emits a single call into [[buildProductSchema]] or
  * [[buildSumSchema]] per derived case-class / sealed-trait, passing compact parallel arrays of
  * field/variant metadata plus thunks that the runtime invokes lazily. Keeping the per-derivation
  * inline bytecode constant-sized is what allows test classes with many `derives Schema` to compile
  * without exceeding JVM class-file limits.
  */
private[kyo] object SchemaCodecRuntime:

    /** Pre-built default for fields that have no Scala-level default. Shared across all derivations
      * so the macro does not have to emit one `() => null` thunk per non-default field.
      */
    // Sentinel thunk: never called at runtime. Stored only in slots where hasDefault == false;
    // readProduct guards every defaults(k)() call with a hasDefault(k) check.
    val nullDefault: () => Any = () => null

    // --- Product (case class) ---

    /** Compact per-field metadata for a derived case class.
      *
      * The macro emits a single string encoding of the per-field metadata; everything else
      * (`fieldIds`, `nameBytes`, parallel boolean arrays) is derived inside this class. Keeping
      * the macro emission to ONE static string per derivation is what makes test classes with
      * many `derives Schema` fit under JVM class-file limits.
      *
      * Encoding format: `name<TAB>flags;name<TAB>flags;...` where `flags` is a 4-character bitmask
      * `[m|.][o|.][d|.][n|.]` for (isMaybe, isOption, hasDefault, innerIsNullable). The names
      * cannot themselves contain tab or semicolon (Scala identifiers don't, so this is safe).
      *
      * `innerIsNullable` matters for `Maybe[Maybe[T]]` / `Maybe[Option[T]]`: the OUTER Maybe wrapper
      * is stripped by the read path (so `schemas(i)` is `Schema[Maybe[T]]`), but a `null` on the
      * wire must round-trip to `Present(Maybe.empty)`, not `Absent`. When the flag is set, the read
      * path skips the isNil short-circuit and lets the inner schema handle nullability.
      */
    final class ProductFieldsMeta(
        val typeName: String,
        encoded: String,
        val defaults: Array[() => Any]
    ):
        private val parsed: Array[(String, Boolean, Boolean, Boolean, Boolean)] =
            val parts =
                if encoded.isEmpty then Array.empty[String]
                else encoded.split(';')
            parts.map { p =>
                val tabIdx = p.indexOf('\t')
                val name   = p.substring(0, tabIdx)
                val flags  = p.substring(tabIdx + 1)
                (name, flags.charAt(0) == 'm', flags.charAt(1) == 'o', flags.charAt(2) == 'd', flags.charAt(3) == 'n')
            }
        end parsed
        val n: Int                          = parsed.length
        val names: Array[String]            = parsed.map(_._1)
        val isMaybe: Array[Boolean]         = parsed.map(_._2)
        val isOption: Array[Boolean]        = parsed.map(_._3)
        val hasDefault: Array[Boolean]      = parsed.map(_._4)
        val innerIsNullable: Array[Boolean] = parsed.map(_._5)
        val fieldIds: Array[Int]            = names.map(CodecMacro.fieldId)
        val nameBytes: Array[Array[Byte]] =
            names.map(_.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        val requiredMask: Long =
            var m   = 0L
            var idx = 0
            while idx < n do
                if !hasDefault(idx) && !isMaybe(idx) && !isOption(idx) then m |= (1L << idx)
                idx += 1
            m
        end requiredMask
    end ProductFieldsMeta

    /** Construct a `Schema[A]` for a derived case class.
      *
      * `tag` is the runtime tag for `A` (or `Tag[Any]` when none is summonable).
      * `typeParamStructures` carries the structural type of each type-parameter slot of `A`. The
      * structure tree is built lazily from `meta`, `tag`, `typeParamStructures`, the resolved
      * field schemas, and per-field default Structure.Value entries.
      */
    def buildProductSchema[A](
        meta: ProductFieldsMeta,
        schemasBuilder: () => Array[Schema[Any]],
        construct: Array[Any] => A,
        sourceFields: Seq[kyo.Field[?, ?]],
        tag: kyo.Tag[Any],
        typeParamStructures: () => Array[kyo.Structure.Type],
        defaultStructureValues: Array[() => kyo.Maybe[kyo.Structure.Value]]
    ): Schema[A] =
        lazy val schemas: Array[Schema[Any]] = schemasBuilder()
        new Schema[A](Seq.empty, sourceFields = sourceFields):
            import scala.annotation.publicInBinary
            @publicInBinary private[kyo] def serializeWrite(value: A, writer: Writer): Unit =
                writeProduct[A](meta, schemas, value, writer)(using kyo.Frame.internal)
            @publicInBinary private[kyo] def serializeRead(reader: Reader): A =
                readProduct[A](meta, schemas, construct, reader)(using kyo.Frame.internal)
            @publicInBinary private[kyo] def getter(value: A): Maybe[Any] = Maybe(value)
            @publicInBinary private[kyo] def setter(value: A, next: Any): A =
                next.asInstanceOf[A]
            private lazy val _structure: kyo.Structure.Type =
                val tps = typeParamStructures()
                val fields = kyo.Chunk.from((0 until meta.n).map { i =>
                    kyo.Structure.Field(
                        meta.names(i),
                        schemas(i).structure,
                        kyo.Maybe.empty,
                        defaultStructureValues(i)(),
                        meta.isMaybe(i) || meta.isOption(i)
                    )
                })
                kyo.Structure.Type.Product(meta.typeName, tag, kyo.Chunk.from(tps), fields)
            end _structure
            override def structure: kyo.Structure.Type = _structure
        end new
    end buildProductSchema

    /** Write a case class instance by walking its parallel field-metadata arrays. */
    def writeProduct[A](
        meta: ProductFieldsMeta,
        schemas: Array[Schema[Any]],
        value: A,
        writer: Writer
    )(using frame: Frame): Unit =
        val product = value.asInstanceOf[scala.Product]
        writer.objectStart(meta.typeName, meta.n)
        var i = 0
        while i < meta.n do
            val raw = product.productElement(i)
            if meta.isMaybe(i) then
                raw.asInstanceOf[kyo.Maybe[Any]] match
                    case kyo.Present(inner) =>
                        writer.fieldBytes(meta.nameBytes(i), meta.fieldIds(i))
                        SchemaSerializer.writeTo(schemas(i), inner, writer)
                    case _ => ()
            else if meta.isOption(i) then
                val opt = raw.asInstanceOf[Option[Any]]
                if opt.isDefined then
                    writer.fieldBytes(meta.nameBytes(i), meta.fieldIds(i))
                    SchemaSerializer.writeTo(schemas(i), opt, writer)
            else
                writer.fieldBytes(meta.nameBytes(i), meta.fieldIds(i))
                SchemaSerializer.writeTo(schemas(i), raw, writer)
            end if
            i += 1
        end while
        writer.objectEnd()
    end writeProduct

    /** Read a case class instance from `reader`. */
    def readProduct[A](
        meta: ProductFieldsMeta,
        schemas: Array[Schema[Any]],
        construct: Array[Any] => A,
        reader: Reader
    )(using frame: Frame): A =
        val n      = meta.n
        val values = new Array[Any](n)
        var k      = 0
        while k < n do
            if meta.hasDefault(k) then values(k) = meta.defaults(k)()
            else if meta.isMaybe(k) then values(k) = kyo.Maybe.empty
            else if meta.isOption(k) then values(k) = None
            k += 1
        end while

        discard(reader.objectStart())
        discard(reader.initFields(n))

        var seen = 0L
        while reader.hasNextField() do
            reader.fieldParse()
            var matchedIdx = -1
            var j          = 0
            while j < n && matchedIdx < 0 do
                if reader.matchField(meta.nameBytes(j)) then matchedIdx = j
                j += 1
            end while
            if matchedIdx < 0 then reader.skip()
            else
                if meta.isMaybe(matchedIdx) then
                    if meta.innerIsNullable(matchedIdx) then
                        // Inner schema handles its own nil; do NOT short-circuit on outer isNil
                        // because `null` here means inner-Absent inside Present, not outer-Absent.
                        values(matchedIdx) = kyo.Present(SchemaSerializer.readFrom(schemas(matchedIdx), reader))
                    else if reader.isNil() then
                        values(matchedIdx) = kyo.Maybe.empty
                    else
                        values(matchedIdx) = kyo.Present(SchemaSerializer.readFrom(schemas(matchedIdx), reader))
                    end if
                else
                    values(matchedIdx) = SchemaSerializer.readFrom(schemas(matchedIdx), reader)
                end if
                seen |= (1L << matchedIdx)
            end if
        end while

        if meta.requiredMask != 0L then
            val combined = seen | reader.droppedFieldsMask(n)
            if (combined & meta.requiredMask) != meta.requiredMask then
                val missing = java.lang.Long.numberOfTrailingZeros((~combined) & meta.requiredMask).toInt
                throw kyo.MissingFieldException(Seq.empty, meta.names(missing))(using reader.frame)
            end if
        end if

        reader.objectEnd()
        val result = construct(values)
        reader.clearFields(n)
        result
    end readProduct

    // --- Sum (sealed trait) ---

    /** Compact per-variant metadata for a derived sealed-trait Schema.
      *
      * `encoded` is `name1;name2;...`: semicolons separate variant names. Inflation produces the
      * names array, hash-based variant ids, and UTF-8 nameBytes arrays.
      */
    final class SumVariantsMeta(
        val typeName: String,
        encoded: String
    ):
        val names: Array[String] =
            if encoded.isEmpty then Array.empty
            else encoded.split(';')
        val n: Int                 = names.length
        val variantIds: Array[Int] = names.map(CodecMacro.fieldId)
        val nameBytes: Array[Array[Byte]] =
            names.map(_.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    end SumVariantsMeta

    /** Construct a `Schema[A]` for a derived sealed trait.
      *
      * `enumValuesEncoded` is `"name1;name2;..."` for the no-arg variant names; empty if none.
      * `typeParamStructures` carries the structural type of each type-parameter slot of `A`, mirroring
      * the same parameter on `buildProductSchema`.
      */
    def buildSumSchema[A](
        meta: SumVariantsMeta,
        matchVariant: A => Int,
        schemasBuilder: () => Array[Schema[Any]],
        sourceFields: Seq[kyo.Field[?, ?]],
        tag: kyo.Tag[Any],
        enumValuesEncoded: String,
        typeParamStructures: () => Array[kyo.Structure.Type]
    ): Schema[A] =
        lazy val schemas: Array[Schema[Any]] = schemasBuilder()
        val enumValues: Array[String] =
            if enumValuesEncoded.isEmpty then Array.empty else enumValuesEncoded.split(';')
        new Schema[A](Seq.empty, sourceFields = sourceFields):
            import scala.annotation.publicInBinary
            @publicInBinary private[kyo] def serializeWrite(value: A, writer: Writer): Unit =
                writeSum[A](meta, matchVariant, schemas, value, writer)(using kyo.Frame.internal)
            @publicInBinary private[kyo] def serializeRead(reader: Reader): A =
                readSum[A](meta, schemas, reader)(using kyo.Frame.internal)
            @publicInBinary private[kyo] def getter(value: A): Maybe[Any] = Maybe(value)
            @publicInBinary private[kyo] def setter(value: A, next: Any): A =
                next.asInstanceOf[A]
            private lazy val _structure: kyo.Structure.Type =
                val tps = typeParamStructures()
                val variants = kyo.Chunk.from((0 until meta.n).map { i =>
                    kyo.Structure.Variant(meta.names(i), schemas(i).structure)
                })
                kyo.Structure.Type.Sum(
                    meta.typeName,
                    tag,
                    kyo.Chunk.from(tps),
                    variants,
                    kyo.Chunk.from(enumValues)
                )
            end _structure
            override def structure: kyo.Structure.Type = _structure
        end new
    end buildSumSchema

    /** Write a sealed-trait value as the wrapper-format `{"VariantName": ...}` shape.
      *
      * No per-variant cast lambda is needed: at runtime the JVM treats `value: A` as `Any` once it
      * crosses the `Schema[Any].serializeWrite` boundary; the variant's own schema knows its real
      * type and handles the downcast internally.
      */
    def writeSum[A](
        meta: SumVariantsMeta,
        matchVariant: A => Int,
        schemas: Array[Schema[Any]],
        value: A,
        writer: Writer
    )(using frame: Frame): Unit =
        val idx = matchVariant(value)
        if idx < 0 then
            throw kyo.TypeMismatchException(Seq.empty, meta.typeName, value.getClass.getName)
        writer.objectStart(meta.typeName, 1)
        writer.fieldBytes(meta.nameBytes(idx), meta.variantIds(idx))
        SchemaSerializer.writeTo(schemas(idx), value.asInstanceOf[Any], writer)
        writer.objectEnd()
    end writeSum

    /** Read a sealed-trait value from the wrapper-format. */
    def readSum[A](
        meta: SumVariantsMeta,
        schemas: Array[Schema[Any]],
        reader: Reader
    )(using frame: Frame): A =
        discard(reader.objectStart())
        if !reader.hasNextField() then
            throw kyo.MissingFieldException(Seq.empty, "<discriminator>")(using reader.frame)
        reader.fieldParse()
        var matchedIdx = -1
        var i          = 0
        while i < meta.n && matchedIdx < 0 do
            if reader.matchField(meta.nameBytes(i)) then matchedIdx = i
            i += 1
        end while
        val result =
            if matchedIdx < 0 then
                val parsed = reader.lastFieldName()
                reader.skip()
                throw kyo.UnknownVariantException(Seq.empty, parsed)(using reader.frame)
            else
                SchemaSerializer.readFrom(schemas(matchedIdx), reader).asInstanceOf[A]
        reader.objectEnd()
        result
    end readSum

end SchemaCodecRuntime
