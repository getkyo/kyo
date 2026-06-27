package kyo

import kyo.Json.JsonSchema
import kyo.Thought.Position
import kyo.Thought.internal.Info

class ThoughtTest extends kyo.test.Test[Any]:

    case class Analysis(
        @doc("step by step")
        stepByStep: String,
        @doc("I followed all instructions")
        verified: Boolean = true
    ) derives Schema

    "aggregate combines opening + closing infos" in {
        val thought = Thought.aggregate(Thought.opening[String], Thought.closing[Boolean])
        val infos   = thought.infos
        assert(infos.size == 2)
        assert(infos.count(_.position == Position.Opening) == 1)
        assert(infos.count(_.position == Position.Closing) == 1)
    }

    "thought name is the type's compile-time unqualified name via Schema.structure.name" in {
        val info = Thought.opening[Analysis].infos.head
        assert(info.name == "Analysis", s"expected 'Analysis', got '${info.name}'")
    }

    "no reasoning is woven in by default; Thought.reflective is Reflect (opening) + Check (closing)" in {
        LLM.run(Thought.internal.infos).map { infos =>
            assert(infos.isEmpty, s"no reasoning should be applied when none registered, got: ${infos.map(_.name)}")
            val byName = Thought.reflective.infos.map(i => (i.name, i.position)).toSet
            assert(byName == Set(("Reflect", Position.Opening), ("Check", Position.Closing)), s"Thought.reflective: $byName")
        }
    }

    "resultJson assembles in opening -> resultValue -> closing order" in {
        val opening      = Info("A", Position.Opening, summon[Schema[String]], (_: String) => ())
        val closing      = Info("B", Position.Closing, summon[Schema[Boolean]], (_: Boolean) => ())
        val resultSchema = Json.jsonSchema[String]
        val schema       = Thought.internal.resultJson(Chunk(opening, closing), resultSchema)
        schema match
            case JsonSchema.Obj(props, required, _, _, _, _) =>
                assert(props.map(_._1) == List("openingThoughts", "resultValue", "closingThoughts"))
                assert(required == List("openingThoughts", "resultValue", "closingThoughts"))
                val openingGroup = props.collectFirst { case ("openingThoughts", JsonSchema.Obj(p, _, _, _, _, _)) => p.map(_._1) }
                val closingGroup = props.collectFirst { case ("closingThoughts", JsonSchema.Obj(p, _, _, _, _, _)) => p.map(_._1) }
                assert(openingGroup.contains(List("A")), s"opening group: $openingGroup")
                assert(closingGroup.contains(List("B")), s"closing group: $closingGroup")
            case other =>
                assert(false, s"expected Obj, got $other")
        end match
    }

    "assembled thought schema carries the @doc descriptions, including on Boolean fields" in {
        val info   = Info("Analysis", Position.Opening, summon[Schema[Analysis]], (_: Analysis) => ())
        val schema = Thought.internal.resultJson(Chunk(info), Json.jsonSchema[String])
        val text   = Json.encode(schema)
        assert(text.contains("\"description\":\"step by step\""), s"missing String @doc in: $text")
        assert(text.contains("\"description\":\"I followed all instructions\""), s"missing Boolean @doc in: $text")
    }

    "process hook fires for opening AND closing thoughts on the decoded value" in {
        LLM.run {
            AtomicRef.init(Maybe.empty[String]).map { aRef =>
                AtomicRef.init(Maybe.empty[Boolean]).map { bRef =>
                    val opening = Info("A", Position.Opening, summon[Schema[String]], (s: String) => aRef.set(Present(s)))
                    val closing = Info("B", Position.Closing, summon[Schema[Boolean]], (b: Boolean) => bRef.set(Present(b)))
                    val record = Structure.Value.Record(Chunk(
                        "openingThoughts" -> Structure.Value.Record(Chunk("A" -> Structure.Value.Str("hello"))),
                        "resultValue"     -> Structure.Value.Str("the answer"),
                        "closingThoughts" -> Structure.Value.Record(Chunk("B" -> Structure.Value.Bool(true)))
                    ))
                    Thought.internal.handle(Chunk(opening, closing), record, summon[Schema[String]]).map { result =>
                        aRef.get.map { a =>
                            bRef.get.map { b =>
                                assert(result == "the answer")
                                assert(a == Present("hello"), s"opening hook: $a")
                                assert(b == Present(true), s"closing hook: $b")
                            }
                        }
                    }
                }
            }
        }
    }

    "an unrecognized thought name fails with AIInvalidThoughtException" in {
        LLM.run {
            Abort.run[AIException] {
                val opening = Info("A", Position.Opening, summon[Schema[String]], (_: String) => ())
                val record = Structure.Value.Record(Chunk(
                    "openingThoughts" -> Structure.Value.Record(Chunk("Unknown" -> Structure.Value.Str("x"))),
                    "resultValue"     -> Structure.Value.Str("the answer"),
                    "closingThoughts" -> Structure.Value.Record(Chunk.empty)
                ))
                Thought.internal.handle(Chunk(opening), record, summon[Schema[String]])
            }
        }.map { result =>
            assert(result.isFailure, s"expected a typed failure, got: $result")
            result match
                case Result.Failure(ex: AIInvalidThoughtException) =>
                    assert(ex.getMessage.contains("invalid thought"), s"message: ${ex.getMessage}")
                case _ => assert(false, s"expected AIInvalidThoughtException, got: $result")
            end match
        }
    }

    "handle iterates opening THEN closing (concatenated)" in {
        LLM.run {
            AtomicRef.init(Chunk.empty[String]).map { order =>
                val opening = Info("A", Position.Opening, summon[Schema[String]], (_: String) => order.updateAndGet(_ :+ "A").unit)
                val closing = Info("B", Position.Closing, summon[Schema[String]], (_: String) => order.updateAndGet(_ :+ "B").unit)
                val record = Structure.Value.Record(Chunk(
                    "openingThoughts" -> Structure.Value.Record(Chunk("A" -> Structure.Value.Str("o"))),
                    "resultValue"     -> Structure.Value.Str("the answer"),
                    "closingThoughts" -> Structure.Value.Record(Chunk("B" -> Structure.Value.Str("c")))
                ))
                Thought.internal.handle(Chunk(opening, closing), record, summon[Schema[String]]).andThen {
                    order.get.map(recorded => assert(recorded == Chunk("A", "B"), s"order: $recorded"))
                }
            }
        }
    }

    "a malformed resultValue fails with AIDecodeException" in {
        LLM.run {
            Abort.run[AIException] {
                val record = Structure.Value.Record(Chunk("resultValue" -> Structure.Value.Str("not-an-int")))
                Thought.internal.handle[Int](Chunk.empty, record, summon[Schema[Int]])
            }
        }.map { result =>
            result match
                case Result.Failure(_: AIDecodeException) => succeed
                case _                                    => assert(false, s"expected AIDecodeException, got: $result")
        }
    }

    "a missing resultValue fails with AIDecodeException" in {
        LLM.run {
            Abort.run[AIException] {
                Thought.internal.handle[Int](Chunk.empty, Structure.Value.Record(Chunk.empty), summon[Schema[Int]])
            }
        }.map { result =>
            result match
                case Result.Failure(ex: AIDecodeException) => assert(ex.getMessage.contains("resultValue"), s"message: ${ex.getMessage}")
                case _                                     => assert(false, s"expected AIDecodeException, got: $result")
        }
    }

    "an undecodable thought field fails with AIDecodeException naming the thought" in {
        val opening = Info("A", Position.Opening, summon[Schema[Int]], (_: Int) => ())
        val record = Structure.Value.Record(Chunk(
            "openingThoughts" -> Structure.Value.Record(Chunk("A" -> Structure.Value.Str("not-an-int"))),
            "resultValue"     -> Structure.Value.Str("answer")
        ))
        LLM.run {
            Abort.run[AIException] {
                Thought.internal.handle[String](Chunk(opening), record, summon[Schema[String]])
            }
        }.map { result =>
            result match
                case Result.Failure(ex: AIDecodeException) =>
                    assert(ex.getMessage.contains("failed to decode thought"), s"message: ${ex.getMessage}")
                case _ => assert(false, s"expected AIDecodeException, got: $result")
        }
    }

    "resultJson with only closing thoughts emits resultValue then closingThoughts" in {
        val closing = Info("B", Position.Closing, summon[Schema[Boolean]], (_: Boolean) => ())
        Thought.internal.resultJson(Chunk(closing), Json.jsonSchema[String]) match
            case JsonSchema.Obj(props, _, _, _, _, _) =>
                assert(props.map(_._1) == List("resultValue", "closingThoughts"), s"props: ${props.map(_._1)}")
            case other => assert(false, s"expected Obj, got $other")
        end match
    }

    "resultJson with no thoughts emits just resultValue" in {
        Thought.internal.resultJson(Chunk.empty, Json.jsonSchema[String]) match
            case JsonSchema.Obj(props, _, _, _, _, _) =>
                assert(props.map(_._1) == List("resultValue"), s"props: ${props.map(_._1)}")
            case other => assert(false, s"expected Obj, got $other")
    }

    "opening threads the process hook's capability S into Thought[S]" in {
        val t: Thought[Check] = Thought.opening[String]((_: String) => Check.require(true, "ok"))
        assert(t.infos.size == 1)
    }

end ThoughtTest
