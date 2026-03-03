package kyo

import kyo.*

/** OpenAPI 3.x specification model and compile-time route generation.
  *
  * Supports OpenAPI in both directions:
  *   - **Routes → spec**: The server auto-generates a spec from registered handlers via `HttpServerConfig.openApi`.
  *   - **Spec → routes**: `HttpOpenApi.fromJson` and `HttpOpenApi.fromFile` are compile-time macros that read an OpenAPI spec and produce
  *     typed `HttpRoute` values. Each operation becomes a route accessed by `operationId`.
  *
  * Note: Spec-to-routes currently supports path, query, and header parameters with primitive types, and JSON response bodies. Request
  * bodies, `$ref` resolution, complex schema composition (`allOf`/`oneOf`/`anyOf`), and security schemes are not yet implemented.
  *
  * @see
  *   [[kyo.HttpRoute]] The route type produced by the macros
  * @see
  *   [[kyo.HttpServerConfig.openApi]] Enables auto-generated spec serving
  */
case class HttpOpenApi(
    openapi: String,
    info: HttpOpenApi.Info,
    paths: Map[String, HttpOpenApi.PathItem],
    components: Option[HttpOpenApi.Components]
) derives Json, CanEqual

object HttpOpenApi:

    transparent inline def fromJson(inline json: String): Any =
        ${ kyo.internal.OpenApiMacro.deriveFromStringImpl('json) }

    transparent inline def fromFile(inline path: String): Any =
        ${ kyo.internal.OpenApiMacro.deriveImpl('path) }

    def toJson(openApi: HttpOpenApi): String =
        Json[HttpOpenApi].encode(openApi)

    def toFile(openApi: HttpOpenApi, path: String): Unit =
        val json = toJson(openApi)
        java.nio.file.Files.writeString(java.nio.file.Path.of(path), json): Unit

    case class Info(
        title: String,
        version: String,
        description: Option[String]
    ) derives Json, CanEqual

    case class PathItem(
        get: Option[Operation],
        post: Option[Operation],
        put: Option[Operation],
        delete: Option[Operation],
        patch: Option[Operation],
        head: Option[Operation],
        options: Option[Operation]
    ) derives Json, CanEqual

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
    ) derives Json, CanEqual

    case class Parameter(
        name: String,
        in: String,
        required: Option[Boolean],
        json: SchemaObject,
        description: Option[String]
    ) derives Json, CanEqual

    case class RequestBody(
        required: Option[Boolean],
        content: Map[String, MediaType],
        description: Option[String]
    ) derives Json, CanEqual

    case class Response(
        description: String,
        content: Option[Map[String, MediaType]]
    ) derives Json, CanEqual

    case class MediaType(
        json: SchemaObject
    ) derives Json, CanEqual

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
    ) derives Json, CanEqual

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
    ) derives Json, CanEqual

    case class SecurityScheme(
        `type`: String,
        scheme: Option[String],
        bearerFormat: Option[String],
        name: Option[String],
        in: Option[String]
    ) derives Json, CanEqual

end HttpOpenApi
