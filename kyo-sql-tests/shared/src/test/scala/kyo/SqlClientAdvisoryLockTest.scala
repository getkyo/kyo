package kyo

import kyo.Sql.*

/** Cross-backend conformance for [[SqlClient.withAdvisoryLock]]: on every backend that contributes a descriptor, the lock excludes a
  * concurrent session while a body holds it, releases when that body returns, keys distinct locks apart, and routes the body's own
  * statements to the locked session.
  *
  * The suite observes the lock ONLY through the typed `withAdvisoryLock` surface, never a raw engine-specific lock probe. That is a
  * deliberate constraint, and it shapes the design: the one acquire semantics every engine shares is the blocking one, so exclusion and
  * release are proven by a blocking handoff between two sessions rather than by a non-blocking try, which only some engines offer. A held
  * lock forces the contender to wait, and the order in which the two record their entries is what the assertion reads.
  *
  * Two behaviors are NOT cross-engine through the typed surface and are therefore absent here: one engine's typed abort when a bounded wait
  * expires (another ignores the timeout and blocks instead, so there is no capability flag to branch on), and one engine's exact wire-lock
  * name, which is a module-internal detail. The cross-engine essence of the latter, that a key names one lock deterministically, is the
  * different-key leaf below.
  */
class SqlClientAdvisoryLockTest extends SqlBackendTest:

    private case class LockProbe(body: String) derives SqlSchema, CanEqual

    /** A pool of exactly one connection, so the lock holds the only session there is.
      *
      * `acquireTimeout` is short on purpose. A statement that failed to route would reach for the pool, wait for the permit the lock itself
      * is holding, and end at that timeout, so the regression surfaces as a fast typed failure rather than a hang. That distinction is why
      * the routing leaf is safe to run in a shared suite at all.
      */
    private def oneConnection: SqlConfig =
        SqlConfig(maxConnections = 1, acquireTimeout = 3.seconds, queryTimeout = 20.seconds)

    "a held lock excludes a concurrent session until the body releases it" - forEachBackend() { (_, client, _) =>
        // Both engines make `withAdvisoryLock` block until the lock is granted, so exclusion shows up as an ordering.
        // The contender attempts only once the holder is inside, and it cannot acquire until the holder releases, which
        // happens as the holder's body returns. The holder records "holder-exit" as the last step INSIDE the body, so
        // that marker is causally before the release and therefore before the contender's post-acquire "contender-enter";
        // recorded after `withAdvisoryLock` returned it would instead race the contender, since both then run only once
        // the same release has happened. The two sessions are two connections of one pool, which the server keeps apart,
        // so the lock is genuinely contended across sessions rather than re-entered on one.
        val key = 918273L
        for
            events        <- AtomicRef.init(Chunk.empty[String])
            holderInside  <- Latch.init(1)
            releaseHolder <- Latch.init(1)
            holder <- Fiber.init {
                client.withAdvisoryLock(key) {
                    events.updateAndGet(_.append("holder-enter"))
                        .andThen(holderInside.release)
                        .andThen(releaseHolder.await)
                        .andThen(events.updateAndGet(_.append("holder-exit")).unit)
                }
            }
            contender <- Fiber.init {
                holderInside.await.andThen {
                    client.withAdvisoryLock(key) {
                        events.updateAndGet(_.append("contender-enter")).unit
                    }
                }
            }
            _   <- holderInside.await
            _   <- releaseHolder.release
            _   <- holder.get
            _   <- contender.get
            log <- events.get
        yield assert(
            log == Chunk("holder-enter", "holder-exit", "contender-enter"),
            s"the contender must not enter the locked section until the holder releases the lock, saw $log"
        )
        end for
    }

    "a lock on a different key does not contend with a held key" - forEachBackend() { (_, client, _) =>
        // The key names the lock: two acquires of the same key contend, two of different keys do not. This is the
        // cross-engine, typed-surface form of the name-mapping guarantee, observed without reading the engine's own
        // lock table. If the two keys wrongly mapped to one lock, the second acquire would block on the held first and
        // this leaf would time out; it completing at all is the proof of non-contention.
        val heldKey  = 4242L
        val otherKey = 4243L
        for
            holderInside  <- Latch.init(1)
            releaseHolder <- Latch.init(1)
            ranOther      <- AtomicBoolean.init(false)
            holder <- Fiber.init {
                client.withAdvisoryLock(heldKey) {
                    holderInside.release.andThen(releaseHolder.await)
                }
            }
            _   <- holderInside.await
            _   <- client.withAdvisoryLock(otherKey)(ranOther.set(true))
            ran <- ranOther.get
            _   <- releaseHolder.release
            _   <- holder.get
        yield assert(ran, "a lock on a different key must acquire while another key is held, but it did not run its body")
        end for
    }

    "the body's statements run on the locked session" - forEachBackend(oneConnection) { (_, client, _) =>
        // On a pool of exactly one connection the lock holds the only session there is, so a statement inside the body
        // can answer at all only if it lands on the locked connection. A statement that failed to route would reach
        // for the pool, find the one permit held by the lock, and fail at `acquireTimeout`; a green leaf is the proof
        // it routed. The round-trip is expressed through the typed insert/select surface, so it carries no
        // engine-specific SQL.
        val key = 55123L
        for
            _ <- client.executeRaw("CREATE TABLE lockprobe (body VARCHAR(64) NOT NULL)")
            _ <- Sql.insert[LockProbe].values(LockProbe("routed")).run
            got <- client.withAdvisoryLock(key) {
                Sql.from[LockProbe]("p").select(c => c.p.body).run
            }
        yield assert(got == Chunk("routed"), s"the body's statement must run on the locked session, saw $got")
        end for
    }

    "an interrupted holder still releases the lock" - forEachBackend() { (_, client, _) =>
        // The release is a scope finalizer, so a holder interrupted mid-body must still free the lock. Without
        // that, the holder's pooled session would carry the held lock to its next borrower, and the contender
        // below would block on the server side instead of completing: the contender's return IS the proof.
        val key = 424242L
        for
            holderInside <- Latch.init(1)
            holder <- Fiber.init {
                client.withAdvisoryLock(key) {
                    holderInside.release.andThen(Async.never)
                }
            }
            _       <- holderInside.await
            _       <- holder.interrupt
            _       <- holder.getResult
            outcome <- client.withAdvisoryLock(key)(42)
        yield assert(outcome == 42, "the contender must acquire a lock an interrupted holder released")
        end for
    }

end SqlClientAdvisoryLockTest
