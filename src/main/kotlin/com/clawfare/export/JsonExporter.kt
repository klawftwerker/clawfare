package com.clawfare.export

import com.clawfare.db.FlightDto
import com.clawfare.db.FlightQueries
import com.clawfare.model.FlightEntry
import com.clawfare.model.FlightSegment
import com.clawfare.model.TicketStructure
import com.clawfare.model.TripType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Export flights to JSON format matching schema.md specification.
 */
object JsonExporter {
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = false
        }

    private val jsonCompact =
        Json {
            prettyPrint = false
            encodeDefaults = false
        }

    /**
     * Export all flights for an investigation to JSON.
     *
     * @param investigationSlug The investigation to export flights from
     * @param prettyPrint Whether to format the JSON output (default: true)
     * @return JSON string containing array of FlightEntry objects
     */
    fun exportByInvestigation(
        investigationSlug: String,
        prettyPrint: Boolean = true,
    ): String {
        val flights = FlightQueries.listByInvestigation(investigationSlug)
        return exportFlights(flights, prettyPrint)
    }

    /**
     * Export all flights in the database to JSON.
     *
     * @param prettyPrint Whether to format the JSON output (default: true)
     * @return JSON string containing array of FlightEntry objects
     */
    fun exportAll(prettyPrint: Boolean = true): String {
        val flights = FlightQueries.listAll()
        return exportFlights(flights, prettyPrint)
    }

    /**
     * Export a list of FlightDto objects to JSON.
     *
     * @param flights List of FlightDto objects to export
     * @param prettyPrint Whether to format the JSON output
     * @return JSON string containing array of FlightEntry objects
     */
    fun exportFlights(
        flights: List<FlightDto>,
        prettyPrint: Boolean = true,
    ): String {
        val entries = flights.map { dtoToEntry(it) }
        return if (prettyPrint) {
            json.encodeToString(entries)
        } else {
            jsonCompact.encodeToString(entries)
        }
    }

    /**
     * Export a single flight to JSON.
     *
     * @param flightId The ID of the flight to export
     * @param prettyPrint Whether to format the JSON output
     * @return JSON string of FlightEntry, or null if not found
     */
    fun exportById(
        flightId: String,
        prettyPrint: Boolean = true,
    ): String? {
        val flight = FlightQueries.getById(flightId) ?: return null
        val entry = dtoToEntry(flight)
        return if (prettyPrint) {
            json.encodeToString(entry)
        } else {
            jsonCompact.encodeToString(entry)
        }
    }

    /**
     * Convert a FlightDto back to a FlightEntry.
     */
    fun dtoToEntry(dto: FlightDto): FlightEntry {
        val outbound = json.decodeFromString<FlightSegment>(dto.outboundJson)
        val returnSegment = dto.returnJson?.let { json.decodeFromString<FlightSegment>(it) }
        val tags = dto.tags?.let { json.decodeFromString<List<String>>(it) }

        return FlightEntry(
            id = dto.id,
            shareLink = dto.shareLink,
            source = dto.source,
            tripType =
                when (dto.tripType) {
                    "round_trip" -> TripType.ROUND_TRIP
                    "one_way" -> TripType.ONE_WAY
                    else -> TripType.ONE_WAY
                },
            ticketStructure =
                when (dto.ticketStructure) {
                    "single" -> TicketStructure.SINGLE
                    "two_one_ways" -> TicketStructure.TWO_ONE_WAYS
                    else -> TicketStructure.SINGLE
                },
            priceCurrency = dto.priceCurrency,
            priceAmount = dto.priceAmount,
            priceMarket = dto.priceMarket,
            capturedAt = dto.capturedAt,
            priceCheckedAt = dto.priceCheckedAt,
            origin = dto.origin,
            destination = dto.destination,
            outbound = outbound,
            returnSegment = returnSegment,
            notes = dto.notes,
            tags = tags,
            bookingClass = dto.bookingClass,
            cabinMixed = dto.cabinMixed,
        )
    }
}
