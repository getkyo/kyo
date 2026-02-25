package kyo

import kyo.*

case class OpenApi(
    openapi: String,
    info: OpenApi.Info,
    paths: Map[String, OpenApi.PathItem],
    components: Option[OpenApi.Components]
) derives Schema, CanEqual

object OpenApi:

    transparent inline def fromJson(inline json: String): Any =
        ${ kyo.internal.OpenApiMacro.deriveFromStringImpl('json) }

    transparent inline def fromFile(inline path: String): Any =
        ${ kyo.internal.OpenApiMacro.deriveImpl('path) }

    def toJson(openApi: OpenApi): String =
        Schema[OpenApi].encode(openApi)

    def toFile(openApi: OpenApi, path: String): Unit =
        val json = toJson(openApi)
        java.nio.file.Files.writeString(java.nio.file.Path.of(path), json): Unit

    case class Info(
        title: String,
        version: String,
        description: Option[String]
    ) derives Schema, CanEqual

    case class PathItem(
        get: Option[Operation],
        post: Option[Operation],
        put: Option[Operation],
        delete: Option[Operation],
        patch: Option[Operation],
        head: Option[Operation],
        options: Option[Operation]
    ) derives Schema, CanEqual

    case class Operation(
        tags: Option[List[String]],
        summary: Option[String],
        description: Option[String],
        operationId: Option[String],
        deprecated: Option[Boolean],
        parameters: Option[List[Parameter]],
        requestBody: Option[RequestBody],
        responses: Map[String, Response],
        security: Option[List[Map[String, List[String]]]]
    ) derives Schema, CanEqual

    case class Parameter(
        name: String,
        in: String,
        required: Option[Boolean],
        schema: SchemaObject,
        description: Option[String]
    ) derives Schema, CanEqual

    case class RequestBody(
        required: Option[Boolean],
        content: Map[String, MediaType],
        description: Option[String]
    ) derives Schema, CanEqual

    case class Response(
        description: String,
        content: Option[Map[String, MediaType]]
    ) derives Schema, CanEqual

    case class MediaType(
        schema: SchemaObject
    ) derives Schema, CanEqual

    case class SchemaObject(
        `type`: Option[String],
        format: Option[String],
        items: Option[SchemaObject],
        properties: Option[Map[String, SchemaObject]],
        required: Option[List[String]],
        additionalProperties: Option[SchemaObject],
        oneOf: Option[List[SchemaObject]],
        `enum`: Option[List[String]],
        `$ref`: Option[String]
    ) derives Schema, CanEqual

    object SchemaObject:
        def string: SchemaObject  = SchemaObject(Some("string"), None, None, None, None, None, None, None, None)
        def integer: SchemaObject = SchemaObject(Some("integer"), Some("int32"), None, None, None, None, None, None, None)
        def long: SchemaObject    = SchemaObject(Some("integer"), Some("int64"), None, None, None, None, None, None, None)
        def number: SchemaObject  = SchemaObject(Some("number"), Some("double"), None, None, None, None, None, None, None)
        def boolean: SchemaObject = SchemaObject(Some("boolean"), None, None, None, None, None, None, None, None)
        def obj: SchemaObject     = SchemaObject(Some("object"), None, None, None, None, None, None, None, None)
        def array(items: SchemaObject): SchemaObject =
            SchemaObject(Some("array"), None, Some(items), None, None, None, None, None, None)
    end SchemaObject

    case class Components(
        schemas: Option[Map[String, SchemaObject]],
        securitySchemes: Option[Map[String, SecurityScheme]]
    ) derives Schema, CanEqual

    case class SecurityScheme(
        `type`: String,
        scheme: Option[String],
        bearerFormat: Option[String],
        name: Option[String],
        in: Option[String]
    ) derives Schema, CanEqual

end OpenApi
