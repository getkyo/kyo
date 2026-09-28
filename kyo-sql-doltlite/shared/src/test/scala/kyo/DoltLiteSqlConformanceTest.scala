package kyo

import kyo.internal.doltlite.DoltLiteConformanceBackend
import kyo.internal.doltlite.DoltLiteEngineProbe

class DoltLiteSqlConformanceTest extends SqlConformanceTest(Seq(new DoltLiteConformanceBackend)):

    // Cancelled where the engine is not published, which the unavailability leaf in DoltLiteClientTest claims. Without
    // this the battery's "no SQL backend available" rule, meant for missing infrastructure, reports every leaf red.
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        if DoltLiteEngineProbe.available then body
        else Sync.defer(cancel("the DoltLite engine is not published for this platform"))

end DoltLiteSqlConformanceTest
