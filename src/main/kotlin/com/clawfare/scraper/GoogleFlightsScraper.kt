package com.clawfare.scraper

import com.clawfare.db.InvestigationDto
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Builds Google Flights URLs and provides scraping utilities.
 */
object GoogleFlightsScraper {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /**
     * Build a Google Flights explore URL for an investigation.
     * This opens the multi-date view for the search parameters.
     */
    fun buildExploreUrl(inv: InvestigationDto): String {
        val origin = inv.origin
        val dest = inv.destination
        val cabin = mapCabinClass(inv.cabinClass)
        val stops = if (inv.maxStops == 0) "1" else "0" // 1 = nonstop only, 0 = any

        // Google Flights URL format:
        // /travel/flights/search?tfs=...&curr=GBP
        // But the explore view is easier:
        // /travel/flights?q=LHR+to+Tokyo+business+May+2026

        // For date range searches, we use the calendar view
        val departStart = LocalDate.parse(inv.departStart, dateFormatter)
        val departEnd = LocalDate.parse(inv.departEnd, dateFormatter)

        // Build a search that shows the date grid
        val monthName = departStart.month.name.lowercase().replaceFirstChar { it.uppercase() }
        val year = departStart.year

        return buildString {
            append("https://www.google.com/travel/flights?q=")
            append("$origin+to+$dest")
            append("+$cabin")
            append("+$monthName+$year")
            if (inv.maxStops == 0) {
                append("+nonstop")
            }
        }
    }

    /**
     * Build a specific date search URL.
     */
    fun buildDateSearchUrl(
        inv: InvestigationDto,
        departDate: LocalDate,
        returnDate: LocalDate?,
    ): String {
        val origin = inv.origin
        val dest = inv.destination
        val cabin = cabinCode(inv.cabinClass)

        // Use the structured URL format
        // /travel/flights/search?tfs=CBwQ...
        // This is complex, so we'll use the simpler format

        val departStr = departDate.format(dateFormatter)

        return buildString {
            append("https://www.google.com/travel/flights/search")
            append("?sxsrf=1")
            append("&hl=en")
            append("&curr=GBP")
            append("&travelclass=$cabin")
            if (inv.maxStops == 0) {
                append("&stops=1") // 1 = nonstop
            }
            // The actual flight params need the complex tfs encoding
            // For now, use the simple search
        }
    }

    /**
     * Build URL for checking a specific date pair.
     * Format: google.com/travel/flights/search?... with encoded params
     */
    fun buildFlightSearchUrl(
        origin: String,
        destination: String,
        departDate: String,
        returnDate: String?,
        cabinClass: String,
        maxStops: Int,
    ): String {
        val cabin = cabinCode(cabinClass)
        val tripType = if (returnDate != null) "1" else "2" // 1 = round trip, 2 = one way

        // Simplified URL that Google will redirect properly
        return buildString {
            append("https://www.google.com/travel/flights")
            append("?q=Flights+from+$origin+to+$destination")
            append("+on+$departDate")
            if (returnDate != null) {
                append("+returning+$returnDate")
            }
            append("+${mapCabinClass(cabinClass)}")
            if (maxStops == 0) {
                append("+nonstop")
            }
            append("&curr=GBP")
        }
    }

    private fun mapCabinClass(cabin: String): String =
        when (cabin.lowercase()) {
            "economy" -> "economy"
            "premium_economy" -> "premium+economy"
            "business" -> "business"
            "first" -> "first+class"
            else -> cabin
        }

    private fun cabinCode(cabin: String): Int =
        when (cabin.lowercase()) {
            "economy" -> 1
            "premium_economy" -> 2
            "business" -> 3
            "first" -> 4
            else -> 1
        }
}
