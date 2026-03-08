package com.clawfare.model

import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/**
 * Validation result - either success or failure with a message.
 */
sealed class ValidationResult {
    data object Success : ValidationResult()

    data class Failure(
        val message: String,
    ) : ValidationResult()
}

/**
 * Combined validation errors.
 */
data class ValidationErrors(
    val errors: List<String>,
) {
    val isValid: Boolean get() = errors.isEmpty()

    fun toResult(): ValidationResult =
        if (isValid) {
            ValidationResult.Success
        } else {
            ValidationResult.Failure(errors.joinToString("; "))
        }
}

/**
 * Flight entry validator - enforces all rules from schema.md and RULES.md.
 */
object FlightValidator {
    /**
     * Allowed airline codes from RULES.md.
     */
    val allowedAirlines =
        setOf(
            // European carriers
            "BA", // British Airways
            "LH", // Lufthansa
            "AF", // Air France
            "LX", // Swiss
            "SK", // SAS
            "DY", // Norwegian
            "IB", // Iberia
            "KL", // KLM
            "AZ", // ITA Airways
            "AY", // Finnair
            "VS", // Virgin Atlantic
            "TP", // TAP Portugal
            "EI", // Aer Lingus
            // Japanese carriers
            "JL", // Japan Airlines
            "NH", // ANA
            // Korean carriers
            "KE", // Korean Air
            "OZ", // Asiana
            // American carriers (North & South)
            "AA", // American Airlines
            "UA", // United Airlines
            "DL", // Delta
            "AC", // Air Canada
            "LA", // LATAM
            "AV", // Avianca
            "AM", // Aeromexico
        )

    /**
     * Blocked airline prefixes (strict - no exceptions).
     * Chinese, Middle Eastern, African, South Asian, Turkish.
     */
    val blockedAirlines =
        setOf(
            // Chinese
            "CA",
            "MU",
            "CZ",
            "HU",
            "3U",
            "MF",
            "ZH",
            "FM",
            "KN",
            "SC",
            // Middle Eastern
            "EK",
            "QR",
            "EY",
            "SV",
            "GF",
            "WY",
            "RJ",
            "MS",
            "KU",
            "OV",
            // African
            "ET",
            "SA",
            "KQ",
            "RK",
            "AT",
            // South Asian
            "AI",
            "6E",
            "UK",
            "SG",
            "PK",
            "BG",
            "UL",
            // Turkish
            "TK",
            "PC",
        )

    /**
     * Generate ID from share link using SHA-256.
     */
    fun generateId(shareLink: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(shareLink.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }.substring(0, 12)
    }

    /**
     * Validate a complete flight entry.
     */
    fun validate(entry: FlightEntry): ValidationErrors {
        val errors = mutableListOf<String>()

        // Required field checks
        errors.addAll(validateRequiredFields(entry))

        // Consistency checks
        errors.addAll(validateConsistency(entry))

        // Airline validation
        errors.addAll(validateAirlines(entry))

        // ID validation
        errors.addAll(validateId(entry))

        return ValidationErrors(errors)
    }

    /**
     * Validate required fields from schema.md.
     */
    fun validateRequiredFields(entry: FlightEntry): List<String> {
        val errors = mutableListOf<String>()

        // share_link must be present and valid URL
        if (entry.shareLink.isBlank()) {
            errors.add("share_link is required")
        } else if (!isValidUrl(entry.shareLink)) {
            errors.add("share_link must be a valid URL")
        }

        // price_amount must be > 0
        if (entry.priceAmount <= 0) {
            errors.add("price_amount must be greater than 0")
        }

        // price_currency must be 3-letter code
        if (!entry.priceCurrency.matches(Regex("^[A-Z]{3}$"))) {
            errors.add("price_currency must be a 3-letter code")
        }

        // outbound must have all FlightSegment fields
        val outboundErrors = validateSegment(entry.outbound, "outbound")
        errors.addAll(outboundErrors)

        // return required if round_trip
        if (entry.tripType == TripType.ROUND_TRIP && entry.returnSegment == null) {
            errors.add("return segment is required for round_trip")
        }

        // Validate return segment if present
        entry.returnSegment?.let {
            errors.addAll(validateSegment(it, "return"))
        }

        return errors
    }

    /**
     * Validate a flight segment.
     */
    fun validateSegment(
        segment: FlightSegment,
        name: String,
    ): List<String> {
        val errors = mutableListOf<String>()

        if (segment.departAirport.isBlank()) {
            errors.add("$name.depart_airport is required")
        } else if (!isValidAirportCode(segment.departAirport)) {
            errors.add("$name.depart_airport must be a 3-letter airport code")
        }

        if (segment.arriveAirport.isBlank()) {
            errors.add("$name.arrive_airport is required")
        } else if (!isValidAirportCode(segment.arriveAirport)) {
            errors.add("$name.arrive_airport must be a 3-letter airport code")
        }

        if (segment.departTime.isBlank()) {
            errors.add("$name.depart_time is required")
        } else if (!isValidIso8601(segment.departTime)) {
            errors.add("$name.depart_time must be valid ISO 8601")
        }

        if (segment.arriveTime.isBlank()) {
            errors.add("$name.arrive_time is required")
        } else if (!isValidIso8601(segment.arriveTime)) {
            errors.add("$name.arrive_time must be valid ISO 8601")
        }

        if (segment.durationMinutes <= 0) {
            errors.add("$name.duration_minutes must be greater than 0")
        }

        if (segment.stops < 0) {
            errors.add("$name.stops cannot be negative")
        }

        if (segment.legs.isEmpty()) {
            errors.add("$name.legs must have at least 1 leg")
        } else {
            segment.legs.forEachIndexed { index, leg ->
                errors.addAll(validateLeg(leg, "$name.legs[$index]"))
            }
        }

        return errors
    }

    /**
     * Validate a flight leg.
     */
    fun validateLeg(
        leg: FlightLeg,
        name: String,
    ): List<String> {
        val errors = mutableListOf<String>()

        if (leg.flightNumber.isBlank()) {
            errors.add("$name.flight_number is required")
        }

        if (leg.airline.isBlank()) {
            errors.add("$name.airline is required")
        }

        if (leg.airlineCode.isBlank()) {
            errors.add("$name.airline_code is required")
        } else if (!leg.airlineCode.matches(Regex("^[A-Z0-9]{2,3}$"))) {
            errors.add("$name.airline_code must be a 2-3 character code")
        }

        if (leg.departAirport.isBlank()) {
            errors.add("$name.depart_airport is required")
        }

        if (leg.arriveAirport.isBlank()) {
            errors.add("$name.arrive_airport is required")
        }

        if (leg.departTime.isBlank()) {
            errors.add("$name.depart_time is required")
        } else if (!isValidIso8601(leg.departTime)) {
            errors.add("$name.depart_time must be valid ISO 8601")
        }

        if (leg.arriveTime.isBlank()) {
            errors.add("$name.arrive_time is required")
        } else if (!isValidIso8601(leg.arriveTime)) {
            errors.add("$name.arrive_time must be valid ISO 8601")
        }

        if (leg.durationMinutes <= 0) {
            errors.add("$name.duration_minutes must be greater than 0")
        }

        return errors
    }

    /**
     * Validate consistency rules from schema.md.
     */
    fun validateConsistency(entry: FlightEntry): List<String> {
        val errors = mutableListOf<String>()

        // stops must equal legs.length - 1
        val outbound = entry.outbound
        if (outbound.legs.isNotEmpty() && outbound.stops != outbound.legs.size - 1) {
            errors.add("outbound.stops (${outbound.stops}) must equal legs.length - 1 (${outbound.legs.size - 1})")
        }

        entry.returnSegment?.let { returnSeg ->
            if (returnSeg.legs.isNotEmpty() && returnSeg.stops != returnSeg.legs.size - 1) {
                errors.add(
                    "return.stops (${returnSeg.stops}) must equal legs.length - 1 " +
                        "(${returnSeg.legs.size - 1})",
                )
            }
        }

        // Leg times must be sequential
        errors.addAll(validateLegSequence(outbound.legs, "outbound"))
        entry.returnSegment?.let {
            errors.addAll(validateLegSequence(it.legs, "return"))
        }

        // First leg depart_airport must match segment depart_airport
        if (outbound.legs.isNotEmpty() && outbound.legs.first().departAirport != outbound.departAirport) {
            errors.add(
                "outbound first leg depart_airport (${outbound.legs.first().departAirport}) " +
                    "must match segment depart_airport (${outbound.departAirport})",
            )
        }

        // Last leg arrive_airport must match segment arrive_airport
        if (outbound.legs.isNotEmpty() && outbound.legs.last().arriveAirport != outbound.arriveAirport) {
            errors.add(
                "outbound last leg arrive_airport (${outbound.legs.last().arriveAirport}) " +
                    "must match segment arrive_airport (${outbound.arriveAirport})",
            )
        }

        // Same checks for return
        entry.returnSegment?.let { returnSeg ->
            if (returnSeg.legs.isNotEmpty() && returnSeg.legs.first().departAirport != returnSeg.departAirport) {
                errors.add(
                    "return first leg depart_airport (${returnSeg.legs.first().departAirport}) " +
                        "must match segment depart_airport (${returnSeg.departAirport})",
                )
            }
            if (returnSeg.legs.isNotEmpty() && returnSeg.legs.last().arriveAirport != returnSeg.arriveAirport) {
                errors.add(
                    "return last leg arrive_airport (${returnSeg.legs.last().arriveAirport}) " +
                        "must match segment arrive_airport (${returnSeg.arriveAirport})",
                )
            }
        }

        // origin must match outbound.depart_airport
        if (entry.origin != outbound.departAirport) {
            errors.add(
                "origin (${entry.origin}) must match outbound.depart_airport (${outbound.departAirport})",
            )
        }

        // destination must match outbound.arrive_airport
        if (entry.destination != outbound.arriveAirport) {
            errors.add(
                "destination (${entry.destination}) must match outbound.arrive_airport (${outbound.arriveAirport})",
            )
        }

        return errors
    }

    /**
     * Validate leg times are sequential.
     */
    fun validateLegSequence(
        legs: List<FlightLeg>,
        segmentName: String,
    ): List<String> {
        val errors = mutableListOf<String>()

        if (legs.size < 2) return errors

        for (i in 0 until legs.size - 1) {
            val currentLeg = legs[i]
            val nextLeg = legs[i + 1]

            try {
                val arriveTime = ZonedDateTime.parse(currentLeg.arriveTime)
                val nextDepartTime = ZonedDateTime.parse(nextLeg.departTime)

                if (arriveTime.isAfter(nextDepartTime)) {
                    errors.add(
                        "$segmentName leg ${i + 1} arrives (${currentLeg.arriveTime}) " +
                            "after leg ${i + 2} departs (${nextLeg.departTime})",
                    )
                }
            } catch (_: DateTimeParseException) {
                // Time format errors handled elsewhere
            }
        }

        // Connection airport consistency
        for (i in 0 until legs.size - 1) {
            if (legs[i].arriveAirport != legs[i + 1].departAirport) {
                errors.add(
                    "$segmentName leg ${i + 1} arrives at ${legs[i].arriveAirport} " +
                        "but leg ${i + 2} departs from ${legs[i + 1].departAirport}",
                )
            }
        }

        return errors
    }

    /**
     * Validate airlines against allowed/blocked lists.
     */
    fun validateAirlines(entry: FlightEntry): List<String> {
        val errors = mutableListOf<String>()

        // Check all legs in outbound
        entry.outbound.legs.forEach { leg ->
            errors.addAll(validateAirlineCode(leg.airlineCode, "outbound"))
        }

        // Check all legs in return
        entry.returnSegment?.legs?.forEach { leg ->
            errors.addAll(validateAirlineCode(leg.airlineCode, "return"))
        }

        return errors
    }

    /**
     * Validate a single airline code.
     */
    fun validateAirlineCode(
        code: String,
        segmentName: String,
    ): List<String> {
        val errors = mutableListOf<String>()

        // Blocked airlines are strict rejection
        if (code in blockedAirlines) {
            errors.add("$segmentName contains blocked airline: $code")
        }

        // Warn if not in allowed list (but not blocked)
        if (code !in allowedAirlines && code !in blockedAirlines) {
            errors.add("$segmentName contains unapproved airline: $code (not in allowlist)")
        }

        return errors
    }

    /**
     * Validate that the ID matches the expected generated ID.
     */
    fun validateId(entry: FlightEntry): List<String> {
        val errors = mutableListOf<String>()

        val expectedId = generateId(entry.shareLink)
        if (entry.id != expectedId) {
            errors.add("id (${entry.id}) does not match expected sha256(share_link)[:12] ($expectedId)")
        }

        return errors
    }

    /**
     * Check if string is a valid URL.
     */
    private fun isValidUrl(url: String): Boolean =
        try {
            val uri = java.net.URI(url)
            uri.scheme in listOf("http", "https")
        } catch (_: Exception) {
            false
        }

    /**
     * Check if string is a valid 3-letter airport code.
     */
    private fun isValidAirportCode(code: String): Boolean = code.matches(Regex("^[A-Z]{3}$"))

    /**
     * Check if string is valid ISO 8601 datetime.
     */
    private fun isValidIso8601(datetime: String): Boolean =
        try {
            ZonedDateTime.parse(datetime)
            true
        } catch (_: DateTimeParseException) {
            false
        }
}

/**
 * Investigation validator.
 */
object InvestigationValidator {
    /**
     * Validate an investigation configuration.
     */
    fun validate(investigation: Investigation): ValidationErrors {
        val errors = mutableListOf<String>()

        if (investigation.slug.isBlank()) {
            errors.add("slug is required")
        } else if (!investigation.slug.matches(Regex("^[a-z0-9-]+$"))) {
            errors.add("slug must be lowercase alphanumeric with hyphens only")
        }

        if (investigation.origin.isBlank()) {
            errors.add("origin is required")
        } else if (!investigation.origin.matches(Regex("^[A-Z]{3}$"))) {
            errors.add("origin must be a 3-letter airport code")
        }

        if (investigation.destination.isBlank()) {
            errors.add("destination is required")
        } else if (!investigation.destination.matches(Regex("^[A-Z]{3}$"))) {
            errors.add("destination must be a 3-letter airport code")
        }

        // Validate dates
        if (!isValidDate(investigation.departStart)) {
            errors.add("depart_start must be a valid date (YYYY-MM-DD)")
        }

        if (!isValidDate(investigation.departEnd)) {
            errors.add("depart_end must be a valid date (YYYY-MM-DD)")
        }

        investigation.returnStart?.let {
            if (!isValidDate(it)) {
                errors.add("return_start must be a valid date (YYYY-MM-DD)")
            }
        }

        investigation.returnEnd?.let {
            if (!isValidDate(it)) {
                errors.add("return_end must be a valid date (YYYY-MM-DD)")
            }
        }

        if (investigation.maxStops < 0 || investigation.maxStops > 2) {
            errors.add("max_stops must be between 0 and 2")
        }

        return ValidationErrors(errors)
    }

    private fun isValidDate(date: String): Boolean = date.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$"))
}
