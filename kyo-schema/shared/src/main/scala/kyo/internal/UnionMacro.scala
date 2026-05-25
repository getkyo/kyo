package kyo.internal

import kyo.*
import kyo.Codec.Reader
import kyo.Codec.Writer
import scala.quoted.*

/** Macro for deriving `Schema[T]` where `T` is a Scala 3 union type `L1 | L2 | ... | Ln`.
  *
  * Wire format is wrapper-style: write produces `{ "L_i": <legValue> }` and read dispatches by leg name. This shape composes with
  * `.discriminator(name)` because `SchemaSerializer.flattenWithDiscriminator` flattens a single-field wrapper Record into flat discriminator
  * format. For bare untagged input the read attempts each leg by matching the wrapper field name; if no leg's name matches (or the input
  * isn't an object) a `TypeMismatchException` is raised naming every attempted branch.
  *
  * Each leg's schema is summoned at compile time; runtime write dispatch is an `isInstanceOf` chain across the legs. Read dispatch is the
  * sealed-trait style `objectStart` + `fieldParse` + `matchField` chain, identical in shape to the sealed-trait reader so the existing
  * `DiscriminatorReader` (which presents flat-discriminator input as wrapper format) composes with no extra wiring.
  */
object UnionMacro:

    /** Entry point. Derives a `Schema[T]` for a union type `T = L1 | ... | Ln`.
      *
      * After flattening and deduplication, single-leg unions (e.g. `String | Nothing =:= String` or `Int | Int` reduced by `=:=`) delegate
      * directly to the leg's summoned `Schema`. Empty legs (theoretically impossible after Nothing-stripping) abort with a compile error.
      */
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

            // For each leg, capture the leg's quoted Type[L] (Quotes-agnostic), Schema[Any] Expr, name, and name bytes.
            // These together let us emit the runtime body without ever referring to a TypeRepr inside a splice context.
            val legInfos: List[LegInfo] = legs.map(legInfoFor)
            val legNames: List[String]  = legInfos.map(_.name)
            val legNameBytesExprs: List[Expr[Array[Byte]]] =
                legNames.map(n => Expr(n.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            val legFieldIds: List[Int]           = legNames.map(CodecMacro.fieldId)
            val legSchemasExprs                  = legInfos.map(_.schemaAny)
            val legNamesExpr: Expr[List[String]] = Expr.ofList(legNames.map(Expr(_)))

            val legNameBytesArrExpr: Expr[Array[Array[Byte]]] =
                '{ Array[Array[Byte]](${ Varargs(legNameBytesExprs) }*) }
            val legSchemasArrExpr: Expr[Array[Schema[Any]]] =
                '{ Array[Schema[Any]](${ Varargs(legSchemasExprs) }*) }

            // Build the write/read body lambdas in the OUTER Quotes context. Each closure captures only
            // Quotes-agnostic state (LegInfo, names, ids, schema-array Expr), so we can call them from
            // within the splice with the splice's bound Exprs ('value, 'writer, 'reader) safely — Expr
            // itself is Quotes-agnostic at the value level.
            def buildWrite(value: Expr[T], writer: Expr[Writer], schemas: Expr[Array[Schema[Any]]]): Expr[Unit] =
                writeBody[T](value, writer, schemas, legInfos, legNames, legFieldIds)

            def buildRead(
                reader: Expr[Reader],
                nameBytes: Expr[Array[Array[Byte]]],
                schemas: Expr[Array[Schema[Any]]],
                names: Expr[List[String]]
            ): Expr[T] =
                readBody[T](reader, nameBytes, schemas, names)

            '{
                val _legNameBytes: Array[Array[Byte]] = $legNameBytesArrExpr
                val _legSchemas: Array[Schema[Any]]   = $legSchemasArrExpr
                val _legNames: List[String]           = $legNamesExpr
                Schema.init[T](
                    writeFn = (value: T, writer: Writer) =>
                        ${ buildWrite('value, 'writer, '{ _legSchemas }) },
                    readFn = (reader: Reader) =>
                        ${ buildRead('reader, '{ _legNameBytes }, '{ _legSchemas }, '{ _legNames }) }
                )
            }
        end if
    end derive

    /** Per-leg compile-time bundle. Holds:
      *   - the leg's quoted `Type[L]` (Quotes-agnostic — survives splice boundaries) as a type member,
      *   - the leg's `Expr[Schema[Any]]` (the summoned, erased schema),
      *   - the leg's simple name for wire serialisation.
      */
    sealed private trait LegInfo:
        type L
        val tpe: Type[L]
        val schemaAny: Expr[Schema[Any]]
        val name: String
    end LegInfo

    /** Builds a `LegInfo` for one leg: summons the leg's Schema, erases to `Schema[Any]`, and derives a stable name. */
    private def legInfoFor(using Quotes)(leg: quotes.reflect.TypeRepr): LegInfo =
        import quotes.reflect.*
        leg.asType match
            case '[t] =>
                val s = Expr.summon[Schema[t]].getOrElse(
                    report.errorAndAbort(
                        s"No given Schema[${leg.show}] for union leg. Define ${leg.show} as a case class or sealed trait, or provide a given Schema[${leg.show}]."
                    )
                )
                val sAny: Expr[Schema[Any]] = '{ $s.asInstanceOf[Schema[Any]] }
                val nm                      = legName(leg)
                new LegInfo:
                    type L = t
                    val tpe       = summon[Type[t]]
                    val schemaAny = sAny
                    val name      = nm
                end new
        end match
    end legInfoFor

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

    /** Emits the write body. For each leg in declaration order, a runtime `isInstanceOf` check selects the leg's schema and writes the
      * value through it inside a single-field wrapper object. Going through `SchemaSerializer.writeTo` (not `serializeWrite`) ensures
      * any per-leg transforms (`.drop`, `.rename`, `.discriminator` on a sealed-trait leg) compose correctly.
      *
      * The terminal branch throws `TypeMismatchException` if a runtime value matches none of the legs.
      */
    private def writeBody[T: Type](using
        Quotes
    )(
        value: Expr[T],
        writer: Expr[Writer],
        legSchemas: Expr[Array[Schema[Any]]],
        legInfos: List[LegInfo],
        legNames: List[String],
        legFieldIds: List[Int]
    ): Expr[Unit] =
        val branches: List[(Expr[Boolean], Expr[Unit])] = legInfos.zipWithIndex.map { (info, idx) =>
            val nameExpr  = Expr(legNames(idx))
            val fieldIdEx = Expr(legFieldIds(idx))
            val idxExpr   = Expr(idx)
            buildLegBranch[T, info.L](value, writer, legSchemas, nameExpr, fieldIdEx, idxExpr)(using
                summon[Type[T]],
                info.tpe
            )
        }

        val legNamesStr = Expr(legNames.mkString(" | "))
        // Unsafe: no Frame is reachable inside the emitted (value, writer) => Unit
        // lambda body. TypeMismatchException requires `using Frame`; the macro-time
        // Frame.internal is the only option here. Same pattern as
        // SerializationMacro.scala:172.
        val terminal: Expr[Unit] =
            '{
                throw kyo.TypeMismatchException(
                    Seq.empty,
                    $legNamesStr,
                    // cast: value is union T at compile time; AnyRef coercion exposes the runtime class for error reporting
                    $value.asInstanceOf[AnyRef].getClass.getName
                )(using kyo.Frame.internal)
            }

        branches.foldRight(terminal) { case ((cond, body), elseBody) =>
            '{ if $cond then $body else $elseBody }
        }
    end writeBody

    /** Builds the (cond, body) pair for one leg, with the leg's Type given. Extracted so the leg type parameter `L` is bound in scope. */
    private def buildLegBranch[T: Type, L: Type](using
        Quotes
    )(
        value: Expr[T],
        writer: Expr[Writer],
        legSchemas: Expr[Array[Schema[Any]]],
        nameExpr: Expr[String],
        fieldIdEx: Expr[Int],
        idxExpr: Expr[Int]
    ): (Expr[Boolean], Expr[Unit]) =
        val cond: Expr[Boolean] = '{ $value.isInstanceOf[L] }
        // Unsafe: no Frame is reachable inside the emitted (value, writer) => Unit
        // lambda body. SchemaSerializer.writeTo requires `using Frame` to thread
        // into possible inner failures. The macro-time Frame.internal is the only
        // option here; same pattern as SerializationMacro.scala:172.
        val body: Expr[Unit] =
            '{
                $writer.objectStart($nameExpr, 1)
                $writer.field($nameExpr, $fieldIdEx)
                kyo.internal.SchemaSerializer.writeTo(
                    $legSchemas($idxExpr),
                    // cast: leg-typed value bound to Schema[Any] array slot; isInstanceOf check above proved L
                    $value.asInstanceOf[Any],
                    $writer
                )(using kyo.Frame.internal)
                $writer.objectEnd()
            }
        (cond, body)
    end buildLegBranch

    /** Emits the read body. Reads a single-field wrapper object, parses the field name once, and dispatches by matching against each leg's
      * pre-encoded name bytes. The selected leg's `readFrom` is invoked through `SchemaSerializer.readFrom` so any leg-level transforms
      * compose. If no leg matches, throws `TypeMismatchException` naming every attempted branch.
      *
      * Pre-dispatch failures (e.g. JsonReader's `Expected '{'` parse error when the wire value isn't a wrapper object) are wrapped with a
      * `TypeMismatchException` listing every leg, so callers consistently see one attempted-leg list. Post-dispatch failures (inside a leg
      * decoder) propagate verbatim so leg-internal context isn't lost.
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
                            // cast: leg readFrom returns Any (Schema[Any] array); store as AnyRef for nullable slot
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
                resultRef.asInstanceOf[T] // cast: dispatched leg produced a T-shaped value via the matching Schema
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
