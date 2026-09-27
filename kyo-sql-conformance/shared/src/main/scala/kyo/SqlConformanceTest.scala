package kyo

/** The SQL conformance battery: every behavior a `kyo.db.Backend` must share with kyo's own engines, run once per descriptor in
  * [[backends]].
  *
  * A backend's tests subclass this and list their descriptors. Test discovery only sees classes the consumer compiles, so the subclass is
  * what makes the battery run:
  *
  * {{{
  * class MyEngineConformanceTest extends SqlConformanceTest(Seq(new MyEngineConformanceBackend))
  * }}}
  *
  * The descriptors are a constructor parameter because leaves register while the class is constructed, and a class parameter is assigned
  * before any of the battery's traits runs.
  *
  * Each [[SqlConformanceBackend]] opens its clients through its own [[SqlConformanceBackend.backend]], so the battery needs no registry and no
  * service file on any platform. A leaf that differs between engines branches on a descriptor capability, never on an engine name.
  *
  * Leaves of the `forEachBackend` shape run once per reachable descriptor, named by its label. Leaves of the `agreeAcrossBackends` shape
  * compare engines inside one leaf; with a single descriptor, a leaf that pins its expected answer still asserts it, and one that only
  * compares is cancelled. A run where no descriptor is reachable fails rather than passing empty.
  *
  * Leaves run one at a time across every suite that shares the global sequential flag, since they contend on shared servers.
  */
abstract class SqlConformanceTest(val backends: Seq[SqlConformanceBackend]) extends kyo.test.Test[Any]
    with SqlBackendTest
    with SqlAgreementConformanceTest
    with SqlCancellationConformanceTest
    with SqlClientAdvisoryLockTest
    with SqlClientInsertOutcomeTest
    with SqlClientNestedTransactionTest
    with SqlCodecConformanceTest
    with SqlCodecTypeMismatchConformanceTest
    with SqlCollationConformanceTest
    with SqlConflictConformanceTest
    with SqlDslConformanceTest
    with SqlEndToEndConformanceTest
    with SqlErrorMappingConformanceTest
    with SqlIdentifierConformanceTest
    with SqlIsolationAnomalyConformanceTest
    with SqlIsolationConformanceTest
    with SqlMetricsConformanceTest
    with SqlNamingScopeConformanceTest
    with SqlPipelineConformanceTest
    with SqlPoolWarmupConformanceTest
    with SqlPreparedStatementConformanceTest
    with SqlQualifiedNameConformanceTest
    with SqlRowColumnMetadataConformanceTest
    with SqlRowTextConformanceTest
    with SqlRunConformanceTest
    with SqlServerVersionConformanceTest
    with SqlStreamingConformanceTest
    with SqlTransactionSemanticsConformanceTest
    with SqlValueDomainConformanceTest
    with SqlWriteCountConformanceTest
    with SqlWriteReturningConformanceTest
