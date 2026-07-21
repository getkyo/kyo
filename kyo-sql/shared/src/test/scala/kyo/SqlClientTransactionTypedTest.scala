package kyo

/** Type-shape tests for [[SqlClient.transactionTyped]] (#513).
  *
  * Verifies the type-level claim that an `Abort[E]` raised inside a `transactionTyped[E]` body propagates as a typed `E` failure on the
  * return effect row, rather than being collapsed to `Result.Panic` as the default [[SqlClient.transaction]] does.
  *
  * No live connection is exercised, these leaves prove the API surface compiles with the intended effect row. Live-rollback tests for the
  * typed-error path are container-gated and live in [[SqlEndToEndTest]] (deferred under #514).
  */
class SqlClientTransactionTypedTest extends Test:

    case class DomainError(reason: String) derives CanEqual

    "SqlClient.transactionTyped[E] body type Abort[E] yields Abort[SqlException | E] on the return row" in {
        val _checked: Int < (Async & Abort[SqlException | DomainError]) =
            SqlClient.transactionTyped[DomainError, Int, Any] {
                Abort.fail(DomainError("boom"))
            }
        succeed
    }

    "SqlClient.transactionTyped[E] with isolation/readOnly overload compiles" in {
        val _checked: Int < (Async & Abort[SqlException | DomainError]) =
            SqlClient.transactionTyped[DomainError, Int, Any](
                Maybe.Absent,
                readOnly = true
            ) {
                Abort.fail(DomainError("boom"))
            }
        succeed
    }

    "SqlClient.transactionTyped[E] preserves success type" in {
        val _checked: String < (Async & Abort[SqlException | DomainError]) =
            SqlClient.transactionTyped[DomainError, String, Any] {
                "ok"
            }
        succeed
    }

end SqlClientTransactionTypedTest
