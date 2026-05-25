package kyo.internal

import kyo.*
import scala.quoted.*

/** Macro implementation for Reflect.symbolToRecord[F].
  *
  * Walks the field intersection type F, maps each field name to the corresponding Symbol accessor, and emits a for/yield expression that
  * sequences effectful accessors (parents, declarations, typeParams, companion, declaredType) and builds a Record[F] via Record.init.
  *
  * Compile-time errors:
  *   - Unrecognized field name: report.errorAndAbort listing all valid names
  *   - Field value type mismatch: report.errorAndAbort with the expected type
  *
  * Place: kyo.internal (flat, matching ReflectMacro/TagMacro precedent).
  */
object SymbolToRecordMacro:

    def symbolToRecordImpl[F: scala.quoted.Type](sym: Expr[kyo.Reflect.Symbol])(using
        q: Quotes
    )
        : Expr[kyo.Record[F] < (kyo.Sync & kyo.Abort[kyo.ReflectError])] =
        import quotes.reflect.*

        val validFieldNames = List(
            "name",
            "binaryName",
            "flags",
            "kind",
            "owner",
            "isInline",
            "isContextual",
            "isOpaque",
            "isPackageObject",
            "isModule",
            "isJava",
            "declaredType",
            "parents",
            "typeParams",
            "declarations",
            "companion",
            "javaSpecific"
        )

        val effectfulNames = Set("declaredType", "parents", "typeParams", "declarations", "companion")

        // Decompose the intersection type F into individual (fieldName, valueType) pairs.
        // Mirrors FieldsMacros.deriveImpl decompose logic.
        def decompose(tpe: TypeRepr): Vector[(String, TypeRepr)] =
            tpe.dealias match
                case AndType(l, r) => decompose(l) ++ decompose(r)
                case other =>
                    if other =:= TypeRepr.of[Any] then Vector.empty
                    else
                        other match
                            case AppliedType(_, List(ConstantType(StringConstant(name)), valueType)) =>
                                Vector((name, valueType))
                            case _ =>
                                try
                                    other.typeSymbol.tree match
                                        case td: TypeDef =>
                                            td.rhs match
                                                case bounds: TypeBoundsTree =>
                                                    val hi = bounds.hi.tpe
                                                    if !(hi =:= TypeRepr.of[Any]) then decompose(hi)
                                                    else Vector.empty
                                                case _ => Vector.empty
                                        case _ => Vector.empty
                                catch
                                    case _: Exception => Vector.empty

        val fields: Vector[(String, TypeRepr)] = decompose(TypeRepr.of[F])

        // If F = Any (no fields), return Record.empty
        if fields.isEmpty then
            val fRepr      = TypeRepr.of[F]
            val isAbstract = fRepr.typeSymbol.isTypeParam || fRepr.typeSymbol.flags.is(Flags.Deferred)
            if !isAbstract && !(fRepr =:= TypeRepr.of[Any]) then
                report.errorAndAbort(s"Record[F] requires F to be Any when empty, got: ${fRepr.show}")
            return '{ kyo.Kyo.lift[kyo.Record[F], kyo.Sync & kyo.Abort[kyo.ReflectError]](kyo.Record.empty.asInstanceOf[kyo.Record[F]]) }
        end if

        // Validate each field: name must be known, value type must match expected
        for (fieldName, valueType) <- fields do
            fieldName match
                case "name" =>
                    if !(valueType =:= TypeRepr.of[kyo.Reflect.Name]) then
                        report.errorAndAbort(
                            s"""Reflect.symbolToRecord: field "name" has declared type ${valueType.show} but the Symbol.name accessor returns Reflect.Name. Use "name" ~ Reflect.Name."""
                        )
                case "binaryName" =>
                    if !(valueType =:= TypeRepr.of[String]) then
                        report.errorAndAbort(
                            s"""Reflect.symbolToRecord: field "binaryName" has declared type ${valueType.show} but the Symbol.binaryName accessor returns String. Use "binaryName" ~ String."""
                        )
                case "flags" =>
                    if !(valueType =:= TypeRepr.of[kyo.Reflect.Flags]) then
                        report.errorAndAbort(
                            s"""Reflect.symbolToRecord: field "flags" has declared type ${valueType.show} but the Symbol.flags accessor returns Reflect.Flags. Use "flags" ~ Reflect.Flags."""
                        )
                case "kind" =>
                    if !(valueType =:= TypeRepr.of[kyo.Reflect.SymbolKind]) then
                        report.errorAndAbort(
                            s"""Reflect.symbolToRecord: field "kind" has declared type ${valueType.show} but the Symbol.kind accessor returns Reflect.SymbolKind. Use "kind" ~ Reflect.SymbolKind."""
                        )
                case "owner" =>
                    if !(valueType =:= TypeRepr.of[kyo.Reflect.Symbol]) then
                        report.errorAndAbort(
                            s"""Reflect.symbolToRecord: field "owner" has declared type ${valueType.show} but the Symbol.owner accessor returns Reflect.Symbol. Use "owner" ~ Reflect.Symbol."""
                        )
                case "isInline" | "isContextual" | "isOpaque" | "isPackageObject" | "isModule" | "isJava" =>
                    if !(valueType =:= TypeRepr.of[Boolean]) then
                        report.errorAndAbort(
                            s"""Reflect.symbolToRecord: field "$fieldName" has declared type ${valueType.show} but the Symbol.$fieldName accessor returns Boolean. Use "$fieldName" ~ Boolean."""
                        )
                case "declaredType" =>
                    if !(valueType =:= TypeRepr.of[kyo.Reflect.Type]) then
                        report.errorAndAbort(
                            s"""Reflect.symbolToRecord: field "declaredType" has declared type ${valueType.show} but the Symbol.declaredType accessor returns Reflect.Type. Use "declaredType" ~ Reflect.Type."""
                        )
                case "parents" =>
                    if !(valueType =:= TypeRepr.of[kyo.Chunk[kyo.Reflect.Type]]) then
                        report.errorAndAbort(
                            s"""Reflect.symbolToRecord: field "parents" has declared type ${valueType.show} but the Symbol.parents accessor returns Chunk[Reflect.Type]. Use "parents" ~ Chunk[Reflect.Type]."""
                        )
                case "typeParams" =>
                    if !(valueType =:= TypeRepr.of[kyo.Chunk[kyo.Reflect.Symbol]]) then
                        report.errorAndAbort(
                            s"""Reflect.symbolToRecord: field "typeParams" has declared type ${valueType.show} but the Symbol.typeParams accessor returns Chunk[Reflect.Symbol]. Use "typeParams" ~ Chunk[Reflect.Symbol]."""
                        )
                case "declarations" =>
                    if !(valueType =:= TypeRepr.of[kyo.Chunk[kyo.Reflect.Symbol]]) then
                        report.errorAndAbort(
                            s"""Reflect.symbolToRecord: field "declarations" has declared type ${valueType.show} but the Symbol.declarations accessor returns Chunk[Reflect.Symbol]. Use "declarations" ~ Chunk[Reflect.Symbol]."""
                        )
                case "companion" =>
                    if !(valueType =:= TypeRepr.of[kyo.Maybe[kyo.Reflect.Symbol]]) then
                        report.errorAndAbort(
                            s"""Reflect.symbolToRecord: field "companion" has declared type ${valueType.show} but the Symbol.companion accessor returns Maybe[Reflect.Symbol]. Use "companion" ~ Maybe[Reflect.Symbol]."""
                        )
                case "javaSpecific" =>
                    if !(valueType =:= TypeRepr.of[kyo.Maybe[kyo.Reflect.JavaMetadata]]) then
                        report.errorAndAbort(
                            s"""Reflect.symbolToRecord: field "javaSpecific" has declared type ${valueType.show} but the Symbol.javaSpecific accessor returns Maybe[Reflect.JavaMetadata]. Use "javaSpecific" ~ Maybe[Reflect.JavaMetadata]."""
                        )
                case other =>
                    report.errorAndAbort(
                        s"""Reflect.symbolToRecord: field "$other" is not a known Symbol accessor.
                           |Valid field names: ${validFieldNames.mkString(", ")}.""".stripMargin
                    )
        end for

        // Helper: emit a pure accessor expression for a given field name (all non-effectful fields)
        def pureExpr(fieldName: String): Expr[Any] =
            fieldName match
                case "name"            => '{ $sym.name }
                case "binaryName"      => '{ $sym.binaryName }
                case "flags"           => '{ $sym.flags }
                case "kind"            => '{ $sym.kind }
                case "owner"           => '{ $sym.owner }
                case "isInline"        => '{ $sym.isInline }
                case "isContextual"    => '{ $sym.isContextual }
                case "isOpaque"        => '{ $sym.isOpaque }
                case "isPackageObject" => '{ $sym.isPackageObject }
                case "isModule"        => '{ $sym.isModule }
                case "isJava"          => '{ $sym.isJava }
                case "javaSpecific"    => '{ $sym.javaSpecific }
                case other             => report.errorAndAbort(s"Bug: unhandled pure field $other")

        // Build a Record[F] from a map of field name -> Expr[Any]
        def buildRecord(resolvedFields: Map[String, Expr[Any]]): Expr[kyo.Record[F]] =
            val pairExprs: List[Expr[(String, Any)]] = fields.map { (name, _) =>
                val v = resolvedFields(name)
                '{ (${ Expr(name) }, ${ v }) }
            }.toList
            val dictExpr: Expr[kyo.Dict[String, Any]] = pairExprs match
                case Nil =>
                    '{ kyo.Dict.empty[String, Any] }
                case head :: tail =>
                    tail.foldLeft[Expr[kyo.Dict[String, Any]]](
                        '{ kyo.Dict[String, Any](${ head }._1 -> ${ head }._2) }
                    ) { (acc, pair) =>
                        '{ $acc ++ kyo.Dict[String, Any](${ pair }._1 -> ${ pair }._2) }
                    }
            '{ kyo.Record.init[F]($dictExpr) }
        end buildRecord

        // Recursively build a flatMap chain for effectful fields, yielding the record at the end.
        def buildChain(
            remaining: List[String],
            boundEffectful: Map[String, Expr[Any]]
        ): Expr[kyo.Record[F] < (kyo.Sync & kyo.Abort[kyo.ReflectError])] =
            remaining match
                case Nil =>
                    val resolvedFields: Map[String, Expr[Any]] = fields.map { (name, _) =>
                        if boundEffectful.contains(name) then name -> boundEffectful(name)
                        else name                                  -> pureExpr(name)
                    }.toMap
                    val recordExpr = buildRecord(resolvedFields)
                    '{ kyo.Kyo.lift[kyo.Record[F], kyo.Sync & kyo.Abort[kyo.ReflectError]]($recordExpr) }

                case fieldName :: rest =>
                    fieldName match
                        case "declaredType" =>
                            '{
                                $sym.declaredType(using $frameExpr).flatMap { (v: kyo.Reflect.Type) =>
                                    ${ buildChain(rest, boundEffectful + (fieldName -> '{ v })) }
                                }(using $frameExpr)
                            }
                        case "parents" =>
                            '{
                                $sym.parents(using $frameExpr).flatMap { (v: kyo.Chunk[kyo.Reflect.Type]) =>
                                    ${ buildChain(rest, boundEffectful + (fieldName -> '{ v })) }
                                }(using $frameExpr)
                            }
                        case "typeParams" =>
                            '{
                                $sym.typeParams(using $frameExpr).flatMap { (v: kyo.Chunk[kyo.Reflect.Symbol]) =>
                                    ${ buildChain(rest, boundEffectful + (fieldName -> '{ v })) }
                                }(using $frameExpr)
                            }
                        case "declarations" =>
                            '{
                                $sym.declarations(using $frameExpr).flatMap { (v: kyo.Chunk[kyo.Reflect.Symbol]) =>
                                    ${ buildChain(rest, boundEffectful + (fieldName -> '{ v })) }
                                }(using $frameExpr)
                            }
                        case "companion" =>
                            '{
                                $sym.companion(using $frameExpr).flatMap { (v: kyo.Maybe[kyo.Reflect.Symbol]) =>
                                    ${ buildChain(rest, boundEffectful + (fieldName -> '{ v })) }
                                }(using $frameExpr)
                            }
                        case other =>
                            report.errorAndAbort(s"Bug: unhandled effectful field in chain $other")

        // Summon Frame after field validation so field errors fire first.
        // Only needed when there are effectful fields; safe to summon here since
        // any error path above already aborted.
        lazy val frameExpr: Expr[kyo.Frame] = Expr.summon[kyo.Frame].getOrElse(
            report.errorAndAbort(
                "No Frame in scope at Reflect.symbolToRecord call site. Add `(using Frame)` to the enclosing def."
            )
        )

        val effectfulInOrder: List[String] = fields.map(_._1).filter(effectfulNames.contains).toList

        if effectfulInOrder.isEmpty then
            // All pure: build record directly and lift
            val resolvedFields: Map[String, Expr[Any]] = fields.map { (name, _) =>
                name -> pureExpr(name)
            }.toMap
            val recordExpr = buildRecord(resolvedFields)
            '{ kyo.Kyo.lift[kyo.Record[F], kyo.Sync & kyo.Abort[kyo.ReflectError]]($recordExpr) }
        else
            buildChain(effectfulInOrder, Map.empty)
        end if
    end symbolToRecordImpl

end SymbolToRecordMacro
