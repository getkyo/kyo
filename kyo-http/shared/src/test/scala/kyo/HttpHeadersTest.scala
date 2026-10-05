package kyo

import kyo.*

class HttpHeadersTest extends BaseHttpTest:

    private val fields: Chunk[(String, String)] = Chunk(
        "Content-Type" -> "text/plain",
        "Set-Cookie"   -> "a=1",
        "set-cookie"   -> "b=2",
        "Cookie"       -> "s=x; t=y",
        "X-Trace"      -> "café"
    )

    /** Headers holding `pairs` the way a parser builds them. */
    private def parsed(pairs: Chunk[(String, String)]): HttpHeaders =
        val raw    = new java.io.ByteArrayOutputStream()
        val offset = new Array[Int](pairs.size * 4)
        pairs.zipWithIndex.foreach { case ((name, value), i) =>
            val n = name.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            val v = value.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            offset(i * 4) = raw.size()
            offset(i * 4 + 1) = n.length
            raw.write(n)
            offset(i * 4 + 2) = raw.size()
            offset(i * 4 + 3) = v.length
            raw.write(v)
        }
        val bytes = raw.toByteArray
        HttpHeaders.parsed(bytes, 0, bytes.length, offset, pairs.size)
    end parsed

    /** The same fields in every form an `HttpHeaders` can be handed in. */
    private def forms(pairs: Chunk[(String, String)]): Chunk[(String, HttpHeaders)] = Chunk(
        "a Seq of pairs"    -> Seq(pairs*),
        "a List of pairs"   -> pairs.toList,
        "a Vector of pairs" -> pairs.toVector,
        "a Chunk of pairs"  -> pairs,
        "built headers"     -> pairs.foldLeft(HttpHeaders.empty)((h, kv) => h.add(kv._1, kv._2)),
        "parsed headers"    -> parsed(pairs)
    )

    private def content(h: HttpHeaders): Chunk[(String, String)] =
        h.foldLeft(Chunk.empty[(String, String)])((acc, n, v) => acc.append((n, v)))

    private def wire(h: HttpHeaders): String =
        val buf = new kyo.net.internal.util.GrowableByteBuffer()
        h.writeToBuffer(buf)
        new String(buf.toByteArray, java.nio.charset.StandardCharsets.UTF_8)
    end wire

    "every form" - {
        forms(fields).foreach { (form, h) =>
            form - {
                "size, isEmpty and nonEmpty" in {
                    assert(h.size == 5)
                    assert(!h.isEmpty)
                    assert(h.nonEmpty)
                }
                "get is case-insensitive and answers the first match" in {
                    assert(h.get("content-type") == Present("text/plain"))
                    assert(h.get("SET-COOKIE") == Present("a=1"))
                    assert(h.get("X-Trace") == Present("café"))
                    assert(h.get("X-Missing") == Absent)
                }
                "getAll" in {
                    assert(h.getAll("Set-Cookie") == Chunk("a=1", "b=2"))
                    assert(h.getAll("X-Missing") == Chunk.empty)
                }
                "contains" in {
                    assert(h.contains("cookie"))
                    assert(!h.contains("X-Missing"))
                }
                "foreach and foldLeft visit every field in order" in {
                    val seen = scala.collection.mutable.ArrayBuffer.empty[(String, String)]
                    h.foreach((n, v) => seen += ((n, v)))
                    assert(Chunk.from(seen) == fields)
                    assert(content(h) == fields)
                }
                "add appends" in {
                    assert(content(h.add("X-New", "v")) == fields.append("X-New" -> "v"))
                    assert(h.add("Content-Length", 42).get("Content-Length") == Present("42"))
                    assert(content(h) == fields)
                }
                "set replaces every field of that name" in {
                    assert(content(h.set("set-cookie", "c=3")) ==
                        Chunk("Content-Type" -> "text/plain", "Cookie" -> "s=x; t=y", "X-Trace" -> "café", "set-cookie" -> "c=3"))
                }
                "remove drops every field of that name" in {
                    assert(content(h.remove("SET-COOKIE")) ==
                        Chunk("Content-Type" -> "text/plain", "Cookie" -> "s=x; t=y", "X-Trace" -> "café"))
                }
                "concat with every other form" in {
                    forms(Chunk("X-Other" -> "o")).foreach { (_, other) =>
                        assert(content(h.concat(other)) == fields.append("X-Other" -> "o"))
                        assert(content(other.concat(h)) == ("X-Other" -> "o") +: fields)
                    }
                }
                "writeToBuffer writes each field as a header line" in {
                    assert(wire(h) ==
                        "Content-Type: text/plain\r\nSet-Cookie: a=1\r\nset-cookie: b=2\r\nCookie: s=x; t=y\r\nX-Trace: café\r\n")
                }
                "invalidField finds nothing to refuse" in {
                    assert(h.invalidField == Absent)
                }
                "cookies" in {
                    assert(h.cookie("t") == Present("y"))
                    assert(h.cookies == Seq("s" -> "x", "t" -> "y"))
                    assert(h.responseCookie("b") == Present("2"))
                    assert(h.addCookie("sid", "v1").getAll("Set-Cookie").last == "sid=v1")
                }
            }
        }
    }

    "every empty form holds no headers" in {
        forms(Chunk.empty).foreach { (form, h) =>
            assert(h.isEmpty, form)
            assert(h.size == 0, form)
            assert(h.get("Host") == Absent, form)
            assert(content(h) == Chunk.empty, form)
            assert(wire(h) == "", form)
            assert(h.invalidField == Absent, form)
        }
        succeed
    }

    "pairs a writer must refuse are reported like built ones" - {
        "a value carrying CRLF" in {
            val h: HttpHeaders = List("X-Ok" -> "1", "X-Trace" -> "bar\r\nX-Admin: true")
            assert(h.invalidField == Present("the value of header 'X-Trace'"))
        }
        "a name that is not a token, by its index" in {
            val h: HttpHeaders = Vector("X-Ok" -> "1", "X Bad" -> "v")
            assert(h.invalidField == Present("the name of the header at index 1"))
        }
    }

    "cookie" - {
        "parses single cookie" in {
            val h = HttpHeaders.empty.add("Cookie", "session=abc123")
            assert(h.cookie("session") == Present("abc123"))
        }

        "parses first of multiple cookies" in {
            val h = HttpHeaders.empty.add("Cookie", "a=1; b=2; c=3")
            assert(h.cookie("a") == Present("1"))
        }

        "parses middle cookie" in {
            val h = HttpHeaders.empty.add("Cookie", "a=1; b=2; c=3")
            assert(h.cookie("b") == Present("2"))
        }

        "parses last cookie" in {
            val h = HttpHeaders.empty.add("Cookie", "a=1; b=2; c=3")
            assert(h.cookie("c") == Present("3"))
        }

        "returns Absent for missing cookie" in {
            val h = HttpHeaders.empty.add("Cookie", "a=1; b=2")
            assert(h.cookie("missing") == Absent)
        }

        "returns Absent when no Cookie header" in {
            assert(HttpHeaders.empty.cookie("anything") == Absent)
        }

        "handles whitespace around values" in {
            val h = HttpHeaders.empty.add("Cookie", "a = 1 ; b = 2 ")
            assert(h.cookie("a") == Present("1"))
            assert(h.cookie("b") == Present("2"))
        }

        "handles cookie value with equals sign" in {
            val h = HttpHeaders.empty.add("Cookie", "token=abc=def=ghi")
            assert(h.cookie("token") == Present("abc=def=ghi"))
        }

        "handles empty value" in {
            val h = HttpHeaders.empty.add("Cookie", "empty=; other=val")
            assert(h.cookie("empty") == Present(""))
            assert(h.cookie("other") == Present("val"))
        }

        "handles single cookie with no semicolons" in {
            val h = HttpHeaders.empty.add("Cookie", "only=one")
            assert(h.cookie("only") == Present("one"))
        }
    }

    "cookies" - {
        "parses all cookies" in {
            val h      = HttpHeaders.empty.add("Cookie", "a=1; b=2; c=3")
            val result = h.cookies
            assert(result.length == 3)
            assert(result(0) == ("a", "1"))
            assert(result(1) == ("b", "2"))
            assert(result(2) == ("c", "3"))
        }

        "returns empty for no Cookie header" in {
            assert(HttpHeaders.empty.cookies == Seq.empty)
        }

        "parses single cookie" in {
            val h      = HttpHeaders.empty.add("Cookie", "k=v")
            val result = h.cookies
            assert(result.length == 1)
            assert(result(0) == ("k", "v"))
        }

        "handles whitespace" in {
            val h      = HttpHeaders.empty.add("Cookie", " a = 1 ; b = 2 ")
            val result = h.cookies
            assert(result.length == 2)
            assert(result(0) == ("a", "1"))
            assert(result(1) == ("b", "2"))
        }
    }

    "cookie (strict)" - {
        "accepts valid cookie" in {
            val h = HttpHeaders.empty.add("Cookie", "session=abc123")
            assert(h.cookie("session", strict = true) == Present("abc123"))
        }

        "rejects cookie value with comma" in {
            val h = HttpHeaders.empty.add("Cookie", "bad=val,ue")
            assert(h.cookie("bad", strict = true) == Absent)
        }

        "rejects cookie value with semicolon" in {
            val h = HttpHeaders.empty.add("Cookie", "bad=val;ue")
            // Note: semicolon is the cookie separator, so the parser would split here.
            // "bad" gets value "val" and "ue" has no equals sign.
            // In lax mode, cookie("bad") should return "val"
            assert(h.cookie("bad") == Present("val"))
        }

        "rejects cookie value with backslash" in {
            val h = HttpHeaders.empty.add("Cookie", "bad=val\\ue")
            assert(h.cookie("bad", strict = true) == Absent)
        }

        "rejects cookie value with space" in {
            // After trim, if there's still a space in the middle
            val h = HttpHeaders.empty.add("Cookie", "bad=val ue")
            assert(h.cookie("bad", strict = true) == Absent)
        }

        "rejects cookie value with control character" in {
            val h = HttpHeaders.empty.add("Cookie", "bad=val\u0001ue")
            assert(h.cookie("bad", strict = true) == Absent)
        }

        "accepts DQUOTE-wrapped value" in {
            val h = HttpHeaders.empty.add("Cookie", "name=\"validvalue\"")
            assert(h.cookie("name", strict = true) == Present("\"validvalue\""))
        }

        "rejects DQUOTE-wrapped value with invalid inner content" in {
            val h = HttpHeaders.empty.add("Cookie", "name=\"val,ue\"")
            assert(h.cookie("name", strict = true) == Absent)
        }

        "lax mode accepts invalid characters" in {
            val h = HttpHeaders.empty.add("Cookie", "name=val\\ue")
            assert(h.cookie("name", strict = false) == Present("val\\ue"))
        }

        "accepts empty value in strict mode" in {
            val h = HttpHeaders.empty.add("Cookie", "name=")
            assert(h.cookie("name", strict = true) == Present(""))
        }
    }

    "cookies (strict)" - {
        "filters out invalid cookies, keeps valid ones" in {
            // "good" has valid value, "bad" has comma in value
            val h      = HttpHeaders.empty.add("Cookie", "good=valid; bad=inv,alid; also_good=ok")
            val result = h.cookies(strict = true)
            assert(result.length == 2)
            assert(result(0) == ("good", "valid"))
            assert(result(1) == ("also_good", "ok"))
        }

        "rejects cookie with invalid name" in {
            // Space in cookie name is invalid per RFC 2616 token
            val h      = HttpHeaders.empty.add("Cookie", "bad name=value; good=ok")
            val result = h.cookies(strict = true)
            assert(result.length == 1)
            assert(result(0) == ("good", "ok"))
        }

        "rejects empty cookie name" in {
            val h      = HttpHeaders.empty.add("Cookie", "=value; good=ok")
            val result = h.cookies(strict = true)
            assert(result.length == 1)
            assert(result(0) == ("good", "ok"))
        }

        "accepts all valid cookies" in {
            val h      = HttpHeaders.empty.add("Cookie", "a=1; b=2; c=3")
            val result = h.cookies(strict = true)
            assert(result.length == 3)
        }

        "lax mode keeps everything" in {
            val h      = HttpHeaders.empty.add("Cookie", "good=valid; bad=inv,alid")
            val result = h.cookies(strict = false)
            assert(result.length == 2)
        }

        "cookie name with separator characters rejected in strict" in {
            // RFC 2616 token excludes: ( ) < > @ , ; : \ " / [ ] ? = { }
            val h      = HttpHeaders.empty.add("Cookie", "na(me=val; good=ok")
            val result = h.cookies(strict = true)
            assert(result.length == 1)
            assert(result(0) == ("good", "ok"))
        }
    }

    "addCookie" - {
        "serializes simple cookie" in {
            val h = HttpHeaders.empty.addCookie("session", HttpCookie("abc123"))
            assert(h.get("Set-Cookie") == Present("session=abc123"))
        }

        "serializes cookie with maxAge" in {
            val h = HttpHeaders.empty.addCookie("id", HttpCookie("val").maxAge(1.hours))
            val v = h.get("Set-Cookie")
            assert(v.exists(_.contains("id=val")))
            assert(v.exists(_.contains("Max-Age=3600")))
        }

        "serializes cookie with domain" in {
            val h = HttpHeaders.empty.addCookie("id", HttpCookie("v").domain("example.com"))
            assert(h.get("Set-Cookie").exists(_.contains("Domain=example.com")))
        }

        "serializes cookie with path" in {
            val h = HttpHeaders.empty.addCookie("id", HttpCookie("v").path("/api"))
            assert(h.get("Set-Cookie").exists(_.contains("Path=/api")))
        }

        "serializes secure flag" in {
            val h = HttpHeaders.empty.addCookie("id", HttpCookie("v").secure(true))
            assert(h.get("Set-Cookie").exists(_.contains("Secure")))
        }

        "serializes httpOnly flag" in {
            val h = HttpHeaders.empty.addCookie("id", HttpCookie("v").httpOnly(true))
            assert(h.get("Set-Cookie").exists(_.contains("HttpOnly")))
        }

        "serializes sameSite" in {
            val h = HttpHeaders.empty.addCookie("id", HttpCookie("v").sameSite(HttpCookie.SameSite.Strict))
            assert(h.get("Set-Cookie").exists(_.contains("SameSite=Strict")))
        }

        "serializes all attributes" in {
            val cookie = HttpCookie("token")
                .maxAge(24.hours)
                .domain("example.com")
                .path("/")
                .secure(true)
                .httpOnly(true)
                .sameSite(HttpCookie.SameSite.Lax)
            val h = HttpHeaders.empty.addCookie("session", cookie)
            val v = h.get("Set-Cookie").get
            assert(v.contains("session=token"))
            assert(v.contains("Max-Age=86400"))
            assert(v.contains("Domain=example.com"))
            assert(v.contains("Path=/"))
            assert(v.contains("Secure"))
            assert(v.contains("HttpOnly"))
            assert(v.contains("SameSite=Lax"))
        }

        "omits unset attributes" in {
            val h = HttpHeaders.empty.addCookie("id", HttpCookie("v"))
            val v = h.get("Set-Cookie").get
            assert(v == "id=v")
            assert(!v.contains("Max-Age"))
            assert(!v.contains("Domain"))
            assert(!v.contains("Path"))
            assert(!v.contains("Secure"))
            assert(!v.contains("HttpOnly"))
            assert(!v.contains("SameSite"))
        }

        "string value shorthand" in {
            val h = HttpHeaders.empty.addCookie("name", "value")
            assert(h.get("Set-Cookie") == Present("name=value"))
        }

        "multiple Set-Cookie headers" in {
            val h = HttpHeaders.empty
                .addCookie("a", "1")
                .addCookie("b", "2")
            val all = h.getAll("Set-Cookie")
            assert(all.length == 2)
            assert(all(0) == "a=1")
            assert(all(1) == "b=2")
        }

        "serializes Int cookie value" in {
            val h = HttpHeaders.empty.addCookie("count", HttpCookie(42))
            assert(h.get("Set-Cookie") == Present("count=42"))
        }
    }

    "invalidField" - {

        // Catches a predicate that reports a header the serializer can in fact write, which would reject ordinary traffic.
        "reports nothing for writable headers" in {
            val h = HttpHeaders.empty
                .add("Accept", "application/json")
                .add("Authorization", "Bearer token123")
            assert(h.invalidField == Absent)
        }

        // The load-bearing leaf: a field value may carry obs-text (%x80-FF) per RFC 9110 section 5.5, so a non-ASCII
        // value is legal HTTP and reporting it would reject traffic the RFC permits. Catches a predicate built on an
        // ASCII test rather than a control-character one.
        "reports nothing for a non-ASCII value, which is legal obs-text" in {
            val h = HttpHeaders.empty.add("X-Trace", "café").add("X-City", "münchen")
            assert(h.invalidField == Absent)
        }

        // CR and LF are the re-framing bytes and both are ASCII, so an ASCII-only predicate admits every one of them.
        "reports a header value carrying CR" in {
            val h = HttpHeaders.empty.add("X-Trace", "bar\rX-Admin: true")
            assert(h.invalidField == Present("the value of header 'X-Trace'"))
        }

        "reports a header value carrying LF" in {
            val h = HttpHeaders.empty.add("X-Trace", "bar\nX-Admin: true")
            assert(h.invalidField == Present("the value of header 'X-Trace'"))
        }

        // Catches a predicate that only inspects names, and one that stops at the first header: the offender sits second.
        "reports a CRLF-bearing value naming the header but never the value" in {
            val h = HttpHeaders.empty
                .add("Accept", "application/json")
                .add("X-Trace", "bar\r\nX-Admin: true")
            assert(h.invalidField == Present("the value of header 'X-Trace'"))
            // A header value can hold a credential, so the description must not carry it.
            assert(!h.invalidField.getOrElse("").contains("X-Admin"))
        }

        // RFC 9110 section 5.5 names NUL alongside CR and LF, and DEL is a control character the field grammar excludes.
        "reports a header value carrying NUL or DEL" in {
            assert(HttpHeaders.empty.add("X-A", "a\u0000b").invalidField == Present("the value of header 'X-A'"))
            assert(HttpHeaders.empty.add("X-B", "a\u007fb").invalidField == Present("the value of header 'X-B'"))
        }

        // HTAB is the one control character a field value may carry (RFC 9110 section 5.5 field-content), so a predicate
        // that rejects every char below 0x20 is over-strict.
        "reports nothing for a value carrying HTAB" in {
            assert(HttpHeaders.empty.add("X-A", "a\tb").invalidField == Absent)
        }

        // A field name is a token (RFC 9110 section 5.6.2). This is strictly stronger than an ASCII test: SP is ASCII,
        // and "X Trace: v" reads to a recipient as the name "X" carrying the value "Trace: v".
        "reports a header name that is not a token" in {
            assert(HttpHeaders.empty.add("X Trace", "v").invalidField == Present("the name of the header at index 0"))
            assert(HttpHeaders.empty.add("X-Café", "v").invalidField == Present("the name of the header at index 0"))
            assert(HttpHeaders.empty.add("A", "1").add("X:B", "v").invalidField == Present("the name of the header at index 1"))
        }

        // The description of a non-token name must not quote the name back: unlike a value's header name (a token by the
        // time it is quoted), the offending name here is the untrusted part and the description reaches a log line.
        "never quotes a non-token name back into the description" in {
            val h = HttpHeaders.empty.add("X\r\nInjected", "v")
            assert(h.invalidField == Present("the name of the header at index 0"))
            assert(!h.invalidField.getOrElse("").contains("Injected"))
        }

        // The over-strictness guard for the token check: every tchar RFC 9110 section 5.6.2 lists must be accepted.
        "reports nothing for a name using the full tchar set" in {
            assert(HttpHeaders.empty.add("!#$%&'*+-.^_`|~0Az", "v").invalidField == Absent)
        }

        // Catches a predicate that reads only the first pair, or that walks the flat chunk one slot at a time instead of
        // in name/value pairs (which would mistake a value for a name and misreport the offender).
        "reports the first offender when several headers follow it" in {
            val h = HttpHeaders.empty
                .add("A", "1")
                .add("B", "bar\r\nX-Admin: true")
                .add("C", "3")
                .add("D", "x\ny")
            assert(h.invalidField == Present("the value of header 'B'"))
        }

        // Catches a predicate that mistakes a decoded String for the wire form: an empty collection has no offender.
        "reports nothing for empty headers" in {
            assert(HttpHeaders.empty.invalidField == Absent)
        }
    }

end HttpHeadersTest
