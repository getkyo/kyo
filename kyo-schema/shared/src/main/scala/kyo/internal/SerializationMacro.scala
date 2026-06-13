package kyo.internal

import kyo.*
import kyo.Codec.Reader
import kyo.Codec.Writer
import kyo.Record.*
import scala.quoted.*

/** Shared serialization helpers used by `Schema.apply` and `Schema.derived`.
  *
  * Contains the write/read-body generators and type-checking utilities for both code paths.
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

        // Every non-Maybe / non-Option field routes through the resolved per-field sub-schema via
        // `SchemaSerializer.writeTo(_subSchemas(idx), value, writer)`. Maybe / Option keep their
        // wire-protocol skip branches (the on-wire shape omits absent values entirely; this is a
        // wire-format rule, not a type classifier).
        val writeStmts: List[Expr[Unit]] = fieldNames.zipWithIndex.flatMap { (fieldName, idx) =>
            val fieldAccess = Select.unique(value.asTerm, fieldName).asExprOf[Any]
            val fid         = fieldIds(idx)._2
            val idxExpr     = Expr(idx)
            val fidExpr     = Expr(fid)
            val schemaExpr  = '{ $subSchemasExpr($idxExpr) }

            if maybeFields.contains(idx) then
                val maybeAccess = fieldAccess.asExprOf[kyo.Maybe[Any]]
                List('{
                    $maybeAccess match
                        case kyo.Present(innerVal) =>
                            $writer.fieldBytes($fieldBytes($idxExpr), $fidExpr)
                            kyo.internal.SchemaSerializer.writeTo($schemaExpr, innerVal, $writer)(using kyo.Frame.internal)
                        case _ => ()
                })
            else if optionFields.contains(idx) then
                val optAccess = fieldAccess.asExprOf[Option[Any]]
                List('{
                    if $optAccess.isDefined then
                        $writer.fieldBytes($fieldBytes($idxExpr), $fidExpr)
                        kyo.internal.SchemaSerializer.writeTo($schemaExpr, $optAccess, $writer)(using kyo.Frame.internal)
                })
            else
                List(
                    '{ $writer.fieldBytes($fieldBytes($idxExpr), $fidExpr) },
                    '{ kyo.internal.SchemaSerializer.writeTo($schemaExpr, $fieldAccess, $writer)(using kyo.Frame.internal) }
                )
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

    /** Generate read body for a case class using SchemaResolver.
      *
      * Unified across simple and recursive paths. For simple types, pass resolvers that ignore the selfSchema parameter.
      *
      * Emits one typed parameter per field on a tail-recursive `loop` method; every non-Maybe field is read via
      * `SchemaSerializer.readFrom(_subSchemas(idx), reader)` and ascribed to the field type. Maybe[T] fields keep a
      * dedicated inner-null dispatch (the slot holds `Schema[T]`, not `Schema[Maybe[T]]`, so an explicit JSON null
      * would otherwise hit the non-nullable inner schema).
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
        val fieldTypes: List[TypeRepr] = fields.map(f => tpe.memberType(f))

        // Emit the decode body as a single tail-recursive `loop` method taking the field values as typed parameters
        // (plus `seen: Long` and `expectedIdx: Int`), returning the constructed case-class instance at its terminal
        // arm. No mutable vars, no `*Ref.create()` boxes, no intermediate `idx` write/read round-trip; the name-match
        // chain is fused with the read-action chain so each `matchField` arm performs the read + `seen`-bit update +
        // `expectedIdx` bump + tail call inline.
        val ownerSym = Symbol.spliceOwner

        // Initial value for field parameter `idx` at loop entry (user default / Maybe.empty / None / null ascribed to
        // the field type). For primitive field types `null.asInstanceOf[t]` lowers to the JVM default (0 / 0L / false /
        // ...); the value is observable only when the required-field bitmap check would have raised
        // [[MissingFieldException]] for an absent field but a [[Codec.Reader.droppedFieldsMask]] entry pre-satisfies
        // the slot (a dropped field), so the lowered default is the documented observable.
        def fieldInitTerm(idx: Int): Term =
            val field = fields(idx)
            val ft    = fieldTypes(idx)
            defaults.get(field.name) match
                case Some(d) =>
                    // User-supplied default: keep the expression as-is and ascribe to the field type.
                    ft.asType match
                        case '[t] => '{ ${ d }.asInstanceOf[t] }.asTerm
                case None if maybeFields.contains(idx) =>
                    ft.asType match
                        case '[t] => '{ kyo.Maybe.empty.asInstanceOf[t] }.asTerm
                case None if optionFields.contains(idx) =>
                    ft.asType match
                        case '[t] => '{ None.asInstanceOf[t] }.asTerm
                case None =>
                    ft.asType match
                        case '[t] => '{ null.asInstanceOf[t] }.asTerm
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

        // The emitted read for field `idx`: a typed `Expr[Any]` producing the fresh field value. Every non-Maybe field
        // routes through the resolved per-field sub-schema via
        // `SchemaSerializer.readFrom(_subSchemas(idx), $reader)`. Maybe keeps its inner-null dispatch because the slot
        // holds `Schema[T]` (the inner type) rather than `Schema[Maybe[T]]`: the macro strips the Maybe wrapper to
        // avoid double-wrapping the read result (see `summonFieldSchemaResolvers` in FocusMacro), so an explicit JSON
        // null would otherwise reach the non-nullable inner schema. When the inner is itself nullable
        // (`Maybe[Maybe[T]]` / `Maybe[Option[T]]`), defer to the inner schema's own nil handling so
        // `Present(Maybe.empty)` round-trips correctly instead of collapsing to `Maybe.empty`.
        def fieldReadExpr(idx: Int): Expr[Any] =
            val idxExpr: Expr[Int]            = Expr(idx)
            val schemaExpr: Expr[Schema[Any]] = '{ $subSchemasExpr($idxExpr) }
            val ft                            = fieldTypes(idx)
            val isMaybe                       = maybeFields.contains(idx)
            if isMaybe then
                val innerIsNullable: Boolean = ft match
                    case AppliedType(_, List(inner)) =>
                        inner <:< TypeRepr.of[kyo.Maybe[Any]] || inner <:< TypeRepr.of[Option[Any]]
                    case _ => false
                ft.asType match
                    case '[t] =>
                        if innerIsNullable then
                            '{
                                kyo.Present(kyo.internal.SchemaSerializer.readFrom(
                                    $schemaExpr,
                                    $reader
                                )(using $reader.frame)).asInstanceOf[t]
                            }
                        else
                            '{
                                if $reader.isNil() then kyo.Maybe.empty.asInstanceOf[t]
                                else
                                    kyo.Present(kyo.internal.SchemaSerializer.readFrom(
                                        $schemaExpr,
                                        $reader
                                    )(using $reader.frame)).asInstanceOf[t]
                            }
                end match
            else
                ft.asType match
                    case '[t] =>
                        '{
                            kyo.internal.SchemaSerializer.readFrom(
                                $schemaExpr,
                                $reader
                            )(using $reader.frame).asInstanceOf[t]
                        }
            end if
        end fieldReadExpr

        // Builds the DefDef for the tail-recursive `loop`. The `paramss` argument gives access to the parameter
        // trees (one list of N+2 `Tree`s) from which we derive `Ref`s typed at the field/seen/expectedIdx types.
        val loopDef: DefDef = DefDef(
            loopSym,
            paramss =>
                // paramss has shape List(List(<N field params> :+ seen :+ expectedIdx)): a single term-param clause.
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
      * and then tries `reader.matchField(variantBytes)` for each known variant. This keeps the read path uniform between wire formats:
      * protobuf's `matchField` compares `CodecMacro.fieldId(name)` against the integer tag, while JSON's compares raw UTF-8 bytes, so
      * top-level `Protobuf.decode[SealedTrait]` works without a field-name map installed on the reader.
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
