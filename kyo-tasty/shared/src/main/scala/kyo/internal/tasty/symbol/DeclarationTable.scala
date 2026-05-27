package kyo.internal.tasty.symbol

import java.util.concurrent.atomic.AtomicReference
import kyo.*

/** Per-class declaration table mapping member names to symbols.
  *
  * Internally uses `Dict[Tasty.Name, Tasty.Symbol]` which chooses flat-array representation for up to 8 entries (O(n) linear scan, no
  * hashing) and HashMap for more than 8 entries. This matches the Dict threshold from kyo-data.
  *
  * The table is initially empty and is populated exactly once via `populate`. An `AtomicReference` CAS-swap guarantees that concurrent
  * readers see either the empty dict (pre-population) or the fully-populated dict (post-population), never a partial intermediate state.
  *
  * Thread safety: `populate` must be called exactly once. Calling it a second time throws `IllegalStateException`.
  */
final class DeclarationTable:
    // None = not yet populated; Some(dict) = fully populated.
    private val ref: AtomicReference[Option[Dict[Tasty.Name, Tasty.Symbol]]] =
        new AtomicReference[Option[Dict[Tasty.Name, Tasty.Symbol]]](None)

    /** Populate the table atomically. Called exactly once by pass 1 after all members are decoded. */
    def populate(entries: Dict[Tasty.Name, Tasty.Symbol]): Unit =
        // CAS from None (unset) to Some(entries) (fully-populated). If it fails, another caller already populated.
        if !ref.compareAndSet(None, Some(entries)) then
            throw new IllegalStateException("DeclarationTable already populated")
    end populate

    /** Look up a member by name. Returns Absent if not found or if the table has not been populated yet. */
    def get(name: Tasty.Name): Maybe[Tasty.Symbol] =
        ref.get() match
            case None       => Absent
            case Some(dict) => dict.get(name)
    end get

    /** Return the current dict (empty if not yet populated). */
    def all: Dict[Tasty.Name, Tasty.Symbol] =
        ref.get() match
            case None       => Dict.empty[Tasty.Name, Tasty.Symbol]
            case Some(dict) => dict
    end all

    /** Return a string describing the internal storage representation.
      *
      * Dict uses flat-array storage for up to `Dict.threshold` (8) entries, and HashMap for more. This accessor lets tests verify the
      * expected storage path without depending on Dict internals directly.
      *
      * Returns "flat-array" when the populated dict has at most 8 entries, "hash-map" when it has more, and "empty" when not yet populated.
      */
    private[kyo] def storageKind: String =
        ref.get() match
            case None => "empty"
            case Some(dict) =>
                if dict.size <= Dict.threshold then "flat-array"
                else "hash-map"
    end storageKind

end DeclarationTable

object DeclarationTable:

    /** Build a Dict from an iterable of (Name, Symbol) pairs and return a populated DeclarationTable. */
    def build(entries: Iterable[(Tasty.Name, Tasty.Symbol)]): DeclarationTable =
        val b = DictBuilder.init[Tasty.Name, Tasty.Symbol]
        entries.foreach { case (k, v) => discard(b.add(k, v)) }
        val table = new DeclarationTable
        table.populate(b.result())
        table
    end build

end DeclarationTable
