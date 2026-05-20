package kyo.internal

import kyo.*
import kyo.Codec.Reader
import kyo.Codec.Writer
import kyo.Record.*
import scala.quoted.*

/** Shared serialization helpers extracted from FocusMacro.
  *
  * Contains the write/read-body generators and type-checking utilities used by both Schema.apply and Schema.derived paths.
  *
  * Uses `SchemaResolver[A]` to unify simple and recursive code paths: simple types wrap a constant `Expr[Schema[Any]]` as `_ =>
  * schemaExpr`, while recursive types pass a builder that references the self schema.
  */
private[internal] object SerializationMacro:

    /** A function that, given the self schema expression, produces the schema expression for a field or variant.
      *
      * For simple (non-recursive) types, the self parameter is ignored: `(_: Expr[Schema[A]]) => schemaExpr`. For recursive types, the
      * builder uses self: `(self: Expr[Schema[A]]) => '{ \$self.asInstanceOf[Schema[Any]] }`.
      */
    type SchemaResolver[A] = Expr[Schema[A]] => Expr[Schema[Any]]

    // ---- Case class write body ----

    /** Generate write body for a case class.
      *
      * Unified across simple and recursive paths via SchemaResolver.
      */
    private[internal] def caseClassWriteBody[A: Type](using
        Quotes
    )(
        typeName: String,
        n: Int,
        fieldNames: List[String],
        fieldIds: List[(String, Int)],
        maybeFields: Set[Int],
        optionFields: Set[Int],
        fieldSchemaResolvers: List[SchemaResolver[A]],
        fieldBytes: Expr[Array[Array[Byte]]],
        subSchemasExpr: Expr[Array[kyo.Schema[Any]]],
        selfSchema: Expr[Schema[A]],
        value: Expr[A],
        writer: Expr[Writer]
    ): Expr[Unit] =
        import quotes.reflect.*
        // Look up each field's type upfront so we can emit a direct primitive writer call for primitive-typed fields
        // instead of routing through the erased `serializeWrite` Function2 (which would box).
        val tpe        = TypeRepr.of[A].dealias
        val fieldSyms  = tpe.typeSymbol.caseFields
        val fieldTypes = fieldSyms.map(f => tpe.memberType(f))

        // Build per-field write statements at macro-expansion time (outside the outer quote).
        // Each splice-embedded call to a helper that requires `using Quotes` would otherwise collide with the
        // splice's own Quotes context; doing the work here keeps everything on a single Quotes.
        val writeStmts: List[Expr[Unit]] = fieldNames.zipWithIndex.flatMap { (fieldName, idx) =>
            val fieldAccess = Select.unique(value.asTerm, fieldName).asExprOf[Any]
            val fid         = fieldIds(idx)._2
            val ft          = fieldTypes(idx)
            val idxExpr     = Expr(idx)
            val fidExpr     = Expr(fid)
            // For the generic-dispatch branches (Maybe, Option, reference-type fallthrough) we read the
            // sub-schema from the per-schema array cached at lambda construction, saving one static accessor
            // call per field per encode. Primitive / primitive-element container / Result branches below
            // don't use the sub-schema — their slots may be null.
            val schemaExpr = '{ $subSchemasExpr($idxExpr) }

            if maybeFields.contains(idx) then
                val maybeAccess = fieldAccess.asExprOf[kyo.Maybe[Any]]
                List('{
                    $maybeAccess match
                        case kyo.Present(innerVal) =>
                            $writer.fieldBytes($fieldBytes($idxExpr), $fidExpr)
                            $schemaExpr.serializeWrite(innerVal, $writer)
                        case _ => ()
                })
            else if optionFields.contains(idx) then
                val optAccess = fieldAccess.asExprOf[Option[Any]]
                List('{
                    if $optAccess.isDefined then
                        $writer.fieldBytes($fieldBytes($idxExpr), $fidExpr)
                        $schemaExpr.serializeWrite($optAccess, $writer)
                })
            else if isPrimitiveType(ft) then
                // Primitive field: emit a direct typed Writer call. No Function2.apply, no autoboxing.
                val primTerm = Select.unique(value.asTerm, fieldName)
                List(
                    '{ $writer.fieldBytes($fieldBytes($idxExpr), $fidExpr) },
                    primitiveWriteExpr(ft, writer, primTerm)
                )
            else
                containerElementSpec(ft) match
                    case Some((containerSym, elemTpe)) =>
                        // Primitive-element container field: emit a specialized per-element Writer loop, avoiding the
                        // per-element virtual call to the inner schema's serializeWrite.
                        val containerTerm = Select.unique(value.asTerm, fieldName)
                        List(
                            '{ $writer.fieldBytes($fieldBytes($idxExpr), $fidExpr) },
                            containerWriteExpr(containerSym, elemTpe, writer, containerTerm)
                        )
                    case None =>
                        resultFieldSpec(ft) match
                            case Some((errTpe, okTpe)) =>
                                // Result[E, A] with both E and A primitive: emit the discriminated-union match
                                // inline, calling primitiveWriteExpr directly on the inner value. Skips the two
                                // schema dispatches (outer Result schema + inner E/A schema) that the generic
                                // path would take.
                                val resultTerm = Select.unique(value.asTerm, fieldName)
                                List(
                                    '{ $writer.fieldBytes($fieldBytes($idxExpr), $fidExpr) },
                                    resultWriteExpr(errTpe, okTpe, writer, resultTerm)
                                )
                            case None =>
                                List(
                                    '{ $writer.fieldBytes($fieldBytes($idxExpr), $fidExpr) },
                                    '{ $schemaExpr.serializeWrite($fieldAccess, $writer) }
                                )
                        end match
                end match
            end if
        }

        val bodyExpr: Expr[Unit] =
            if writeStmts.isEmpty then '{ () } else Expr.block(writeStmts.init, writeStmts.last)

        '{
            $writer.objectStart(${ Expr(typeName) }, ${ Expr(n) })
            $bodyExpr
            $writer.objectEnd()
        }
    end caseClassWriteBody

    // ---- Sealed trait write body ----

    /** Info about a sealed trait child variant needed for write body generation. */
    case class VariantInfo[A](
        name: String,
        checkExpr: Expr[A] => Expr[Boolean],
        castExpr: Expr[A] => Expr[Any],
        schemaResolver: SchemaResolver[A]
    )

    /** Generate write body for a sealed trait.
      *
      * Unified across simple and recursive paths via SchemaResolver.
      */
    private[internal] def sealedWriteBody[A: Type](using
        Quotes
    )(
        typeName: String,
        variantIds: List[(String, Int)],
        variants: List[VariantInfo[A]],
        fieldBytes: Expr[Array[Array[Byte]]],
        selfSchema: Expr[Schema[A]],
        value: Expr[A],
        writer: Expr[Writer]
    ): Expr[Unit] =
        val checks = variants.zipWithIndex.map { case (info, idx) =>
            val vid = variantIds(idx)._2
            (
                info.checkExpr(value),
                '{
                    $writer.objectStart(${ Expr(typeName) }, 1)
                    $writer.fieldBytes($fieldBytes(${ Expr(idx) }), ${ Expr(vid) })
                    ${ info.schemaResolver(selfSchema) }.serializeWrite(${ info.castExpr(value) }, $writer)
                    $writer.objectEnd()
                }
            )
        }
        checks.foldRight('{
            throw kyo.TypeMismatchException(Seq.empty, ${ Expr(typeName) }, $value.getClass.getName)(using kyo.Frame.internal)
        }: Expr[Unit]) { (check, elseExpr) =>
            val (cond, body) = check
            '{ if $cond then $body else $elseExpr }
        }
    end sealedWriteBody

    // ---- Case class read body ----

    /** Returns true if the given TypeRepr is one of the eight JVM primitive types (Int, Long, Double, Float, Boolean, Short, Byte, Char).
      *
      * Used by [[caseClassReadBodyResolved]] and [[caseClassWriteBody]] to decide whether a field can be read/written via a direct
      * primitive-typed `Reader`/`Writer` call (no boxing) or must go through the erased `serializeRead`/`serializeWrite`
      * `Function1`/`Function2` (which boxes across the erased `Object` argument/return).
      */
    private[internal] def isPrimitiveType(using Quotes)(tpe: quotes.reflect.TypeRepr): Boolean =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived
        val dealiased                  = tpe.dealias
        val sym                        = dealiased.typeSymbol
        sym == TypeRepr.of[Int].typeSymbol ||
        sym == TypeRepr.of[Long].typeSymbol ||
        sym == TypeRepr.of[Double].typeSymbol ||
        sym == TypeRepr.of[Float].typeSymbol ||
        sym == TypeRepr.of[Boolean].typeSymbol ||
        sym == TypeRepr.of[Short].typeSymbol ||
        sym == TypeRepr.of[Byte].typeSymbol ||
        sym == TypeRepr.of[Char].typeSymbol ||
        sym == TypeRepr.of[String].typeSymbol ||
        dealiased =:= TypeRepr.of[kyo.Span[Byte]] ||
        sym == TypeRepr.of[java.time.Instant].typeSymbol ||
        sym == TypeRepr.of[java.time.Duration].typeSymbol ||
        sym == TypeRepr.of[BigInt].typeSymbol ||
        sym == TypeRepr.of[BigDecimal].typeSymbol ||
        sym == TypeRepr.of[java.util.UUID].typeSymbol ||
        sym == TypeRepr.of[java.time.LocalDate].typeSymbol ||
        sym == TypeRepr.of[java.time.LocalDateTime].typeSymbol
    end isPrimitiveType

    /** Emits a direct primitive `Reader` call for a primitive field type. Only valid when `isPrimitiveType(tpe)` is true. */
    private def primitiveReadExpr(using
        Quotes
    )(tpe: quotes.reflect.TypeRepr, reader: Expr[Reader]): Expr[Any] =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived
        val dealiased                  = tpe.dealias
        val sym                        = dealiased.typeSymbol
        if sym == TypeRepr.of[Int].typeSymbol then '{ $reader.int() }
        else if sym == TypeRepr.of[Long].typeSymbol then '{ $reader.long() }
        else if sym == TypeRepr.of[Double].typeSymbol then '{ $reader.double() }
        else if sym == TypeRepr.of[Float].typeSymbol then '{ $reader.float() }
        else if sym == TypeRepr.of[Boolean].typeSymbol then '{ $reader.boolean() }
        else if sym == TypeRepr.of[Short].typeSymbol then '{ $reader.short() }
        else if sym == TypeRepr.of[Byte].typeSymbol then '{ $reader.byte() }
        else if sym == TypeRepr.of[Char].typeSymbol then '{ $reader.char() }
        else if sym == TypeRepr.of[String].typeSymbol then '{ $reader.string() }
        else if dealiased =:= TypeRepr.of[kyo.Span[Byte]] then '{ $reader.bytes() }
        else if sym == TypeRepr.of[java.time.Instant].typeSymbol then '{ $reader.instant() }
        else if sym == TypeRepr.of[java.time.Duration].typeSymbol then '{ $reader.duration() }
        else if sym == TypeRepr.of[BigInt].typeSymbol then '{ $reader.bigInt() }
        else if sym == TypeRepr.of[BigDecimal].typeSymbol then '{ $reader.bigDecimal() }
        else if sym == TypeRepr.of[java.util.UUID].typeSymbol then '{ java.util.UUID.fromString($reader.string()) }
        else if sym == TypeRepr.of[java.time.LocalDate].typeSymbol then '{ java.time.LocalDate.parse($reader.string()) }
        else if sym == TypeRepr.of[java.time.LocalDateTime].typeSymbol then '{ java.time.LocalDateTime.parse($reader.string()) }
        else report.errorAndAbort(s"primitiveReadExpr called on non-primitive type ${tpe.show}")
        end if
    end primitiveReadExpr

    /** Recognizes field types of the form `C[P]` where `C` is one of the six specialized container symbols (`List`, `kyo.Chunk`,
      * `scala.Array`, `kyo.Span`, `Vector`, `Set`) and `P` is a primitive covered by [[isPrimitiveType]]. Returns
      * `Some((containerSymbol, elementType))` for a match, `None` otherwise.
      *
      * Used by [[caseClassWriteBody]] / [[caseClassReadBodyResolved]] to emit a specialized per-element loop calling the direct
      * `Writer`/`Reader` primitive methods, avoiding the per-element virtual call through the container's inner-schema `serializeWrite` /
      * `serializeRead`.
      *
      * Nested containers (e.g. `List[List[Int]]`) intentionally return `None`: they fall through to the generic dispatch path.
      */
    private[internal] def containerElementSpec(using
        Quotes
    )(tpe: quotes.reflect.TypeRepr): Option[(quotes.reflect.Symbol, quotes.reflect.TypeRepr)] =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived
        val dealiased                  = tpe.dealias
        // Span[Byte] is already a direct-method primitive via isPrimitiveType; the container specialization only applies to
        // other Span[P] element types, so we intentionally skip the exact Span[Byte] match here.
        if dealiased =:= TypeRepr.of[kyo.Span[Byte]] then None
        else
            dealiased match
                case AppliedType(tycon, List(inner)) =>
                    val tyconSym = tycon.typeSymbol
                    val isSupportedContainer =
                        tyconSym == TypeRepr.of[List].typeSymbol ||
                            tyconSym == TypeRepr.of[kyo.Chunk].typeSymbol ||
                            tyconSym == TypeRepr.of[Array].typeSymbol ||
                            tyconSym == TypeRepr.of[kyo.Span].typeSymbol ||
                            tyconSym == TypeRepr.of[Vector].typeSymbol ||
                            tyconSym == TypeRepr.of[Set].typeSymbol
                    if isSupportedContainer && isPrimitiveType(inner) then Some((tyconSym, inner))
                    else None
                case _ => None
            end match
        end if
    end containerElementSpec

    /** Emits a specialized write loop for a primitive-element container field. Must only be called when [[containerElementSpec]] returns a
      * `Some` for the field's type.
      *
      * For each of the six supported container shapes the loop iterates the elements directly and calls the primitive `Writer` method for
      * the element type (via [[primitiveWriteExpr]]), bracketing the emission with `writer.arrayStart(size)` / `writer.arrayEnd()`. No
      * per-element virtual call through a child `Schema` takes place.
      */
    private def containerWriteExpr(using
        Quotes
    )(
        containerSym: quotes.reflect.Symbol,
        elemTpe: quotes.reflect.TypeRepr,
        writer: Expr[Writer],
        valueTerm: quotes.reflect.Term
    ): Expr[Unit] =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived

        // Build a write-element expression: given an Expr[e] referring to one element, emit the primitive Writer call.
        def writeOne(elemExpr: Expr[Any]): Expr[Unit] =
            primitiveWriteExpr(elemTpe, writer, elemExpr.asTerm)

        elemTpe.asType match
            case '[e] =>
                if containerSym == TypeRepr.of[List].typeSymbol then
                    val listExpr = valueTerm.asExprOf[List[e]]
                    '{
                        val xs0 = $listExpr
                        $writer.arrayStart(xs0.size)
                        var it = xs0
                        while !it.isEmpty do
                            val elem = it.head
                            ${ writeOne('{ elem }) }
                            it = it.tail
                        end while
                        $writer.arrayEnd()
                    }
                else if containerSym == TypeRepr.of[kyo.Chunk].typeSymbol then
                    val chunkExpr = valueTerm.asExprOf[kyo.Chunk[e]]
                    '{
                        val xs0 = $chunkExpr
                        val n   = xs0.size
                        $writer.arrayStart(n)
                        var i = 0
                        while i < n do
                            val elem = xs0(i)
                            ${ writeOne('{ elem }) }
                            i += 1
                        end while
                        $writer.arrayEnd()
                    }
                else if containerSym == TypeRepr.of[Array].typeSymbol then
                    val arrExpr = valueTerm.asExprOf[Array[e]]
                    '{
                        val xs0 = $arrExpr
                        val n   = xs0.length
                        $writer.arrayStart(n)
                        var i = 0
                        while i < n do
                            val elem = xs0(i)
                            ${ writeOne('{ elem }) }
                            i += 1
                        end while
                        $writer.arrayEnd()
                    }
                else if containerSym == TypeRepr.of[kyo.Span].typeSymbol then
                    val spanExpr = valueTerm.asExprOf[kyo.Span[e]]
                    '{
                        val xs0 = $spanExpr
                        val n   = xs0.size
                        $writer.arrayStart(n)
                        var i = 0
                        while i < n do
                            val elem = xs0(i)
                            ${ writeOne('{ elem }) }
                            i += 1
                        end while
                        $writer.arrayEnd()
                    }
                else if containerSym == TypeRepr.of[Vector].typeSymbol then
                    val vecExpr = valueTerm.asExprOf[Vector[e]]
                    '{
                        val xs0 = $vecExpr
                        val n   = xs0.size
                        $writer.arrayStart(n)
                        var i = 0
                        while i < n do
                            val elem = xs0(i)
                            ${ writeOne('{ elem }) }
                            i += 1
                        end while
                        $writer.arrayEnd()
                    }
                else if containerSym == TypeRepr.of[Set].typeSymbol then
                    val setExpr = valueTerm.asExprOf[Set[e]]
                    '{
                        val xs0 = $setExpr
                        $writer.arrayStart(xs0.size)
                        val it = xs0.iterator
                        while it.hasNext do
                            val elem = it.next()
                            ${ writeOne('{ elem }) }
                        end while
                        $writer.arrayEnd()
                    }
                else
                    report.errorAndAbort(s"containerWriteExpr called with unsupported container ${containerSym.fullName}")
                end if
        end match
    end containerWriteExpr

    /** Emits a specialized read loop for a primitive-element container field. Must only be called when [[containerElementSpec]] returns a
      * `Some` for the field's type.
      *
      * The emitted expression opens the array, reads elements directly via [[primitiveReadExpr]] into a builder (or array for `Array[P]` /
      * `Span[P]`), closes the array, and yields the finished container. The result type matches the original field type `C[P]`, so the
      * caller may `asInstanceOf[t]` it to the case class field's declared type without introducing boxing on the element path.
      */
    private def containerReadExpr(using
        Quotes
    )(
        containerSym: quotes.reflect.Symbol,
        elemTpe: quotes.reflect.TypeRepr,
        reader: Expr[Reader]
    ): Expr[Any] =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived

        elemTpe.asType match
            case '[e] =>
                val readElem: Expr[e] = primitiveReadExpr(elemTpe, reader).asExprOf[e]
                if containerSym == TypeRepr.of[List].typeSymbol then
                    '{
                        kyo.discard($reader.arrayStart())
                        val builder = List.newBuilder[e]
                        @scala.annotation.tailrec
                        def loop(count: Int): Unit =
                            if $reader.hasNextElement() then
                                $reader.checkCollectionSize(count)
                                builder += $readElem
                                loop(count + 1)
                        loop(1)
                        $reader.arrayEnd()
                        builder.result()
                    }
                else if containerSym == TypeRepr.of[kyo.Chunk].typeSymbol then
                    '{
                        kyo.discard($reader.arrayStart())
                        val builder = kyo.Chunk.newBuilder[e]
                        @scala.annotation.tailrec
                        def loop(count: Int): Unit =
                            if $reader.hasNextElement() then
                                $reader.checkCollectionSize(count)
                                builder += $readElem
                                loop(count + 1)
                        loop(1)
                        $reader.arrayEnd()
                        builder.result()
                    }
                else if containerSym == TypeRepr.of[Array].typeSymbol then
                    val ctExpr: Expr[scala.reflect.ClassTag[e]] =
                        Expr.summon[scala.reflect.ClassTag[e]].getOrElse(
                            report.errorAndAbort(
                                s"containerReadExpr: missing ClassTag for Array element type ${elemTpe.show}"
                            )
                        )
                    '{
                        given ct: scala.reflect.ClassTag[e] = $ctExpr
                        kyo.discard($reader.arrayStart())
                        val builder = Array.newBuilder[e]
                        @scala.annotation.tailrec
                        def loop(count: Int): Unit =
                            if $reader.hasNextElement() then
                                $reader.checkCollectionSize(count)
                                builder += $readElem
                                loop(count + 1)
                        loop(1)
                        $reader.arrayEnd()
                        builder.result()
                    }
                else if containerSym == TypeRepr.of[kyo.Span].typeSymbol then
                    val ctExpr: Expr[scala.reflect.ClassTag[e]] =
                        Expr.summon[scala.reflect.ClassTag[e]].getOrElse(
                            report.errorAndAbort(
                                s"containerReadExpr: missing ClassTag for Span element type ${elemTpe.show}"
                            )
                        )
                    '{
                        given ct: scala.reflect.ClassTag[e] = $ctExpr
                        kyo.discard($reader.arrayStart())
                        val builder = kyo.Chunk.newBuilder[e]
                        @scala.annotation.tailrec
                        def loop(count: Int): Unit =
                            if $reader.hasNextElement() then
                                $reader.checkCollectionSize(count)
                                builder += $readElem
                                loop(count + 1)
                        loop(1)
                        $reader.arrayEnd()
                        kyo.Span.from(builder.result())
                    }
                else if containerSym == TypeRepr.of[Vector].typeSymbol then
                    '{
                        kyo.discard($reader.arrayStart())
                        val builder = Vector.newBuilder[e]
                        @scala.annotation.tailrec
                        def loop(count: Int): Unit =
                            if $reader.hasNextElement() then
                                $reader.checkCollectionSize(count)
                                builder += $readElem
                                loop(count + 1)
                        loop(1)
                        $reader.arrayEnd()
                        builder.result()
                    }
                else if containerSym == TypeRepr.of[Set].typeSymbol then
                    '{
                        kyo.discard($reader.arrayStart())
                        val builder = Set.newBuilder[e]
                        @scala.annotation.tailrec
                        def loop(count: Int): Unit =
                            if $reader.hasNextElement() then
                                $reader.checkCollectionSize(count)
                                builder += $readElem
                                loop(count + 1)
                        loop(1)
                        $reader.arrayEnd()
                        builder.result()
                    }
                else
                    report.errorAndAbort(s"containerReadExpr called with unsupported container ${containerSym.fullName}")
                end if
        end match
    end containerReadExpr

    /** Recognizes field types of the form `kyo.Result[E, A]` where both `E` and `A` are primitives covered by [[isPrimitiveType]]. Returns
      * `Some((errTpe, okTpe))` when the specialization applies, `None` otherwise.
      *
      * Used by [[caseClassWriteBody]] / [[caseClassReadBodyResolved]] to emit an inline discriminated-union match that calls the direct
      * `Writer` / `Reader` primitive methods on the inner value, avoiding two per-call virtual dispatches (one to the `Result` schema's
      * `serializeWrite`/`serializeRead` and one to the inner `E`/`A` schema).
      *
      * Non-primitive inner types fall through to the generic path.
      */
    private[internal] def resultFieldSpec(using
        Quotes
    )(tpe: quotes.reflect.TypeRepr): Option[(quotes.reflect.TypeRepr, quotes.reflect.TypeRepr)] =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived
        val dealiased                  = tpe.dealias
        dealiased match
            case AppliedType(tycon, List(errTpe, okTpe)) if tycon.typeSymbol == TypeRepr.of[kyo.Result].typeSymbol =>
                if isPrimitiveType(errTpe) && isPrimitiveType(okTpe) then Some((errTpe, okTpe))
                else None
            case _ => None
        end match
    end resultFieldSpec

    /** Emits the inline discriminated-union write for a `Result[E, A]` field with primitive `E` and `A`. Must only be called when
      * [[resultFieldSpec]] returns a `Some` for the field's type.
      *
      * The emitted expression pattern-matches the `Result` value into `Success`, `Failure`, and `Panic` arms and writes the identical JSON
      * shape as [[kyo.Schema.resultSchema]]: `{"$type":"success"|"failure"|"panic","value":<inner>}`. The inner value is written via the
      * direct primitive [[primitiveWriteExpr]] call (no schema dispatch). The Panic arm mirrors the generic schema: it writes the exception
      * message as a string (or `null`), which does not depend on the `E`/`A` types.
      */
    private def resultWriteExpr(using
        Quotes
    )(
        errTpe: quotes.reflect.TypeRepr,
        okTpe: quotes.reflect.TypeRepr,
        writer: Expr[Writer],
        valueTerm: quotes.reflect.Term
    ): Expr[Unit] =
        import quotes.reflect.*
        val resultExpr = valueTerm.asExprOf[kyo.Result[Any, Any]]
        (errTpe.asType, okTpe.asType) match
            case ('[e], '[a]) =>
                val writeOk: quotes.reflect.Term => Expr[Unit]  = t => primitiveWriteExpr(okTpe, writer, t)
                val writeErr: quotes.reflect.Term => Expr[Unit] = t => primitiveWriteExpr(errTpe, writer, t)
                '{
                    $resultExpr match
                        case kyo.Result.Success(ok) =>
                            $writer.objectStart("Result", 2)
                            $writer.field("$type", 0)
                            $writer.string("success")
                            $writer.field("value", 1)
                            ${ writeOk('{ ok.asInstanceOf[a] }.asTerm) }
                            $writer.objectEnd()
                        case kyo.Result.Failure(err) =>
                            $writer.objectStart("Result", 2)
                            $writer.field("$type", 0)
                            $writer.string("failure")
                            $writer.field("value", 1)
                            ${ writeErr('{ err.asInstanceOf[e] }.asTerm) }
                            $writer.objectEnd()
                        case kyo.Result.Panic(ex) =>
                            $writer.objectStart("Result", 2)
                            $writer.field("$type", 0)
                            $writer.string("panic")
                            $writer.field("value", 1)
                            kyo.Maybe(ex.getMessage) match
                                case kyo.Maybe.Present(msg) => $writer.string(msg)
                                case _                      => $writer.nil()
                            $writer.objectEnd()
                }
        end match
    end resultWriteExpr

    /** Emits the inline discriminated-union read for a `Result[E, A]` field with primitive `E` and `A`. Must only be called when
      * [[resultFieldSpec]] returns a `Some` for the field's type.
      *
      * The emitted expression reads the same JSON shape as [[kyo.Schema.resultSchema]] and dispatches to the inner primitive
      * [[primitiveReadExpr]] based on the `$type` discriminator value. The Panic arm mirrors the generic schema: the captured value is
      * either `null` or a string.
      */
    private def resultReadExpr(using
        Quotes
    )(
        errTpe: quotes.reflect.TypeRepr,
        okTpe: quotes.reflect.TypeRepr,
        reader: Expr[Reader]
    ): Expr[Any] =
        import quotes.reflect.*
        // Closures capture the outer Quotes, letting us call primitiveReadExpr with `errTpe`/`okTpe` (both bound to
        // the outer Quotes). Each closure takes an `Expr[Reader]` whose referent (`capturedReader`) is defined
        // inside the emitted code; the returned `Expr` is spliced into that same scope.
        val readOk: Expr[Reader] => Expr[Any]  = r => primitiveReadExpr(okTpe, r)
        val readErr: Expr[Reader] => Expr[Any] = r => primitiveReadExpr(errTpe, r)
        (errTpe.asType, okTpe.asType) match
            case ('[e], '[a]) =>
                '{
                    kyo.discard($reader.objectStart())
                    var typeName: kyo.Maybe[String]           = kyo.Maybe.empty
                    var captured: kyo.Maybe[kyo.Codec.Reader] = kyo.Maybe.empty
                    @scala.annotation.tailrec
                    def loop(): Unit =
                        if $reader.hasNextField() then
                            $reader.field() match
                                case "$type" => typeName = kyo.Maybe($reader.string())
                                case "value" => captured = kyo.Maybe($reader.captureValue())
                                case _       => $reader.skip()
                            end match
                            loop()
                    loop()
                    $reader.objectEnd()
                    if typeName.isEmpty then
                        throw kyo.MissingFieldException(Seq.empty, "$type")(using $reader.frame)
                    if captured.isEmpty then
                        throw kyo.MissingFieldException(Seq.empty, "value")(using $reader.frame)
                    val capturedReader = captured.get
                    typeName.get match
                        case "success" =>
                            val okValue: a = ${ readOk('{ capturedReader }).asExprOf[a] }
                            kyo.Result.succeed[e, a](okValue)
                        case "failure" =>
                            val errValue: e = ${ readErr('{ capturedReader }).asExprOf[e] }
                            kyo.Result.fail[e, a](errValue)
                        case "panic" =>
                            val msg: kyo.Maybe[String] =
                                if capturedReader.isNil() then kyo.Maybe.empty
                                else kyo.Maybe(capturedReader.string())
                            kyo.Result.panic[e, a](new RuntimeException(msg.getOrElse(null: String)))
                        case other =>
                            throw kyo.UnknownVariantException(Seq.empty, other)(using $reader.frame)
                    end match
                }
        end match
    end resultReadExpr

    /** Emits a direct primitive `Writer` call for a primitive field type. Only valid when `isPrimitiveType(tpe)` is true.
      *
      * `valueTerm` must be a term whose static type is the primitive value type (e.g. a `Select` onto a case class's `Int` field).
      */
    private def primitiveWriteExpr(using
        Quotes
    )(tpe: quotes.reflect.TypeRepr, writer: Expr[Writer], valueTerm: quotes.reflect.Term): Expr[Unit] =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived
        val dealiased                  = tpe.dealias
        val sym                        = dealiased.typeSymbol
        if sym == TypeRepr.of[Int].typeSymbol then '{ $writer.int(${ valueTerm.asExprOf[Int] }) }
        else if sym == TypeRepr.of[Long].typeSymbol then '{ $writer.long(${ valueTerm.asExprOf[Long] }) }
        else if sym == TypeRepr.of[Double].typeSymbol then '{ $writer.double(${ valueTerm.asExprOf[Double] }) }
        else if sym == TypeRepr.of[Float].typeSymbol then '{ $writer.float(${ valueTerm.asExprOf[Float] }) }
        else if sym == TypeRepr.of[Boolean].typeSymbol then '{ $writer.boolean(${ valueTerm.asExprOf[Boolean] }) }
        else if sym == TypeRepr.of[Short].typeSymbol then '{ $writer.short(${ valueTerm.asExprOf[Short] }) }
        else if sym == TypeRepr.of[Byte].typeSymbol then '{ $writer.byte(${ valueTerm.asExprOf[Byte] }) }
        else if sym == TypeRepr.of[Char].typeSymbol then '{ $writer.char(${ valueTerm.asExprOf[Char] }) }
        else if sym == TypeRepr.of[String].typeSymbol then '{ $writer.string(${ valueTerm.asExprOf[String] }) }
        else if dealiased =:= TypeRepr.of[kyo.Span[Byte]] then '{ $writer.bytes(${ valueTerm.asExprOf[kyo.Span[Byte]] }) }
        else if sym == TypeRepr.of[java.time.Instant].typeSymbol then '{ $writer.instant(${ valueTerm.asExprOf[java.time.Instant] }) }
        else if sym == TypeRepr.of[java.time.Duration].typeSymbol then '{ $writer.duration(${ valueTerm.asExprOf[java.time.Duration] }) }
        else if sym == TypeRepr.of[BigInt].typeSymbol then '{ $writer.bigInt(${ valueTerm.asExprOf[BigInt] }) }
        else if sym == TypeRepr.of[BigDecimal].typeSymbol then '{ $writer.bigDecimal(${ valueTerm.asExprOf[BigDecimal] }) }
        else if sym == TypeRepr.of[java.util.UUID].typeSymbol then '{ $writer.string(${ valueTerm.asExprOf[java.util.UUID] }.toString) }
        else if sym == TypeRepr.of[java.time.LocalDate].typeSymbol then
            '{ $writer.string(${ valueTerm.asExprOf[java.time.LocalDate] }.toString) }
        else if sym == TypeRepr.of[java.time.LocalDateTime].typeSymbol then
            '{ $writer.string(${ valueTerm.asExprOf[java.time.LocalDateTime] }.toString) }
        else report.errorAndAbort(s"primitiveWriteExpr called on non-primitive type ${tpe.show}")
        end if
    end primitiveWriteExpr

    /** Returns a zero-valued `Term` usable as an initializer for a typed local `var` of the given type.
      *
      * For primitive types, returns the appropriate literal (`0`, `0L`, `0.0`, `false`, `'\u0000'`, etc.). For `String`, returns `""`. For
      * reference types, returns `null` ascribed to the reference type. The var's initial value is observable only when the field is absent
      * from the wire AND is pre-satisfied by [[Codec.Reader.droppedFieldsMask]] (i.e. dropped by a schema transform); otherwise, either the
      * dispatch arm overwrites it or the required-field bitmap check raises [[MissingFieldException]]. This mirrors the previous
      * `SchemaSerializer.zeroForField` behavior that the transform-aware path relied on via `Array[AnyRef]` pre-population.
      */
    private def zeroInitTerm(using Quotes)(tpe: quotes.reflect.TypeRepr): quotes.reflect.Term =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived
        val sym                        = tpe.dealias.typeSymbol
        if sym == TypeRepr.of[Int].typeSymbol then Literal(IntConstant(0))
        else if sym == TypeRepr.of[Long].typeSymbol then Literal(LongConstant(0L))
        else if sym == TypeRepr.of[Double].typeSymbol then Literal(DoubleConstant(0.0))
        else if sym == TypeRepr.of[Float].typeSymbol then Literal(FloatConstant(0.0f))
        else if sym == TypeRepr.of[Boolean].typeSymbol then Literal(BooleanConstant(false))
        else if sym == TypeRepr.of[Short].typeSymbol then Literal(ShortConstant(0.toShort))
        else if sym == TypeRepr.of[Byte].typeSymbol then Literal(ByteConstant(0.toByte))
        else if sym == TypeRepr.of[Char].typeSymbol then Literal(CharConstant('\u0000'))
        else if sym == TypeRepr.of[String].typeSymbol then Literal(StringConstant(""))
        else
            // Reference field: use null ascribed to the field's type.
            tpe.asType match
                case '[t] => '{ null.asInstanceOf[t] }.asTerm
        end if
    end zeroInitTerm

    /** Generate read body for a case class using SchemaResolver.
      *
      * Unified across simple and recursive paths. For simple types, pass resolvers that ignore the selfSchema parameter.
      *
      * Emits one typed local `var` per field. Primitive fields are read via direct `Reader.int()`, `Reader.long()`, etc.; reference fields
      * go through `schemaExpr.serializeRead.get(reader)`. No `Array[AnyRef]` buffer is allocated — every primitive stays in a typed local
      * throughout the decode, eliminating the box/unbox round-trip that the previous `values(idx).asInstanceOf[T]` protocol required.
      *
      * Required-field presence is tracked by a `Long` bitmap (`seen`): bit `i` is set when field `i` is successfully read. After the loop,
      * the macro validates `(seen | reader.droppedFieldsMask(n)) & requiredMask == requiredMask`. The 64-bit width caps case classes at 64
      * fields; exceeding this raises a compile-time error.
      */
    private[internal] def caseClassReadBodyResolved[A: Type](using
        Quotes
    )(
        reader: Expr[Reader],
        fieldBytesExpr: Expr[Array[Array[Byte]]],
        fieldNamesExpr: Expr[Array[String]],
        fieldSchemaResolvers: List[(String, SchemaResolver[A])],
        subSchemasExpr: Expr[Array[kyo.Schema[Any]]],
        selfSchema: Expr[Schema[A]]
    ): Expr[A] =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived

        val tpe      = TypeRepr.of[A].dealias
        val sym      = tpe.typeSymbol
        val fields   = sym.caseFields
        val n        = fields.size
        val defaults = CodecMacro.caseClassDefaultsPublic(tpe)

        if n > 64 then
            report.errorAndAbort(
                s"kyo-schema: case class ${tpe.show} has $n fields; the generated decoder uses a Long required-field bitmap and supports at most 64 fields."
            )
        end if

        // Detect Maybe/Option fields
        val (maybeFields, optionFields) = MacroUtils.detectMaybeOptionFields(tpe, fields)

        // Required-field mask: bit i set iff field i is required (no default, not Maybe, not Option).
        val requiredMaskValue: Long =
            var m = 0L
            fields.zipWithIndex.foreach { (field, idx) =>
                val hasDefault = defaults.contains(field.name)
                val isOptional = maybeFields.contains(idx) || optionFields.contains(idx)
                if !hasDefault && !isOptional then m |= (1L << idx)
            }
            m
        end requiredMaskValue

        // Per-field type information
        val fieldTypes: List[TypeRepr]      = fields.map(f => tpe.memberType(f))
        val fieldIsPrimitive: List[Boolean] = fieldTypes.map(isPrimitiveType(_))

        // Emit the decode body as a single tail-recursive `loop` method taking the field values as typed parameters
        // (plus `seen: Long` and `expectedIdx: Int`), returning the constructed case-class instance at its terminal
        // arm. No mutable vars, no `*Ref.create()` boxes, no intermediate `idx` write/read round-trip — the name-match
        // chain is fused with the read-action chain so each `matchField` arm performs the read + `seen`-bit update +
        // `expectedIdx` bump + tail call inline.
        val ownerSym = Symbol.spliceOwner

        // Initial value for field parameter `idx` at loop entry (default / Maybe.empty / None / primitive zero / null).
        def fieldInitTerm(idx: Int): Term =
            val field = fields(idx)
            val ft    = fieldTypes(idx)
            defaults.get(field.name) match
                case Some(d) =>
                    // User-supplied default — keep the expression as-is and ascribe to the field type.
                    ft.asType match
                        case '[t] => '{ ${ d }.asInstanceOf[t] }.asTerm
                case None if maybeFields.contains(idx) =>
                    ft.asType match
                        case '[t] => '{ kyo.Maybe.empty.asInstanceOf[t] }.asTerm
                case None if optionFields.contains(idx) =>
                    ft.asType match
                        case '[t] => '{ None.asInstanceOf[t] }.asTerm
                case None =>
                    zeroInitTerm(ft)
            end match
        end fieldInitTerm

        val nExpr: Expr[Int] = Expr(n)

        // Build the `loop` method symbol. Parameters are the `n` field values (typed), then `seen: Long`, then
        // `expectedIdx: Int`; return type is the case class `A` itself.
        val loopParamNames: List[String] = fields.indices.toList.map(i => s"f$$$i") ++ List("seen", "expectedIdx")
        val loopParamTypes: List[TypeRepr] =
            fieldTypes ++ List(TypeRepr.of[Long], TypeRepr.of[Int])
        val loopReturnType: TypeRepr = TypeRepr.of[A]
        val loopMethodType: MethodType =
            MethodType(loopParamNames)(_ => loopParamTypes, _ => loopReturnType)
        val loopSym: Symbol = Symbol.newMethod(ownerSym, "loop", loopMethodType, Flags.EmptyFlags, Symbol.noSymbol)

        // Required-field validation expression, reading `seen` from a parameter `Expr[Long]`.
        def requiredCheckExpr(seen: Expr[Long]): Expr[Unit] =
            if requiredMaskValue == 0L then '{ () }
            else
                val maskLit = Literal(LongConstant(requiredMaskValue)).asExprOf[Long]
                '{
                    val requiredMask = $maskLit
                    val combined     = ${ seen } | $reader.droppedFieldsMask($nExpr)
                    if (combined & requiredMask) != requiredMask then
                        val missing = java.lang.Long.numberOfTrailingZeros((~combined) & requiredMask).toInt
                        throw kyo.MissingFieldException(Seq.empty, $fieldNamesExpr(missing))(using $reader.frame)
                    end if
                }
        end requiredCheckExpr

        // Construct the case-class instance from a list of field-value terms.
        def constructFromTerms(fieldTerms: List[Term]): Expr[A] =
            val companion = Ref(sym.companionModule)
            val typeArgs = tpe match
                case AppliedType(_, args) => args
                case _                    => List.empty
            Select.overloaded(companion, "apply", typeArgs, fieldTerms).asExprOf[A]
        end constructFromTerms

        // The emitted read for field `idx`: a typed `Expr[Any]` producing the fresh field value. Primitive fields use
        // the direct `Reader` primitive; primitive-element containers use the specialized loop; `Result[E, A]` with
        // primitive inner types uses the inline discriminated-union read; Maybe/Option/reference fields go through the
        // sub-schema cached in the closure (`_subSchemas`).
        def fieldReadExpr(idx: Int): Expr[Any] =
            val idxExpr: Expr[Int]            = Expr(idx)
            val schemaExpr: Expr[Schema[Any]] = '{ $subSchemasExpr($idxExpr) }
            val ft                            = fieldTypes(idx)
            val isMaybe                       = maybeFields.contains(idx)
            val isOption                      = optionFields.contains(idx)
            if isMaybe then
                // Maybe[T]: wrap the inner serializeRead result in kyo.Present.
                ft.asType match
                    case '[t] => '{ kyo.Present($schemaExpr.serializeRead($reader)).asInstanceOf[t] }
            else if isOption then
                // Option[T]: the Option schema's serializeRead already yields Option[T].
                ft.asType match
                    case '[t] => '{ $schemaExpr.serializeRead($reader).asInstanceOf[t] }
            else if fieldIsPrimitive(idx) then
                // Direct primitive reader call — no boxing.
                primitiveReadExpr(ft, reader)
            else
                containerElementSpec(ft) match
                    case Some((containerSym, elemTpe)) =>
                        val readExpr = containerReadExpr(containerSym, elemTpe, reader)
                        ft.asType match
                            case '[t] => '{ $readExpr.asInstanceOf[t] }
                    case None =>
                        resultFieldSpec(ft) match
                            case Some((errTpe, okTpe)) =>
                                val readExpr = resultReadExpr(errTpe, okTpe, reader)
                                ft.asType match
                                    case '[t] => '{ $readExpr.asInstanceOf[t] }
                            case None =>
                                ft.asType match
                                    case '[t] => '{ $schemaExpr.serializeRead($reader).asInstanceOf[t] }
                        end match
                end match
            end if
        end fieldReadExpr

        // Builds the DefDef for the tail-recursive `loop`. The `paramss` argument gives access to the parameter
        // trees (one list of N+2 `Tree`s) from which we derive `Ref`s typed at the field/seen/expectedIdx types.
        val loopDef: DefDef = DefDef(
            loopSym,
            paramss =>
                // paramss has shape List(List(<N field params> :+ seen :+ expectedIdx)) — a single term-param clause.
                val termParams: List[Term]     = paramss.head.asInstanceOf[List[Term]]
                val fieldParamRefs: List[Term] = termParams.take(n)
                val seenParamRef: Term         = termParams(n)
                val expectedParamRef: Term     = termParams(n + 1)
                val seenExpr: Expr[Long]       = seenParamRef.asExprOf[Long]
                val expectedExpr: Expr[Int]    = expectedParamRef.asExprOf[Int]

                // Tail-recursive call to `loop` with field `idx` replaced by `newValue`, `seen` updated with its bit,
                // and `expectedIdx` bumped to `idx + 1`.
                def loopCall(idx: Int, newValue: Term): Term =
                    val bit     = 1L << idx
                    val newSeen = '{ ${ seenExpr } | ${ Expr(bit) } }.asTerm
                    val newExp  = Literal(IntConstant(idx + 1))
                    val args: List[Term] =
                        fieldParamRefs.zipWithIndex.map { (ref, i) =>
                            if i == idx then newValue else ref
                        } ++ List(newSeen, newExp)
                    Apply(Ref(loopSym), args)
                end loopCall

                // Tail-recursive call with all field values unchanged and `seen`/`expectedIdx` passed through (the
                // "skip unknown field" arm).
                def loopCallUnchanged: Term =
                    val args: List[Term] = fieldParamRefs ++ List(seenParamRef, expectedParamRef)
                    Apply(Ref(loopSym), args)
                end loopCallUnchanged

                // Body for the `expectedIdx` fast-path arm for a specific field index `i`: read the field, tail-call.
                def fastPathCallFor(i: Int): Expr[A] =
                    val ft       = fieldTypes(i)
                    val readExpr = fieldReadExpr(i)
                    ft.asType match
                        case '[t] =>
                            // Bind the read value to a fresh local so the tail call's argument is a pure ref.
                            val readTerm: Term = readExpr.asExprOf[t].asTerm
                            val call: Term     = loopCall(i, readTerm)
                            call.asExprOf[A]
                    end match
                end fastPathCallFor

                // Build the `expectedIdx` fast-path dispatch: `expectedIdx` is in `[0, n)` at this point (the outer
                // `if` guards that), so exhaustively match on it. Each arm reads the field specialized to its index
                // and tail-calls `loop` with the new value in position `i`.
                val fastPathMatch: Expr[A] =
                    val caseDefs: List[CaseDef] = fields.indices.toList.map { i =>
                        CaseDef(Literal(IntConstant(i)), None, fastPathCallFor(i).asTerm)
                    }
                    // Wildcard arm: unreachable at runtime (guarded by `expectedIdx < n`), but required for
                    // exhaustiveness. Tail-call with unchanged state.
                    val wildcardCase: CaseDef = CaseDef(Wildcard(), None, loopCallUnchanged)
                    Match(expectedParamRef, caseDefs :+ wildcardCase).asExprOf[A]
                end fastPathMatch

                // Full fused name-match chain. Starts from the terminal "unknown field" arm (skip + loop) and folds
                // each field's `matchField` check from the last field back to the first.
                val nameChain: Expr[A] =
                    val terminalSkip: Expr[A] = '{
                        $reader.skip()
                        ${ loopCallUnchanged.asExprOf[A] }
                    }
                    fields.zipWithIndex.foldRight(terminalSkip) {
                        case ((_, i), elseExpr) =>
                            val iExpr = Expr(i)
                            '{
                                if $reader.matchField($fieldBytesExpr($iExpr)) then
                                    ${ fastPathCallFor(i) }
                                else
                                    $elseExpr
                            }
                    }
                end nameChain

                // Iteration body: if `expectedIdx` hits, take the fast path (direct read for the next expected field);
                // otherwise fall through to the full fused name chain.
                val iterExpr: Expr[A] = '{
                    $reader.fieldParse()
                    if $expectedExpr < $nExpr && $reader.matchField($fieldBytesExpr($expectedExpr)) then
                        $fastPathMatch
                    else
                        $nameChain
                    end if
                }

                // Terminal: no more fields. Validate required fields, close the reader, construct the instance.
                val terminalExpr: Expr[A] = '{
                    ${ requiredCheckExpr(seenExpr) }
                    $reader.objectEnd()
                    val result = ${ constructFromTerms(fieldParamRefs) }
                    $reader.clearFields($nExpr)
                    result
                }

                // Loop step: `if !hasNextField then terminal else iter`. Self-recursive tail call lives in `iterExpr`
                // (both fast-path arms and every name-chain arm end in `loop(...)`).
                val rhs: Expr[A] = '{
                    if $reader.hasNextField() then $iterExpr
                    else $terminalExpr
                }
                Some(rhs.asTerm.changeOwner(loopSym))
        )

        // Outer body: open the object, initialize pooled-reader state, call `loop` with field defaults / primitive
        // zeros / null placeholders and `seen = 0L`, `expectedIdx = 0`.
        val initialArgs: List[Term] =
            fields.indices.toList.map(i => fieldInitTerm(i)) ++
                List(Literal(LongConstant(0L)), Literal(IntConstant(0)))
        val initialCall: Term = Apply(Ref(loopSym), initialArgs)

        val bodyExpr: Expr[A] = '{
            val _       = $reader.objectStart()
            val nFields = $nExpr
            // `initFields` is purely for pooled-reader lifecycle (e.g. JsonReader depth tracking). Result is discarded.
            val _ = $reader.initFields(nFields)
            ${ initialCall.asExprOf[A] }
        }

        Block(List(loopDef), bodyExpr.asTerm).asExprOf[A]
    end caseClassReadBodyResolved

    /** Generate read body for a case class (convenience for simple non-recursive types).
      *
      * Wraps direct schema expressions as resolvers and delegates to caseClassReadBodyResolved.
      */
    private[internal] def caseClassReadBody[A: Type](using
        Quotes
    )(
        reader: Expr[Reader],
        fieldBytesExpr: Expr[Array[Array[Byte]]],
        fieldNamesExpr: Expr[Array[String]],
        schemaExprs: List[(String, Expr[Schema[Any]])],
        subSchemasExpr: Expr[Array[kyo.Schema[Any]]]
    ): Expr[A] =
        val resolvers: List[(String, SchemaResolver[A])] = schemaExprs.map { (name, schemaExpr) =>
            (name, (_: Expr[Schema[A]]) => schemaExpr)
        }
        // For simple types, selfSchema is never used by the resolvers, so pass a null placeholder
        caseClassReadBodyResolved[A](reader, fieldBytesExpr, fieldNamesExpr, resolvers, subSchemasExpr, '{ null.asInstanceOf[Schema[A]] })
    end caseClassReadBody

    /** Generate read body for sealed trait.
      *
      * Mirrors the case-class field dispatch mechanism: the generated code calls `reader.fieldParse()` to advance past the discriminator
      * and then tries `reader.matchField(variantBytes)` for each known variant. This keeps the read path uniform between wire formats —
      * protobuf's `matchField` compares `CodecMacro.fieldId(name)` against the integer tag, while JSON's compares raw UTF-8 bytes — so a
      * top-level `Protobuf.decode[SealedTrait]` now works without needing a field-name map to be installed on the reader.
      */
    private[internal] def sealedReadBody[A: Type](using
        Quotes
    )(
        reader: Expr[Reader],
        fieldBytesExpr: Expr[Array[Array[Byte]]],
        childSchemas: List[(String, Expr[Schema[Any]])]
    ): Expr[A] =

        '{
            kyo.discard($reader.objectStart())
            if ! $reader.hasNextField() then
                throw kyo.MissingFieldException(Seq.empty, "<discriminator>")(using $reader.frame)
            val fieldBytes = $fieldBytesExpr
            $reader.fieldParse()
            val result: A = ${
                val checks = childSchemas.zipWithIndex.map { case ((_, schemaExpr), i) =>
                    (
                        '{ $reader.matchField(fieldBytes(${ Expr(i) })) },
                        '{ $schemaExpr.serializeRead($reader).asInstanceOf[A] }
                    )
                }
                checks.foldRight('{
                    val variantName = $reader.lastFieldName()
                    $reader.skip()
                    throw kyo.UnknownVariantException(Seq.empty, variantName)(using $reader.frame)
                }: Expr[A]) { (check, elseExpr) =>
                    val (cond, body) = check
                    '{ if $cond then $body else $elseExpr }
                }
            }
            $reader.objectEnd()
            result
        }
    end sealedReadBody

    /** Checks if a type is serializable without triggering inline given derived.
      *
      * Returns true for:
      *   - Primitive types (String, Int, Long, Double, Float, Boolean, Short, Byte, Char, BigInt, BigDecimal)
      *   - java.time.Instant, java.time.Duration
      *   - Span[Byte]
      *   - Known container types with serializable inner type (List, Vector, Set, Chunk, Maybe, Option, Map[String, V], Dict)
      *   - Case classes and sealed traits (will be handled by Schema.derived)
      */
    private[internal] def isSerializableType(using Quotes)(tpe: quotes.reflect.TypeRepr): Boolean =
        import quotes.reflect.*

        given CanEqual[Symbol, Symbol] = CanEqual.derived

        // Primitive types with built-in schemas
        val primitiveSymbols = Set(
            TypeRepr.of[String].typeSymbol,
            TypeRepr.of[Int].typeSymbol,
            TypeRepr.of[Long].typeSymbol,
            TypeRepr.of[Double].typeSymbol,
            TypeRepr.of[Float].typeSymbol,
            TypeRepr.of[Boolean].typeSymbol,
            TypeRepr.of[Short].typeSymbol,
            TypeRepr.of[Byte].typeSymbol,
            TypeRepr.of[Char].typeSymbol,
            TypeRepr.of[BigInt].typeSymbol,
            TypeRepr.of[BigDecimal].typeSymbol,
            TypeRepr.of[java.time.Instant].typeSymbol,
            TypeRepr.of[java.time.Duration].typeSymbol,
            TypeRepr.of[kyo.Frame].typeSymbol
        )

        // Container type constructors that need inner type checked
        // NOTE: Only include types that have corresponding Schema[Container[A]] givens in Schema companion
        val containerSymbols = Set(
            TypeRepr.of[List].typeSymbol,
            TypeRepr.of[Vector].typeSymbol,
            TypeRepr.of[Set].typeSymbol,
            // NOT Seq - there's no Schema[Seq[A]] given
            TypeRepr.of[kyo.Chunk].typeSymbol,
            TypeRepr.of[kyo.Maybe].typeSymbol,
            TypeRepr.of[Option].typeSymbol,
            TypeRepr.of[kyo.Result].typeSymbol
        )

        // Map-like types
        val mapSymbols = Set(
            TypeRepr.of[Map].typeSymbol,
            TypeRepr.of[kyo.Dict].typeSymbol
        )

        // Use a mutable set to track visited types and avoid infinite recursion
        val visited = scala.collection.mutable.Set[Symbol]()

        def check(t: TypeRepr): Boolean =
            val dealiased = t.dealias
            val sym       = dealiased.typeSymbol

            // Check if it's a primitive
            if primitiveSymbols.contains(sym) then
                true
            // Check Span[Byte]
            else if dealiased =:= TypeRepr.of[kyo.Span[Byte]] then
                true
            // Check Tag[A] - always serializable
            else if sym == TypeRepr.of[kyo.Tag].typeSymbol then
                true
            // Check container types with single type parameter
            else
                dealiased match
                    case AppliedType(tycon, List(inner)) if containerSymbols.contains(tycon.typeSymbol) =>
                        check(inner)
                    case AppliedType(tycon, List(key, value)) if mapSymbols.contains(tycon.typeSymbol) =>
                        // For Map/Dict, check both key and value
                        check(key) && check(value)
                    case AppliedType(tycon, List(err, success)) if tycon.typeSymbol == TypeRepr.of[kyo.Result].typeSymbol =>
                        // Result[E, A] needs both E and A serializable
                        check(err) && check(success)
                    case _ =>
                        // Check if it's a case class or sealed trait
                        if sym.isClassDef && sym.flags.is(Flags.Case) then
                            // Avoid infinite recursion for recursive types
                            if visited.contains(sym) then true
                            else
                                visited += sym
                                // For case classes, recursively check all fields
                                sym.caseFields.forall { field =>
                                    check(dealiased.memberType(field))
                                }
                            end if
                        else if sym.flags.is(Flags.Sealed) then
                            // Avoid infinite recursion for recursive types
                            if visited.contains(sym) then true
                            else
                                visited += sym
                                // For sealed traits, check all children
                                sym.children.forall { child =>
                                    check(child.typeRef)
                                }
                            end if
                        else
                            false
                        end if
                end match
            end if
        end check

        check(tpe)
    end isSerializableType

    /** Checks if a type contains (references) another type, for detecting recursion. Also checks case class field types for enum variants
      * that reference the parent sealed trait.
      */
    private[internal] def containsType(using
        Quotes
    )(
        haystack: quotes.reflect.TypeRepr,
        needle: quotes.reflect.TypeRepr
    ): Boolean =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived
        val visited                    = scala.collection.mutable.Set[Symbol]()

        def loop(t: TypeRepr): Boolean =
            val h = t.dealias
            if h =:= needle then true
            else
                h match
                    case AppliedType(_, args) => args.exists(loop)
                    case AndType(l, r)        => loop(l) || loop(r)
                    case OrType(l, r)         => loop(l) || loop(r)
                    case _                    =>
                        // Also check case class fields for recursive references, guarding against
                        // mutually-recursive types (e.g. A has a field of B, B has a field of A).
                        val sym = h.typeSymbol
                        if sym.isClassDef && sym.flags.is(Flags.Case) then
                            if visited.contains(sym) then false
                            else
                                visited += sym
                                sym.caseFields.exists { field =>
                                    loop(h.memberType(field))
                                }
                            end if
                        else false
                        end if
                end match
            end if
        end loop

        loop(haystack)
    end containsType

end SerializationMacro
