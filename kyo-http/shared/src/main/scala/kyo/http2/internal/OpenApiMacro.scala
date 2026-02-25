package kyo.http2.internal

import kyo.Result
import kyo.http2.HttpCodec
import kyo.http2.HttpPath
import kyo.http2.OpenApi
import kyo.http2.Schema
import scala.quoted.*

private[http2] object OpenApiMacro:

    def deriveFromStringImpl(spec: Expr[String])(using Quotes): Expr[Any] =
        import quotes.reflect.*
        generateRoutes(parseSpec(spec.valueOrAbort, ""))

    def deriveImpl(path: Expr[String])(using Quotes): Expr[Any] =
        import quotes.reflect.*

        val filePath   = path.valueOrAbort
        val sourcePath = Position.ofMacroExpansion.sourceFile.getJPath
        val sourceDir  = sourcePath.map(_.getParent)

        val resolved = sourceDir match
            case Some(dir) => dir.resolve(filePath)
            case None      => java.nio.file.Paths.get(filePath)

        if !java.nio.file.Files.exists(resolved) then
            report.errorAndAbort(s"OpenAPI spec file not found: $resolved")

        val jsonStr = new String(java.nio.file.Files.readAllBytes(resolved), "UTF-8")
        generateRoutes(parseSpec(jsonStr, s" at $resolved"))
    end deriveImpl

    private def parseSpec(jsonStr: String, context: String)(using Quotes): OpenApi =
        import quotes.reflect.*
        Schema[OpenApi].decode(jsonStr) match
            case Result.Success(v) => v
            case Result.Failure(e) => report.errorAndAbort(s"Failed to parse OpenAPI spec$context: $e")
            case Result.Panic(ex)  => report.errorAndAbort(s"Failed to parse OpenAPI spec$context: ${ex.getMessage}")
        end match
    end parseSpec

    private def generateRoutes(openApi: OpenApi)(using Quotes): Expr[Any] =
        import quotes.reflect.*

        val operations =
            for
                (pathTemplate, pathItem) <- openApi.paths.toList
                (method, operation)      <- extractOperations(pathItem)
            yield (
                operation.operationId.getOrElse(generateOperationName(method, pathTemplate)),
                method,
                pathTemplate,
                operation
            )

        if operations.isEmpty then
            report.errorAndAbort("OpenAPI spec contains no operations")

        val names = operations.map(_._1)
        val dupes = names.diff(names.distinct)
        if dupes.nonEmpty then
            report.errorAndAbort(s"Duplicate operation names: ${dupes.mkString(", ")}")

        val routeTerms = operations.map((name, method, path, op) => buildRoute(name, method, path, op))

        routeTerms.reduce { (left, right) =>
            (innerType(left), innerType(right)) match
                case ('[l], '[r]) =>
                    '{ ${ left.asExprOf[kyo.Record2[l]] } & ${ right.asExprOf[kyo.Record2[r]] } }.asTerm
        }.asExpr
    end generateRoutes

    private def buildRoute(name: String, method: String, pathTemplate: String, operation: OpenApi.Operation)(using
        Quotes
    ): quotes.reflect.Term =
        import quotes.reflect.*

        val params       = operation.parameters.getOrElse(Nil)
        val nameExpr     = Expr(name)
        val pathTerm     = buildPath(pathTemplate, params)
        val queryParams  = params.filter(_.in == "query")
        val headerParams = params.filter(_.in == "header")

        innerType(pathTerm) match
            case '[pathType] =>
                val pathExpr = pathTerm.asExprOf[kyo.http2.HttpPath[pathType]]

                val methodExpr: Expr[kyo.http2.HttpMethod] = method match
                    case "GET"     => '{ kyo.http2.HttpMethod.GET }
                    case "POST"    => '{ kyo.http2.HttpMethod.POST }
                    case "PUT"     => '{ kyo.http2.HttpMethod.PUT }
                    case "DELETE"  => '{ kyo.http2.HttpMethod.DELETE }
                    case "PATCH"   => '{ kyo.http2.HttpMethod.PATCH }
                    case "HEAD"    => '{ kyo.http2.HttpMethod.HEAD }
                    case "OPTIONS" => '{ kyo.http2.HttpMethod.OPTIONS }
                    case _         => report.errorAndAbort(s"Unsupported HTTP method: $method")

                // Build RequestDef with fields
                val reqTerm = buildRequestDef(
                    pathTerm,
                    queryParams ++ headerParams
                )

                // Build ResponseDef
                val successResponse = operation.responses.get("200")
                    .orElse(operation.responses.get("201"))
                    .orElse(operation.responses.get("default"))
                val jsonResponseSchema =
                    for
                        resp    <- successResponse
                        content <- resp.content
                        media   <- content.get("application/json")
                    yield media.schema

                val respTerm = buildResponseDef(jsonResponseSchema)

                // Assemble HttpRoute
                (innerType(reqTerm), innerType(respTerm)) match
                    case ('[reqType], '[respType]) =>
                        val req   = reqTerm.asExprOf[kyo.http2.HttpRoute.RequestDef[reqType]]
                        val resp  = respTerm.asExprOf[kyo.http2.HttpRoute.ResponseDef[respType]]
                        val route = '{ kyo.http2.HttpRoute[reqType, respType, Nothing]($methodExpr, $req, $resp) }
                        '{ kyo.Record2.~($nameExpr)($route) }.asTerm
                end match
        end match
    end buildRoute

    /** Builds a RequestDef by constructing Field.Param instances directly. */
    private def buildRequestDef(using Quotes)(pathTerm: quotes.reflect.Term, params: List[OpenApi.Parameter]): quotes.reflect.Term =
        import quotes.reflect.*

        if params.isEmpty then
            return innerType(pathTerm) match
                case '[pathType] =>
                    val p = pathTerm.asExprOf[kyo.http2.HttpPath[pathType]]
                    '{ kyo.http2.HttpRoute.RequestDef[pathType]($p) }.asTerm
        end if

        // Build each field as an Expr, accumulating the intersection type
        var fieldExprs = List.empty[Expr[kyo.http2.HttpRoute.Field[?]]]
        var resultType = innerTypeRepr(pathTerm)

        for param <- params do
            val codecType = resolveCodecType(param.schema)
            val optional  = !param.required.getOrElse(false)
            val isQuery   = param.in == "query"
            val location = if isQuery then '{ kyo.http2.HttpRoute.Field.Param.Location.Query }
            else '{ kyo.http2.HttpRoute.Field.Param.Location.Header }

            val nameType = ConstantType(StringConstant(param.name))

            codecType.asType match
                case '[a] =>
                    val codec = Expr.summon[kyo.http2.HttpCodec[a]] match
                        case Some(c) => c
                        case None    => report.errorAndAbort(s"No HttpCodec found for type ${codecType.show}")
                    val nameExpr = Expr(param.name)
                    val fieldExpr =
                        if optional then
                            '{
                                kyo.http2.HttpRoute.Field.Param[String, a, kyo.Maybe[a]](
                                    $location,
                                    $nameExpr,
                                    "",
                                    $codec,
                                    kyo.Absent,
                                    true,
                                    ""
                                )
                            }
                        else
                            '{
                                kyo.http2.HttpRoute.Field.Param[String, a, a](
                                    $location,
                                    $nameExpr,
                                    "",
                                    $codec,
                                    kyo.Absent,
                                    false,
                                    ""
                                )
                            }
                    fieldExprs = fieldExprs :+ fieldExpr.asInstanceOf[Expr[kyo.http2.HttpRoute.Field[?]]]

                    // Accumulate type: In & "name" ~ A or In & "name" ~ Maybe[A]
                    val tildeType      = TypeRepr.of[kyo.Record2.~].appliedTo(List(nameType, codecType))
                    val tildeMaybeType = TypeRepr.of[kyo.Record2.~].appliedTo(List(nameType, TypeRepr.of[kyo.Maybe].appliedTo(codecType)))
                    resultType = AndType(resultType, if optional then tildeMaybeType else tildeType)
            end match
        end for

        // Construct RequestDef with the accumulated fields
        val fieldsExpr = fieldExprs.foldLeft('{ kyo.Chunk.empty[kyo.http2.HttpRoute.Field[?]] }) { (acc, field) =>
            '{ $acc.append($field) }
        }
        val pathExpr = pathTerm match
            case term =>
                innerType(term) match
                    case '[pathType] => term.asExprOf[kyo.http2.HttpPath[pathType]]

        val reqDefType = TypeRepr.of[kyo.http2.HttpRoute.RequestDef].appliedTo(resultType)
        val reqTerm = innerType(pathTerm) match
            case '[pathType] =>
                val p = pathTerm.asExprOf[kyo.http2.HttpPath[pathType]]
                '{
                    kyo.http2.HttpRoute.RequestDef[pathType](
                        $p,
                        $fieldsExpr.asInstanceOf[kyo.Chunk[kyo.http2.HttpRoute.Field[? >: pathType]]]
                    )
                }.asTerm

        Typed(reqTerm, Inferred(reqDefType))
    end buildRequestDef

    /** Builds a ResponseDef, optionally with a JSON body field. */
    private def buildResponseDef(jsonSchema: Option[OpenApi.SchemaObject])(using Quotes): quotes.reflect.Term =
        import quotes.reflect.*

        jsonSchema match
            case None =>
                '{ kyo.http2.HttpRoute.ResponseDef[Any]() }.asTerm
            case Some(schema) =>
                val codecType = resolveCodecType(schema)
                codecType.asType match
                    case '[a] =>
                        val s = Expr.summon[kyo.http2.Schema[a]] match
                            case Some(s) => s
                            case None    => report.errorAndAbort(s"No Schema found for type ${codecType.show}")

                        val bodyField = '{
                            kyo.http2.HttpRoute.Field.Body[String, a](
                                "body",
                                kyo.http2.HttpRoute.ContentType.Json($s),
                                ""
                            )
                        }
                        val fields = '{ kyo.Chunk.empty[kyo.http2.HttpRoute.Field[?]].append($bodyField) }
                        val respTerm = '{
                            kyo.http2.HttpRoute.ResponseDef[Any](
                                kyo.http2.HttpStatus.Success.OK,
                                $fields.asInstanceOf[kyo.Chunk[kyo.http2.HttpRoute.Field[? >: Any]]]
                            )
                        }.asTerm

                        val nameType    = ConstantType(StringConstant("body"))
                        val tildeType   = TypeRepr.of[kyo.Record2.~].appliedTo(List(nameType, TypeRepr.of[a]))
                        val resultType  = AndType(TypeRepr.of[Any], tildeType)
                        val respDefType = TypeRepr.of[kyo.http2.HttpRoute.ResponseDef].appliedTo(resultType)

                        Typed(respTerm, Inferred(respDefType))
                end match
        end match
    end buildResponseDef

    // ==================== Path building ====================

    private def buildPath(pathTemplate: String, params: List[OpenApi.Parameter])(using Quotes): quotes.reflect.Term =
        import quotes.reflect.*

        val segments = pathTemplate.split("/").filter(_.nonEmpty).toList

        if segments.isEmpty then
            '{ kyo.http2.HttpPath.Literal("/") }.asTerm
        else
            val pathParams = params.filter(_.in == "path")

            val segmentTerms = segments.map { seg =>
                if seg.startsWith("{") && seg.endsWith("}") then
                    buildCapture(seg.drop(1).dropRight(1), pathParams)
                else
                    '{ kyo.http2.HttpPath.Literal(${ Expr(seg) }) }.asTerm
            }

            segmentTerms.reduce { (left, right) =>
                (innerType(left), innerType(right)) match
                    case ('[l], '[r]) =>
                        '{ ${ left.asExprOf[kyo.http2.HttpPath[l]] } / ${ right.asExprOf[kyo.http2.HttpPath[r]] } }.asTerm
            }
        end if
    end buildPath

    private def buildCapture(paramName: String, pathParams: List[OpenApi.Parameter])(using Quotes): quotes.reflect.Term =
        import quotes.reflect.*

        val schema    = pathParams.find(_.name == paramName).map(_.schema)
        val codecType = schema.map(resolveCodecType).getOrElse(TypeRepr.of[String])

        val nameExpr  = Expr(paramName)
        val nameType  = ConstantType(StringConstant(paramName))
        val tildeType = TypeRepr.of[kyo.Record2.~].appliedTo(List(nameType, codecType))
        val pathType  = TypeRepr.of[kyo.http2.HttpPath].appliedTo(tildeType)

        codecType.asType match
            case '[a] =>
                val codec = Expr.summon[kyo.http2.HttpCodec[a]] match
                    case Some(c) => c
                    case None    => report.errorAndAbort(s"No HttpCodec found for type ${codecType.show}")
                Typed(
                    '{ kyo.http2.HttpPath.Capture[String, a]($nameExpr, "", $codec) }.asTerm,
                    Inferred(pathType)
                )
        end match
    end buildCapture

    // ==================== Helpers ====================

    /** Resolves an OpenAPI SchemaObject to a Scala TypeRepr. */
    private def resolveCodecType(schema: OpenApi.SchemaObject)(using Quotes): quotes.reflect.TypeRepr =
        import quotes.reflect.*
        (schema.`type`, schema.format) match
            case (Some("integer"), Some("int64")) => TypeRepr.of[Long]
            case (Some("integer"), _)             => TypeRepr.of[Int]
            case (Some("string"), Some("uuid"))   => TypeRepr.of[java.util.UUID]
            case (Some("string"), _)              => TypeRepr.of[String]
            case (Some("boolean"), _)             => TypeRepr.of[Boolean]
            case (Some("number"), _)              => TypeRepr.of[Double]
            case _                                => TypeRepr.of[String]
        end match
    end resolveCodecType

    /** Extracts the inner type A from a Term of type F[A] as a Type. */
    private def innerType(using Quotes)(term: quotes.reflect.Term): Type[?] =
        import quotes.reflect.*
        innerTypeRepr(term).asType

    /** Extracts the inner type A from a Term of type F[A] as a TypeRepr. */
    private def innerTypeRepr(using Quotes)(term: quotes.reflect.Term): quotes.reflect.TypeRepr =
        import quotes.reflect.*
        term.tpe.widen match
            case AppliedType(_, List(a)) => a
            case _                       => TypeRepr.of[Any]
    end innerTypeRepr

    private def extractOperations(pathItem: OpenApi.PathItem): List[(String, OpenApi.Operation)] =
        List(
            "GET"     -> pathItem.get,
            "POST"    -> pathItem.post,
            "PUT"     -> pathItem.put,
            "DELETE"  -> pathItem.delete,
            "PATCH"   -> pathItem.patch,
            "HEAD"    -> pathItem.head,
            "OPTIONS" -> pathItem.options
        ).collect { case (method, Some(op)) => (method, op) }

    private def generateOperationName(method: String, path: String): String =
        val parts = path.split("/").filter(_.nonEmpty).map { segment =>
            if segment.startsWith("{") && segment.endsWith("}") then
                val name = segment.drop(1).dropRight(1)
                name.take(1).toUpperCase + name.drop(1)
            else
                segment.take(1).toUpperCase + segment.drop(1)
        }
        method.toLowerCase + parts.mkString
    end generateOperationName

end OpenApiMacro
