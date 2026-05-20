package kyo

import kyo.*

/** OpenAPI 3.x specification model and bidirectional integration with typed routes.
  *
  * Supports OpenAPI in both directions:
  *   - **Routes → spec**: The server auto-generates a spec from all registered handlers when `HttpServerConfig.openApi` is configured and
  *     serves it at the specified path (default `/openapi.json`). No additional code is required beyond configuring the server.
  *   - **Spec → routes**: `HttpOpenApi.fromJson` and `HttpOpenApi.fromFile` are compile-time macros that parse an OpenAPI spec and produce
  *     an anonymous object with one typed `HttpRoute` field per operation, named by `operationId`. Errors in the spec are reported at
  *     compile time.
  *
  * Note: The spec-to-routes macros currently support path, query, and header parameters with primitive types, and JSON response bodies.
  * Request bodies, `$ref` schema resolution, complex schema composition (`allOf` / `oneOf` / `anyOf`), and security schemes are not yet
  * implemented.
  *
  * @see
  *   [[kyo.HttpRoute]] The route type produced by the spec-to-routes macros
  * @see
  *   [[kyo.HttpServerConfig]] `openApi` field enables auto-generated spec serving
  * @see
  *   [[kyo.HttpHandler]] Registered handlers are the source for the generated spec
  */
case class HttpOpenApi(
    openapi: String,
    info: HttpOpenApi.Info,
    paths: Map[String, HttpOpenApi.PathItem],
    components: Option[HttpOpenApi.Components]
) derives Schema, CanEqual

object HttpOpenApi:

    transparent inline def fromJson(inline json: String): Any =
        ${ kyo.internal.codec.OpenApiMacro.deriveFromStringImpl('json) }

    transparent inline def fromFile(inline path: String): Any =
        ${ kyo.internal.codec.OpenApiMacro.deriveImpl('path) }

    def toJson(openApi: HttpOpenApi)(using Frame): String =
        Json.encode(openApi)

    def toFile(openApi: HttpOpenApi, path: String)(using Frame): Unit =
        val json = toJson(openApi)
        java.nio.file.Files.writeString(java.nio.file.Path.of(path), json): Unit

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

    case class Info(
        title: String,
        version: String,
        description: Option[String]
    ) derives Schema, CanEqual

    case class MediaType(
        json: SchemaObject
    ) derives Schema, CanEqual

    case class Parameter(
        name: String,
        in: String,
        required: Option[Boolean],
        json: SchemaObject,
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

    case class PathItem(
        get: Option[Operation],
        post: Option[Operation],
        put: Option[Operation],
        delete: Option[Operation],
        patch: Option[Operation],
        head: Option[Operation],
        options: Option[Operation]
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

end HttpOpenApi
