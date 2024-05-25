package kyo

import _root_.caliban.*
import _root_.caliban.render
import _root_.caliban.schema.Schema
import kyo.*
import kyoTest.KyoTest
import zio.Task

class resolversTest extends KyoTest:

    def runZIO[T](v: Task[T]): T =
        zio.Unsafe.unsafe(implicit u =>
            zio.Runtime.default.unsafe.run(v).getOrThrow()
        )

    case class Query(
        k1: Int < Aborts[Throwable],
        k2: Int < ZIOs,
        k3: Int < (Aborts[Throwable] & ZIOs),
        k4: Int < IOs
    ) derives Schema.SemiAuto

    "schema derivation" in {
        val expected = """type Query {
                         |  k1: Int
                         |  k2: Int
                         |  k3: Int
                         |  k4: Int
                         |}""".stripMargin
        assert(render[Query].trim == expected)
    }

    "execution" in runZIO {
        val api = graphQL(RootResolver(Query(42, 42, 42, 42)))
        for
            interpreter <- api.interpreter
            res         <- interpreter.execute("{ k1 k2 k3 k4 }")
        yield assert(res.data.toString == """{"k1":42,"k2":42,"k3":42,"k4":42}""")
        end for
    }

end resolversTest
