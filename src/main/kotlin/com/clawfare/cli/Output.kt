package com.clawfare.cli

import com.clawfare.db.FlightDto
import com.clawfare.db.InvestigationDto
import com.clawfare.db.PriceHistoryDto
import com.clawfare.model.FlightSegment
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Output formatting utilities for CLI display.
 */
object Output {
    @PublishedApi
    internal val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }

    @PublishedApi
    internal val compactJson =
        Json {
            prettyPrint = false
            encodeDefaults = false
        }

    /**
     * Format an investigation for display.
     */
    fun formatInvestigation(
        inv: InvestigationDto,
        detailed: Boolean = false,
    ): String =
        buildString {
            appendLine("Investigation: ${inv.slug}")
            appendLine("  Route:    ${inv.origin} → ${inv.destination}")
            appendLine("  Depart:   ${inv.departStart} to ${inv.departEnd}")
            if (inv.returnStart != null) {
                appendLine("  Return:   ${inv.returnStart} to ${inv.returnEnd}")
            }
            appendLine("  Cabin:    ${inv.cabinClass}")
            appendLine("  Max stops: ${inv.maxStops}")
            if (detailed) {
                appendLine("  Created:  ${inv.createdAt}")
                appendLine("  Updated:  ${inv.updatedAt}")
            }
        }

    /**
     * Format a list of investigations as a table.
     */
    fun formatInvestigationTable(investigations: List<InvestigationDto>): String {
        if (investigations.isEmpty()) {
            return "No investigations found."
        }

        val headers = listOf("Slug", "Route", "Depart Range", "Cabin", "Stops")
        val rows =
            investigations.map { inv ->
                listOf(
                    inv.slug,
                    "${inv.origin} → ${inv.destination}",
                    "${inv.departStart} to ${inv.departEnd}",
                    inv.cabinClass,
                    inv.maxStops.toString(),
                )
            }

        return formatTable(headers, rows)
    }

    /**
     * Format a flight for display.
     */
    fun formatFlight(
        flight: FlightDto,
        segment: FlightSegment?,
        returnSeg: FlightSegment?,
    ): String =
        buildString {
            appendLine("Flight: ${flight.id}")
            appendLine("  Investigation: ${flight.investigationSlug}")
            appendLine(
                "  Price:     ${formatPrice(flight.priceAmount, flight.priceCurrency)} " +
                    "(${flight.priceMarket})",
            )
            appendLine("  Route:     ${flight.origin} → ${flight.destination}")
            appendLine("  Type:      ${flight.tripType} / ${flight.ticketStructure}")
            appendLine("  Source:    ${flight.source}")

            if (segment != null) {
                appendLine()
                appendLine("  Outbound:")
                appendLine("    ${segment.departAirport} → ${segment.arriveAirport}")
                appendLine("    Depart:  ${segment.departTime}")
                appendLine("    Arrive:  ${segment.arriveTime}")
                appendLine("    Duration: ${formatDuration(segment.durationMinutes)}")
                appendLine("    Stops:   ${segment.stops}")
                segment.legs.forEachIndexed { i, leg ->
                    appendLine(
                        "    Leg ${i + 1}: ${leg.airlineCode}${leg.flightNumber} " +
                            "${leg.departAirport}→${leg.arriveAirport} (${leg.airline})",
                    )
                }
            }

            if (returnSeg != null) {
                appendLine()
                appendLine("  Return:")
                appendLine("    ${returnSeg.departAirport} → ${returnSeg.arriveAirport}")
                appendLine("    Depart:  ${returnSeg.departTime}")
                appendLine("    Arrive:  ${returnSeg.arriveTime}")
                appendLine("    Duration: ${formatDuration(returnSeg.durationMinutes)}")
                appendLine("    Stops:   ${returnSeg.stops}")
                returnSeg.legs.forEachIndexed { i, leg ->
                    appendLine(
                        "    Leg ${i + 1}: ${leg.airlineCode}${leg.flightNumber} " +
                            "${leg.departAirport}→${leg.arriveAirport} (${leg.airline})",
                    )
                }
            }

            if (!flight.tags.isNullOrBlank()) {
                appendLine()
                appendLine("  Tags:      ${flight.tags}")
            }
            if (!flight.notes.isNullOrBlank()) {
                appendLine("  Notes:     ${flight.notes}")
            }
            appendLine()
            appendLine("  Link:      ${flight.shareLink}")
            appendLine("  Captured:  ${flight.capturedAt}")
        }

    /**
     * Format a list of flights as a compact table.
     */
    fun formatFlightTable(flights: List<FlightDto>): String {
        if (flights.isEmpty()) {
            return "No flights found."
        }

        val headers = listOf("ID", "Price", "Route", "Type", "Tags", "Captured")
        val rows =
            flights.map { flight ->
                listOf(
                    flight.id,
                    formatPrice(flight.priceAmount, flight.priceCurrency),
                    "${flight.origin}→${flight.destination}",
                    flight.tripType.replace("_", " "),
                    flight.tags?.let { parseTags(it).joinToString(",") } ?: "",
                    flight.capturedAt.substring(0, 10),
                )
            }

        return formatTable(headers, rows)
    }

    /**
     * Format price history as a table.
     */
    fun formatPriceHistory(history: List<PriceHistoryDto>): String {
        if (history.isEmpty()) {
            return "No price history found."
        }

        val headers = listOf("Date", "Amount", "Currency")
        val rows =
            history.map { entry ->
                listOf(
                    entry.checkedAt.substring(0, 19).replace("T", " "),
                    "%.2f".format(entry.amount),
                    entry.currency,
                )
            }

        return formatTable(headers, rows)
    }

    /**
     * Format a price with currency symbol.
     */
    fun formatPrice(
        amount: Double,
        currency: String,
    ): String {
        val symbol =
            when (currency) {
                "GBP" -> "£"
                "USD" -> "$"
                "EUR" -> "€"
                "JPY" -> "¥"
                else -> currency
            }
        return when (currency) {
            "JPY" -> "$symbol${amount.toLong()}"
            else -> "$symbol%.2f".format(amount)
        }
    }

    /**
     * Format duration in minutes to human readable.
     */
    fun formatDuration(minutes: Int): String {
        val hours = minutes / 60
        val mins = minutes % 60
        return when {
            hours == 0 -> "${mins}m"
            mins == 0 -> "${hours}h"
            else -> "${hours}h ${mins}m"
        }
    }

    /**
     * Format data as JSON.
     */
    inline fun <reified T> toJson(
        data: T,
        pretty: Boolean = true,
    ): String = if (pretty) json.encodeToString(data) else compactJson.encodeToString(data)

    /**
     * Parse tags from JSON string.
     */
    fun parseTags(tagsJson: String): List<String> =
        try {
            compactJson.decodeFromString<List<String>>(tagsJson)
        } catch (_: Exception) {
            emptyList()
        }

    /**
     * Encode tags to JSON string.
     */
    fun encodeTags(tags: List<String>): String = compactJson.encodeToString(tags)

    /**
     * Format a simple ASCII table.
     */
    fun formatTable(
        headers: List<String>,
        rows: List<List<String>>,
    ): String {
        if (rows.isEmpty()) {
            return headers.joinToString(" | ")
        }

        // Calculate column widths
        val widths =
            headers.indices.map { col ->
                maxOf(
                    headers[col].length,
                    rows.maxOfOrNull { it.getOrElse(col) { "" }.length } ?: 0,
                )
            }

        return buildString {
            // Header row
            val headerLine =
                headers
                    .mapIndexed { i, h -> h.padEnd(widths[i]) }
                    .joinToString(" │ ")
            appendLine(headerLine)

            // Separator
            val separator = widths.joinToString("─┼─") { "─".repeat(it) }
            appendLine(separator)

            // Data rows
            rows.forEach { row ->
                val dataLine =
                    row
                        .mapIndexed { i, cell ->
                            cell.padEnd(widths.getOrElse(i) { cell.length })
                        }.joinToString(" │ ")
                appendLine(dataLine)
            }
        }
    }

    /**
     * Print success message.
     */
    fun success(message: String) {
        println("✓ $message")
    }

    /**
     * Print error message to stderr.
     */
    fun error(message: String) {
        System.err.println("✗ $message")
    }

    /**
     * Print warning message.
     */
    fun warn(message: String) {
        println("⚠ $message")
    }
}
