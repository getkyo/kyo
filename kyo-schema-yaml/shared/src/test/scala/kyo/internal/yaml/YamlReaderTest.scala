package kyo.internal.yaml

import kyo.*

case class YRTHolder(label: String, value: Structure.Value) derives CanEqual, Schema
case class YRTList(items: List[YRTHolder]) derives CanEqual, Schema

class YamlReaderTest extends kyo.test.Test[Any]:

    private val linkRecord = Structure.Value.Record(Chunk("link" -> Structure.Value.Str("x")))

    "a Structure.Value field reads the mapping it holds, after or before the record's other fields" in {
        assert(Yaml.decode[YRTHolder]("label: a\nvalue:\n  link: x\n") == Result.succeed(YRTHolder("a", linkRecord)))
        assert(Yaml.decode[YRTHolder]("value:\n  link: x\nlabel: a\n") == Result.succeed(YRTHolder("a", linkRecord)))
        assert(Yaml.decode[YRTHolder]("label: a\nvalue: {link: x}\n") == Result.succeed(YRTHolder("a", linkRecord)))
    }

    "a Structure.Value field reads a sequence and a scalar" in {
        assert(Yaml.decode[YRTHolder]("label: a\nvalue:\n  - 1\n  - two\n") ==
            Result.succeed(YRTHolder("a", Structure.Value.Sequence(Chunk(Structure.Value.Integer(1), Structure.Value.Str("two"))))))
        assert(Yaml.decode[YRTHolder]("label: a\nvalue: 1.5\n") == Result.succeed(YRTHolder("a", Structure.Value.Decimal(1.5))))
    }

    "a Structure.Value field of a record inside a sequence reads its own mapping" in {
        assert(Yaml.decode[YRTList]("items:\n  - label: a\n    value:\n      link: x\n  - label: b\n    value: 2\n") ==
            Result.succeed(YRTList(List(YRTHolder("a", linkRecord), YRTHolder("b", Structure.Value.Integer(2))))))
    }

    "an alias read as a Structure.Value is the anchored node, a mapping included" in {
        val anchored = Structure.Value.Record(Chunk("k" -> Structure.Value.Integer(1)))
        assert(Yaml.decode[Structure.Value]("a: &x\n  k: 1\nb: *x\n") ==
            Result.succeed(Structure.Value.Record(Chunk("a" -> anchored, "b" -> anchored))))
        assert(Yaml.decode[YRTHolder]("label: &x a\nvalue: *x\n") == Result.succeed(YRTHolder("a", Structure.Value.Str("a"))))
    }

    "a Structure.Value field holding an alias to a collection reads the anchored collection" - {
        val anchored = Structure.Value.Sequence(Chunk(Structure.Value.Integer(1), Structure.Value.Integer(2)))

        "anchored by a sibling field" in {
            assert(Yaml.decode[YRTAnchored]("base: &x [1, 2]\nvalue: *x\n") == Result.succeed(YRTAnchored(List(1, 2), anchored)))
        }

        "anchored on the field itself, with no alias" in {
            val record = Structure.Value.Record(Chunk("k" -> Structure.Value.Integer(1)))
            assert(Yaml.decode[YRTHolder]("label: a\nvalue: &x\n  k: 1\n") == Result.succeed(YRTHolder("a", record)))
        }

        "anchored by an earlier element's field" in {
            assert(Yaml.decode[YRTList]("items:\n  - label: a\n    value: &x [1, 2]\n  - label: b\n    value: *x\n") ==
                Result.succeed(YRTList(List(YRTHolder("a", anchored), YRTHolder("b", anchored)))))
        }
    }

end YamlReaderTest

case class YRTAnchored(base: List[Int], value: Structure.Value) derives CanEqual, Schema
