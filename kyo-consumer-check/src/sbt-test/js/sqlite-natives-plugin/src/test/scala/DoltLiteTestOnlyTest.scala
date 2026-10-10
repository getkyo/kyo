import kyo.*
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

// DoltLite reaches this test only through the test classpath, so the query is answered only when the delivery read
// the Test configuration and the test run can resolve what it wrote.
class DoltLiteTestOnlyTest extends munit.FunSuite:

    test("a test-only dependency's engine answers a query in the test run") {
        import AllowUnsafe.embrace.danger
        val outcome: String < Async =
            Abort.run[Any](DB.run("doltlite://:memory:")(sql"SELECT 1 + 1".as[Int].run)).map {
                case Result.Success(rows)  => rows.mkString(",")
                case Result.Failure(error) => s"failure:${error.getClass.getSimpleName}"
                case Result.Panic(error)   => s"panic:${error.getClass.getSimpleName}"
            }
        val future: Future[String] = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(outcome).flatMap(_.toFuture))
        future.map(answer => assertEquals(answer, "2"))
    }
end DoltLiteTestOnlyTest
