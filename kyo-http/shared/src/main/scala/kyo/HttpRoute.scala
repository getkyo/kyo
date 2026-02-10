package kyo

import HttpRequest.Method
import HttpRequest.Part
import HttpResponse.Status

/** Typed endpoint contract that serves as the single source of truth for both server-side handlers and client-side typed calls.
  *
  * Define a route once and use it on both sides: `.handle(f)` creates a server-side `HttpHandler`, and `HttpClient.call(route, in)` makes a
  * typed client call. The route captures the full contract — HTTP method, URL path with typed captures, query/header parameters, request
  * body schema, response body schema, and typed error responses with status codes — so server and client stay in sync by construction.
  *
  * The `In` type parameter accumulates as parameters are added — each `.query`, `.header`, and `.input` call appends to the input type via
  * the `Combine` type class, which concatenates named tuple types. Named tuple fields are accessed by name (e.g., `in.id`, `in.limit`,
  * `in.body`). The field name matches the parameter name string literal.
  *
  * For example, `HttpRoute.get("users" / HttpPath.int("id")).query[Int]("limit")` produces
  * `HttpRoute[(id: Int, limit: Int), Unit, Nothing]`.
  *
  * Routes carry OpenAPI documentation metadata (tag, summary, description, operationId, deprecated, security) which feeds into automatic
  * spec generation via `HttpOpenApi`.
  *
  *   - Typed path captures via `HttpPath` (int, long, string, uuid, boolean)
  *   - Query parameters with optional defaults and `Schema`-based deserialization
  *   - Header parameter extraction
  *   - Request/response body schemas for automatic JSON serialization
  *   - Typed error responses mapped to HTTP status codes
  *   - OpenAPI metadata (tag, summary, description, operationId, deprecated, externalDocs, security)
  *   - `.handle(f)` to convert into an `HttpHandler`
  *   - Client-side typed invocation via `HttpClient.call`
  *
  * IMPORTANT: Parameter order in the `In` type follows declaration order: path captures, then `.query`, then `.header`, then `.input`. The
  * handler function must accept parameters in this order.
  *
  * IMPORTANT: Error types passed to `.error[E](status)` require `ConcreteTag[E]` — must be concrete types, not type parameters or unions.
  *
  * @tparam In
  *   Accumulated input type from path captures, query, header, and body parameters (named tuple or EmptyTuple)
  * @tparam Out
  *   Response body type
  * @tparam Err
  *   Union of possible typed error types
  *
  * @see
  *   [[kyo.HttpPath]]
  * @see
  *   [[kyo.HttpHandler]]
  * @see
  *   [[kyo.HttpClient.call]]
  * @see
  *   [[kyo.HttpOpenApi]]
  * @see
  *   [[kyo.Schema]]
  */
case class HttpRoute[In, Out, Err](
    method: Method,
    path: HttpPath[Any],
    outputStatus: Status,
    tag: Maybe[String],
    summary: Maybe[String],
    queryParams: Seq[HttpRoute.QueryParam[?]] = Seq.empty,
    headerParams: Seq[HttpRoute.HeaderParam] = Seq.empty,
    cookieParams: Seq[HttpRoute.CookieParam] = Seq.empty,
    inputSchema: Maybe[Schema[?]] = Absent,
    outputSchema: Maybe[Schema[?]] = Absent,
    errorSchemas: Seq[(Status, Schema[?], ConcreteTag[Any])] = Seq.empty,
    description: Maybe[String] = Absent,
    operationId: Maybe[String] = Absent,
    isDeprecated: Boolean = false,
    externalDocsUrl: Maybe[String] = Absent,
    externalDocsDesc: Maybe[String] = Absent,
    securityScheme: Maybe[String] = Absent,
    multipartInput: Boolean = false,
    private[kyo] paramCounter: Int = 0,
    private[kyo] bodyId: Int = -1
):
    import HttpRoute.*

    // --- Query Parameters ---

    /** Appends a required query parameter to `In` via `Combine`. The field name in the named tuple matches the parameter name. */
    transparent inline def query[A: Schema](inline name: String): Any =
        ${
            internal.CaptureNameMacro.addField[HttpRoute[In, Out, Err], In, Out, Err, A](
                '{ copy(queryParams = queryParams :+ QueryParam(name, Schema[A], Absent, paramCounter), paramCounter = paramCounter + 1) },
                '{ name }
            )
        }

    /** Appends an optional query parameter with a default value. */
    transparent inline def query[A: Schema](inline name: String, default: A): Any =
        ${
            internal.CaptureNameMacro.addField[HttpRoute[In, Out, Err], In, Out, Err, A](
                '{
                    copy(
                        queryParams = queryParams :+ QueryParam(name, Schema[A], Present(default), paramCounter),
                        paramCounter = paramCounter + 1
                    )
                },
                '{ name }
            )
        }

    // --- Headers ---

    /** Appends a required header parameter (always `String`) to `In` via `Combine`. */
    transparent inline def header(inline name: String): Any =
        ${
            internal.CaptureNameMacro.addField[HttpRoute[In, Out, Err], In, Out, Err, String](
                '{ copy(headerParams = headerParams :+ HeaderParam(name, Absent, id = paramCounter), paramCounter = paramCounter + 1) },
                '{ name }
            )
        }

    /** Appends an optional header parameter with a default value. */
    transparent inline def header(inline name: String, default: String): Any =
        ${
            internal.CaptureNameMacro.addField[HttpRoute[In, Out, Err], In, Out, Err, String](
                '{
                    copy(
                        headerParams = headerParams :+ HeaderParam(name, Present(default), id = paramCounter),
                        paramCounter = paramCounter + 1
                    )
                },
                '{ name }
            )
        }

    // --- Cookies ---

    transparent inline def cookie(inline name: String): Any =
        ${
            internal.CaptureNameMacro.addField[HttpRoute[In, Out, Err], In, Out, Err, String](
                '{ copy(cookieParams = cookieParams :+ CookieParam(name, Absent, paramCounter), paramCounter = paramCounter + 1) },
                '{ name }
            )
        }

    transparent inline def cookie(inline name: String, default: String): Any =
        ${
            internal.CaptureNameMacro.addField[HttpRoute[In, Out, Err], In, Out, Err, String](
                '{
                    copy(cookieParams = cookieParams :+ CookieParam(name, Present(default), paramCounter), paramCounter = paramCounter + 1)
                },
                '{ name }
            )
        }

    // --- Auth ---

    transparent inline def authBearer: Any =
        ${
            internal.CaptureNameMacro.addFieldFixed[HttpRoute[In, Out, Err], In, Out, Err, String](
                '{
                    copy(
                        headerParams = headerParams :+ HeaderParam("Authorization", Absent, Present(AuthScheme.Bearer), paramCounter),
                        paramCounter = paramCounter + 1
                    )
                },
                "bearer"
            )
        }

    transparent inline def authBasic: Any =
        ${
            internal.CaptureNameMacro.addTwoFieldsFixed[HttpRoute[In, Out, Err], In, Out, Err, String, String](
                '{
                    copy(
                        headerParams = headerParams :+ HeaderParam("Authorization", Absent, Present(AuthScheme.Basic), paramCounter),
                        paramCounter = paramCounter + 1
                    )
                },
                "username",
                "password"
            )
        }

    transparent inline def authApiKey(inline name: String): Any =
        ${
            internal.CaptureNameMacro.addField[HttpRoute[In, Out, Err], In, Out, Err, String](
                '{
                    copy(
                        headerParams = headerParams :+ HeaderParam(name, Absent, Present(AuthScheme.ApiKey), paramCounter),
                        paramCounter = paramCounter + 1
                    )
                },
                '{ name }
            )
        }

    // --- Request Body ---

    /** Sets the request body schema. The deserialized body becomes a named field `body` in the handler input. */
    transparent inline def input[A: Schema]: Any =
        ${
            internal.CaptureNameMacro.addFieldFixed[HttpRoute[In, Out, Err], In, Out, Err, A](
                '{ copy(inputSchema = Present(Schema[A]), bodyId = paramCounter, paramCounter = paramCounter + 1) },
                "body"
            )
        }

    transparent inline def inputText: Any =
        ${
            internal.CaptureNameMacro.addFieldFixed[HttpRoute[In, Out, Err], In, Out, Err, String](
                '{ copy(inputSchema = Present(Schema[String]), bodyId = paramCounter, paramCounter = paramCounter + 1) },
                "body"
            )
        }

    transparent inline def inputForm[A: Schema]: Any =
        ${
            internal.CaptureNameMacro.addFieldFixed[HttpRoute[In, Out, Err], In, Out, Err, A](
                '{ copy(inputSchema = Present(Schema[A]), bodyId = paramCounter, paramCounter = paramCounter + 1) },
                "body"
            )
        }

    transparent inline def inputMultipart: Any =
        ${
            internal.CaptureNameMacro.addFieldFixed[HttpRoute[In, Out, Err], In, Out, Err, Seq[Part]](
                '{ copy(multipartInput = true, bodyId = paramCounter, paramCounter = paramCounter + 1) },
                "parts"
            )
        }

    // --- Response Body ---

    /** Sets the response body schema for automatic JSON serialization. */
    def output[O: Schema]: HttpRoute[In, O, Err] =
        copy(outputSchema = Present(Schema[O]))
            .asInstanceOf[HttpRoute[In, O, Err]]

    def output[O: Schema](status: Status): HttpRoute[In, O, Err] =
        copy(outputStatus = status, outputSchema = Present(Schema[O]))
            .asInstanceOf[HttpRoute[In, O, Err]]

    def outputText: HttpRoute[In, String, Err] =
        output[String]

    // --- Errors ---

    /** Registers a typed error response for the given status code. Requires `ConcreteTag[E]` — must be a concrete type. */
    def error[E: Schema](status: Status)(using tag: ConcreteTag[E]): HttpRoute[In, Out, Err | E] =
        copy(errorSchemas = errorSchemas :+ (status, Schema[E], tag.asInstanceOf[ConcreteTag[Any]]))
            .asInstanceOf[HttpRoute[In, Out, Err | E]]

    // --- Documentation ---

    def tag(t: String): HttpRoute[In, Out, Err] =
        copy(tag = Present(t))

    def summary(s: String): HttpRoute[In, Out, Err] =
        copy(summary = Present(s))

    def description(d: String): HttpRoute[In, Out, Err] =
        copy(description = Present(d))

    def operationId(id: String): HttpRoute[In, Out, Err] =
        copy(operationId = Present(id))

    def deprecated: HttpRoute[In, Out, Err] =
        copy(isDeprecated = true)

    def externalDocs(url: String): HttpRoute[In, Out, Err] =
        copy(externalDocsUrl = Present(url))

    def externalDocs(url: String, desc: String): HttpRoute[In, Out, Err] =
        copy(externalDocsUrl = Present(url), externalDocsDesc = Present(desc))

    def security(scheme: String): HttpRoute[In, Out, Err] =
        copy(securityScheme = Present(scheme))

    // --- Server ---

    /** Converts this route into an HttpHandler. `f` receives the accumulated `In` named tuple — path captures, then query, then header,
      * then body params — and returns the output type. Errors matching route error schemas get the corresponding status code.
      */
    def handle[S](f: In => Out < (Abort[Err] & Async & S))(using Frame): HttpHandler[S] =
        HttpHandler.init(this)(f)

end HttpRoute

object HttpRoute:

    // --- Internal param types ---

    private[kyo] case class QueryParam[A](name: String, schema: Schema[A], default: Maybe[A], id: Int = 0)
    private[kyo] enum AuthScheme derives CanEqual:
        case Bearer
        case Basic
        case ApiKey
    end AuthScheme

    private[kyo] case class HeaderParam(name: String, default: Maybe[String], authScheme: Maybe[AuthScheme] = Absent, id: Int = 0)
        derives CanEqual
    private[kyo] case class CookieParam(name: String, default: Maybe[String], id: Int = 0) derives CanEqual

    // --- Route factory methods ---

    private def init[A](method: Method, path: HttpPath[A]): HttpRoute[A, Unit, Nothing] =
        HttpRoute(
            method = method,
            path = path.asInstanceOf[HttpPath[Any]],
            outputStatus = Status.OK,
            tag = Absent,
            summary = Absent
        )

    def get[A](path: HttpPath[A]): HttpRoute[A, Unit, Nothing]     = init(Method.GET, path)
    def post[A](path: HttpPath[A]): HttpRoute[A, Unit, Nothing]    = init(Method.POST, path)
    def put[A](path: HttpPath[A]): HttpRoute[A, Unit, Nothing]     = init(Method.PUT, path)
    def patch[A](path: HttpPath[A]): HttpRoute[A, Unit, Nothing]   = init(Method.PATCH, path)
    def delete[A](path: HttpPath[A]): HttpRoute[A, Unit, Nothing]  = init(Method.DELETE, path)
    def head[A](path: HttpPath[A]): HttpRoute[A, Unit, Nothing]    = init(Method.HEAD, path)
    def options[A](path: HttpPath[A]): HttpRoute[A, Unit, Nothing] = init(Method.OPTIONS, path)

end HttpRoute
