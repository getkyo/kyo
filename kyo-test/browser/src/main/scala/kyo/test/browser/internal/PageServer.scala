package kyo.test.browser.internal

import kyo.*
import scala.annotation.tailrec

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
private[browser] object PageServer:

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
           |  // CDP carries a binding payload as UTF-8 JSON, which cannot hold an unpaired surrogate, so backslashes and every surrogate
           |  // code unit are escaped here and restored by the runner.
           |  function escapeUnits(message) {
           |    var escaped = "";
           |    for (var i = 0; i < message.length; i++) {
           |      var unit = message.charCodeAt(i);
           |      if (unit === 92) escaped += String.fromCharCode(92, 92);
           |      else if (unit >= 0xd800 && unit <= 0xdfff) escaped += String.fromCharCode(92, 117) + ("000" + unit.toString(16)).slice(-4);
           |      else escaped += message.charAt(i);
           |    }
           |    return escaped;
           |  }
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
           |      $sendBinding(escapeUnits(message));
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

    /** Restores a message the page escaped before sending it through [[sendBinding]]: a doubled backslash is a backslash, and a backslash,
      * `u` and four hex digits is that UTF-16 code unit.
      */
    def unescapeUnits(payload: String): Result[String, String] =
        val message = new StringBuilder(payload.length)
        def isHex(from: Int): Boolean =
            (from until from + 4).forall(i => Character.digit(payload.charAt(i), 16) >= 0)
        @tailrec def loop(i: Int): Result[String, String] =
            if i >= payload.length then Result.succeed(message.toString)
            else if payload.charAt(i) != '\\' then
                discard(message.append(payload.charAt(i)))
                loop(i + 1)
            else if i + 1 < payload.length && payload.charAt(i + 1) == '\\' then
                discard(message.append('\\'))
                loop(i + 2)
            else if i + 5 < payload.length && payload.charAt(i + 1) == 'u' && isHex(i + 2) then
                discard(message.append(Integer.parseInt(payload.substring(i + 2, i + 6), 16).toChar))
                loop(i + 6)
            else Result.fail(s"malformed escape at offset $i of a message from the page")
        loop(0)
    end unescapeUnits

    /** `value` as a JavaScript string literal. Surrogate code units are escaped so the literal survives CDP's UTF-8 JSON. */
    def jsString(value: String): String =
        val literal = new StringBuilder("\"")
        value.foreach {
            case '"'                                                                         => literal.append("\\\"")
            case '\\'                                                                        => literal.append("\\\\")
            case '\n'                                                                        => literal.append("\\n")
            case '\r'                                                                        => literal.append("\\r")
            case c if c < ' ' || c.isSurrogate || c == '\u2028' || c == '\u2029' || c == '<' => literal.append(f"\\u${c.toInt}%04x")
            case c                                                                           => literal.append(c)
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
