package kyo.test.browser.internal

import kyo.*

class PageServerTest extends kyo.test.Test[Any]:

    "contentType gives module scripts a JavaScript type and WebAssembly its own" in {
        assert(PageServer.contentType("main.js") == "text/javascript; charset=utf-8")
        assert(PageServer.contentType("nested/dir/chunk.mjs") == "text/javascript; charset=utf-8")
        assert(PageServer.contentType("main.wasm") == "application/wasm")
        assert(PageServer.contentType("main.js.map") == "application/json; charset=utf-8")
        assert(PageServer.contentType("README") == "application/octet-stream")
        assert(PageServer.contentType("dir.with.dots/file") == "application/octet-stream")
    }

    "jsString escapes quotes, backslashes, control characters, line separators and the start of a closing tag" in {
        val input = "a\"b\\c\nd\re" + 1.toChar + "f" + 0x2028.toChar + "g" + 0x2029.toChar + "</script>"
        assert(PageServer.jsString(input) == "\"a\\\"b\\\\c\\nd\\re\\u0001f\\u2028g\\u2029\\u003c/script>\"")
        assert(PageServer.jsString("") == "\"\"")
    }

    "jsString escapes each surrogate code unit, paired or not" in {
        val input = "a" + 0xd83d.toChar + 0xde00.toChar + "b" + 0xdc00.toChar
        assert(PageServer.jsString(input) == "\"a\\ud83d\\ude00b\\udc00\"")
    }

    "unescapeUnits restores backslashes and code unit escapes" in {
        assert(PageServer.unescapeUnits("plain") == Result.succeed("plain"))
        assert(PageServer.unescapeUnits("a\\\\b") == Result.succeed("a\\b"))
        assert(PageServer.unescapeUnits("x\\ud800y\\uDC00") == Result.succeed("x" + 0xd800.toChar + "y" + 0xdc00.toChar))
        assert(PageServer.unescapeUnits("\\\\ud800") == Result.succeed("\\ud800"))
    }

    "unescapeUnits rejects an escape the page never produces" in {
        assert(PageServer.unescapeUnits("a\\nb").isFailure)
        assert(PageServer.unescapeUnits("a\\u12").isFailure)
        assert(PageServer.unescapeUnits("a\\uzzzz").isFailure)
        assert(PageServer.unescapeUnits("trailing\\").isFailure)
    }

    "page loads an ESModule main module as a module script after the com setup" in {
        val html = PageServer.page("main.js", PageServer.ModuleKind.ESModule)
        assert(html.contains("""<script type="module" src="main.js""""))
        assert(html.indexOf("window.scalajsCom") < html.indexOf("""src="main.js""""))
        assert(html.contains(s"${PageServer.readyBinding}(\"\")"))
    }

    "page loads a Script main module as a classic script" in {
        val html = PageServer.page("main.js", PageServer.ModuleKind.Script)
        assert(html.contains("""<script src="main.js""""))
        assert(!html.contains("""type="module""""))
    }

    "page escapes a module path in the attribute and in the load failure message" in {
        val html = PageServer.page("we\"ird's.js", PageServer.ModuleKind.ESModule)
        assert(html.contains("""src="we&quot;ird&#39;s.js""""))
        assert(html.contains("could not load the main module we\\&quot;ird&#39;s.js"))
    }

    "load keys every regular file by its path relative to the directory" in {
        Scope.run {
            for
                dir   <- Path.run(Path.tempDir("kyo-test-browser-page-"))
                _     <- Path.run((dir / "main.js").write("export {};"))
                _     <- Path.run((dir / "nested").mkDir)
                _     <- Path.run((dir / "nested" / "main.wasm").writeBytes(Span.fromUnsafe(Array[Byte](0, 97, 115, 109))))
                files <- PageServer.load(dir)
            yield
                assert(files.keySet == Set("main.js", "nested/main.wasm"))
                assert(new String(files("main.js").toArray, "UTF-8") == "export {};")
                assert(files("nested/main.wasm").toArray.toSeq == Seq[Byte](0, 97, 115, 109))
        }
    }

    "handlers serve the page at the root and each file with its type, and 404 for anything else" in {
        val wasm  = Span.fromUnsafe(Array[Byte](0, 97, 115, 109, 1, 0, 0, 0))
        val files = Map("main.js" -> Span.fromUnsafe("export {};".getBytes("UTF-8")), "nested/main.wasm" -> wasm)
        Scope.run {
            HttpServer.init(HttpServerConfig.default.withoutAutoFilters)(
                PageServer.handlers(PageServer.page("main.js", PageServer.ModuleKind.ESModule), files)*
            ).map { server =>
                val base = s"http://127.0.0.1:${server.port}"
                for
                    root    <- HttpClient.getBinaryResponse(s"$base/")
                    module  <- HttpClient.getBinaryResponse(s"$base/main.js")
                    binary  <- HttpClient.getBinaryResponse(s"$base/nested/main.wasm")
                    missing <- HttpClient.getBinaryResponse(s"$base/missing.js", failOnError = false)
                yield
                    assert(root.headers.get("Content-Type") == Present("text/html; charset=utf-8"))
                    assert(new String(root.fields.body.toArray, "UTF-8").contains("window.scalajsCom"))
                    assert(module.headers.get("Content-Type") == Present("text/javascript; charset=utf-8"))
                    assert(new String(module.fields.body.toArray, "UTF-8") == "export {};")
                    assert(binary.headers.get("Content-Type") == Present("application/wasm"))
                    assert(binary.fields.body.toArray.toSeq == wasm.toArray.toSeq)
                    assert(missing.status == HttpStatus.NotFound)
                end for
            }
        }
    }

end PageServerTest
