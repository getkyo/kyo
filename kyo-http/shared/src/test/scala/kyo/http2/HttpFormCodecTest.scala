package kyo.http2

import kyo.Test

class HttpFormCodecTest extends Test:

    case class LoginForm(username: String, password: String) derives HttpFormCodec, CanEqual
    case class SearchForm(query: String, page: Int) derives HttpFormCodec, CanEqual
    case class SingleField(name: String) derives HttpFormCodec, CanEqual

    "encode" - {
        "simple case class" in {
            val codec   = summon[HttpFormCodec[LoginForm]]
            val encoded = codec.encode(LoginForm("admin", "secret"))
            assert(encoded == "username=admin&password=secret")
        }

        "with Int field" in {
            val codec   = summon[HttpFormCodec[SearchForm]]
            val encoded = codec.encode(SearchForm("hello", 3))
            assert(encoded == "query=hello&page=3")
        }

        "single field" in {
            val codec   = summon[HttpFormCodec[SingleField]]
            val encoded = codec.encode(SingleField("test"))
            assert(encoded == "name=test")
        }

        "URL-encodes special characters" in {
            val codec   = summon[HttpFormCodec[LoginForm]]
            val encoded = codec.encode(LoginForm("user@example.com", "p&ss=word"))
            assert(encoded.contains("user%40example.com"))
            assert(encoded.contains("p%26ss%3Dword"))
        }
    }

    "decode" - {
        "simple case class" in {
            val codec   = summon[HttpFormCodec[LoginForm]]
            val decoded = codec.decode("username=admin&password=secret")
            assert(decoded == LoginForm("admin", "secret"))
        }

        "with Int field" in {
            val codec   = summon[HttpFormCodec[SearchForm]]
            val decoded = codec.decode("query=hello&page=3")
            assert(decoded == SearchForm("hello", 3))
        }

        "URL-decodes values" in {
            val codec   = summon[HttpFormCodec[LoginForm]]
            val decoded = codec.decode("username=user%40example.com&password=p%26ss%3Dword")
            assert(decoded == LoginForm("user@example.com", "p&ss=word"))
        }

        "missing field throws" in {
            val codec = summon[HttpFormCodec[LoginForm]]
            assertThrows[IllegalArgumentException] {
                codec.decode("username=admin")
            }
        }

        "empty string throws for required fields" in {
            val codec = summon[HttpFormCodec[LoginForm]]
            assertThrows[IllegalArgumentException] {
                codec.decode("")
            }
        }
    }

    "roundtrip" - {
        "LoginForm" in {
            val codec = summon[HttpFormCodec[LoginForm]]
            val value = LoginForm("admin", "secret")
            assert(codec.decode(codec.encode(value)) == value)
        }

        "SearchForm" in {
            val codec = summon[HttpFormCodec[SearchForm]]
            val value = SearchForm("hello world", 42)
            assert(codec.decode(codec.encode(value)) == value)
        }

        "special characters roundtrip" in {
            val codec = summon[HttpFormCodec[LoginForm]]
            val value = LoginForm("a=b&c", "d e+f")
            assert(codec.decode(codec.encode(value)) == value)
        }
    }

end HttpFormCodecTest
