package kyo.internal

import kyo.*

/** In-memory session store. */
private[kyo] class SessionStore(ref: AtomicRef[Map[String, UISession.Session]]):

    def get(id: String)(using Frame): Maybe[UISession.Session] < Sync =
        for m <- ref.get
        yield Maybe.when(m.contains(id))(m(id))

    def put(session: UISession.Session)(using Frame): Unit < Sync =
        ref.getAndUpdate(_ + (session.id -> session)).unit

    def remove(id: String)(using Frame): Unit < Sync =
        ref.getAndUpdate(_ - id).unit

end SessionStore

private[kyo] object SessionStore:
    def init(using Frame): SessionStore < Sync =
        for ref <- AtomicRef.init(Map.empty[String, UISession.Session])
        yield new SessionStore(ref)
end SessionStore
