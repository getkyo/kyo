package kyo.http2.internal

import kyo.Result
import kyo.http2.OpenApi
import kyo.http2.Schema
import scala.quoted.*

private[http2] object OpenApiMacro:

    def deriveFromStringImpl(spec: Expr[String])(using Quotes): Expr[Any] =
        import quotes.reflect.*

        val jsonStr = spec.valueOrAbort

        val openApi = Schema[OpenApi].decode(jsonStr) match
            case Result.Success(v) => v
            case Result.Failure(e) => report.errorAndAbort(s"Failed to parse OpenAPI spec: $e")
            case Result.Panic(ex)  => report.errorAndAbort(s"Failed to parse OpenAPI spec: ${ex.getMessage}")

        generateRoutes(openApi)
    end deriveFromStringImpl

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

        val openApi = Schema[OpenApi].decode(jsonStr) match
            case Result.Success(v) => v
            case Result.Failure(e) => report.errorAndAbort(s"Failed to parse OpenAPI spec at $resolved: $e")
            case Result.Panic(ex)  => report.errorAndAbort(s"Failed to parse OpenAPI spec at $resolved: ${ex.getMessage}")

        generateRoutes(openApi)
    end deriveImpl

    private case class OpInfo(
        name: String,
        method: String,
        pathTemplate: String,
        params: List[OpenApi.Parameter],
        requestBody: Option[OpenApi.RequestBody],
        responses: Map[String, OpenApi.Response]
    )

    private def generateRoutes(openApi: OpenApi)(using Quotes): Expr[Any] =
        import quotes.reflect.*

        val operations =
            for
                (pathTemplate, pathItem) <- openApi.paths.toList
                (method, operation)      <- extractOperations(pathItem)
            yield OpInfo(
                name = operation.operationId.getOrElse(generateOperationName(method, pathTemplate)),
                method = method,
                pathTemplate = pathTemplate,
                params = operation.parameters.getOrElse(Nil),
                requestBody = operation.requestBody,
                responses = operation.responses
            )

        if operations.isEmpty then
            report.errorAndAbort("OpenAPI spec contains no operations")

        val names = operations.map(_.name)
        val dupes = names.diff(names.distinct)
        if dupes.nonEmpty then
            report.errorAndAbort(s"Duplicate operation names: ${dupes.mkString(", ")}")

        val routeTerms = operations.map(op => buildRouteTerm(op, openApi))
        routeTerms.reduce(buildAnd).asExpr
    end generateRoutes

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

    private def buildRouteTerm(op: OpInfo, openApi: OpenApi)(using Quotes): quotes.reflect.Term =
        import quotes.reflect.*

        val nameExpr = Expr(op.name)
        val pathTerm = buildPathTerm(op.pathTemplate, op.params)

        // Extract the path type A from HttpPath[A] and use the builder API with proper types
        val pathType = pathTerm.tpe.widen match
            case AppliedType(_, List(a)) => a
            case other                   => TypeRepr.of[Any]

        pathType.asType match
            case '[a] =>
                val pathExpr = pathTerm.asExprOf[kyo.http2.HttpPath[a]]
                val routeExpr = op.method match
                    case "GET"     => '{ kyo.http2.HttpRoute.get[a]($pathExpr) }
                    case "POST"    => '{ kyo.http2.HttpRoute.post[a]($pathExpr) }
                    case "PUT"     => '{ kyo.http2.HttpRoute.put[a]($pathExpr) }
                    case "DELETE"  => '{ kyo.http2.HttpRoute.delete[a]($pathExpr) }
                    case "PATCH"   => '{ kyo.http2.HttpRoute.patch[a]($pathExpr) }
                    case "HEAD"    => '{ kyo.http2.HttpRoute.head[a]($pathExpr) }
                    case "OPTIONS" => '{ kyo.http2.HttpRoute.options[a]($pathExpr) }
                    case _         => report.errorAndAbort(s"Unsupported HTTP method: ${op.method}")
                '{ kyo.Record2.~($nameExpr)($routeExpr) }.asTerm
        end match
    end buildRouteTerm

    private def buildPathTerm(pathTemplate: String, params: List[OpenApi.Parameter])(using Quotes): quotes.reflect.Term =
        import quotes.reflect.*

        val segments = pathTemplate.split("/").filter(_.nonEmpty).toList

        if segments.isEmpty then
            '{ kyo.http2.HttpPath.Literal("/") }.asTerm
        else
            val pathParams = params.filter(_.in == "path")

            val segmentTerms: List[Term] = segments.map { seg =>
                if seg.startsWith("{") && seg.endsWith("}") then
                    val paramName = seg.drop(1).dropRight(1)
                    val param     = pathParams.find(_.name == paramName)
                    val schema    = param.map(_.schema)
                    buildCaptureTerm(paramName, schema)
                else
                    val segExpr = Expr(seg)
                    '{ kyo.http2.HttpPath.Literal($segExpr) }.asTerm
            }

            segmentTerms.reduce { (left, right) =>
                val leftType = left.tpe.widen match
                    case AppliedType(_, List(a)) => a
                    case _                       => TypeRepr.of[Any]
                val rightType = right.tpe.widen match
                    case AppliedType(_, List(a)) => a
                    case _                       => TypeRepr.of[Any]
                (leftType.asType, rightType.asType) match
                    case ('[l], '[r]) =>
                        val leftExpr  = left.asExprOf[kyo.http2.HttpPath[l]]
                        val rightExpr = right.asExprOf[kyo.http2.HttpPath[r]]
                        '{ $leftExpr / $rightExpr }.asTerm
                end match
            }
        end if
    end buildPathTerm

    private def buildCaptureTerm(paramName: String, schema: Option[OpenApi.SchemaObject])(using Quotes): quotes.reflect.Term =
        import quotes.reflect.*

        val codecType = schema.flatMap(s =>
            (s.`type`, s.format) match
                case (Some("integer"), Some("int64")) => Some(TypeRepr.of[Long])
                case (Some("integer"), _)             => Some(TypeRepr.of[Int])
                case (Some("string"), Some("uuid"))   => Some(TypeRepr.of[java.util.UUID])
                case (Some("string"), _)              => Some(TypeRepr.of[String])
                case (Some("boolean"), _)             => Some(TypeRepr.of[Boolean])
                case (Some("number"), _)              => Some(TypeRepr.of[Double])
                case _                                => None
        ).getOrElse(TypeRepr.of[String])

        val nameType = ConstantType(StringConstant(paramName))

        codecType.asType match
            case '[a] =>
                val codec = Expr.summon[kyo.http2.HttpCodec[a]] match
                    case Some(c) => c
                    case None    => report.errorAndAbort(s"No HttpCodec found for type ${codecType.show}")
                // Build: HttpPath.Capture[N, A](fieldName, wireName, codec)
                // N must be String & Singleton, use ConstantType for the singleton
                val singletonType   = AndType(nameType, AndType(TypeRepr.of[String], TypeRepr.of[scala.Singleton]))
                val tildeType       = TypeRepr.of[kyo.Record2.~].appliedTo(List(nameType, codecType))
                val captureClass    = Symbol.requiredClass("kyo.http2.HttpPath.Capture")
                val ctor            = captureClass.primaryConstructor
                val captureTypeRepr = TypeRepr.of[kyo.http2.HttpPath].appliedTo(tildeType)
                val result = Apply(
                    TypeApply(
                        Select(New(TypeIdent(captureClass)), ctor),
                        List(Inferred(singletonType), TypeTree.of[a])
                    ),
                    List(Literal(StringConstant(paramName)), Literal(StringConstant("")), codec.asTerm)
                )
                Typed(result, Inferred(captureTypeRepr))
        end match
    end buildCaptureTerm

    private def extractRecordFieldType(using Quotes)(tpe: quotes.reflect.TypeRepr): quotes.reflect.TypeRepr =
        import quotes.reflect.*
        tpe.widen match
            case AppliedType(_, List(inner)) => inner
            case other                       => report.errorAndAbort(s"Expected Record2[F], got: ${other.show}")
    end extractRecordFieldType

    private def buildAnd(using Quotes)(left: quotes.reflect.Term, right: quotes.reflect.Term): quotes.reflect.Term =
        import quotes.reflect.*
        val rightFieldType = extractRecordFieldType(right.tpe)
        Apply(
            TypeApply(
                Select.unique(left, "&"),
                List(Inferred(rightFieldType))
            ),
            List(right)
        )
    end buildAnd

end OpenApiMacro
