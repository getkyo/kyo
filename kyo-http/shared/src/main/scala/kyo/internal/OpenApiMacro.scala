package kyo.internal

import kyo.*
import kyo.Record2.~
import scala.quoted.*

private[kyo] object OpenApiMacro:

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
            innerType(left) match
                case '[l] =>
                    innerType(right) match
                        case '[r] =>
                            '{ ${ left.asExprOf[kyo.Record2[l]] } & ${ right.asExprOf[kyo.Record2[r]] } }.asTerm
        }.asExpr
    end generateRoutes

    private def buildRoute(name: String, method: String, pathTemplate: String, operation: OpenApi.Operation)(using
        Quotes
    ): quotes.reflect.Term =
        import quotes.reflect.*

        val params       = operation.parameters.getOrElse(Nil)
        val nameExpr     = Expr(name)
        val opContext    = s" of operation '$name' ($method $pathTemplate)"
        val pathTerm     = buildPath(pathTemplate, params, opContext)
        val queryParams  = params.filter(_.in == "query")
        val headerParams = params.filter(_.in == "header")

        innerType(pathTerm) match
            case '[pathType] =>
                val pathExpr = pathTerm.asExprOf[kyo.HttpPath[pathType]]

                // Matches the 7 methods defined on OpenApi.PathItem (OpenAPI 3.x has no trace/connect)
                val methodExpr: Expr[kyo.HttpMethod] = method match
                    case "GET"     => '{ kyo.HttpMethod.GET }
                    case "POST"    => '{ kyo.HttpMethod.POST }
                    case "PUT"     => '{ kyo.HttpMethod.PUT }
                    case "DELETE"  => '{ kyo.HttpMethod.DELETE }
                    case "PATCH"   => '{ kyo.HttpMethod.PATCH }
                    case "HEAD"    => '{ kyo.HttpMethod.HEAD }
                    case "OPTIONS" => '{ kyo.HttpMethod.OPTIONS }
                    case _         => report.errorAndAbort(s"Unsupported HTTP method '$method'$opContext.")

                // Build RequestDef with fields
                val reqTerm = buildRequestDef(
                    pathTerm,
                    queryParams ++ headerParams,
                    opContext
                )

                // Build ResponseDef — find the first 2xx response using HttpStatus hierarchy
                val (successStatus, successResponse) = operation.responses.toList
                    .flatMap { case (key, resp) =>
                        key.toIntOption.map(code => (kyo.HttpStatus(code), resp))
                    }
                    .sortBy(_._1.code)
                    .find(_._1.isSuccess)
                    .getOrElse(
                        operation.responses.get("default")
                            .map(resp => (kyo.HttpStatus.Success.OK: kyo.HttpStatus, resp))
                            .getOrElse((kyo.HttpStatus.Success.OK: kyo.HttpStatus, OpenApi.Response("", None)))
                    )
                val jsonResponseSchema =
                    for
                        content <- successResponse.content
                        media   <- content.get("application/json")
                    yield media.schema

                val respTerm = buildResponseDef(jsonResponseSchema, successStatus, opContext)

                // Assemble HttpRoute
                innerType(reqTerm) match
                    case '[reqType] =>
                        innerType(respTerm) match
                            case '[respType] =>
                                val req   = reqTerm.asExprOf[kyo.HttpRoute.RequestDef[reqType]]
                                val resp  = respTerm.asExprOf[kyo.HttpRoute.ResponseDef[respType]]
                                val route = '{ kyo.HttpRoute[reqType, respType, Nothing]($methodExpr, $req, $resp) }
                                '{ kyo.Record2.~($nameExpr)($route) }.asTerm
                end match
        end match
    end buildRoute

    /** Builds a RequestDef by constructing Field.Param instances directly. */
    private def buildRequestDef(using
        Quotes
    )(pathTerm: quotes.reflect.Term, params: List[OpenApi.Parameter], context: String = ""): quotes.reflect.Term =
        import quotes.reflect.*

        if params.isEmpty then
            return innerType(pathTerm) match
                case '[pathType] =>
                    val p = pathTerm.asExprOf[kyo.HttpPath[pathType]]
                    '{ kyo.HttpRoute.RequestDef[pathType]($p) }.asTerm
        end if

        // Build each field as an Expr, accumulating the intersection type
        var fieldExprs = List.empty[Expr[kyo.HttpRoute.Field[?]]]
        var resultType = innerTypeRepr(pathTerm)

        for param <- params do
            val codecType = resolveCodecType(param.schema)
            val optional  = !param.required.getOrElse(false)
            val isQuery   = param.in == "query"
            val location = if isQuery then '{ kyo.HttpRoute.Field.Param.Location.Query } else '{ kyo.HttpRoute.Field.Param.Location.Header }

            val nameType = ConstantType(StringConstant(param.name))

            codecType.asType match
                case '[a] =>
                    val codec = Expr.summon[kyo.HttpCodec[a]] match
                        case Some(c) => c
                        case None =>
                            report.errorAndAbort(
                                s"No HttpCodec[${codecType.show}] found for ${param.in} parameter '${param.name}'$context. " +
                                    s"Hint: add `given HttpCodec[${codecType.show}]` in scope."
                            )
                    val nameExpr = Expr(param.name)
                    val fieldExpr =
                        if optional then
                            '{
                                kyo.HttpRoute.Field.Param[String, a, kyo.Maybe[a]](
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
                                kyo.HttpRoute.Field.Param[String, a, a](
                                    $location,
                                    $nameExpr,
                                    "",
                                    $codec,
                                    kyo.Absent,
                                    false,
                                    ""
                                )
                            }
                    fieldExprs = fieldExprs :+ fieldExpr.asInstanceOf[Expr[kyo.HttpRoute.Field[?]]]

                    // Accumulate type: In & "name" ~ A or In & "name" ~ Maybe[A]
                    val tildeType      = TypeRepr.of[kyo.Record2.~].appliedTo(List(nameType, codecType))
                    val tildeMaybeType = TypeRepr.of[kyo.Record2.~].appliedTo(List(nameType, TypeRepr.of[kyo.Maybe].appliedTo(codecType)))
                    resultType = AndType(resultType, if optional then tildeMaybeType else tildeType)
            end match
        end for

        // Construct RequestDef with the accumulated fields
        val fieldsExpr = fieldExprs.foldLeft('{ kyo.Chunk.empty[kyo.HttpRoute.Field[?]] }) { (acc, field) =>
            '{ $acc.append($field) }
        }
        val pathExpr = pathTerm match
            case term =>
                innerType(term) match
                    case '[pathType] => term.asExprOf[kyo.HttpPath[pathType]]

        val reqDefType = TypeRepr.of[kyo.HttpRoute.RequestDef].appliedTo(resultType)
        val reqTerm = innerType(pathTerm) match
            case '[pathType] =>
                val p = pathTerm.asExprOf[kyo.HttpPath[pathType]]
                '{
                    kyo.HttpRoute.RequestDef[pathType](
                        $p,
                        $fieldsExpr.asInstanceOf[kyo.Chunk[kyo.HttpRoute.Field[? >: pathType]]]
                    )
                }.asTerm

        Typed(reqTerm, Inferred(reqDefType))
    end buildRequestDef

    /** Builds a ResponseDef, optionally with a JSON body field. */
    private def buildResponseDef(jsonSchema: Option[OpenApi.SchemaObject], status: kyo.HttpStatus, context: String = "")(using
        Quotes
    ): quotes.reflect.Term =
        import quotes.reflect.*

        val statusCode = Expr(status.code)
        jsonSchema match
            case None =>
                '{ kyo.HttpRoute.ResponseDef[Any](kyo.HttpStatus($statusCode)) }.asTerm
            case Some(schema) =>
                val codecType = resolveCodecType(schema)
                codecType.asType match
                    case '[a] =>
                        val s = Expr.summon[kyo.Schema[a]] match
                            case Some(s) => s
                            case None =>
                                report.errorAndAbort(
                                    s"No Schema[${codecType.show}] found for response body$context. " +
                                        s"Hint: add `given Schema[${codecType.show}] = Schema.derived` in scope."
                                )

                        val bodyField = '{
                            kyo.HttpRoute.Field.Body[String, a](
                                "body",
                                kyo.HttpRoute.ContentType.Json($s),
                                ""
                            )
                        }
                        val fields = '{ kyo.Chunk.empty[kyo.HttpRoute.Field[?]].append($bodyField) }
                        val respTerm = '{
                            kyo.HttpRoute.ResponseDef[Any](
                                kyo.HttpStatus($statusCode),
                                $fields.asInstanceOf[kyo.Chunk[kyo.HttpRoute.Field[? >: Any]]]
                            )
                        }.asTerm

                        val nameType    = ConstantType(StringConstant("body"))
                        val tildeType   = TypeRepr.of[kyo.Record2.~].appliedTo(List(nameType, TypeRepr.of[a]))
                        val resultType  = AndType(TypeRepr.of[Any], tildeType)
                        val respDefType = TypeRepr.of[kyo.HttpRoute.ResponseDef].appliedTo(resultType)

                        Typed(respTerm, Inferred(respDefType))
                end match
        end match
    end buildResponseDef

    // ==================== Path building ====================

    private def buildPath(pathTemplate: String, params: List[OpenApi.Parameter], context: String = "")(using Quotes): quotes.reflect.Term =
        import quotes.reflect.*

        val segments = pathTemplate.split("/").filter(_.nonEmpty).toList

        if segments.isEmpty then
            '{ kyo.HttpPath.Literal("/") }.asTerm
        else
            val pathParams = params.filter(_.in == "path")

            val segmentTerms = segments.map { seg =>
                if seg.startsWith("{") && seg.endsWith("}") then
                    buildCapture(seg.drop(1).dropRight(1), pathParams, context)
                else
                    '{ kyo.HttpPath.Literal(${ Expr(seg) }) }.asTerm
            }

            segmentTerms.reduce { (left, right) =>
                innerType(left) match
                    case '[l] =>
                        innerType(right) match
                            case '[r] =>
                                '{ ${ left.asExprOf[kyo.HttpPath[l]] } / ${ right.asExprOf[kyo.HttpPath[r]] } }.asTerm
            }
        end if
    end buildPath

    private def buildCapture(paramName: String, pathParams: List[OpenApi.Parameter], context: String = "")(using
        Quotes
    ): quotes.reflect.Term =
        import quotes.reflect.*

        val schema    = pathParams.find(_.name == paramName).map(_.schema)
        val codecType = schema.map(resolveCodecType).getOrElse(TypeRepr.of[String])

        val nameExpr  = Expr(paramName)
        val nameType  = ConstantType(StringConstant(paramName))
        val tildeType = TypeRepr.of[kyo.Record2.~].appliedTo(List(nameType, codecType))
        val pathType  = TypeRepr.of[kyo.HttpPath].appliedTo(tildeType)

        codecType.asType match
            case '[a] =>
                val codec = Expr.summon[kyo.HttpCodec[a]] match
                    case Some(c) => c
                    case None =>
                        report.errorAndAbort(
                            s"No HttpCodec[${codecType.show}] found for path parameter '$paramName'$context. " +
                                s"Hint: add `given HttpCodec[${codecType.show}]` in scope."
                        )
                Typed(
                    '{ kyo.HttpPath.Capture[String, a]($nameExpr, "", $codec) }.asTerm,
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
