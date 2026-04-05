package kyo

import kyo.*
import kyo.AllowUnsafe.embrace.danger

/** HTTP admin endpoints for flag introspection and mutation.
  *
  * FlagAdmin provides composable HTTP handlers for listing, inspecting, updating, and reloading configuration flags at runtime. Handlers
  * are returned as a `Seq` to mount on any server -- dedicated admin port, behind auth middleware, or under a prefix.
  *
  * Endpoints:
  *   - `GET /{prefix}` -- list all flags (optional `?filter=glob`)
  *   - `GET /{prefix}/:name` -- single flag detail (JSON)
  *   - `PUT /{prefix}/:name` -- update a [[DynamicFlag]] expression
  *   - `POST /{prefix}/:name/reload` -- reload a [[DynamicFlag]] from its config source
  *
  * IMPORTANT: PUT body is plain text (the rollout expression), NOT JSON. JSON bodies are detected and rejected with a helpful error
  * message. Example: `curl -X PUT -d 'true@premium/50%' /flags/myapp.features.newCheckout`.
  *
  * Security: token auth via system property `kyo.flag.admin.token`. When set, PUT and POST require `Authorization: Bearer <token>`. GET
  * endpoints are always open. Read-only mode (`readOnly=true`) blocks PUT/POST with 403.
  *
  * @see
  *   [[kyo.Flag]] for flag registry and lookup
  * @see
  *   [[kyo.DynamicFlag]] for runtime-mutable flags
  * @see
  *   [[kyo.FlagSync]] for background polling to keep flags in sync
  */
object FlagAdmin:

    // --- Public API ---

    /** Returns HTTP handlers for flag admin endpoints.
      *
      * Creates handlers for listing, inspecting, updating, and reloading flags. The returned handlers can be mounted on any HTTP server,
      * optionally behind auth middleware or under a prefix.
      *
      * @param prefix
      *   URL prefix for all endpoints (e.g., "flags" produces GET /flags, GET /flags/:name, etc.)
      * @param readOnly
      *   When true, PUT and POST endpoints return 403 Forbidden
      * @return
      *   Sequence of HTTP handlers to mount on a server
      */
    def routes(prefix: String = "flags", readOnly: Boolean = false)(using Frame): Seq[HttpHandler[?, ?, ?]] =

        // GET /{prefix} — list all flags
        val listRoute = HttpRoute.getRaw(prefix)
            .request(_.queryOpt[String]("filter"))
            .response(_.bodyText)
        val listHandler = listRoute.handler { req =>
            val filter = req.fields.filter
            val flags  = Flag.all
            val filtered = filter match
                case Present(glob) => flags.filter(f => matchGlob(glob, f.name))
                case Absent        => flags
            val infos = filtered.map(toFlagInfo)
            jsonOk(jsonFlagInfos.encode(infos))
        }

        // GET /{prefix}/:name — single flag detail
        val getRoute = HttpRoute.getRaw(prefix / Capture[String]("name"))
            .response(_.bodyText)
        val getHandler = getRoute.handler { req =>
            val name = req.fields.name
            Flag.get(name) match
                case Some(flag) =>
                    jsonOk(jsonFlagInfo.encode(toFlagInfo(flag)))
                case None =>
                    errorResp(HttpStatus.NotFound, s"Flag '$name' not found")
            end match
        }

        // PUT /{prefix}/:name — update dynamic flag expression (plain text body)
        val updateRoute = HttpRoute.putRaw(prefix / Capture[String]("name"))
            .request(_.bodyText)
            .response(_.bodyText)
        val updateHandler = updateRoute.handler { req =>
            if readOnly then
                errorResp(HttpStatus.Forbidden, "read-only mode")
            else if !checkAuth(req) then
                errorResp(HttpStatus.Unauthorized, "unauthorized")
            else
                val name = req.fields.name
                Flag.get(name) match
                    case None =>
                        errorResp(HttpStatus.NotFound, s"Flag '$name' not found")
                    case Some(d: DynamicFlag[?]) =>
                        val body = req.fields.body.trim
                        if body.nonEmpty && (body.charAt(0) == '{' || body.charAt(0) == '[') then
                            errorResp(
                                HttpStatus.BadRequest,
                                "Expression must be plain text, not JSON. Send the expression as plain text, e.g.: true@premium/50%"
                            )
                        else
                            try
                                d.update(body)
                                jsonOk(jsonFlagInfo.encode(toFlagInfo(d)))
                            catch
                                case e: FlagException =>
                                    errorResp(HttpStatus.BadRequest, e.getMessage)
                        end if
                    case Some(_) =>
                        errorResp(HttpStatus.Conflict, s"Flag '$name' is static and cannot be updated")
                end match
        }

        // POST /{prefix}/:name/reload — reload dynamic flag from config source
        val reloadRoute = HttpRoute.postRaw(prefix / Capture[String]("name") / "reload")
            .response(_.bodyText)
        val reloadHandler = reloadRoute.handler { req =>
            if readOnly then
                errorResp(HttpStatus.Forbidden, "read-only mode")
            else if !checkAuth(req) then
                errorResp(HttpStatus.Unauthorized, "unauthorized")
            else
                val name = req.fields.name
                Flag.get(name) match
                    case None =>
                        errorResp(HttpStatus.NotFound, s"Flag '$name' not found")
                    case Some(d: DynamicFlag[?]) =>
                        val resp = d.reload() match
                            case Flag.ReloadResult.Updated(expr) =>
                                ReloadResponse(d.name, expr, reloaded = true, None)
                            case Flag.ReloadResult.Unchanged =>
                                ReloadResponse(d.name, d.expression, reloaded = false, Some("expression unchanged"))
                            case Flag.ReloadResult.NoSource =>
                                ReloadResponse(
                                    d.name,
                                    d.expression,
                                    reloaded = false,
                                    Some("source is Default — use PUT to update")
                                )
                        jsonOk(jsonReload.encode(resp))
                    case Some(_) =>
                        errorResp(HttpStatus.Conflict, s"Flag '$name' is static and cannot be reloaded")
                end match
        }

        Seq(listHandler, getHandler, updateHandler, reloadHandler)
    end routes

    // --- Response types ---

    /** Flag details returned by the list and get endpoints. */
    case class FlagInfo(
        name: String,
        `type`: String,
        value: Option[String],
        expression: Option[String],
        default: String,
        source: String,
        evaluations: Option[Map[String, Long]],
        history: Option[List[HistoryInfo]]
    ) derives Json, CanEqual

    /** A single entry in a dynamic flag's update history. */
    case class HistoryInfo(
        timestamp: Long,
        from: String,
        to: String
    ) derives Json, CanEqual

    /** Error response body for failed admin operations. */
    case class ErrorResponse(
        error: String
    ) derives Json, CanEqual

    /** Response body for the reload endpoint. */
    case class ReloadResponse(
        name: String,
        expression: String,
        reloaded: Boolean,
        reason: Option[String]
    ) derives Json, CanEqual

    // --- Internal ---

    private def token: String = Flag("kyo.flag.admin.token", "")

    private def checkAuth(req: HttpRequest[?]): Boolean =
        token.isEmpty || {
            req.headers.get("Authorization") match
                case Present(v) => v == s"Bearer $token"
                case Absent     => false
        }

    private val jsonFlagInfo  = Json[FlagInfo]
    private val jsonFlagInfos = Json[List[FlagInfo]]
    private val jsonError     = Json[ErrorResponse]
    private val jsonReload    = Json[ReloadResponse]

    private def jsonOk(body: String): HttpResponse["body" ~ String] =
        HttpResponse(HttpStatus.OK, HttpHeaders.empty, Record.empty)
            .addField("body", body)
            .setHeader("Content-Type", "application/json")

    private def jsonResp(status: HttpStatus, body: String): HttpResponse["body" ~ String] =
        HttpResponse(status, HttpHeaders.empty, Record.empty)
            .addField("body", body)
            .setHeader("Content-Type", "application/json")

    private def errorResp(status: HttpStatus, error: String): HttpResponse["body" ~ String] =
        jsonResp(status, jsonError.encode(ErrorResponse(error)))

    private[kyo] def toFlagInfo(flag: Flag[?]): FlagInfo = flag match
        case f: StaticFlag[?] =>
            FlagInfo(
                name = f.name,
                `type` = "static",
                value = Some(String.valueOf(f.value)),
                expression = None,
                default = String.valueOf(f.default),
                source = f.source.toString,
                evaluations = None,
                history = None
            )
        case d: DynamicFlag[?] =>
            FlagInfo(
                name = d.name,
                `type` = "dynamic",
                value = None,
                expression = Some(d.expression),
                default = String.valueOf(d.default),
                source = d.source.toString,
                evaluations = Some(d.evaluationCounts),
                history = Some(d.updateHistory.map(r => HistoryInfo(r.timestamp, r.previousExpression, r.newExpression)))
            )

    /** Simple glob matching: `*` matches any sequence of characters except `.`, `**` matches anything. */
    private[kyo] def matchGlob(glob: String, name: String): Boolean =
        // Build regex by scanning for ** and * tokens, escaping everything else
        val sb  = new StringBuilder
        val len = glob.length
        @scala.annotation.tailrec
        def loop(i: Int): Unit =
            if i < len then
                if i + 1 < len && glob.charAt(i) == '*' && glob.charAt(i + 1) == '*' then
                    discard(sb.append(".*"))
                    loop(i + 2)
                else if glob.charAt(i) == '*' then
                    discard(sb.append("[^.]*"))
                    loop(i + 1)
                else
                    val ch = glob.charAt(i)
                    if "\\^$.|?+()[]{}".indexOf(ch) >= 0 then
                        discard(sb.append('\\').append(ch))
                    else
                        discard(sb.append(ch))
                    end if
                    loop(i + 1)
                end if
        loop(0)
        name.matches(sb.toString)
    end matchGlob

end FlagAdmin
