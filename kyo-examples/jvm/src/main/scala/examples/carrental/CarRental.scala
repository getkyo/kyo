package examples.carrental

import kyo.*

// Events that describe what happened in the rental fleet. The sealed hierarchy derives Schema
// so EventLog encodes and decodes payloads automatically.
sealed trait RentalEvent derives Schema, CanEqual
object RentalEvent:
    case class VehicleAdded(vehicleId: String, make: String, model: String)
        extends RentalEvent derives Schema, CanEqual
    case class ReservationMade(vehicleId: String, customerId: String, pickupDate: String, returnDate: String)
        extends RentalEvent derives Schema, CanEqual
    case class VehiclePickedUp(vehicleId: String, reservationId: String)
        extends RentalEvent derives Schema, CanEqual
    case class VehicleReturned(vehicleId: String, reservationId: String, condition: String)
        extends RentalEvent derives Schema, CanEqual
end RentalEvent

// Read-model: availability state for one vehicle, derived by replaying events.
case class VehicleState(
    vehicleId: String,
    make: String,
    model: String,
    available: Boolean
) derives CanEqual

// Folds a typed event sequence into the current fleet map. Pure: the same events always
// produce the same state regardless of how many times this function is called.
def replayFleet(events: Chunk[EventLog.Record[RentalEvent]]): Map[String, VehicleState] =
    events.foldLeft(Map.empty[String, VehicleState]) { (fleet, record) =>
        record.payload match
            case RentalEvent.VehicleAdded(id, make, model) =>
                fleet + (id -> VehicleState(id, make, model, available = true))
            case RentalEvent.ReservationMade(vehicleId, _, _, _) =>
                fleet.updatedWith(vehicleId)(_.map(_.copy(available = false)))
            case RentalEvent.VehiclePickedUp(_, _) =>
                fleet // availability already marked at reservation time
            case RentalEvent.VehicleReturned(vehicleId, _, _) =>
                fleet.updatedWith(vehicleId)(_.map(_.copy(available = true)))
    }

object CarRental extends KyoApp:

    // The stream family every RentalEvent member routes through: each vehicle owns one
    // stream, keyed on its vehicle id, so replaying one vehicle's history never touches
    // another vehicle's events.
    private val vehicleStreamName: EventLog.StreamName =
        Abort.run[EventLog.PreparationFailure](EventLog.StreamName("vehicle")).eval.getOrThrow

    // Resolves a vehicle id to its stream directly, independent of any concrete RentalEvent
    // member; reused on the read side to locate each vehicle's stream without constructing
    // a throwaway event just to run a selector.
    private val vehicleIdSelector: EventLog.StreamSelector[String] =
        EventLog.StreamSelector.by[String](vehicleStreamName)(id => Chunk(id))

    // Every RentalEvent member routes to its vehicle's own stream: each member gets its own
    // StreamSelector instance, keyed on the vehicle id, so the routing genuinely varies per
    // member rather than sharing one fixed stream.
    private given EventLog.EventDefinition[RentalEvent, RentalEvent.VehicleAdded] =
        EventLog.EventDefinition.schema[RentalEvent, RentalEvent.VehicleAdded](
            EventLog.StreamSelector.by[RentalEvent.VehicleAdded](vehicleStreamName)(e => Chunk(e.vehicleId))
        )
    private given EventLog.EventDefinition[RentalEvent, RentalEvent.ReservationMade] =
        EventLog.EventDefinition.schema[RentalEvent, RentalEvent.ReservationMade](
            EventLog.StreamSelector.by[RentalEvent.ReservationMade](vehicleStreamName)(e => Chunk(e.vehicleId))
        )
    private given EventLog.EventDefinition[RentalEvent, RentalEvent.VehiclePickedUp] =
        EventLog.EventDefinition.schema[RentalEvent, RentalEvent.VehiclePickedUp](
            EventLog.StreamSelector.by[RentalEvent.VehiclePickedUp](vehicleStreamName)(e => Chunk(e.vehicleId))
        )
    private given EventLog.EventDefinition[RentalEvent, RentalEvent.VehicleReturned] =
        EventLog.EventDefinition.schema[RentalEvent, RentalEvent.VehicleReturned](
            EventLog.StreamSelector.by[RentalEvent.VehicleReturned](vehicleStreamName)(e => Chunk(e.vehicleId))
        )

    run {
        Scope.run {
            Path.run {
                for
                    dir           <- Path.tempDir("car-rental")
                    fleetId       <- JournalId("fleet")
                    codecs        <- EventLogCodecs.schema[RentalEvent]()
                    configuration <- FileJournal.Binary.configuration(fleetId, codecs)
                    log           <- EventLog.init(codecs, fleetId)
                    backend       <- Journal.Backend.file(dir, configuration)
                    _ <- Journal.run(backend) {
                        for
                            _ <- log.append(
                                RentalEvent.VehicleAdded("V001", "Toyota", "Camry"),
                                EventLog.AppendDirective.expected(ExpectedOffset.NoStream)
                            )
                            _ <- log.append(RentalEvent.VehicleAdded("V002", "Honda", "Civic"))
                            _ <- log.append(RentalEvent.VehicleAdded("V003", "Ford", "Explorer"))
                            _ <- log.append(RentalEvent.ReservationMade("V001", "C100", "2026-08-01", "2026-08-05"))
                            _ <- log.append(RentalEvent.VehiclePickedUp("V001", "R001"))
                            _ <- log.append(RentalEvent.VehicleReturned("V001", "R001", "good"))
                            readouts <- Kyo.foreach(Chunk("V001", "V002", "V003")) { vehicleId =>
                                for
                                    streamId <- vehicleIdSelector.resolve(vehicleId)
                                    records  <- log.read(streamId, StreamOffset.first, maxCount = 100)
                                yield records
                            }
                            fleet = replayFleet(readouts.flattenChunk)
                            _ <- Console.printLine("=== Car Rental Fleet ===")
                            _ <- Kyo.foreachDiscard(fleet.values.toList.sortBy(_.vehicleId)) { v =>
                                val status = if v.available then "available" else "on rent"
                                Console.printLine(s"  ${v.vehicleId}  ${v.make} ${v.model}  $status")
                            }
                        yield ()
                        end for
                    }
                yield ()
            }
        }
    }

end CarRental
