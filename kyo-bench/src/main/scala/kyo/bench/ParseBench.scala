package kyo.bench

import kyo.*
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import scala.io.Source
import scala.util.Using
import zio.Chunk as ZChunk
import zio.parser.*

/*
Inspired by ZIO-Parser's benchmarks: https://github.com/zio/zio-parser/tree/master/benchmarks/
 */
class ParseBench extends BaseBench:

    val repeatAnyCharInput: String                                 = "hello" * 1000
    val repeatAnyCharKyoParser: Chunk[Char] < Parse[Char]          = Parse.repeat(Parse.any[Char])
    val repeatAnyCharZIOParser: Parser[String, Char, ZChunk[Char]] = Parser.anyChar.repeat

    @Benchmark def repeatAnyCharKyo(bh: Blackhole) = bh.consume(Parse.runResult(repeatAnyCharInput)(repeatAnyCharKyoParser).eval)
    @Benchmark def repeatAnyCharZIO(bh: Blackhole) = bh.consume(repeatAnyCharZIOParser.parseString(repeatAnyCharInput))

    val repeatSpecificCharInput: String                                 = "abcdefghijklmnop" * 100
    val repeatSpecificCharKyoParser: Chunk[Char] < Parse[Char]          = Parse.repeat(Parse.anyIn('a' to 'p'))
    val repeatSpecificCharZIOParser: Parser[String, Char, ZChunk[Char]] = Parser.charIn('a' to 'p'*).repeat

    @Benchmark def repeatSpecificCharKyo(bh: Blackhole) =
        bh.consume(Parse.runResult(repeatSpecificCharInput)(repeatSpecificCharKyoParser).eval)
    @Benchmark def repeatSpecificCharZIO(bh: Blackhole) = bh.consume(repeatSpecificCharZIOParser.parseString(repeatSpecificCharInput))

    val repeatFirstOfCharInput: String = "truefalsemaybemaybenot" * 1000
    val repeatFirstOfCharKyoParser: Chunk[Text] < Parse[Char] = Parse.repeat(Parse.firstOf(
        Parse.literal(Text("true")),
        Parse.literal(Text("false")),
        Parse.literal(Text("maybe")),
        Parse.literal(Text("maybenot"))
    ))

    val repeatFirstOfCharZIOParser: Parser[String, Char, ZChunk[String]] = Parser.string("true", "true")
        .orElse(Parser.string("false", "false"))
        .orElse(Parser.string("maybe", "maybe"))
        .orElse(Parser.string("maybenot", "maybenot"))
        .repeat0

    @Benchmark def repeatFirstOfCharKyo(bh: Blackhole) =
        bh.consume(Parse.runResult(repeatFirstOfCharInput)(repeatFirstOfCharKyoParser).eval)
    @Benchmark def repeatFirstOfCharZIO(bh: Blackhole) = bh.consume(repeatFirstOfCharZIOParser.parseString(repeatFirstOfCharInput))

    /*
    Json
     */

    enum Json:
        case Null
        case Bool(value: Boolean)
        case Num(value: BigDecimal)
        case Str(value: String)
        case Array(elements: Chunk[Json] | ZChunk[Json])
        case Obj(fields: Chunk[(String, Json)] | ZChunk[(String, Json)])
    end Json

    def stringSource(resource: String): String =
        Using.resource(Source.fromInputStream(classOf[BaseBench].getResourceAsStream(resource)))(_.mkString)

    val jsonBar       = stringSource("/json/bar.json")
    val jsonBla25     = stringSource("/json/bla25.json")
    val jsonCountries = stringSource("/json/countries.geo.json")
    val jsonQux2      = stringSource("/json/qux2.json")
    val jsonUgh10k    = stringSource("/json/ugh10k.json")

    val jsonKyoParser: Json < Parse[Char] =
        val nul = Parse.literal("null").andThen(Json.Null)

        val bool = Parse.firstOf(
            Parse.literal("true").andThen(Json.Bool(true)),
            Parse.literal("false").andThen(Json.Bool(false))
        )

        val num =
            Parse.read[Char, Json]: in =>
                val num = in.remaining.takeWhile(c => c.isDigit || c == '.' || c == '-')
                try
                    val result = Json.Num(BigDecimal(num.mkString))
                    Result.succeed((in.advance(num.length), result))
                catch
                    case _: NumberFormatException =>
                        Result.fail(Chunk(ParseFailure("Invalid decimal", in.position)))
                end try

        val rawStr = Parse.literal("\"")
            .andThen(Parse.repeatUntil(Parse.any, Parse.literal("\"")))
            .map(_.mkString)

        val str = rawStr.map(Json.Str.apply)

        lazy val array: Json < Parse[Char] = Parse.between(
            Parse.literal("["),
            Parse.separatedBy(json, Parse.literal(",")),
            Parse.literal("]")
        ).map(Json.Array.apply)

        lazy val obj: Json < Parse[Char] = Parse.between(
            Parse.literal("{"),
            Parse.separatedBy(Parse.inOrder(rawStr, Parse.literal(":"), json), Parse.literal(",")),
            Parse.literal("}")
        ).map(parsedFields => Json.Obj(parsedFields.map((name, _, value) => (name, value))))

        lazy val json: Json < Parse[Char] = Parse.firstOf(
            obj,
            array,
            str,
            num,
            bool,
            nul
        )

        json
    end jsonKyoParser

    val jsonZIOParser: Parser[String, Char, Json] =
        val whitespaces = Parser.charIn(' ', '\t', '\r', '\n').*.unit

        val quote       = Parser.char('\"')
        val escapedChar = Parser.charNotIn('\"') // TODO: real escaping support

        val quotedString = (quote ~> escapedChar.*.string <~ quote)

        val nul = Parser.string("null", Json.Null)

        val bool =
            Parser.string("true", Json.Bool(true)) <>
                Parser.string("false", Json.Bool(false))

        val str = quotedString
            .map(Json.Str.apply)

        val digits       = Parser.digit.repeat
        val signedIntStr = Parser.char('-').? ~ digits
        val frac         = Parser.char('.') ~> digits
        val exp          = Parser.charIn('e', 'E') ~ Parser.charIn('+', '-') ~ digits
        val jsonNum      = (signedIntStr ~ frac.? ~ exp.?).string

        val num = jsonNum
            .map(s => Json.Num(BigDecimal(s)))

        lazy val listSep = Parser.char(',').surroundedBy(whitespaces)

        lazy val list: Parser[String, Char, Json] =
            (Parser.char('[') ~> json.repeatWithSep0(listSep) <~ Parser.char(']'))
                .map(Json.Array.apply)

        lazy val keyValueSep = Parser.char(':').surroundedBy(whitespaces)

        lazy val keyValue = (quotedString ~ keyValueSep ~ json).map[(String, Json)](
            { case (key, value) => (key, value) }
        )

        lazy val obj = (
            Parser.char('{') ~>
                keyValue.repeatWithSep0(listSep).surroundedBy(whitespaces) <~
                Parser.char('}')
        ).map(Json.Obj.apply)

        lazy val json: Parser[String, Char, Json] =
            (nul <>
                bool <>
                str <>
                num <>
                list <>
                obj).manualBacktracking

        json
    end jsonZIOParser

    @Benchmark def jsonBarKyo(bh: Blackhole) = bh.consume(Parse.runResult(jsonBar)(jsonKyoParser).eval)
    @Benchmark def jsonBarZIO(bh: Blackhole) = bh.consume(jsonZIOParser.parseString(jsonBar))

    @Benchmark def jsonBla25Kyo(bh: Blackhole) = bh.consume(Parse.runResult(jsonBla25)(jsonKyoParser).eval)
    @Benchmark def jsonBla25ZIO(bh: Blackhole) = bh.consume(jsonZIOParser.parseString(jsonBla25))

    @Benchmark def jsonCountriesKyo(bh: Blackhole) = bh.consume(Parse.runResult(jsonCountries)(jsonKyoParser).eval)
    @Benchmark def jsonCountriesZIO(bh: Blackhole) = bh.consume(jsonZIOParser.parseString(jsonCountries))

    @Benchmark def jsonQux2Kyo(bh: Blackhole) = bh.consume(Parse.runResult(jsonQux2)(jsonKyoParser).eval)
    @Benchmark def jsonQux2ZIO(bh: Blackhole) = bh.consume(jsonZIOParser.parseString(jsonQux2))

    @Benchmark def jsonUgh10kKyo(bh: Blackhole) = bh.consume(Parse.runResult(jsonUgh10k)(jsonKyoParser).eval)
    @Benchmark def jsonUgh10kZIO(bh: Blackhole) = bh.consume(jsonZIOParser.parseString(jsonUgh10k))

end ParseBench
