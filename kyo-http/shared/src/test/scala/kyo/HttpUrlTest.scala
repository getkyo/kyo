package kyo

import kyo.*
import kyo.HttpUrlParseException.Reason

class HttpUrlTest extends BaseHttpTest:

    private def reason(url: String): Maybe[Reason] =
        HttpUrl.parse(url) match
            case Result.Failure(e: HttpUrlParseException) => Present(e.reason)
            case _                                        => Absent

    "parse refuses" - {

        "empty text" in {
            assert(reason("") == Present(Reason.Empty))
        }

        "a URL with no host" in {
            val inputs = Chunk("http://", "https://", "http:///path", "http://:8080/", "ws://", "http+unix:///path")
            assert(inputs.map(reason) == Chunk.fill(6)(Present(Reason.EmptyHost)))
        }

        "text that is neither an absolute URL nor a path from the root" in {
            assert(Chunk("not a url", "example.com/x", "?q=1", "//example.com/x").map(reason) == Chunk.fill(4)(Present(Reason.Relative)))
        }

        "text before :// that is not a scheme" in {
            assert(Chunk("://x", "1http://x", "ht tp://x").map(reason) == Chunk.fill(3)(Present(Reason.InvalidScheme)))
        }

        "a scheme kyo-http does not send to" in {
            assert(reason("ftp://x/") == Present(Reason.UnsupportedScheme("ftp")))
        }

        "a space or a control character anywhere, at its position" in {
            assert(Chunk(
                "https://exa mple.com/x",
                "https://example.com/a b",
                "https://example.com/?q=a b",
                "/a b",
                "https://example.com/\t"
            ).map(reason) == Chunk(
                Present(Reason.InvalidAuthority(11)),
                Present(Reason.InvalidCharacter(21)),
                Present(Reason.InvalidCharacter(24)),
                Present(Reason.InvalidCharacter(2)),
                Present(Reason.InvalidCharacter(20))
            ))
        }

        "a character RFC 3986 excludes from every component" in {
            assert(Chunk(
                "https://example.com/a|b",
                "https://example.com/{x}",
                "https://example.com/a\"b",
                "https://example.com/a<b>"
            ).map(reason) == Chunk(
                Present(Reason.InvalidCharacter(21)),
                Present(Reason.InvalidCharacter(20)),
                Present(Reason.InvalidCharacter(21)),
                Present(Reason.InvalidCharacter(21))
            ))
        }

        "a percent sign that does not start a two-digit hex escape" in {
            assert(Chunk("https://example.com/%zz", "https://example.com/a%2", "https://example.com/?q=50%").map(reason) ==
                Chunk(20, 21, 25).map(p => Present(Reason.InvalidPercentEncoding(p))))
        }

        "a host with a character outside a registered name" in {
            assert(Chunk("http://a{b}/", "http://a\"b/", "http://ex%ample.com/").map(reason) == Chunk(
                Present(Reason.InvalidAuthority(8)),
                Present(Reason.InvalidAuthority(8)),
                Present(Reason.InvalidPercentEncoding(9))
            ))
        }

        "a port that is not 1 to 5 digits up to 65535" in {
            assert(Chunk("http://host:abc/", "http://host:99999/", "http://host:-1/", "http://host:1a/").map(reason) ==
                Chunk.fill(4)(Present(Reason.InvalidPort(12))))
        }

        "an IP literal without its closing bracket, with text after it, or that is not an address" in {
            assert(Chunk("http://[::1/", "http://[::1]x/", "http://[not-an-ip]/").map(reason) ==
                Chunk(7, 12, 8).map(p => Present(Reason.InvalidAuthority(p))))
        }

        "and the failure names the rule without the query" in {
            // Built from parts: the message quotes the source lines around its frame, which hold this leaf's literals.
            val marker = Seq("query", "marker").mkString("-")
            HttpUrl.parse(s"https://exa mple.com/x?token=$marker") match
                case Result.Failure(e: HttpUrlParseException) =>
                    assert(e.getMessage.contains("the authority has an invalid character at position 11"))
                    assert(!e.getMessage.contains(marker))
                case other => fail(s"expected a refusal, got $other")
            end match
        }
    }

    "parse accepts" - {

        "an empty port as the scheme's default" in {
            assert(HttpUrl.parse("http://host:/p").map(u => (u.host, u.port)) == Result.succeed(("host", 80)))
        }

        "a registered name with sub-delims and percent escapes, and the characters a path, query and fragment allow" in {
            val url = HttpUrl.parse("https://a-b.c~d!$&'()*+,;=%41/p:@!$&'()*+,;=-._~%20?q=/?:@&=+$,#frag/?").getOrThrow
            assert((url.host, url.path, url.rawQuery) == ("a-b.c~d!$&'()*+,;=%41", "/p:@!$&'()*+,;=-._~%20", Present("q=/?:@&=+$,")))
        }

        "an IP literal with its port" in {
            assert(HttpUrl.parse("http://[::1]:8080/p").map(u => (u.host, u.port)) == Result.succeed(("::1", 8080)))
        }

        "a path from the root, with its query" in {
            assert(HttpUrl.parse("/a/b?c=d").map(u => (u.scheme, u.path, u.rawQuery)) == Result.succeed((Absent, "/a/b", Present("c=d"))))
        }

        "the largest port" in {
            assert(HttpUrl.parse("http://host:65535/").map(_.port) == Result.succeed(65535))
        }

        "characters beyond ASCII, which the send boundary refuses with HttpNonAsciiException" in {
            assert(HttpUrl.parse("http://münchen.de/café").map(u => (u.host, u.path)) == Result.succeed(("münchen.de", "/café")))
        }
    }

    "a Unix socket path" - {

        val socket = HttpUrl(Present("http"), "localhost", 80, "/p", Absent, Present("/tmp/a b+c.sock"))

        "is written with RFC 3986 escapes: a space is %20 and a '+' is %2B" in {
            assert(socket.full == "http+unix://%2Ftmp%2Fa%20b%2Bc.sock/p")
        }

        "reads a '+' as itself, not as a space" in {
            assert(HttpUrl.parse("http+unix://%2Ftmp%2Fa+b.sock/p").map(_.unixSocket) == Result.succeed(Present("/tmp/a+b.sock")))
        }

        "round-trips through full and parse" in {
            assert(HttpUrl.parse(socket.full).map(_.unixSocket) == Result.succeed(socket.unixSocket))
        }
    }

    "resolve" - {

        // RFC 3986 section 5.4's base and examples. A fragment is dropped, as parse drops it.
        val base = HttpUrl.parse("http://a/b/c/d;p?q").getOrThrow

        def resolved(reference: String): Maybe[String] =
            HttpUrl.resolve(base, reference) match
                case Result.Success(url) => Present(url.full)
                case _                   => Absent

        "the normal examples of section 5.4.1" in {
            val cases = Chunk(
                "g"       -> "http://a/b/c/g",
                "./g"     -> "http://a/b/c/g",
                "g/"      -> "http://a/b/c/g/",
                "/g"      -> "http://a/g",
                "//g"     -> "http://g/",
                "?y"      -> "http://a/b/c/d;p?y",
                "g?y"     -> "http://a/b/c/g?y",
                "#s"      -> "http://a/b/c/d;p?q",
                "g#s"     -> "http://a/b/c/g",
                ";x"      -> "http://a/b/c/;x",
                "g;x"     -> "http://a/b/c/g;x",
                ""        -> "http://a/b/c/d;p?q",
                "."       -> "http://a/b/c/",
                "./"      -> "http://a/b/c/",
                ".."      -> "http://a/b/",
                "../"     -> "http://a/b/",
                "../g"    -> "http://a/b/g",
                "../.."   -> "http://a/",
                "../../"  -> "http://a/",
                "../../g" -> "http://a/g"
            )
            assert(cases.map((reference, _) => resolved(reference)) == cases.map((_, expected) => Present(expected)))
        }

        "the abnormal examples of section 5.4.2" in {
            val cases = Chunk(
                "../../../g"    -> "http://a/g",
                "../../../../g" -> "http://a/g",
                "/./g"          -> "http://a/g",
                "/../g"         -> "http://a/g",
                "g."            -> "http://a/b/c/g.",
                ".g"            -> "http://a/b/c/.g",
                "g.."           -> "http://a/b/c/g..",
                "..g"           -> "http://a/b/c/..g",
                "./../g"        -> "http://a/b/g",
                "./g/."         -> "http://a/b/c/g/",
                "g/./h"         -> "http://a/b/c/g/h",
                "g/../h"        -> "http://a/b/c/h"
            )
            assert(cases.map((reference, _) => resolved(reference)) == cases.map((_, expected) => Present(expected)))
        }

        "an absolute reference is itself, with its dot segments removed" in {
            assert(resolved("https://x:8443/y/../z?w") == Present("https://x:8443/z?w"))
        }

        "a relative reference on a Unix socket URL keeps the socket" in {
            val socket = HttpUrl.parse("http+unix://%2Ftmp%2Fd.sock/v1/containers/json").getOrThrow
            assert(HttpUrl.resolve(socket, "../images/json").map(u => (u.unixSocket, u.path)) ==
                Result.succeed((Present("/tmp/d.sock"), "/v1/images/json")))
        }

        "a reference parse would refuse is refused with the same reason" in {
            def refusal(reference: String): Maybe[Reason] =
                HttpUrl.resolve(base, reference) match
                    case Result.Failure(e: HttpUrlParseException) => Present(e.reason)
                    case _                                        => Absent
            assert(Chunk("g h", "?a b", "//x y/", "ftp://x/", "//").map(refusal) == Chunk(
                Present(Reason.InvalidCharacter(1)),
                Present(Reason.InvalidCharacter(2)),
                Present(Reason.InvalidAuthority(3)),
                Present(Reason.UnsupportedScheme("ftp")),
                Present(Reason.EmptyHost)
            ))
        }
    }

end HttpUrlTest
