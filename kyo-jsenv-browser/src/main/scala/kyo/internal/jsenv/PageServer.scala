package kyo.internal.jsenv

import kyo.*

/** Serves a linked Scala.js output directory and the page that runs it.
  *
  * `GET /` answers with a generated page: a classic script that installs the `scalajsCom` global the Scala.js test bridge expects, then
  * the linked main module. Every other path answers with the file of that relative path in the directory, loaded once at startup, with a
  * `Content-Type` a browser accepts for it: a module script must be JavaScript, and `WebAssembly.instantiateStreaming`, which the WasmGC
  * loader uses, requires `application/wasm`.
  *
  * The page talks to the runner through three CDP bindings: [[sendBinding]] carries each message the page sends, [[readyBinding]] reports
  * that `scalajsCom` is installed so messages for the page can be delivered, and [[failBinding]] reports a main module that could not be
  * loaded, which would otherwise leave the run waiting.
  */
private[jsenv] object PageServer:

    /** How the page loads the main module. */
    enum ModuleKind derives CanEqual:
        case ESModule, Script

    val sendBinding  = "__kyoJsEnvSend"
    val readyBinding = "__kyoJsEnvReady"
    val failBinding  = "__kyoJsEnvFail"

    /** The page function the runner calls with each message for the page. */
    val receiveFunction = "__kyoJsEnvReceive"

    private val contentTypes: Map[String, String] = Map(
        "js"   -> "text/javascript; charset=utf-8",
        "mjs"  -> "text/javascript; charset=utf-8",
        "wasm" -> "application/wasm",
        "map"  -> "application/json; charset=utf-8",
        "json" -> "application/json; charset=utf-8",
        "html" -> "text/html; charset=utf-8"
    )

    def contentType(path: String): String =
        val name = path.substring(path.lastIndexOf('/') + 1)
        name.lastIndexOf('.') match
            case -1 => "application/octet-stream"
            case i  => contentTypes.getOrElse(name.substring(i + 1).toLowerCase, "application/octet-stream")
    end contentType

    /** The page for `module`, a path relative to the served directory.
      *
      * The com mirrors scalajs-env-nodejs: messages that arrive before `scalajsCom.init` are queued, and the queue is handed to the
      * callback in a microtask after `init` returns, with messages arriving in the meantime appended behind it, so the callback sees every
      * message in arrival order.
      */
    def page(module: String, kind: ModuleKind): String =
        val src     = escapeAttribute(module)
        val onError = escapeAttribute(s"$failBinding(${jsString(s"could not load the main module $module")})")
        val moduleTag = kind match
            case ModuleKind.ESModule => s"""<script type="module" src="$src" onerror="$onError"></script>"""
            case ModuleKind.Script   => s"""<script src="$src" onerror="$onError"></script>"""
        s"""<!doctype html>
           |<html>
           |<head>
           |<meta charset="utf-8">
           |<title>Scala.js test run</title>
           |<script>
           |(function () {
           |  var onMessage = null;
           |  var queued = [];
           |  window.scalajsCom = {
           |    init: function (callback) {
           |      if (onMessage !== null) throw new Error("Com already initialized");
           |      onMessage = callback;
           |      Promise.resolve().then(function () {
           |        var pending = queued;
           |        queued = null;
           |        for (var i = 0; i < pending.length; i++) onMessage(pending[i]);
           |      });
           |    },
           |    send: function (message) {
           |      $sendBinding(message);
           |    }
           |  };
           |  window.$receiveFunction = function (message) {
           |    if (queued !== null) queued.push(message);
           |    else onMessage(message);
           |  };
           |  $readyBinding("");
           |})();
           |</script>
           |$moduleTag
           |</head>
           |<body></body>
           |</html>
           |""".stripMargin
    end page

    /** `value` as a JavaScript string literal. */
    def jsString(value: String): String =
        val literal = new StringBuilder("\"")
        value.foreach {
            case '"'                                                        => literal.append("\\\"")
            case '\\'                                                       => literal.append("\\\\")
            case '\n'                                                       => literal.append("\\n")
            case '\r'                                                       => literal.append("\\r")
            case c if c < ' ' || c == '\u2028' || c == '\u2029' || c == '<' => literal.append(f"\\u${c.toInt}%04x")
            case c                                                          => literal.append(c)
        }
        literal.append('"').toString
    end jsString

    private def escapeAttribute(value: String): String =
        value.flatMap {
            case '&'   => "&amp;"
            case '"'   => "&quot;"
            case '\''  => "&#39;"
            case '<'   => "&lt;"
            case '>'   => "&gt;"
            case other => other.toString
        }

    /** Every regular file under `root`, keyed by its forward-slash path relative to `root`. */
    def load(root: Path)(using Frame): Map[String, Span[Byte]] < (Async & Abort[FileSystemException]) =
        val depth = root.parts.size
        Path.runReadOnly(
            Scope.run(root.walk.run).map { entries =>
                Kyo.foreach(entries) { entry =>
                    entry.isRegularFile.map {
                        case false => Absent
                        case true  => entry.readBytes.map(bytes => Present(entry.parts.drop(depth).mkString("/") -> bytes))
                    }
                }.map(pairs => pairs.collect { case Present(pair) => pair }.toMap)
            }
        )
    end load

    /** The handlers answering `GET /` with `page` and every other path from `files`. */
    def handlers(page: String, files: Map[String, Span[Byte]])(using Frame): Seq[HttpHandler[?, ?, ?]] =
        val pageBytes = Span.fromUnsafe(page.getBytes("UTF-8"))
        Seq(
            HttpRoute.getRaw("").response(_.bodyBinary).handler { _ =>
                HttpResponse.ok(pageBytes).setHeader("Content-Type", "text/html; charset=utf-8").noCache
            },
            HttpRoute.getRaw(Capture.Rest("path")).response(_.bodyBinary).handler { request =>
                val path = request.fields.path.takeWhile(_ != '?')
                files.get(path) match
                    case Some(bytes) => HttpResponse.ok(bytes).setHeader("Content-Type", contentType(path)).noCache
                    case None =>
                        HttpResponse.notFound(Span.fromUnsafe(s"not found: $path".getBytes("UTF-8")))
                            .setHeader("Content-Type", "text/plain; charset=utf-8")
                end match
            }
        )
    end handlers

end PageServer
