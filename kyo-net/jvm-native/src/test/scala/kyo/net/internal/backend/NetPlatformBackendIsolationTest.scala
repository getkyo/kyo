package kyo.net.internal.backend

import kyo.*
import kyo.net.Test

/** Regression guard for the `KYO_NET_ONLY` -> `-Dkyo.net.backend` bridge (see the `locally` block at the top of `kyo.net.Test`).
  *
  * `TestBackends.all` (used by `eachBackend`/`eachBackendTls`) already
  * filters on `KYO_NET_ONLY`, but a shared leaf that touches the process-shared `NetPlatform.transport` directly instead of fanning out over
  * `TestBackends` -- e.g. `TransportListenerTest`, which deliberately asserts against the single production entry point rather than a
  * per-backend matrix -- selects through `-Dkyo.net.backend` (`IoBackend.select`), which `KYO_NET_ONLY` never reached. On a Linux host with
  * io_uring available, a `KYO_NET_ONLY=epoll` cell-isolation run therefore still exercised io_uring through that one leaf, silently
  * contaminating an "epoll-only" run with io_uring-specific bugs it was meant to exclude (e.g. the io_uring accept-loop hang under load).
  *
  * Cancels cleanly when `KYO_NET_ONLY` is unset (a normal, non-isolated full-matrix run has nothing to isolate).
  */
class NetPlatformBackendIsolationTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    "IoBackendPlatform.selected honors KYO_NET_ONLY, not just the parameterized eachBackend/eachBackendTls leaves" in {
        sys.env.get("KYO_NET_ONLY") match
            case None => cancel("KYO_NET_ONLY is not set on this run; nothing to isolate")
            case Some(only) =>
                val selected = IoBackendPlatform.selected
                assert(
                    selected.name == only,
                    s"KYO_NET_ONLY=$only but the production selection entry point (which NetPlatform.transport uses) picked ${selected.name}"
                )
    }

end NetPlatformBackendIsolationTest
