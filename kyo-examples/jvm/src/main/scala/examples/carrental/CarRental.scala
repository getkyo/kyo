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

    // Every RentalEvent member routes to the fleet's single stream (each member gets its own
    // StreamSelector instance; a real service with per-vehicle streams would vary this per member).
    private given EventLog.EventDefinition[RentalEvent, RentalEvent.VehicleAdded] =
        EventLog.EventDefinition.schema[RentalEvent, RentalEvent.VehicleAdded](new EventLog.StreamSelector[RentalEvent.VehicleAdded] {})
    private given EventLog.EventDefinition[RentalEvent, RentalEvent.ReservationMade] =
        EventLog.EventDefinition.schema[
            RentalEvent,
            RentalEvent.ReservationMade
        ](new EventLog.StreamSelector[RentalEvent.ReservationMade] {})
    private given EventLog.EventDefinition[RentalEvent, RentalEvent.VehiclePickedUp] =
        EventLog.EventDefinition.schema[
            RentalEvent,
            RentalEvent.VehiclePickedUp
        ](new EventLog.StreamSelector[RentalEvent.VehiclePickedUp] {})
    private given EventLog.EventDefinition[RentalEvent, RentalEvent.VehicleReturned] =
        EventLog.EventDefinition.schema[
            RentalEvent,
            RentalEvent.VehicleReturned
        ](new EventLog.StreamSelector[RentalEvent.VehicleReturned] {})

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
                    streamId      <- Abort.get(StreamId(fleetId.value))
                    _ <- Journal.run(backend) {
                        for
                            _ <- log.append(
                                RentalEvent.VehicleAdded("V001", "Toyota", "Camry"),
                                EventLog.AppendDirective.expected(ExpectedOffset.NoStream)
                            )
                            _      <- log.append(RentalEvent.VehicleAdded("V002", "Honda", "Civic"))
                            _      <- log.append(RentalEvent.VehicleAdded("V003", "Ford", "Explorer"))
                            _      <- log.append(RentalEvent.ReservationMade("V001", "C100", "2026-08-01", "2026-08-05"))
                            _      <- log.append(RentalEvent.VehiclePickedUp("V001", "R001"))
                            _      <- log.append(RentalEvent.VehicleReturned("V001", "R001", "good"))
                            events <- log.read(streamId, StreamOffset.first, maxCount = 100)
                            fleet = replayFleet(events)
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
