package demo

import kyo.*

/** Validates the kyo-eventlog demo against the real file journal and EventLog pipeline.
  *
  * Drives the SAME `flow` a fleet-service author reads (no re-implemented copy) through
  * `FleetLedgerDemo.flow` and asserts `validate` returns `Absent`.
  */
class DemoValidationTest extends kyo.test.Test[Any]:

    "FleetLedgerDemo: flow drives Journal.Backend.file and validate returns Absent" in {
        Abort.run[FileException | JournalError | EventLog.PreparationFailure](FleetLedgerDemo.flow).map {
            case Result.Success(snapshot) =>
                val verdict = FleetLedgerDemo.validate(snapshot)
                assert(verdict == Absent, s"demo validate must return Absent; got: $verdict")
            case other =>
                assert(false, s"demo flow must not abort; got: $other")
        }
    }

end DemoValidationTest
