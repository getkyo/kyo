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
        selfSchema: Expr[Schema[A]],
        value: Expr[A],
        writer: Expr[Writer]
    ): Expr[Unit] =
        import quotes.reflect.*
        '{
            $writer.objectStart(${ Expr(typeName) }, ${ Expr(n) })
            ${
                val stmts = fieldNames.zipWithIndex.flatMap { (fieldName, idx) =>
                    val fieldAccess = Select.unique(value.asTerm, fieldName).asExprOf[Any]
                    val schemaExpr  = fieldSchemaResolvers(idx)(selfSchema)
                    val fid         = fieldIds(idx)._2

                    if maybeFields.contains(idx) then
                        val maybeAccess = fieldAccess.asExprOf[kyo.Maybe[Any]]
                        List('{
                            $maybeAccess match
                                case kyo.Present(innerVal) =>
                                    $writer.fieldBytes($fieldBytes(${ Expr(idx) }), ${ Expr(fid) })
                                    $schemaExpr.serializeWrite.get(innerVal, $writer)
                                case _ => ()
                        })
                    else if optionFields.contains(idx) then
                        val optAccess = fieldAccess.asExprOf[Option[Any]]
                        List('{
                            if $optAccess.isDefined then
                                $writer.fieldBytes($fieldBytes(${ Expr(idx) }), ${ Expr(fid) })
                                $schemaExpr.serializeWrite.get($optAccess, $writer)
                        })
                    else
                        List(
                            '{ $writer.fieldBytes($fieldBytes(${ Expr(idx) }), ${ Expr(fid) }) },
                            '{ $schemaExpr.serializeWrite.get($fieldAccess, $writer) }
                        )
                    end if
                }
                if stmts.isEmpty then '{ () } else Expr.block(stmts.init, stmts.last)
            }
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
                    ${ info.schemaResolver(selfSchema) }.serializeWrite.get(${ info.castExpr(value) }, $writer)
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
      */
    private[internal] def caseClassReadBodyResolved[A: Type](using
        Quotes
    )(
        reader: Expr[Reader],
        fieldBytesExpr: Expr[Array[Array[Byte]]],
        fieldSchemaResolvers: List[(String, SchemaResolver[A])],
        selfSchema: Expr[Schema[A]]
    ): Expr[A] =
        import quotes.reflect.*

        val tpe      = TypeRepr.of[A].dealias
        val sym      = tpe.typeSymbol
        val fields   = sym.caseFields
        val n        = fields.size
        val defaults = CodecMacro.caseClassDefaultsPublic(tpe)

        // Detect Maybe/Option fields
        val (maybeFields, optionFields) = MacroUtils.detectMaybeOptionFields(tpe, fields)

        // Resolve all schemas using the selfSchema
        val schemaExprs: List[(String, Expr[Schema[Any]])] = fieldSchemaResolvers.map { (name, resolver) =>
            (name, resolver(selfSchema))
        }

        // Pre-build default initializers
        val defaultInits: List[(Int, Expr[Any])] =
            fields.zipWithIndex.flatMap { (field, idx) =>
                defaults.get(field.name) match
                    case Some(d) => Some((idx, d))
                    case None if maybeFields.contains(idx) =>
                        Some((idx, '{ kyo.Maybe.empty }))
                    case None if optionFields.contains(idx) =>
                        Some((idx, '{ None }))
                    case None => None
            }

        // Pre-build required field checks
        val requiredFields: List[(Int, String)] =
            fields.zipWithIndex.flatMap { (field, idx) =>
                if defaults.contains(field.name) || maybeFields.contains(idx) || optionFields.contains(idx) then None
                else Some((idx, field.name))
            }

        '{
            val _          = $reader.objectStart()
            val fieldBytes = $fieldBytesExpr
            val values     = $reader.initFields(${ Expr(n) })
            val nFields    = ${ Expr(n) }

            ${
                if defaultInits.isEmpty then '{ () }
                else
                    val stmts = defaultInits.map { (idx, defaultExpr) =>
                        '{ values(${ Expr(idx) }) = $defaultExpr.asInstanceOf[AnyRef] }
                    }
                    Expr.block(stmts.init, stmts.last)
            }

            var expectedIdx = 0

            @scala.annotation.tailrec
            def loop(): Unit =
                if $reader.hasNextField() then
                    $reader.fieldParse()
                    var idx = -1
                    if expectedIdx < nFields && $reader.matchField(fieldBytes(expectedIdx)) then
                        idx = expectedIdx
                        expectedIdx += 1
                    else
                        ${
                            val checks = schemaExprs.zipWithIndex.map { case ((_, _), i) =>
                                '{ $reader.matchField(fieldBytes(${ Expr(i) })) } -> Expr(i)
                            }
                            checks.foldRight('{ idx = -1 }: Expr[Unit]) { case ((cond, idxVal), elseExpr) =>
                                '{ if $cond then idx = $idxVal else $elseExpr }
                            }
                        }
                    end if
                    if idx == -1 then
                        $reader.skip()
                    else
                        ${
                            val branches = schemaExprs.zipWithIndex.map { case ((_, schemaExpr), i) =>
                                val readExpr =
                                    if maybeFields.contains(i) then
                                        '{ kyo.Present($schemaExpr.serializeRead.get($reader)).asInstanceOf[AnyRef] }
                                    else
                                        '{ $schemaExpr.serializeRead.get($reader).asInstanceOf[AnyRef] }
                                Expr(i) -> '{ values(${ Expr(i) }) = $readExpr }
                            }
                            branches.foldRight('{ () }: Expr[Unit]) { case ((idxVal, body), elseExpr) =>
                                '{ if idx == $idxVal then $body else $elseExpr }
                            }
                        }
                    end if
                    loop()
            loop()

            $reader.objectEnd()

            ${
                if requiredFields.isEmpty then '{ () }
                else
                    val checks = requiredFields.map { (idx, name) =>
                        '{
                            if values(${ Expr(idx) }) == null then
                                throw kyo.MissingFieldException(Seq.empty, ${ Expr(name) })(using $reader.frame)
                        }
                    }
                    Expr.block(checks.init, checks.last)
            }

            val result = ${
                // Cannot use MacroUtils.constructCaseClass here: sym/tpe/args are bound to the outer
                // Quotes instance, but this splice introduces a new Quotes context, causing
                // path-dependent type mismatch.
                val companion = Ref(sym.companionModule)
                val typeArgs = tpe match
                    case AppliedType(_, args) => args
                    case _                    => List.empty
                val args: List[Term] = fields.zipWithIndex.map { (field, idx) =>
                    val fieldType = tpe.memberType(field)
                    fieldType.asType match
                        case '[t] =>
                            '{ values(${ Expr(idx) }).asInstanceOf[t] }.asTerm
                }
                Select.overloaded(companion, "apply", typeArgs, args).asExprOf[A]
            }
            $reader.clearFields(nFields)
            result
        }
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
        schemaExprs: List[(String, Expr[Schema[Any]])]
    ): Expr[A] =
        val resolvers: List[(String, SchemaResolver[A])] = schemaExprs.map { (name, schemaExpr) =>
            (name, (_: Expr[Schema[A]]) => schemaExpr)
        }
        // For simple types, selfSchema is never used by the resolvers, so pass a null placeholder
        caseClassReadBodyResolved[A](reader, fieldBytesExpr, resolvers, '{ null.asInstanceOf[Schema[A]] })
    end caseClassReadBody

    /** Generate read body for sealed trait. */
    private[internal] def sealedReadBody[A: Type](using
        Quotes
    )(
        reader: Expr[Reader],
        childSchemas: List[(String, Expr[Schema[Any]])]
    ): Expr[A] =

        '{
            kyo.discard($reader.objectStart())
            if ! $reader.hasNextField() then
                throw kyo.MissingFieldException(Seq.empty, "<discriminator>")(using $reader.frame)
            val variantName = $reader.field()
            val result: A = ${
                val checks = childSchemas.map { (childName, schemaExpr) =>
                    (
                        '{ variantName == ${ Expr(childName) } },
                        '{ $schemaExpr.serializeRead.get($reader).asInstanceOf[A] }
                    )
                }
                checks.foldRight('{
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
        val h = haystack.dealias
        if h =:= needle then true
        else
            h match
                case AppliedType(_, args) => args.exists(containsType(_, needle))
                case AndType(l, r)        => containsType(l, needle) || containsType(r, needle)
                case OrType(l, r)         => containsType(l, needle) || containsType(r, needle)
                case _                    =>
                    // Also check case class fields for recursive references
                    val sym = h.typeSymbol
                    if sym.isClassDef && sym.flags.is(Flags.Case) then
                        sym.caseFields.exists { field =>
                            val fieldType = h.memberType(field)
                            containsType(fieldType, needle)
                        }
                    else false
                    end if
        end if
    end containsType

end SerializationMacro
