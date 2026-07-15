package kyo

import kyo.MsgPack.Config
import kyo.MsgPack.DurationEncoding
import kyo.MsgPack.InstantEncoding
import kyo.MsgPack.KeyEncoding
import kyo.internal.msgpack.MsgPackReader
import kyo.internal.msgpack.MsgPackWriter
import scala.concurrent.duration.*

class MsgPackTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    // ===== writer/reader primitives =====

    "writer/reader primitives" - {

        "int boundaries round-trip across every size class" in {
            val cases = List(0, 1, 127, 128, 255, 256, 65535, 65536, Int.MaxValue, -1, -32, -33, -128, -129, -32768, -32769, Int.MinValue)
            cases.foreach { v =>
                val w = new MsgPackWriter(Config.Default)
                w.int(v)
                val r = new MsgPackReader(w.resultBytes, Config.Default)
                assert(r.int() == v, s"int $v failed")
            }
            succeed
        }

        "long boundaries round-trip" in {
            val cases = List(0L, 255L, 65536L, 0xffffffffL, 0x100000000L, Long.MaxValue, -1L, -129L, Long.MinValue)
            cases.foreach { v =>
                val w = new MsgPackWriter(Config.Default)
                w.long(v)
                val r = new MsgPackReader(w.resultBytes, Config.Default)
                assert(r.long() == v, s"long $v failed")
            }
            succeed
        }

        "string / boolean / double / float / nil round-trip" in {
            val w = new MsgPackWriter(Config.Default)
            w.string("hello é world")
            w.boolean(true)
            w.double(3.14159)
            w.float(1.5f)
            w.nil()
            val r = new MsgPackReader(w.resultBytes, Config.Default)
            assert(r.string() == "hello é world")
            assert(r.boolean() == true)
            assert(r.double() == 3.14159)
            assert(r.float() == 1.5f)
            assert(r.isNil())
        }

        "short / byte / char round-trip with range checks" in {
            val w = new MsgPackWriter(Config.Default)
            w.short(30000.toShort)
            w.byte((-5).toByte)
            w.char('Z')
            val r = new MsgPackReader(w.resultBytes, Config.Default)
            assert(r.short() == 30000.toShort)
            assert(r.byte() == (-5).toByte)
            assert(r.char() == 'Z')
        }
    }

    // ===== wire sanity =====

    "wire sanity" - {

        "nil is 0xc0, true is 0xc3, false is 0xc2" in {
            def first(f: MsgPackWriter => Unit): Int =
                val w = new MsgPackWriter(Config.Default)
                f(w)
                w.resultBytes(0) & 0xff
            end first
            assert(first(_.nil()) == 0xc0)
            assert(first(_.boolean(true)) == 0xc3)
            assert(first(_.boolean(false)) == 0xc2)
        }

        "small map uses a fixmap header" in {
            val bytes = MsgPack.encode(MPPerson("Alice", 30))
            // 2-field map => fixmap header 0x82
            assert((bytes.toArray(0) & 0xff) == 0x82)
        }

        "positive fixint encodes in one byte" in {
            val w = new MsgPackWriter(Config.Default)
            w.int(5)
            assert(w.resultBytes.length == 1)
            assert((w.resultBytes(0) & 0xff) == 5)
        }
    }

    // ===== encode/decode =====

    "encode/decode" - {

        "simple case class round-trip" in {
            assert(CodecTestSupport.roundTrip[MPPerson, MsgPack](MPPerson("Alice", 30)) == MPPerson("Alice", 30))
        }

        "all-primitive case class round-trip" in {
            val v = MPAllPrims(true, -7, 9_000_000_000L, 2.5f, 1.25, 12.toShort, (-3).toByte, 'k', "kyo")
            assert(CodecTestSupport.roundTrip[MPAllPrims, MsgPack](v) == v)
        }

        "deterministic encoding" in {
            val v = MPPerson("Charlie", 40)
            assert(CodecTestSupport.sameBytes(MsgPack.encode(v), MsgPack.encode(v)))
        }

        "distinct values encode distinctly" in {
            assert(MsgPack.encode(MPPerson("Alice", 30)).toArray.toSeq != MsgPack.encode(MPPerson("Bob", 25)).toArray.toSeq)
        }

        "nested case class with collection round-trip" in {
            val v = MPNested(MPPerson("Alice", 30), List("a", "b", "c"))
            assert(CodecTestSupport.roundTrip[MPNested, MsgPack](v) == v)
        }
    }

    // ===== collections / option / maybe / map / bytes =====

    "containers" - {

        "Option present and absent round-trip" in {
            assert(CodecTestSupport.roundTrip[MPOpt, MsgPack](MPOpt("a", Some("nick"), Maybe(7))) == MPOpt("a", Some("nick"), Maybe(7)))
            assert(CodecTestSupport.roundTrip[MPOpt, MsgPack](MPOpt("a", None, Maybe.empty)) == MPOpt("a", None, Maybe.empty))
        }

        "Map[String, Int] round-trip preserves arbitrary keys" in {
            val v = MPMap(Map("x" -> 1, "really long key name" -> 2, "é" -> 3))
            assert(CodecTestSupport.roundTrip[MPMap, MsgPack](v) == v)
        }

        "Span[Byte] round-trip" in {
            val original = Span.from(Array[Byte](1, 2, 3, -1, 0, 127))
            val bytes    = MsgPack.encode(MPBytes(original))
            val decoded  = MsgPack.decode[MPBytes](bytes).getOrThrow
            assert(CodecTestSupport.sameBytes(decoded.data, original))
        }

        "BigInt and BigDecimal round-trip losslessly" in {
            val v = MPBig(BigInt("123456789012345678901234567890"), BigDecimal("3.141592653589793238462643383279"))
            assert(CodecTestSupport.roundTrip[MPBig, MsgPack](v) == v)
        }

        "recursive tree round-trip" in {
            val tree = TreeNode(1, List(TreeNode(2, Nil), TreeNode(3, List(TreeNode(4, Nil)))))
            assert(CodecTestSupport.roundTrip[TreeNode, MsgPack](tree) == tree)
        }

        "Result round-trip" in {
            val ok: Result[String, Int]  = Result.succeed(42)
            val err: Result[String, Int] = Result.fail("boom")
            assert(CodecTestSupport.roundTrip[Result[String, Int], MsgPack](ok) == ok)
            assert(CodecTestSupport.roundTrip[Result[String, Int], MsgPack](err) == err)
        }
    }

    // ===== sealed traits / enums =====

    "sealed traits and enums" - {

        "sealed trait variants discriminate" in {
            val c: MPShape = MPCircle(2.0)
            val d: MPShape = MPDot
            assert(CodecTestSupport.roundTrip[MPShape, MsgPack](c) == c)
            assert(CodecTestSupport.roundTrip[MPShape, MsgPack](d) == d)
        }

        "enum round-trips every case" in {
            assert(CodecTestSupport.roundTrip[MPColor, MsgPack](MPColor.Red) == MPColor.Red)
            assert(CodecTestSupport.roundTrip[MPColor, MsgPack](MPColor.Green) == MPColor.Green)
            assert(CodecTestSupport.roundTrip[MPColor, MsgPack](MPColor.Blue) == MPColor.Blue)
        }

        "shared MTShape via MTDrawing round-trip" in {
            val v = MTDrawing("d", MTCircle(1.5))
            assert(CodecTestSupport.roundTrip[MTDrawing, MsgPack](v) == v)
        }
    }

    // ===== key encoding config =====

    "key encoding" - {

        "FieldId mode round-trips case classes" in {
            given MsgPack = MsgPack(Config(keyEncoding = KeyEncoding.FieldId))
            val v         = MPPerson("Alice", 30)
            assert(MsgPack.decode[MPPerson](MsgPack.encode(v)).getOrThrow == v)
        }

        "FieldId mode still round-trips Map keys as strings" in {
            given MsgPack = MsgPack(Config(keyEncoding = KeyEncoding.FieldId))
            val v         = MPMap(Map("alpha" -> 1, "beta" -> 2))
            assert(MsgPack.decode[MPMap](MsgPack.encode(v)).getOrThrow == v)
        }

        "FieldId mode is more compact than StringName for long field names" in {
            val v = MPLongFields(1, 2)
            val strBits =
                MsgPack.encode(v)(using summon[Schema[MPLongFields]], MsgPack(Config(keyEncoding = KeyEncoding.StringName)), summon[Frame])
            val idBits =
                MsgPack.encode(v)(using summon[Schema[MPLongFields]], MsgPack(Config(keyEncoding = KeyEncoding.FieldId)), summon[Frame])
            assert(idBits.size < strBits.size)
        }

        "FieldId mode still round-trips sealed variants and the Result discriminator" in {
            given MsgPack                = MsgPack(Config(keyEncoding = KeyEncoding.FieldId))
            val shape: MPShape           = MPCircle(2.0)
            val res: Result[String, Int] = Result.fail("boom")
            assert(MsgPack.decode[MPShape](MsgPack.encode(shape)).getOrThrow == shape)
            assert(MsgPack.decode[Result[String, Int]](MsgPack.encode(res)).getOrThrow == res)
        }

        "reader decodes StringName bytes regardless of its own config" in {
            val v = MPPerson("Alice", 30)
            val strBytes =
                MsgPack.encode(v)(using summon[Schema[MPPerson]], MsgPack(Config(keyEncoding = KeyEncoding.StringName)), summon[Frame])
            val decoded = MsgPack.decode[MPPerson](strBytes)(using
                MsgPack(Config(keyEncoding = KeyEncoding.FieldId)),
                summon[Schema[MPPerson]],
                summon[Frame]
            )
            assert(decoded.getOrThrow == v)
        }
    }

    // ===== temporal config =====

    "temporal encoding" - {

        val instant  = java.time.Instant.ofEpochSecond(1_700_000_000L, 123_456_789L)
        val duration = java.time.Duration.ofSeconds(86_400L, 250_000_000L)

        "default (Primitive instant, Lossless duration) round-trips at nanosecond precision" in {
            val v = MPTime(instant, duration)
            assert(CodecTestSupport.roundTrip[MPTime, MsgPack](v) == v)
        }

        "Instant Extension mode round-trips losslessly" in {
            given MsgPack = MsgPack(Config(instantEncoding = InstantEncoding.Extension))
            val v         = MPTime(instant, duration)
            assert(MsgPack.decode[MPTime](MsgPack.encode(v)).getOrThrow == v)
        }

        "Instant Extension uses the spec timestamp ext type -1 (0xc7, len 12, 0xff)" in {
            given MsgPack = MsgPack(Config(instantEncoding = InstantEncoding.Extension))
            // single Instant field: map header (0x81) + key "at" + ext header
            val bytes = MsgPack.encode(MPInstant(instant)).toArray.map(_ & 0xff)
            assert(bytes.containsSlice(Seq(0xc7, 12, 0xff)))
        }

        "Instant Extension bytes decode through a Primitive-config reader" in {
            val v = MPTime(instant, duration)
            val extBits = MsgPack.encode(v)(using
                summon[Schema[MPTime]],
                MsgPack(Config(instantEncoding = InstantEncoding.Extension)),
                summon[Frame]
            )
            val decoded = MsgPack.decode[MPTime](extBits)(using
                MsgPack(Config(instantEncoding = InstantEncoding.Primitive)),
                summon[Schema[MPTime]],
                summon[Frame]
            )
            assert(decoded.getOrThrow == v)
        }

        "Duration Compat mode round-trips and matches the upickle nanos-string wire form" in {
            given MsgPack = MsgPack(Config(durationEncoding = DurationEncoding.Compat))
            val d         = java.time.Duration.ofSeconds(1L) // 1_000_000_000 nanos, like upickle's rw(1.second, "1000000000")
            val bytes     = MsgPack.encode(MPDuration(d))
            assert(MsgPack.decode[MPDuration](bytes).getOrThrow == MPDuration(d))
            // wire: map(0x81) + key "d" + str "1000000000"; assert the nanos string is present as a msgpack str
            val r = new MsgPackReader(bytes.toArray, Config(durationEncoding = DurationEncoding.Compat))
            assert(r.objectStart() == 1)
            assert(r.hasNextField())
            r.fieldParse()
            assert(r.duration() == d)
        }

        "Duration Compat string bytes decode through a Lossless-config reader (reader auto-detects)" in {
            val d = java.time.Duration.ofSeconds(86_400L, 250_000_000L)
            val compatBits = MsgPack.encode(MPDuration(d))(using
                summon[Schema[MPDuration]],
                MsgPack(Config(durationEncoding = DurationEncoding.Compat)),
                summon[Frame]
            )
            val decoded = MsgPack.decode[MPDuration](compatBits)(using
                MsgPack(Config(durationEncoding = DurationEncoding.Lossless)),
                summon[Schema[MPDuration]],
                summon[Frame]
            )
            assert(decoded.getOrThrow == MPDuration(d))
        }
    }

    // ===== scala.concurrent.duration =====

    "scala.concurrent.duration" - {

        "FiniteDuration round-trips (Lossless and Compat)" in {
            val v = MPFinite(5.seconds, 250.millis)
            assert(CodecTestSupport.roundTrip[MPFinite, MsgPack](v) == v)
            given MsgPack = MsgPack(Config(durationEncoding = DurationEncoding.Compat))
            assert(MsgPack.decode[MPFinite](MsgPack.encode(v)).getOrThrow == v)
        }

        "FiniteDuration Compat matches the upickle nanos-string form" in {
            given MsgPack = MsgPack(Config(durationEncoding = DurationEncoding.Compat))
            val bytes     = MsgPack.encode(MPFiniteOne(1.second))
            // map(0x81) + key + str "1000000000"; confirm the nanos string is on the wire
            val r = new MsgPackReader(bytes.toArray, Config(durationEncoding = DurationEncoding.Compat))
            discard(r.objectStart()); discard(r.hasNextField()); r.fieldParse()
            assert(r.string() == "1000000000")
        }

        "abstract Duration round-trips finite and infinite cases as the upickle string form" in {
            List[Duration](7.seconds, Duration.Inf, Duration.MinusInf, Duration.Undefined).foreach { d =>
                val bytes   = MsgPack.encode(MPScalaDur(d))
                val decoded = MsgPack.decode[MPScalaDur](bytes).getOrThrow
                if d eq Duration.Undefined then assert(decoded.dur eq Duration.Undefined)
                else assert(decoded.dur == d, s"duration $d failed")
            }
            succeed
        }

        "abstract Duration encodes infinities as upickle sentinels" in {
            def strOf(d: Duration): String =
                val bytes = MsgPack.encode(MPScalaDur(d))
                val r     = new MsgPackReader(bytes.toArray, Config.Default)
                discard(r.objectStart()); discard(r.hasNextField()); r.fieldParse()
                r.string()
            end strOf
            assert(strOf(Duration.Inf) == "inf")
            assert(strOf(Duration.MinusInf) == "-inf")
            assert(strOf(Duration.Undefined) == "undef")
        }
    }

    // ===== introspection / open value =====

    "introspection" - {

        "Structure.Value round-trips through MsgPack" in {
            val v: Structure.Value = Structure.Value.Record(Chunk(
                "name"   -> Structure.Value.Str("Alice"),
                "age"    -> Structure.Value.Integer(30L),
                "active" -> Structure.Value.Bool(true),
                "tags"   -> Structure.Value.Sequence(Chunk(Structure.Value.Str("a"), Structure.Value.Str("b")))
            ))
            val schema = summon[Schema[Structure.Value]]
            val bytes  = schema.encode[MsgPack](v)
            val back   = schema.decode[MsgPack](bytes).getOrThrow
            assert(back == v)
        }

        "readStructure materializes a plain map without a tagged wrapper" in {
            val bytes  = MsgPack.encode(MPPerson("Alice", 30))
            val reader = new MsgPackReader(bytes.toArray, Config.Default)
            reader.readStructure() match
                case Structure.Value.Record(fields) =>
                    assert(fields.exists { case (k, v) => k == "name" && v == Structure.Value.Str("Alice") })
                    assert(fields.exists { case (k, v) => k == "age" && v == Structure.Value.Integer(30L) })
                case other => fail(s"expected Record, got $other")
            end match
        }
    }

    // ===== error paths =====

    "error paths" - {

        "truncated string length throws TruncatedInputException" in {
            // fixstr header claiming length 5 but no payload
            val data = Array[Byte](0xa5.toByte)
            val r    = new MsgPackReader(data, Config.Default)
            intercept[TruncatedInputException](r.string())
            ()
        }

        "out-of-range Short throws RangeException" in {
            val w = new MsgPackWriter(Config.Default)
            w.int(40000)
            val r = new MsgPackReader(w.resultBytes, Config.Default)
            intercept[RangeException](r.short())
            ()
        }

        "non-numeric BigInt payload throws ParseException" in {
            val w = new MsgPackWriter(Config.Default)
            w.string("not a number")
            val r = new MsgPackReader(w.resultBytes, Config.Default)
            intercept[ParseException](r.bigInt())
            ()
        }

        "missing required field throws via decode" in {
            val empty  = MsgPack.encode(MPEmpty())
            val result = MsgPack.decode[MPPerson](empty)
            assert(result.failure.exists(_.isInstanceOf[MissingFieldException]))
        }

        "collection-size limit is enforced" in {
            val v      = MPList(List.fill(20)(1))
            val bytes  = MsgPack.encode(v)
            val result = MsgPack.decode[MPList](bytes, maxCollectionSize = 5)
            assert(result.failure.exists(_.isInstanceOf[LimitExceededException]))
        }

        "nesting-depth limit is enforced" in {
            val deep   = List.range(1, 6).foldRight(TreeNode(0, Nil)) { case (v, acc) => TreeNode(v, List(acc)) }
            val bytes  = MsgPack.encode(deep)
            val result = MsgPack.decode[TreeNode](bytes, maxDepth = 2)
            assert(result.failure.exists(_.isInstanceOf[LimitExceededException]))
        }

        "skipping an unknown deeply-nested field still enforces the depth limit" in {
            // A known type (MPPerson) plus an unknown field whose value is a deeply nested array. The
            // decoder reaches the unknown field, calls skip(), and must honor maxDepth on that path.
            val w = new MsgPackWriter(Config.Default)
            w.objectStart("MPPerson", 3)
            w.field("name", 0); w.string("Alice")
            w.field("age", 0); w.int(30)
            w.field("extra", 0)
            val depth = 50
            (1 to depth).foreach(_ => w.arrayStart(1))
            w.nil()
            (1 to depth).foreach(_ => w.arrayEnd())
            w.objectEnd()
            val result = MsgPack.decode[MPPerson](w.result(), maxDepth = 8)
            assert(result.failure.exists(_.isInstanceOf[LimitExceededException]))
        }

        "skipping an unknown oversized collection still enforces the collection-size limit" in {
            // The unknown field's value is an array header claiming a huge element count; skip() must
            // reject it via checkCollectionSize before attempting to read elements.
            val w = new MsgPackWriter(Config.Default)
            w.objectStart("MPPerson", 3)
            w.field("name", 0); w.string("Alice")
            w.field("age", 0); w.int(30)
            w.field("extra", 0); w.arrayStart(1_000_000) // header only; elements intentionally omitted
            w.objectEnd()
            val result = MsgPack.decode[MPPerson](w.result(), maxCollectionSize = 100)
            assert(result.failure.exists(_.isInstanceOf[LimitExceededException]))
        }
    }

    // ===== size-class boundaries and empty containers =====

    "size-class boundaries" - {

        "string lengths cross fixstr/str8/str16/str32 boundaries" in {
            List(0, 31, 32, 255, 256, 65535, 65536).foreach { len =>
                val s = "x" * len
                val w = new MsgPackWriter(Config.Default)
                w.string(s)
                val r = new MsgPackReader(w.resultBytes, Config.Default)
                assert(r.string() == s, s"string length $len failed")
            }
            succeed
        }

        "binary lengths cross bin8/bin16/bin32 boundaries" in {
            List(0, 255, 256, 65535, 65536).foreach { len =>
                val arr = Array.tabulate(len)(i => (i % 256).toByte)
                val w   = new MsgPackWriter(Config.Default)
                w.bytes(Span.from(arr))
                val r = new MsgPackReader(w.resultBytes, Config.Default)
                assert(r.bytes().toArray.toSeq == arr.toSeq, s"bin length $len failed")
            }
            succeed
        }

        "list sizes cross fixarray/array16/array32 boundaries" in {
            List(0, 15, 16, 65535, 65536).foreach { n =>
                val v = MPList(List.fill(n)(7))
                assert(CodecTestSupport.roundTrip[MPList, MsgPack](v) == v, s"list size $n failed")
            }
            succeed
        }

        "map sizes cross fixmap/map16/map32 boundaries" in {
            List(0, 15, 16, 65536).foreach { n =>
                val v = MPMap((0 until n).map(i => s"k$i" -> i).toMap)
                assert(CodecTestSupport.roundTrip[MPMap, MsgPack](v) == v, s"map size $n failed")
            }
            succeed
        }

        "case class with more than 15 fields uses a map16 object header and round-trips" in {
            val v = MPWide(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17)
            // 17 entries => map16 header 0xde
            assert((MsgPack.encode(v).toArray(0) & 0xff) == 0xde)
            assert(CodecTestSupport.roundTrip[MPWide, MsgPack](v) == v)
        }

        "empty containers round-trip" in {
            assert(CodecTestSupport.roundTrip[MPList, MsgPack](MPList(Nil)) == MPList(Nil))
            assert(CodecTestSupport.roundTrip[MPMap, MsgPack](MPMap(Map.empty)) == MPMap(Map.empty))
            assert(CodecTestSupport.roundTrip[MPPerson, MsgPack](MPPerson("", 0)) == MPPerson("", 0))
            val emptyBytes = MsgPack.decode[MPBytes](MsgPack.encode(MPBytes(Span.empty))).getOrThrow
            assert(emptyBytes.data.toArray.isEmpty)
        }
    }

    // ===== dictSchema non-String-key Dict =====

    "dictSchema non-String-key Dict" - {

        "round-trips a non-String-key Dict" in {
            val holder  = MTIntStringDict(Dict(1 -> "one", 2 -> "two", 3 -> "three"))
            val decoded = MsgPack.decode[MTIntStringDict](MsgPack.encode(holder)).getOrThrow
            assert(decoded.d.get(1) == Maybe("one"))
            assert(decoded.d.get(2) == Maybe("two"))
            assert(decoded.d.get(3) == Maybe("three"))
            assert(decoded.d.size == 3)
        }

        "round-trips a non-String-key Dict with non-empty collection values" in {
            val holder  = MTIntChunkDict(Dict(1 -> Chunk("a", "b"), 2 -> Chunk("c")))
            val decoded = MsgPack.decode[MTIntChunkDict](MsgPack.encode(holder)).getOrThrow
            assert(decoded.d.get(1) == Maybe(Chunk("a", "b")))
            assert(decoded.d.get(2) == Maybe(Chunk("c")))
        }

    }

end MsgPackTest

// ===== test fixtures (each shares a name prefix with no source file; local to this suite) =====

case class MPPerson(name: String, age: Int) derives Schema, CanEqual
case class MPAllPrims(b: Boolean, i: Int, l: Long, f: Float, d: Double, s: Short, by: Byte, c: Char, str: String) derives Schema, CanEqual
case class MPNested(person: MPPerson, tags: List[String]) derives Schema, CanEqual
case class MPOpt(name: String, nick: Option[String], maybe: Maybe[Int]) derives Schema, CanEqual
case class MPBytes(data: Span[Byte]) derives Schema
case class MPMap(scores: Map[String, Int]) derives Schema, CanEqual
case class MPBig(i: BigInt, d: BigDecimal) derives Schema, CanEqual
case class MPTime(at: java.time.Instant, dur: java.time.Duration) derives Schema, CanEqual
case class MPInstant(at: java.time.Instant) derives Schema, CanEqual
case class MPDuration(d: java.time.Duration) derives Schema, CanEqual

given CanEqual[scala.concurrent.duration.FiniteDuration, scala.concurrent.duration.FiniteDuration] = CanEqual.derived
given CanEqual[scala.concurrent.duration.Duration, scala.concurrent.duration.Duration]             = CanEqual.derived

case class MPFinite(a: scala.concurrent.duration.FiniteDuration, b: scala.concurrent.duration.FiniteDuration) derives Schema, CanEqual
case class MPFiniteOne(d: scala.concurrent.duration.FiniteDuration) derives Schema, CanEqual
case class MPScalaDur(dur: scala.concurrent.duration.Duration) derives Schema, CanEqual
case class MPList(items: List[Int]) derives Schema, CanEqual
case class MPEmpty() derives Schema, CanEqual
case class MPLongFields(theFirstLongFieldName: Int, theSecondLongFieldName: Int) derives Schema, CanEqual
case class MPWide(
    f1: Int,
    f2: Int,
    f3: Int,
    f4: Int,
    f5: Int,
    f6: Int,
    f7: Int,
    f8: Int,
    f9: Int,
    f10: Int,
    f11: Int,
    f12: Int,
    f13: Int,
    f14: Int,
    f15: Int,
    f16: Int,
    f17: Int
) derives Schema, CanEqual

sealed trait MPShape derives Schema, CanEqual
case class MPCircle(radius: Double) extends MPShape derives CanEqual
case object MPDot                   extends MPShape derives CanEqual

enum MPColor derives Schema, CanEqual:
    case Red
    case Green
    case Blue
end MPColor
