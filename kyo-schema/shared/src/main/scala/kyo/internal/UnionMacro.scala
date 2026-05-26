package kyo.internal

import kyo.*
import kyo.Codec.Reader
import kyo.Codec.Writer
import scala.quoted.*

/** Macro for deriving `Schema[T]` where `T` is a Scala 3 union type `L1 | L2 | ... | Ln`.
  *
  * Wire format is wrapper-style: write produces `{ "L_i": <legValue> }` and read dispatches by leg name. This shape composes with
  * `.discriminator(name)` because `SchemaSerializer.flattenWithDiscriminator` flattens a single-field wrapper Record into flat discriminator
  * format.
  *
  * Write delegates to `SerializationMacro.sealedWriteBody` (each leg becomes a `VariantInfo` with `isInstanceOf` check and a summoned-Schema
  * resolver). Read stays union-specific: it reports `TypeMismatchException` listing every leg on failure (instead of `UnknownVariantException`
  * / `MissingFieldException` like a sealed trait), and wraps any pre-dispatch parse failure with the same leg-list message so callers see a
  * consistent attempted-branch summary.
  */
object UnionMacro:

    /** Entry point. After flattening and deduplication, single-leg unions delegate directly to the leg's summoned `Schema`. */
    def derive[T: Type](using Quotes): Expr[Schema[T]] =
        import quotes.reflect.*

        val tpe = TypeRepr.of[T]
        // Pre-dedup raw legs: lets us catch degenerate unions where the user wrote `A | A` or any chain that
        // contains structurally identical legs (e.g. `Foo | (Bar | Foo)`). Scala's type-equality often collapses
        // `A | A` to `A` BEFORE the macro sees it (then `tpe` is no longer an OrType and dispatch never lands
        // here), but defensive shapes that survive (aliases, sealed-trait re-exports, etc.) still need rejection.
        val rawLegs = collectOrTypeLegsRaw(tpe)
        val legs    = collectOrTypeLegs(tpe)

        // Defensive: degenerate / unrecoverable shapes
        degenerate(rawLegs, legs) match
            case Some(msg) => report.errorAndAbort(msg)
            case None      => ()

        // After Nothing-stripping and dedup, a single remaining leg means the union reduces to that leg
        // (e.g. String | Nothing =:= String). Delegate directly to the leg's summoned schema.
        if legs.size == 1 then
            legs.head.asType match
                case '[l] =>
                    Expr.summon[Schema[l]] match
                        case Some(s) => '{ $s.asInstanceOf[Schema[T]] }
                        case None =>
                            report.errorAndAbort(
                                s"No given Schema[${legs.head.show}] for union leg. Define ${legs.head.show} as a case class or sealed trait, or provide a given Schema[${legs.head.show}]."
                            )
        else
            // Build a VariantInfo for each leg. The leg's `Type[L]` is captured inside the closures so the
            // splice site (inside `sealedWriteBody`) sees the correct instanceOf / asInstanceOf widening.
            val legNames: List[String]          = legs.map(legName)
            val variantIds: List[(String, Int)] = legNames.map(n => n -> CodecMacro.fieldId(n))
            val variants: List[SerializationMacro.VariantInfo[T]] =
                legs.map(leg => buildVariant[T](leg))

            // Pre-encoded UTF-8 bytes for each leg name, used by both write (sealedWriteBody.fieldBytes)
            // and the union-specific read body's matchField chain.
            val legNameBytesExprs: List[Expr[Array[Byte]]] =
                legNames.map(n => Expr(n.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            val legNameBytesArrExpr: Expr[Array[Array[Byte]]] =
                '{ Array[Array[Byte]](${ Varargs(legNameBytesExprs) }*) }

            // Per-leg summoned Schema[Any] array, needed by the union-specific read body for runtime dispatch.
            val legSchemasExprs: List[Expr[Schema[Any]]] =
                variants.map(_.schemaResolver('{ null.asInstanceOf[Schema[T]] }))
            val legSchemasArrExpr: Expr[Array[Schema[Any]]] =
                '{ Array[Schema[Any]](${ Varargs(legSchemasExprs) }*) }

            val legNamesExpr: Expr[List[String]] = Expr.ofList(legNames.map(Expr(_)))
            // Joined name string used as the sealedWriteBody `typeName` (advisory: drives the
            // TypeMismatchException source string in the write-terminal; JsonWriter / ProtobufWriter
            // ignore the objectStart name argument).
            val joinedNames: String = legNames.mkString(" | ")

            val tagExprUnion: Expr[Tag[T]] = Expr.summon[Tag[T]].getOrElse('{ kyo.Tag[Any].asInstanceOf[Tag[T]] })
            '{
                val _legNameBytes: Array[Array[Byte]] = $legNameBytesArrExpr
                val _legSchemas: Array[Schema[Any]]   = $legSchemasArrExpr
                val _legNames: List[String]           = $legNamesExpr
                Schema.init[T](
                    writeFn = (value: T, writer: Writer) =>
                        ${
                            SerializationMacro.sealedWriteBody[T](
                                joinedNames,
                                variantIds,
                                variants,
                                '{ _legNameBytes },
                                '{ null.asInstanceOf[Schema[T]] },
                                'value,
                                'writer
                            )
                        },
                    readFn = (reader: Reader) =>
                        ${ readBody[T]('reader, '{ _legNameBytes }, '{ _legSchemas }, '{ _legNames }) }
                )(using $tagExprUnion)
            }
        end if
    end derive

    /** Builds a `VariantInfo[T]` for a single union leg. The leg's quoted `Type[L]` is captured inside the closures so the splice that
      * consumes the VariantInfo (inside `sealedWriteBody`) emits `isInstanceOf[L]` / `asInstanceOf[L]` with the correct widening.
      */
    private def buildVariant[T: Type](using
        Quotes
    )(leg: quotes.reflect.TypeRepr): SerializationMacro.VariantInfo[T] =
        import quotes.reflect.*
        leg.asType match
            case '[l] =>
                val summoned: Expr[Schema[l]] = Expr.summon[Schema[l]].getOrElse(
                    report.errorAndAbort(
                        s"No given Schema[${leg.show}] for union leg. Define ${leg.show} as a case class or sealed trait, or provide a given Schema[${leg.show}]."
                    )
                )
                SerializationMacro.VariantInfo[T](
                    name = legName(leg),
                    checkExpr = (v: Expr[T]) => '{ $v.isInstanceOf[l] },
                    castExpr = (v: Expr[T]) => '{ $v.asInstanceOf[l].asInstanceOf[Any] },
                    schemaResolver = (_: Expr[Schema[T]]) => '{ $summoned.asInstanceOf[Schema[Any]] }
                )
        end match
    end buildVariant

    /** Flattens nested `OrType`s into a list of leaf legs, dropping `Nothing` (bottom) and deduplicating by `=:=`.
      *
      * Nesting like `(A | B) | C` is flattened to `[A, B, C]`. `Nothing` is dropped because `A | Nothing =:= A`; the type system already
      * simplifies many such occurrences, but a defensive filter here covers the rest. Deduplication uses structural type equality.
      */
    private[internal] def collectOrTypeLegs(using Quotes)(tpe: quotes.reflect.TypeRepr): List[quotes.reflect.TypeRepr] =
        import quotes.reflect.*

        val nothingTpe = TypeRepr.of[Nothing]
        val raw        = collectOrTypeLegsRaw(tpe).filterNot(_ =:= nothingTpe)

        val out = scala.collection.mutable.ListBuffer[TypeRepr]()
        for leg <- raw do
            if !out.exists(_ =:= leg) then out += leg
        out.toList
    end collectOrTypeLegs

    /** Like [[collectOrTypeLegs]] but WITHOUT Nothing-stripping or dedup. Used to detect degenerate unions
      * (duplicate legs) that the dedup step would otherwise silently collapse.
      */
    private[internal] def collectOrTypeLegsRaw(using Quotes)(tpe: quotes.reflect.TypeRepr): List[quotes.reflect.TypeRepr] =
        import quotes.reflect.*

        def go(t: TypeRepr): List[TypeRepr] = t.dealias match
            case OrType(a, b) => go(a) ++ go(b)
            case other        => List(other)

        go(tpe)
    end collectOrTypeLegsRaw

    /** Detects union shapes that cannot produce a valid schema. Returns `Some(message)` to abort with, or `None` if the leg list is
      * usable (including the single-leg case which is handled by delegating to the leg's schema in [[derive]]).
      *
      * Rejects:
      *   - empty leg list (the union reduced to `Nothing`),
      *   - degenerate / duplicate legs (`A | A`, `A | (B | A)`, etc.) where the user almost certainly meant a tagged union but wrote a
      *     no-op type expression. A compile error here surfaces the mistake instead of silently delegating to the single remaining leg.
      */
    private[internal] def degenerate(using
        Quotes
    )(
        rawLegs: List[quotes.reflect.TypeRepr],
        dedupedLegs: List[quotes.reflect.TypeRepr]
    ): Option[String] =
        import quotes.reflect.*
        val nothingTpe   = TypeRepr.of[Nothing]
        val rawNonNothng = rawLegs.filterNot(_ =:= nothingTpe)
        if dedupedLegs.isEmpty then
            Some("Union type reduces to Nothing; no schema can be derived.")
        else if rawNonNothng.size > dedupedLegs.size then
            val show = rawNonNothng.map(_.show).mkString(" | ")
            Some(
                s"Degenerate / duplicate union: `$show` contains structurally identical legs. " +
                    "Each leg of a Union schema must be distinct. If you intended a single-leg schema, derive it directly."
            )
        else None
        end if
    end degenerate

    /** Derives the wire name for a leg. Uses the type symbol's simple name, falling back to `tpe.show` for structural types. */
    private def legName(using Quotes)(leg: quotes.reflect.TypeRepr): String =
        import quotes.reflect.*
        val sym = leg.typeSymbol
        if sym.exists then sym.name else leg.show
    end legName

    /** Emits the union-specific read body. Reads a single-field wrapper object and dispatches by leg name.
      *
      * Kept separate from `SerializationMacro.sealedReadBody`: failure semantics differ (sealed traits raise
      * `MissingFieldException("<discriminator>")` / `UnknownVariantException(name)`; unions surface the full leg list in one
      * `TypeMismatchException`). Pre-dispatch parse failures are wrapped with the same leg-list message so callers see a consistent
      * attempted-branch summary; post-dispatch failures (inside a leg decoder) propagate verbatim.
      *
      * Tagged input (when `.discriminator(name)` is applied to the resulting schema) reaches this body via
      * `SchemaSerializer.DiscriminatorReader`, which transforms flat-discriminator JSON into wrapper format.
      */
    private def readBody[T: Type](using
        Quotes
    )(
        reader: Expr[Reader],
        legNameBytes: Expr[Array[Array[Byte]]],
        legSchemas: Expr[Array[Schema[Any]]],
        legNames: Expr[List[String]]
    ): Expr[T] =
        '{
            val nameBytes         = $legNameBytes
            val schemas           = $legSchemas
            val names             = $legNames
            var dispatched        = false
            var resultRef: AnyRef = null
            try
                kyo.discard($reader.objectStart())
                if ! $reader.hasNextField() then
                    throw kyo.TypeMismatchException(
                        Seq.empty,
                        names.mkString(" | "),
                        "empty object"
                    )(using $reader.frame)
                end if
                $reader.fieldParse()
                var i = 0
                val n = nameBytes.length
                while !dispatched && i < n do
                    if $reader.matchField(nameBytes(i)) then
                        // Dispatched: flip BEFORE the call so the outer catch distinguishes "dispatch
                        // failed" (wrap with leg list) from "leg decode failed" (rethrow verbatim).
                        dispatched = true
                        resultRef =
                            kyo.internal.SchemaSerializer.readFrom(schemas(i), $reader)(using $reader.frame).asInstanceOf[AnyRef]
                    end if
                    i += 1
                end while
                if !dispatched then
                    val fieldName = $reader.lastFieldName()
                    $reader.skip()
                    throw kyo.TypeMismatchException(
                        Seq.empty,
                        names.mkString(" | "),
                        s"unknown leg '$fieldName'"
                    )(using $reader.frame)
                end if
                $reader.objectEnd()
                resultRef.asInstanceOf[T]
            catch
                case scala.util.control.NonFatal(t) if !dispatched =>
                    // Preserve the original pre-dispatch failure as a suppressed exception
                    // so the cause isn't lost when wrapped in TypeMismatchException.
                    val wrapped = kyo.TypeMismatchException(
                        Seq.empty,
                        names.mkString(" | "),
                        s"${t.getClass.getSimpleName}: ${t.getMessage}"
                    )(using $reader.frame)
                    wrapped.addSuppressed(t)
                    throw wrapped
            end try
        }
    end readBody

end UnionMacro
