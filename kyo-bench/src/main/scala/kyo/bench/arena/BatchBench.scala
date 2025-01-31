package kyo.bench.arena

class BatchBench extends ArenaBench.SyncAndFork(5050):

    val count = 100

    object CatsImpl:
        import cats.data.NonEmptyList
        import cats.effect.*
        import fetch.*

        object PlusOne extends Data[Int, Int]:
            def name = "PlusOne"

            val source: DataSource[IO, Int, Int] = new DataSource[IO, Int, Int]:
                override def data: Data[Int, Int] = PlusOne

                override def CF: Concurrent[IO] = Concurrent[IO]

                override def fetch(id: Int): IO[Option[Int]] =
                    IO(Some(id + 1))

                override def batch(ids: NonEmptyList[Int]): IO[Map[Int, Int]] =
                    IO(ids.toList.view.map(id => id -> (id + 1)).toMap)
        end PlusOne

        def fetchPlusOne(n: Int): Fetch[IO, Int] =
            Fetch(n, PlusOne.source)
    end CatsImpl

    def catsBench() =
        import cats.effect.*
        import CatsImpl.*
        import fetch.*
        import cats.syntax.all.*

        val reqs  = (0 until count).toList.map(fetchPlusOne)
        val query = reqs.sequence[Fetch[IO, *], Int].map(_.sum)
        Fetch.run(query)
    end catsBench

    object KyoImpl:
        import kyo.*
        val source = Batch.source[Int, Int, Any]((seq: Seq[Int]) => ((key: Int) => key + 1): (Int => Int < Any))

    def kyoBench() =
        import kyo.*
        import KyoImpl.*
        Batch.run(Batch.foreach(0 until count)(source)).map(_.sum)
    end kyoBench

    object ZIOImpl:
        import zio.*
        import zio.query.*
        case class Req(i: Int) extends Request[Nothing, Int]
        val ds = DataSource.fromFunctionBatchedZIO("PlusOne") { (reqs: Chunk[Req]) => ZIO.succeed(reqs.map(_.i + 1)) }
    end ZIOImpl

    def zioBench() =
        import zio.*
        import zio.query.*
        import ZIOImpl.*

        val reqs = (0 until count).toList.map(i => ZQuery.fromRequest(Req(i))(ds))
        ZQuery.collectAllBatched(reqs).map(_.sum).run
    end zioBench
end BatchBench
