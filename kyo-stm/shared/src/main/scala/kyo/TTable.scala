package kyo

import Record.AsFields
import Record.Field
import scala.annotation.implicitNotFound

/** A transactional table implementation that provides atomic operations on structured records within STM transactions. Tables support CRUD
  * operations (Create, Read, Update, Delete) with strong consistency guarantees and optional indexing for efficient querying.
  *
  * TTable provides two main implementations:
  *   - Basic tables with primary key access
  *   - Indexed tables that maintain secondary indexes for efficient querying on specified fields
  *
  * All operations are performed within STM transactions to ensure consistency. The table guarantees that:
  *   - Records are uniquely identified by an auto-incrementing ID
  *   - Updates are atomic - either all fields are updated or none
  *   - Indexes (if configured) are automatically maintained in sync with record changes
  *   - Concurrent access is safely handled through STM transactions
  *
  * @tparam Fields
  *   The record structure defined as a type-level list of field definitions (e.g. "name" ~ String & "age" ~ Int)
  */
sealed abstract class TTable[Fields]:

    /** The type of record IDs for this table. Represented as an opaque Int subtype to provide type safety. */
    type Id <: Int

    /** Converts a raw Int to the table's ID type. Should only be used internally by the implementation.
      *
      * @param id
      *   The raw integer ID value
      * @return
      *   The typed ID value
      */
    def unsafeId(id: Int): Id

    /** Retrieves a record by its ID.
      *
      * @param id
      *   The ID of the record to retrieve
      * @return
      *   The record if found, None otherwise, within the STM effect
      */
    def get(id: Id)(using Frame): Maybe[Record[Fields]] < STM

    /** Inserts a new record into the table.
      *
      * @param record
      *   The record to insert
      * @return
      *   The ID assigned to the new record, within the STM effect
      */
    def insert(record: Record[Fields])(using Frame): Id < STM

    /** Updates an existing record in the table.
      *
      * @param id
      *   The ID of the record to update
      * @param record
      *   The new record data
      * @return
      *   The previous record if it existed, None otherwise, within the STM effect
      */
    def update(id: Id, record: Record[Fields])(using Frame): Maybe[Record[Fields]] < STM

    /** Updates an existing record or inserts a new one if it doesn't exist.
      *
      * @param id
      *   The ID of the record to update or insert
      * @param record
      *   The record data
      */
    def upsert(id: Id, record: Record[Fields])(using Frame): Unit < STM

    /** Removes a record from the table by its ID.
      *
      * @param id
      *   The ID of the record to remove
      * @return
      *   The removed record if it existed, Absent otherwise, within the STM effect
      */
    def remove(id: Id)(using Frame): Maybe[Record[Fields]] < STM

    /** Returns the current number of records in the table.
      *
      * @return
      *   The number of records, within the STM effect
      */
    def size(using Frame): Int < STM

    /** Checks if the table is empty.
      *
      * @return
      *   true if the table contains no records, false otherwise, within the STM effect
      */
    def isEmpty(using Frame): Boolean < STM

    /** Creates a snapshot of the entire table contents.
      *
      * @return
      *   A map of all record IDs to their corresponding records, within the STM effect
      */
    def snapshot(using Frame): Map[Id, Record[Fields]] < STM

end TTable

object TTable:

    /** Initializes a new basic table without indexing.
      *
      * @tparam Fields
      *   The record structure for the table
      * @return
      *   A new TTable instance within the Sync effect
      */
    def init[Fields: AsFields](using Frame): TTable[Fields] < Sync =
        for
            nextId <- TRef.init(0)
            store  <- TMap.init[Int, Record[Fields]]
        yield new Base(nextId, store)

    final private class Base[Fields](
        private val nextId: TRef[Int],
        private val store: TMap[Int, Record[Fields]]
    ) extends TTable[Fields]:
        opaque type Id <: Int = Int

        def unsafeId(id: Int) = id

        def get(id: Id)(using Frame) = store.get(id)

        def insert(record: Record[Fields])(using Frame) =
            for
                id <- nextId.get
                _  <- nextId.set(id + 1)
                _  <- store.put(id, record)
            yield id

        def update(id: Id, record: Record[Fields])(using Frame) =
            store.get(id).map {
                case Absent => Absent
                case Present(prev) =>
                    store.put(id, record).andThen(Maybe(prev))
            }

        def upsert(id: Id, record: Record[Fields])(using Frame) =
            store.put(id, record)

        def remove(id: Id)(using Frame) = store.remove(id)
        def size(using Frame)           = store.size
        def isEmpty(using Frame)        = store.isEmpty
        def snapshot(using Frame)       = store.snapshot
    end Base

    /** An indexed table implementation that maintains secondary indexes for efficient querying.
      *
      * @tparam Fields
      *   The record structure for the table
      * @tparam Indexes
      *   The subset of fields that should be indexed
      */
    final class Indexed[Fields, Indexes >: Fields: AsFields] private (
        val store: TTable[Fields],
        indexes: Map[Field[?, ?], TMap[Any, Set[Int]]]
    ) extends TTable[Fields]:

        type Id = store.Id

        def unsafeId(id: Int): Id = store.unsafeId(id)

        def get(id: Id)(using Frame) = store.get(id)

        def insert(record: Record[Fields])(using Frame) =
            for
                id <- store.insert(record)
                _  <- updateIndexes(id, record)
            yield id

        def update(id: Id, record: Record[Fields])(using Frame) =
            for
                prev <- store.update(id, record)
                _ <-
                    if prev.isDefined then
                        removeFromIndexes(id, prev.get)
                            .andThen(updateIndexes(id, record))
                    else
                        Kyo.unit
            yield prev

        def upsert(id: Id, record: Record[Fields])(using Frame) =
            store.upsert(id, record).andThen(updateIndexes(id, record))

        def remove(id: Id)(using Frame) =
            for
                record  <- store.get(id)
                deleted <- store.remove(id)
                _       <- if deleted.nonEmpty then removeFromIndexes(id, record.get) else Kyo.unit
            yield deleted

        def size(using Frame)     = store.size
        def isEmpty(using Frame)  = store.isEmpty
        def snapshot(using Frame) = store.snapshot

        def indexFields: Set[Field[?, ?]] = indexes.keySet

        private inline val indexMismatch = """
            Cannot query on fields that are not indexed.
            The filter contains fields that are not part of the table's index configuration.
            
            Filter fields: ${A}
            Indexed fields: ${Indexes}
            
            Make sure all fields in the filter are included in the table's index definition.
        """

        /** Queries the table for records matching the given filter criteria using indexed fields.
          *
          * @param filter
          *   The filter criteria specified as a record containing field values to match
          * @return
          *   A chunk containing the IDs of matching records, within the STM effect
          */
        def queryIds[A](filter: Record[A])(
            using
            @implicitNotFound(indexMismatch) ev: Indexes <:< A,
            frame: Frame
        ): Chunk[Id] < STM =
            Kyo.foreach(filter.toMap.toSeq) { (field, value) =>
                indexes(field).getOrElse(value, Set.empty)
            }.map { r =>
                if r.isEmpty then Chunk.empty
                else Chunk.from(r.reduce(_ intersect _).toSeq.sorted.map(unsafeId(_)))
            }

        /** Queries the table for records matching the given filter criteria using indexed fields.
          *
          * @param filter
          *   The filter criteria specified as a record containing field values to match
          * @return
          *   A chunk containing the matching records, within the STM effect
          */
        def query[A: AsFields](filter: Record[A])(
            using
            @implicitNotFound(indexMismatch) ev: Indexes <:< A,
            frame: Frame
        ): Chunk[Record[Fields]] < STM =
            queryIds(filter).map { ids =>
                Kyo.foreach(ids) { id =>
                    store.get(id).map {
                        case Absent     => Chunk.empty
                        case Present(v) => Chunk(v)
                    }
                }.map(_.flattenChunk)
            }
        end query

        private def updateIndexes(id: Id, record: Record[Fields])(using Frame): Unit < STM =
            val map = record.toMap
            Kyo.foreachDiscard(indexes.toSeq) { case (field, idx) =>
                idx.updateWith(map(field)) {
                    case Absent     => Maybe(Set(id))
                    case Present(c) => Maybe(c + id)
                }
            }
        end updateIndexes

        private def removeFromIndexes(id: Id, record: Record[Fields])(using Frame): Unit < STM =
            val map = record.toMap
            Kyo.foreachDiscard(indexes.toSeq) { case (field, idx) =>
                idx.updateWith(map(field)) {
                    case Absent     => Absent
                    case Present(c) => Maybe(c - id)
                }
            }
        end removeFromIndexes
    end Indexed

    object Indexed:

        /** Initializes a new indexed table.
          *
          * @tparam Fields
          *   The record structure for the table
          * @tparam Indexes
          *   The subset of fields that should be indexed
          * @return
          *   A new Indexed table instance within the Sync effect
          */
        def init[Fields: AsFields as fields, Indexes >: Fields: AsFields as indexFields](using Frame): Indexed[Fields, Indexes] < Sync =
            for
                table <- TTable.init[Fields]
                indexes <-
                    Kyo.foreach(indexFields.toSeq) { field =>
                        TMap.init[Any, Set[Int]].map(field -> _)
                    }
            yield new Indexed(table, indexes.toMap)(using fields)
    end Indexed
end TTable
