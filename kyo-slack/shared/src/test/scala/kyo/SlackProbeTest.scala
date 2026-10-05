package kyo

import kyo.schema.*

object SlackProbe:
    @discriminator("type")
    sealed trait Root[A]
    sealed trait Acked                                                      extends Root[Int]
    sealed trait Plain                                                      extends Root[Unit]
    @rename("hello") final case class Hi(@rename("num_connections") n: Int) extends Plain derives Schema
    @rename("events_api") final case class Ev(@rename("envelope_id") id: String, payload: Inner)
        extends Acked
    @catchAll() final case class Unk(`type`: String, payload: Structure.Value) extends Acked
    final case class Inner(x: Int) derives Schema
    object Root:
        given s: Schema[Root[?]] = Schema.derived[Root[?]]

    @discriminator("type")
    sealed trait Ev2 derives Schema
    @rename("msg") final case class Msg(user: String, @rename("thread_ts") threadTs: Maybe[String] = Absent) extends Ev2
    @catchAll() final case class Other(`type`: String, raw: Structure.Value)                                 extends Ev2

    @tagOnly()
    enum Style derives Schema:
        @rename("primary") case Primary
        @rename("danger") case Danger
    end Style

    @tagOnly()
    sealed trait Reason derives Schema
    @rename("warning") case object Warning            extends Reason
    @catchAll() final case class Unknown(raw: String) extends Reason

    final case class Holder(m: Map[SlackId.BlockId, String], o: Maybe[String] = Absent) derives Schema

    final case class Inter(@rename("trigger_id") t: String, payload: Ev2) derives Schema
end SlackProbe

class SlackProbeTest extends kyo.test.Test[Any]:
    import SlackProbe.*

    "p1 nested sub-trait discriminator" in {
        val a = Json.decode[Root[?]]("""{"type":"hello","num_connections":1}""")
        val b = Json.decode[Root[?]]("""{"type":"events_api","envelope_id":"E1","payload":{"x":1}}""")
        val c = Json.decode[Root[?]]("""{"type":"zzz","envelope_id":"E1"}""")
        val d = Json.decode[Root[?]]("""{"type":"events_api","payload":{"x":1}}""")
        println(s"P1 a=$a\nb=$b\nc=$c\nd=$d")
        println(s"P1 enc=${Json.encode[Root[?]](Hi(2))} ${Json.encode[Root[?]](Ev("E", Inner(3)))}")
        succeed
    }

    "p2 catchAll on known malformed" in {
        val a = Json.decode[Ev2]("""{"type":"msg","user":"u","thread_ts":"1"}""")
        val b = Json.decode[Ev2]("""{"type":"msg"}""")
        val c = Json.decode[Ev2]("""{"type":"new","q":1}""")
        println(s"P2 a=$a\nb=$b\nc=$c enc=${c.map(Json.encode[Ev2](_))}")
        println(s"P2 encMsg=${Json.encode[Ev2](Msg("u"))}")
        succeed
    }

    "p3 tagOnly" in {
        println(s"P3 ${Json.encode[Style](Style.Primary)} ${Json.decode[Style]("\"danger\"")}")
        println(s"P3 ${Json.encode[Reason](Warning)} ${Json.decode[Reason]("\"x\"")} ${Json.encode[Reason](Unknown("y"))}")
        succeed
    }

    "p4 map and maybe" in {
        println(s"P4 ${Json.encode(Holder(Map(SlackId.BlockId("b") -> "e")))} ${Json.decode[Holder]("""{"m":{"b":"e"}}""")}")
        println(s"P4 inter ${Json.decode[Inter]("""{"trigger_id":"t","payload":{"type":"msg","user":"u"}}""")}")
        succeed
    }
end SlackProbeTest
