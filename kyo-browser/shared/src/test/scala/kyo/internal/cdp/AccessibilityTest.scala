package kyo.internal.cdp

import kyo.*
import kyo.internal.CdpClient
import kyo.internal.SharedChrome
import kyo.internal.cdp.Accessibility

/** Pure + integration tests for [[Accessibility]].
  *
  * Pure scenarios (no browser, no I/O) exercise the custom [[Accessibility.AxValue]] Schema and the end-to-end
  * [[Accessibility.parseAxTree]] decoder. Integration scenarios drive a real Chrome to verify [[Accessibility.getFullAXTree]] returns the
  * expected wire shape.
  */
class AccessibilityTest extends kyo.BrowserTest:

    // --- helpers ---

    /** Wraps an inner AX-tree payload in the CDP reply envelope (`{"id":..,"result":{...}}`).
      * [[Accessibility.parseAxTree]] decodes from this whole-wire shape.
      */
    private def wrap(innerTree: String): String = s"""{"id":1,"result":$innerTree}"""

    // -----------------------------------------------------------------------
    // AxValue Schema: per-discriminator behaviour
    // -----------------------------------------------------------------------

    "Accessibility.AxValue Schema decodes each CDP discriminator" - {

        import Accessibility.AxValue
        import Accessibility.AxValue.asString

        "string lifts the JSON string verbatim into the `string` variant" in {
            given Frame = Frame.internal
            val json    = """{"type":"string","value":"hello"}"""
            Json.decode[AxValue](json) match
                case Result.Success(v: AxValue.`string`) =>
                    assert(v.value == "hello")
                    assert(v.asString == Present("hello"))
                case other => fail(s"expected AxValue.string but got $other")
            end match
        }

        "computedString / token / role / internalRole / tokenList lift string-typed values into their own variant" in {
            given Frame = Frame.internal
            val cases: Seq[(String, AxValue)] = Seq(
                "computedString" -> AxValue.`computedString`("X"),
                "token"          -> AxValue.`token`("X"),
                "role"           -> AxValue.`role`("X"),
                "internalRole"   -> AxValue.`internalRole`("X"),
                "tokenList"      -> AxValue.`tokenList`("X")
            )
            cases.foreach { (kind, expected) =>
                val json = s"""{"type":"$kind","value":"X"}"""
                Json.decode[AxValue](json) match
                    case Result.Success(v) =>
                        assert(v == expected, s"kind $kind decoded as $v")
                        assert(v.asString == Present("X"))
                    case other => fail(s"kind $kind failed: $other")
                end match
            }
            ()
        }

        "boolean true / false decode into AxValue.boolean with the typed value" in {
            given Frame = Frame.internal
            Json.decode[AxValue]("""{"type":"boolean","value":true}""") match
                case Result.Success(v: AxValue.`boolean`) =>
                    assert(v.value == true)
                    assert(v.asString == Present("true"))
                case other => fail(s"true case: $other")
            end match
            Json.decode[AxValue]("""{"type":"boolean","value":false}""") match
                case Result.Success(v: AxValue.`boolean`) =>
                    assert(v.value == false)
                    assert(v.asString == Present("false"))
                case other => fail(s"false case: $other")
            end match
        }

        "number preserves int-vs-decimal source distinction via asString (integer stays 'N', decimal stays 'N.M')" in {
            given Frame = Frame.internal
            Json.decode[AxValue]("""{"type":"number","value":3.5}""") match
                case Result.Success(v: AxValue.`number`) =>
                    assert(v.value == 3.5)
                    assert(v.asString == Present("3.5"), s"decimal case: got ${v.asString}")
                case other => fail(s"decimal case: $other")
            end match
            Json.decode[AxValue]("""{"type":"number","value":20}""") match
                case Result.Success(v: AxValue.`number`) =>
                    assert(v.value == 20.0)
                    assert(v.asString == Present("20"), s"integer case: got ${v.asString}")
                case other => fail(s"integer case: $other")
            end match
        }

        "integer lifts the integer literal as the typed Long value" in {
            given Frame = Frame.internal
            val json    = """{"type":"integer","value":42}"""
            Json.decode[AxValue](json) match
                case Result.Success(v: AxValue.`integer`) =>
                    assert(v.value == 42L)
                    assert(v.asString == Present("42"))
                case other => fail(s"$other")
            end match
        }

        "idref / idrefList (object / array payloads) decode into their own zero-field variant; asString is Absent" in {
            given Frame = Frame.internal
            // CDP's idref / idrefList variants carry object / array payloads. The discriminated variants carry no
            // typed fields; permissive decoding skips the wire `value` payload, and `asString` returns Absent.
            val idref = """{"type":"idref","value":{"idref":"x","backendDOMNodeId":7}}"""
            Json.decode[AxValue](idref) match
                case Result.Success(v: AxValue.`idref`) => assert(v.asString == Absent)
                case other                              => fail(s"$other")
            end match

            val idrefList = """{"type":"idrefList","value":[{"idref":"a"}]}"""
            Json.decode[AxValue](idrefList) match
                case Result.Success(v: AxValue.`idrefList`) => assert(v.asString == Absent)
                case other                                  => fail(s"$other")
            end match
        }

        "unknown discriminator value surfaces as a typed UnknownVariantException at decode time" in {
            given Frame = Frame.internal
            val json    = """{"type":"unknownVariantForTestOnly","value":"mixed"}"""
            Json.decode[AxValue](json) match
                case Result.Failure(ex: kyo.UnknownVariantException) => assert(ex.variantName == "unknownVariantForTestOnly")
                case other => fail(s"expected UnknownVariantException for unknown discriminator but got: $other")
            end match
        }

        "encode/decode round-trips a string-typed value" in {
            given Frame           = Frame.internal
            val original: AxValue = AxValue.`string`("OK")
            val encoded           = Json.encode(original)
            assert(encoded == """{"type":"string","value":"OK"}""", s"encoded=$encoded")
            Json.decode[AxValue](encoded) match
                case Result.Success(v) => assert(v == original)
                case other             => fail(s"$other")
        }
    }

    // -----------------------------------------------------------------------
    // parseAxTree: end-to-end wire decoding
    // -----------------------------------------------------------------------

    "parseAxTree returns Abort failure on malformed JSON" in {
        val result = Abort.run(Accessibility.parseAxTree("not valid json {{ "))
        result.map { r =>
            assert(r.isFailure, s"expected Failure but got $r")
        }
    }

    "parseAxTree returns Chunk.empty when the result has no nodes field" in {
        val result = Abort.run(Accessibility.parseAxTree(wrap("""{}""")))
        result.map { r =>
            assert(r == Result.Success(Chunk.empty[Accessibility.AxNode]), s"expected Chunk.empty but got $r")
        }
    }

    "parseAxTree returns Chunk.empty when nodes is empty" in {
        val result = Abort.run(Accessibility.parseAxTree(wrap("""{"nodes":[]}""")))
        result.map { r =>
            assert(r == Result.Success(Chunk.empty[Accessibility.AxNode]), s"got $r")
        }
    }

    "parseAxTree projects role, name, and string properties into AxNode" in {
        val tree = wrap(
            """{"nodes":[
              |  {"nodeId":"1","ignored":false,
              |   "role":{"type":"role","value":"button"},
              |   "name":{"type":"computedString","value":"Save"},
              |   "properties":[
              |     {"name":"disabled","value":{"type":"boolean","value":true}},
              |     {"name":"level","value":{"type":"integer","value":3}}
              |   ]}
              |]}""".stripMargin
        )
        Abort.run(Accessibility.parseAxTree(tree)).map { r =>
            r match
                case Result.Success(nodes) =>
                    assert(nodes.size == 1, s"got ${nodes.size}")
                    val n = nodes.head
                    assert(n.nodeId == "1")
                    assert(n.role == "button")
                    assert(n.name == "Save")
                    assert(n.properties.get("disabled") == Present("true"))
                    assert(n.properties.get("level") == Present("3"))
                case other => fail(s"$other")
        }
    }

    "parseAxTree surfaces backendDOMNodeId as a string entry in properties when the wire field is present" in {
        val tree = wrap(
            """{"nodes":[
              |  {"nodeId":"1","backendDOMNodeId":42,"ignored":false,
              |   "role":{"type":"role","value":"button"},
              |   "name":{"type":"computedString","value":"Save"},
              |   "properties":[]}
              |]}""".stripMargin
        )
        Abort.run(Accessibility.parseAxTree(tree)).map { r =>
            r match
                case Result.Success(nodes) =>
                    assert(nodes.size == 1)
                    assert(nodes.head.properties.get("backendDOMNodeId") == Present("42"))
                case other => fail(s"$other")
        }
    }

    "parseAxTree omits backendDOMNodeId entry when the wire field is absent (virtual AX-only node)" in {
        val tree = wrap(
            """{"nodes":[
              |  {"nodeId":"1","ignored":false,
              |   "role":{"type":"role","value":"none"},
              |   "name":{"type":"computedString","value":""},
              |   "properties":[]}
              |]}""".stripMargin
        )
        Abort.run(Accessibility.parseAxTree(tree)).map { r =>
            r match
                case Result.Success(nodes) =>
                    assert(nodes.size == 1)
                    assert(nodes.head.properties.get("backendDOMNodeId") == Absent)
                case other => fail(s"$other")
        }
    }

    "parseAxTree drops properties whose value is non-stringifiable (idref payload)" in {
        val tree = wrap(
            """{"nodes":[
              |  {"nodeId":"1","ignored":false,
              |   "role":{"type":"role","value":"button"},
              |   "name":{"type":"computedString","value":"X"},
              |   "properties":[
              |     {"name":"labelledby","value":{"type":"idref","value":{"idref":"l1","backendDOMNodeId":9}}},
              |     {"name":"focused","value":{"type":"boolean","value":true}}
              |   ]}
              |]}""".stripMargin
        )
        Abort.run(Accessibility.parseAxTree(tree)).map { r =>
            r match
                case Result.Success(nodes) =>
                    val n = nodes.head
                    assert(n.properties.get("labelledby") == Absent, s"idref must be dropped, got ${n.properties}")
                    assert(n.properties.get("focused") == Present("true"), s"boolean must be kept")
                case other => fail(s"$other")
        }
    }

    "parseAxTree surfaces a CDP error from the envelope's error field as BrowserProtocolErrorException" in {
        val errWire = """{"id":1,"error":{"code":-32000,"message":"Cannot find context"}}"""
        Abort.run(Accessibility.parseAxTree(errWire)).map {
            case Result.Failure(ex: BrowserProtocolErrorException) =>
                assert(ex.error.contains("Cannot find context"), s"got '${ex.error}'")
            case other => fail(s"expected BrowserProtocolErrorException but got $other")
        }
    }

    // -----------------------------------------------------------------------
    // Integration scenarios: real Chrome
    // -----------------------------------------------------------------------

    "getFullAXTree returns a non-empty tree for a page with a button" in {
        withBrowser {
            val html = page("<button id='ok'>OK</button>")
            Browser.goto(html).andThen {
                Browser.use { tab =>
                    val s = tab.client.withSession(tab.sessionId)
                    Accessibility.getFullAXTree(s).map { nodes =>
                        assert(nodes.nonEmpty, s"expected a non-empty AX tree but got $nodes")
                    }
                }
            }
        }
    }

    "AX tree entries carry role, name, and a properties map with state keys" in {
        withBrowser {
            val html = page("<button id='ok' disabled aria-label='OK'>OK</button>")
            Browser.goto(html).andThen {
                Browser.use { tab =>
                    val s = tab.client.withSession(tab.sessionId)
                    Accessibility.getFullAXTree(s).map { nodes =>
                        val button = nodes.find(n => n.role == "button")
                        assert(button.isDefined, s"expected a button AX node but got roles=${nodes.map(_.role).distinct}")
                        val b = button.get
                        assert(b.role == "button", s"expected role='button' but got '${b.role}'")
                        assert(b.name == "OK", s"expected name='OK' but got '${b.name}'")
                        assert(
                            b.properties.get("disabled").contains("true"),
                            s"expected properties('disabled')='true' but got ${b.properties}"
                        )
                    }
                }
            }
        }
    }

    "getFullAXTree propagates BrowserConnectionException via typed Abort on a closed client" in {
        SharedChrome.init.map { wsUrl =>
            for
                client <- CdpClient.initUnscoped(wsUrl, Browser.LaunchConfig.default)
                _      <- client.close(30.seconds)
                result <- Abort.run[BrowserConnectionException](Accessibility.getFullAXTree(client))
            yield result match
                case Result.Failure(_: BrowserConnectionException) =>
                    succeed("Accessibility.getFullAXTree on a closed client surfaces as a typed BrowserConnectionException")
                case other => fail(s"expected Accessibility wrapper to fail with BrowserConnectionException but got $other")
            end for
        }
    }

end AccessibilityTest
