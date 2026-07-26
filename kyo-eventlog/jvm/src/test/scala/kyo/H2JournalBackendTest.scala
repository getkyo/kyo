package kyo

import org.h2.jdbcx.JdbcDataSource

/** Runs the backend contract suite ([[JournalBackendTest]]) against the JDBC-backed
  * [[kyo.internal.H2Journal]], reached through [[Journal.Backend.h2]]. Each leaf's own evaluation
  * of the constructor argument opens a fresh, uniquely-named in-memory H2 database, so no state
  * leaks between scenarios; `DB_CLOSE_DELAY=-1` keeps that database alive across the independent
  * borrow-and-return connection cycle every [[kyo.internal.H2Journal]] operation performs.
  */
class H2JournalBackendTest extends JournalBackendTest(
        // Each leaf's own evaluation opens a fresh backend; the open-time Abort[JournalStorageError]
        // is discharged to a panic here (test-infra breakage, not a modeled condition), mirroring the
        // file backend subclasses.
        Abort.run[JournalStorageError] {
            val dataSource = new JdbcDataSource()
            dataSource.setURL(s"jdbc:h2:mem:journal-backend-${java.util.UUID.randomUUID()};DB_CLOSE_DELAY=-1")
            Journal.Backend.h2(dataSource)
        }.map {
            case Result.Success(backend) => backend
            case Result.Failure(err)     => throw err
            case panic: Result.Panic     => throw panic.exception
        }
    )
