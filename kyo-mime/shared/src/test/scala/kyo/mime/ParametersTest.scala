package kyo.mime

import kyo.*
import kyo.mime.Parameters.Value

class ParametersTest extends kyo.test.Test[Any]:

    private def read(value: String): Chunk[(String, Value)] = Parameters.read(value, 0)

    private def octets(value: Value): Seq[Int] =
        value match
            case Value.Encoded(_, _, octets) => octets.toArray.toSeq.map(_ & 0xff)
            case Value.Text(_)               => Seq.empty

    private def ascii(s: String): Seq[Int] = s.map(_.toInt)

    "RFC 2231 section 7 name forms" - {
        "name*N segments are joined in index order, however written" in {
            assert(read("; name*1=b; name*0=a; name*2=c") == Chunk("name" -> Value.Text("abc")))
            assert(read("; name*0=a; name*2=c; name*5=f") == Chunk("name" -> Value.Text("acf")))
            assert(read("; name*0=a; name*0=x; name*1=b") == Chunk("name" -> Value.Text("ab")))
            assert(read("; name*01=b; name*00=a") == Chunk("name" -> Value.Text("ab")))
        }
        "a section of more than nine digits, or a name of another shape, is a plain parameter of that whole name" in {
            assert(read("; a*1234567890=x; a*b=1; 3**=2") ==
                Chunk("a*1234567890" -> Value.Text("x"), "a*b" -> Value.Text("1"), "3**" -> Value.Text("2")))
        }
        "the continuation set wins over name*, which wins over the plain form, at the position of the first" in {
            assert(read("; a=1; name=plain; b=2; name*=utf-8''ext").map(_._1) == Chunk("a", "name", "b"))
            assert(read("; name*=utf-8''single; name*0=con; name*1=tinued") == Chunk("name" -> Value.Text("continued")))
        }
        "names are lowercased and a name written twice keeps its first value" in {
            assert(read("; A=1; a=2") == Chunk("a" -> Value.Text("1")))
        }
    }

    "an encoded value" - {
        "carries its charset, language and octets, %XX in either case decoded and a bad escape kept" in {
            val Chunk(("name", value)) = read("; name*=utf-8'en-us'a%c3%A9%zz%4"): @unchecked
            assert(value == Value.Encoded(Present("utf-8"), Present("en-us"), octetsOf(value)))
            assert(octets(value) == Seq(0x61, 0xc3, 0xa9) ++ ascii("%zz%4"))
        }
        "the charset and language of segment 0 apply to every segment, encoded or not" in {
            val Chunk(("name", value)) = read("; name*0*=iso-8859-1''caf%E9; name*1=\" au lait\""): @unchecked
            assert(value.asInstanceOf[Value.Encoded].charset == Present("iso-8859-1"))
            assert(value.asInstanceOf[Value.Encoded].language == Absent)
            assert(octets(value) == ascii("caf") ++ Seq(0xe9) ++ ascii(" au lait"))
        }
        "an unencoded segment's octets come from the caller's function, UTF-8 by default" in {
            val utf8   = read("; name*0*=iso-8859-1''a; name*1=é")
            val latin1 = Parameters.read("; name*0*=iso-8859-1''a; name*1=é", 0, s => Span.from(s.map(_.toByte).toArray))
            assert(octets(utf8.head._2) == Seq(0x61, 0xc3, 0xa9))
            assert(octets(latin1.head._2) == Seq(0x61, 0xe9))
        }
        "an empty charset or an empty language is absent" in {
            val Chunk(("name", value)) = read("; name*=''caf%E9"): @unchecked
            assert(value == Value.Encoded(Absent, Absent, octetsOf(value)))
            assert(octets(value) == ascii("caf") :+ 0xe9)
        }
        "segment 0 without the two quotes is data with no charset" in {
            val Chunk(("name", value)) = read("; name*0*=abc%41"): @unchecked
            assert(value.asInstanceOf[Value.Encoded].charset == Absent)
            assert(octets(value) == ascii("abcA"))
        }
        "only segment 0 carries a charset: a later segment's charset-like prefix is data" in {
            val Chunk(("name", value)) = read("; name*0*=iso-8859-1''a; name*1*=iso-8859-1''b"): @unchecked
            assert(octets(value) == ascii("aiso-8859-1''b"))
        }
        "an extended value written as a quoted string has its quotes removed first" in {
            val Chunk(("name", value)) = read("; name*=\"iso-8859-1''HasenundFr%F6sche.txt\""): @unchecked
            assert(value.asInstanceOf[Value.Encoded].charset == Present("iso-8859-1"))
            assert(octets(value) == ascii("HasenundFr") ++ Seq(0xf6) ++ ascii("sche.txt"))
        }
        "with no encoded segment the segments are text, not decoded" in {
            assert(read("; name*0=%41; name*1=b") == Chunk("name" -> Value.Text("%41b")))
        }
        "readDecoded decodes as UTF-8 by default and through the caller's decoder otherwise" in {
            assert(Parameters.readDecoded("; name*=utf-8''caf%C3%A9; x=1", 0) == Chunk("name" -> "café", "x" -> "1"))
            assert(Parameters.readDecoded("; name*=iso-8859-1''caf%E9", 0) == Chunk("name" -> "caf�"))
            val latin1 = Parameters.readDecoded(
                "; name*=iso-8859-1''caf%E9",
                0,
                {
                    case Value.Encoded(_, _, octets) => octets.toArray.map(b => (b & 0xff).toChar).mkString
                    case Value.Text(text)            => text
                }
            )
            assert(latin1 == Chunk("name" -> "café"))
        }
        "the decoder sees a plain value too, so a protocol can read a further encoding inside it" in {
            val decoded = Parameters.readDecoded(
                "; name=\"=?utf-8?Q?caf=C3=A9?=\"; x*=utf-8''a",
                0,
                {
                    case Value.Text(text) if text.startsWith("=?") => "decoded:" + text
                    case other                                     => Parameters.decodeUtf8(other)
                }
            )
            assert(decoded == Chunk("name" -> "decoded:=?utf-8?Q?caf=C3=A9?=", "x" -> "a"))
            assert(MediaType.parse(
                "text/plain; name=\"=?utf-8?Q?a?=\"",
                { case Value.Text(t) => t.toUpperCase; case e => Parameters.decodeUtf8(e) }
            )
                .map(_.parameter("name")) == Result.succeed(Present("=?UTF-8?Q?A?=")))
        }
        "RFC 8187 3.2.3 examples" in {
            assert(Parameters.readDecoded("; title*=UTF-8''%c2%a3%20and%20%e2%82%ac%20rates", 0) ==
                Chunk("title" -> "£ and € rates"))
            val Chunk(("title", value)) = read("; title*=iso-8859-1'en'%A3%20rates"): @unchecked
            assert(value.asInstanceOf[Value.Encoded].charset == Present("iso-8859-1"))
            assert(value.asInstanceOf[Value.Encoded].language == Present("en"))
            assert(octets(value) == Seq(0xa3) ++ ascii(" rates"))
        }
    }

    "write" - {
        "a token bare, a printable value quoted, in both styles" in {
            assert(Parameters.write("charset", "utf-8", Parameters.Style.Http) == Result.succeed(Chunk("charset=utf-8")))
            assert(Parameters.write("charset", "utf-8", Parameters.Style.Mime) == Result.succeed(Chunk("charset=utf-8")))
            assert(Parameters.write("name", "a b", Parameters.Style.Http) == Result.succeed(Chunk("name=\"a b\"")))
            assert(Parameters.write("name", "", Parameters.Style.Mime) == Result.succeed(Chunk("name=\"\"")))
        }
        "the HTTP style writes one RFC 8187 unit for non-ASCII, controls, CR and LF, with no length cap" in {
            assert(Parameters.write("filename", "€ rates", Parameters.Style.Http) ==
                Result.succeed(Chunk("filename*=UTF-8''%E2%82%AC%20rates")))
            val long = "x" * 500
            assert(Parameters.write("name", long + "é", Parameters.Style.Http) ==
                Result.succeed(Chunk(s"name*=UTF-8''$long%C3%A9")))
        }
        "the MIME style writes 76-octet units: quoted continuations for printable text, %XX segments otherwise" in {
            val printable             = "a" * 100
            val Result.Success(units) = Parameters.write("name", printable, Parameters.Style.Mime): @unchecked
            assert(units.size == 2 && units.forall(_.length <= 76))
            assert(units(0).startsWith("name*0=\"") && units(1).startsWith("name*1=\""))
            val nonAscii                = "é" * 20
            val Result.Success(encoded) = Parameters.write("name", nonAscii, Parameters.Style.Mime): @unchecked
            assert(encoded == Chunk("name*0*=utf-8''" + "%C3%A9" * 10, "name*1*=" + "%C3%A9" * 10))
            assert(Parameters.readDecoded("; " + encoded.mkString("; "), 0) == Chunk("name" -> nonAscii))
            assert(Parameters.readDecoded("; " + units.mkString("; "), 0) == Chunk("name" -> printable))
        }
        "the MIME style never cuts a quoted pair or a %XX across units" in {
            val quotes                = "\"" * 60
            val Result.Success(units) = Parameters.write("name", quotes, Parameters.Style.Mime): @unchecked
            assert(units.forall(u => u.length <= 76 && !u.endsWith("\\\"")))
            assert(Parameters.readDecoded("; " + units.mkString("; "), 0) == Chunk("name" -> quotes))
        }
        "an unpaired surrogate is written as U+FFFD in both styles, on every platform, and read back as octets EF BF BD" in {
            assert(Parameters.write("name", "a\ud800.pdf", Parameters.Style.Mime) == Result.succeed(Chunk("name*=utf-8''a%EF%BF%BD.pdf")))
            assert(Parameters.write("name", "a\ud800.pdf", Parameters.Style.Http) == Result.succeed(Chunk("name*=UTF-8''a%EF%BF%BD.pdf")))
            assert(Parameters.readDecoded("; name*=utf-8''a%EF%BF%BD.pdf", 0) == Chunk("name" -> "a�.pdf"))
            val Chunk(("name", value)) = read("; name*0*=''x; name*1=\ud800"): @unchecked
            assert(octets(value) == Seq(0x78, 0xef, 0xbf, 0xbd))
        }
        "a value holding =? goes through RFC 2231 in the MIME style, so no encoded word appears" in {
            assert(Parameters.write("name", "=?utf-8?Q?a?=", Parameters.Style.Mime) ==
                Result.succeed(Chunk("name*=utf-8''%3D%3Futf-8%3FQ%3Fa%3F%3D")))
        }
        "refuses a name that is not a token or that holds *" in {
            assert(Parameters.write("n me", "1", Parameters.Style.Http) ==
                Result.fail(MimeInvalidParameterException(MimeException.Violation.NotAToken("parameter name", "n me"))))
            assert(Parameters.write("name*0", "1", Parameters.Style.Http) ==
                Result.fail(MimeInvalidParameterException(MimeException.Violation.UnwritableParameterName("name*0"))))
        }
        "render joins the head and every unit with ; and SP" in {
            assert(Parameters.render("form-data", Chunk("name" -> "f", "filename" -> "a b"), Parameters.Style.Http) ==
                Result.succeed("form-data; name=f; filename=\"a b\""))
            assert(Parameters.render("inline", Chunk.empty, Parameters.Style.Http) == Result.succeed("inline"))
        }
    }

    "pathological inputs" - {
        "10,000 continuation segments arriving in reverse order are joined in index order" in {
            val count  = 10000
            val value  = (count - 1 to 0 by -1).map(i => s"; name*$i=${i % 10}").mkString
            val joined = (0 until count).map(i => (i % 10).toString).mkString
            assert(read(value) == Chunk("name" -> Value.Text(joined)))
        }
        "100,000 parameters are all read, in order" in {
            val count = 100000
            val all   = read((0 until count).map(i => s"; p$i=$i").mkString)
            assert(all.size == count && all.head == ("p0" -> Value.Text("0")) &&
                all.last == (s"p${count - 1}"             -> Value.Text(s"${count - 1}")))
        }
        "an unquoted value followed by 200,000 unclosed ( is that text, ( being data" in {
            val tail = " (" * 200000
            assert(read(s"; a=x$tail") == Chunk("a" -> Value.Text("x" + tail)))
        }
        "200,000 closed comments between a value and a parameter with no ; before it" in {
            val comments = " (c)" * 200000
            assert(read(s"; a=x$comments b=y") == Chunk("a" -> Value.Text("x"), "b" -> Value.Text("y")))
        }
        "a quoted value followed by a megabyte of ; and white space" in {
            assert(read("; a=\"1\"" + "; \t" * (1024 * 1024 / 3)) == Chunk("a" -> Value.Text("1")))
        }
    }

    private def octetsOf(value: Value): Span[Byte] =
        value match
            case Value.Encoded(_, _, octets) => octets
            case Value.Text(_)               => Span.empty[Byte]

end ParametersTest
