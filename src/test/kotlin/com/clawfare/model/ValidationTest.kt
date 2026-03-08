package com.clawfare.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValidationTest {
    // Helper to create a valid leg
    private fun createValidLeg(
        flightNumber: String = "JL402",
        airline: String = "Japan Airlines",
        airlineCode: String = "JL",
        departAirport: String = "LHR",
        departTime: String = "2026-05-10T11:30:00+01:00",
        arriveAirport: String = "NRT",
        arriveTime: String = "2026-05-11T07:45:00+09:00",
        durationMinutes: Int = 735,
    ) = FlightLeg(
        flightNumber = flightNumber,
        airline = airline,
        airlineCode = airlineCode,
        departAirport = departAirport,
        departTime = departTime,
        arriveAirport = arriveAirport,
        arriveTime = arriveTime,
        durationMinutes = durationMinutes,
    )

    // Helper to create a valid segment
    private fun createValidSegment(
        departAirport: String = "LHR",
        departTime: String = "2026-05-10T11:30:00+01:00",
        arriveAirport: String = "NRT",
        arriveTime: String = "2026-05-11T07:45:00+09:00",
        durationMinutes: Int = 735,
        stops: Int = 0,
        legs: List<FlightLeg> = listOf(createValidLeg()),
    ) = FlightSegment(
        departAirport = departAirport,
        departTime = departTime,
        arriveAirport = arriveAirport,
        arriveTime = arriveTime,
        durationMinutes = durationMinutes,
        stops = stops,
        legs = legs,
    )

    // Helper to create a valid flight entry
    private fun createValidFlightEntry(
        shareLink: String = "https://www.google.com/travel/flights/booking?tfs=abc123",
        tripType: TripType = TripType.ROUND_TRIP,
        ticketStructure: TicketStructure = TicketStructure.SINGLE,
        priceCurrency: String = "GBP",
        priceAmount: Double = 847.0,
        priceMarket: String = "UK",
        origin: String = "LHR",
        destination: String = "NRT",
        outbound: FlightSegment = createValidSegment(),
        returnSegment: FlightSegment? =
            createValidSegment(
                departAirport = "NRT",
                arriveAirport = "LHR",
                legs =
                    listOf(
                        createValidLeg(
                            flightNumber = "JL401",
                            departAirport = "NRT",
                            arriveAirport = "LHR",
                            departTime = "2026-05-20T10:30:00+09:00",
                            arriveTime = "2026-05-20T15:45:00+01:00",
                        ),
                    ),
            ),
    ): FlightEntry {
        val id = FlightValidator.generateId(shareLink)
        return FlightEntry(
            id = id,
            shareLink = shareLink,
            source = "google_flights",
            tripType = tripType,
            ticketStructure = ticketStructure,
            priceCurrency = priceCurrency,
            priceAmount = priceAmount,
            priceMarket = priceMarket,
            capturedAt = "2026-03-08T03:15:00Z",
            priceCheckedAt = "2026-03-08T03:15:00Z",
            origin = origin,
            destination = destination,
            outbound = outbound,
            returnSegment = returnSegment,
        )
    }

    // ==================== ID Generation Tests ====================

    @Test
    fun `generateId produces 12-character hex string`() {
        val id = FlightValidator.generateId("https://example.com/flight")
        assertEquals(12, id.length)
        assertTrue(id.matches(Regex("^[a-f0-9]{12}$")))
    }

    @Test
    fun `generateId is deterministic`() {
        val link = "https://www.google.com/travel/flights/booking?tfs=test123"
        val id1 = FlightValidator.generateId(link)
        val id2 = FlightValidator.generateId(link)
        assertEquals(id1, id2)
    }

    @Test
    fun `generateId produces different IDs for different links`() {
        val id1 = FlightValidator.generateId("https://example.com/flight1")
        val id2 = FlightValidator.generateId("https://example.com/flight2")
        assertTrue(id1 != id2)
    }

    // ==================== Valid Entry Tests ====================

    @Test
    fun `valid round trip entry passes validation`() {
        val entry = createValidFlightEntry()
        val result = FlightValidator.validate(entry)
        assertTrue(result.isValid, "Errors: ${result.errors}")
    }

    @Test
    fun `valid one way entry passes validation`() {
        val entry =
            createValidFlightEntry(
                tripType = TripType.ONE_WAY,
                returnSegment = null,
            )
        val result = FlightValidator.validate(entry)
        assertTrue(result.isValid, "Errors: ${result.errors}")
    }

    // ==================== Required Field Tests ====================

    @Test
    fun `missing share_link fails validation`() {
        val entry = createValidFlightEntry(shareLink = "")
        // Force ID since empty link would generate different ID
        val entryWithForcedId =
            entry.copy(
                id = "000000000000",
            )
        val result = FlightValidator.validate(entryWithForcedId)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("share_link") })
    }

    @Test
    fun `invalid share_link URL fails validation`() {
        val entry =
            createValidFlightEntry(
                shareLink = "not-a-valid-url",
            )
        val result = FlightValidator.validate(entry)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("share_link must be a valid URL") })
    }

    @Test
    fun `zero price_amount fails validation`() {
        val entry = createValidFlightEntry().copy(priceAmount = 0.0)
        val result = FlightValidator.validate(entry)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("price_amount must be greater than 0") })
    }

    @Test
    fun `negative price_amount fails validation`() {
        val entry = createValidFlightEntry().copy(priceAmount = -100.0)
        val result = FlightValidator.validate(entry)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("price_amount must be greater than 0") })
    }

    @Test
    fun `invalid price_currency fails validation`() {
        val entry = createValidFlightEntry().copy(priceCurrency = "GBPX")
        val result = FlightValidator.validate(entry)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("price_currency must be a 3-letter code") })
    }

    @Test
    fun `lowercase price_currency fails validation`() {
        val entry = createValidFlightEntry().copy(priceCurrency = "gbp")
        val result = FlightValidator.validate(entry)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("price_currency must be a 3-letter code") })
    }

    @Test
    fun `round_trip without return segment fails validation`() {
        val entry =
            createValidFlightEntry(
                tripType = TripType.ROUND_TRIP,
                returnSegment = null,
            )
        val result = FlightValidator.validate(entry)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("return segment is required for round_trip") })
    }

    @Test
    fun `empty legs array fails validation`() {
        val segment = createValidSegment(legs = emptyList())
        val entry =
            createValidFlightEntry(
                outbound = segment,
            )
        val result = FlightValidator.validate(entry)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("outbound.legs must have at least 1 leg") })
    }

    // ==================== Consistency Tests ====================

    @Test
    fun `stops mismatch with legs count fails validation`() {
        val segment =
            createValidSegment(
                stops = 2, // Should be 0 for 1 leg
            )
        val entry = createValidFlightEntry(outbound = segment)
        val result = FlightValidator.validate(entry)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("stops") && it.contains("legs.length - 1") })
    }

    @Test
    fun `first leg depart airport mismatch fails validation`() {
        val leg = createValidLeg(departAirport = "JFK")
        val segment =
            createValidSegment(
                departAirport = "LHR",
                legs = listOf(leg),
            )
        val entry = createValidFlightEntry(outbound = segment)
        val result = FlightValidator.validate(entry)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("first leg depart_airport") })
    }

    @Test
    fun `last leg arrive airport mismatch fails validation`() {
        val leg = createValidLeg(arriveAirport = "HND")
        val segment =
            createValidSegment(
                arriveAirport = "NRT",
                legs = listOf(leg),
            )
        val entry = createValidFlightEntry(outbound = segment)
        val result = FlightValidator.validate(entry)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("last leg arrive_airport") })
    }

    @Test
    fun `origin mismatch with outbound depart_airport fails validation`() {
        val entry =
            createValidFlightEntry(
                origin = "JFK",
            )
        val result = FlightValidator.validate(entry)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("origin") && it.contains("outbound.depart_airport") })
    }

    @Test
    fun `destination mismatch with outbound arrive_airport fails validation`() {
        val entry =
            createValidFlightEntry(
                destination = "HND",
            )
        val result = FlightValidator.validate(entry)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("destination") && it.contains("outbound.arrive_airport") })
    }

    @Test
    fun `connecting legs with sequential times pass validation`() {
        val leg1 =
            createValidLeg(
                departAirport = "LHR",
                departTime = "2026-05-10T11:30:00+01:00",
                arriveAirport = "FRA",
                arriveTime = "2026-05-10T14:00:00+02:00",
                durationMinutes = 90,
            )
        val leg2 =
            createValidLeg(
                flightNumber = "LH710",
                airline = "Lufthansa",
                airlineCode = "LH",
                departAirport = "FRA",
                departTime = "2026-05-10T16:00:00+02:00",
                arriveAirport = "NRT",
                arriveTime = "2026-05-11T10:00:00+09:00",
                durationMinutes = 660,
            )
        val segment =
            createValidSegment(
                departAirport = "LHR",
                arriveAirport = "NRT",
                stops = 1,
                legs = listOf(leg1, leg2),
            )
        val entry =
            createValidFlightEntry(
                outbound = segment,
            )
        val result = FlightValidator.validate(entry)
        assertTrue(result.isValid, "Errors: ${result.errors}")
    }

    @Test
    fun `connecting legs with overlapping times fails validation`() {
        val leg1 =
            createValidLeg(
                departAirport = "LHR",
                departTime = "2026-05-10T11:30:00+01:00",
                arriveAirport = "FRA",
                arriveTime = "2026-05-10T18:00:00+02:00", // Arrives at 18:00
                durationMinutes = 90,
            )
        val leg2 =
            createValidLeg(
                flightNumber = "LH710",
                airline = "Lufthansa",
                airlineCode = "LH",
                departAirport = "FRA",
                departTime = "2026-05-10T16:00:00+02:00", // Departs at 16:00 (before arrival!)
                arriveAirport = "NRT",
                arriveTime = "2026-05-11T10:00:00+09:00",
                durationMinutes = 660,
            )
        val segment =
            createValidSegment(
                departAirport = "LHR",
                arriveAirport = "NRT",
                stops = 1,
                legs = listOf(leg1, leg2),
            )
        val entry =
            createValidFlightEntry(
                outbound = segment,
            )
        val result = FlightValidator.validate(entry)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("arrives") && it.contains("after") && it.contains("departs") })
    }

    @Test
    fun `connection airport mismatch fails validation`() {
        val leg1 =
            createValidLeg(
                departAirport = "LHR",
                arriveAirport = "FRA", // Arrives Frankfurt
                departTime = "2026-05-10T11:30:00+01:00",
                arriveTime = "2026-05-10T14:00:00+02:00",
            )
        val leg2 =
            createValidLeg(
                flightNumber = "LH710",
                airline = "Lufthansa",
                airlineCode = "LH",
                departAirport = "MUC", // Departs Munich (different!)
                arriveAirport = "NRT",
                departTime = "2026-05-10T16:00:00+02:00",
                arriveTime = "2026-05-11T10:00:00+09:00",
            )
        val segment =
            createValidSegment(
                departAirport = "LHR",
                arriveAirport = "NRT",
                stops = 1,
                legs = listOf(leg1, leg2),
            )
        val entry =
            createValidFlightEntry(
                outbound = segment,
            )
        val result = FlightValidator.validate(entry)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("FRA") && it.contains("MUC") })
    }

    // ==================== Airline Validation Tests ====================

    @Test
    fun `allowed airline passes validation`() {
        val entry = createValidFlightEntry() // Uses JL (Japan Airlines)
        val result = FlightValidator.validate(entry)
        assertTrue(result.isValid, "Errors: ${result.errors}")
    }

    @Test
    fun `blocked airline fails validation`() {
        val leg = createValidLeg(airlineCode = "TK") // Turkish Airlines - blocked
        val segment = createValidSegment(legs = listOf(leg))
        val entry = createValidFlightEntry(outbound = segment)
        val result = FlightValidator.validate(entry)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("blocked airline") && it.contains("TK") })
    }

    @Test
    fun `Chinese airline fails validation`() {
        val leg = createValidLeg(airlineCode = "CA") // Air China - blocked
        val segment = createValidSegment(legs = listOf(leg))
        val entry = createValidFlightEntry(outbound = segment)
        val result = FlightValidator.validate(entry)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("blocked airline") && it.contains("CA") })
    }

    @Test
    fun `Middle Eastern airline fails validation`() {
        val leg = createValidLeg(airlineCode = "EK") // Emirates - blocked
        val segment = createValidSegment(legs = listOf(leg))
        val entry = createValidFlightEntry(outbound = segment)
        val result = FlightValidator.validate(entry)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("blocked airline") && it.contains("EK") })
    }

    @Test
    fun `unapproved but not blocked airline produces error`() {
        val leg = createValidLeg(airlineCode = "XX") // Unknown airline
        val segment = createValidSegment(legs = listOf(leg))
        val entry = createValidFlightEntry(outbound = segment)
        val result = FlightValidator.validate(entry)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("unapproved airline") && it.contains("XX") })
    }

    @Test
    fun `all allowed airlines are in allowlist`() {
        val expectedAllowed =
            setOf(
                "BA",
                "JL",
                "NH",
                "SK",
                "LH",
                "AF",
                "LX",
                "DY",
                "IB",
                "KL",
                "AZ",
                "AY",
                "VS",
                "KE",
                "OZ",
                "AA",
                "UA",
                "DL",
                "AC",
                "LA",
                "AV",
                "AM",
                "TP",
                "EI",
            )
        assertTrue(FlightValidator.allowedAirlines.containsAll(expectedAllowed))
    }

    // ==================== ID Validation Tests ====================

    @Test
    fun `incorrect ID fails validation`() {
        val entry =
            createValidFlightEntry().copy(
                id = "wrongid12345",
            )
        val result = FlightValidator.validate(entry)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("id") && it.contains("does not match") })
    }

    // ==================== Segment Validation Tests ====================

    @Test
    fun `invalid airport code in segment fails validation`() {
        val segment = createValidSegment(departAirport = "LHRX")
        val entry = createValidFlightEntry(outbound = segment)
        val result = FlightValidator.validate(entry)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("depart_airport must be a 3-letter") })
    }

    @Test
    fun `invalid ISO 8601 datetime fails validation`() {
        val segment = createValidSegment(departTime = "not-a-date")
        val entry = createValidFlightEntry(outbound = segment)
        val result = FlightValidator.validate(entry)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("depart_time must be valid ISO 8601") })
    }

    @Test
    fun `zero duration_minutes fails validation`() {
        val segment = createValidSegment(durationMinutes = 0)
        val entry = createValidFlightEntry(outbound = segment)
        val result = FlightValidator.validate(entry)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("duration_minutes must be greater than 0") })
    }

    @Test
    fun `negative stops fails validation`() {
        val segment = createValidSegment(stops = -1)
        val entry = createValidFlightEntry(outbound = segment)
        val result = FlightValidator.validate(entry)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("stops cannot be negative") })
    }

    // ==================== Leg Validation Tests ====================

    @Test
    fun `missing flight_number fails validation`() {
        val leg = createValidLeg(flightNumber = "")
        val segment = createValidSegment(legs = listOf(leg))
        val entry = createValidFlightEntry(outbound = segment)
        val result = FlightValidator.validate(entry)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("flight_number is required") })
    }

    @Test
    fun `invalid airline_code format fails validation`() {
        val leg = createValidLeg(airlineCode = "TOOLONG")
        val segment = createValidSegment(legs = listOf(leg))
        val entry = createValidFlightEntry(outbound = segment)
        val result = FlightValidator.validate(entry)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("airline_code must be a 2-3 character code") })
    }

    // ==================== Investigation Validation Tests ====================

    @Test
    fun `valid investigation passes validation`() {
        val investigation =
            Investigation(
                slug = "tokyo-may-2026",
                origin = "LHR",
                destination = "NRT",
                departStart = "2026-05-01",
                departEnd = "2026-05-15",
                returnStart = "2026-05-15",
                returnEnd = "2026-05-30",
                cabinClass = "business",
                maxStops = 1,
                createdAt = "2026-03-08T00:00:00Z",
                updatedAt = "2026-03-08T00:00:00Z",
            )
        val result = InvestigationValidator.validate(investigation)
        assertTrue(result.isValid, "Errors: ${result.errors}")
    }

    @Test
    fun `invalid slug format fails validation`() {
        val investigation =
            Investigation(
                slug = "Tokyo_May_2026", // Uppercase and underscore not allowed
                origin = "LHR",
                destination = "NRT",
                departStart = "2026-05-01",
                departEnd = "2026-05-15",
                cabinClass = "economy",
                maxStops = 1,
                createdAt = "2026-03-08T00:00:00Z",
                updatedAt = "2026-03-08T00:00:00Z",
            )
        val result = InvestigationValidator.validate(investigation)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("slug must be lowercase") })
    }

    @Test
    fun `invalid origin airport code fails validation`() {
        val investigation =
            Investigation(
                slug = "test-inv",
                origin = "lhr", // Should be uppercase
                destination = "NRT",
                departStart = "2026-05-01",
                departEnd = "2026-05-15",
                cabinClass = "economy",
                maxStops = 1,
                createdAt = "2026-03-08T00:00:00Z",
                updatedAt = "2026-03-08T00:00:00Z",
            )
        val result = InvestigationValidator.validate(investigation)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("origin must be a 3-letter airport code") })
    }

    @Test
    fun `invalid date format fails validation`() {
        val investigation =
            Investigation(
                slug = "test-inv",
                origin = "LHR",
                destination = "NRT",
                departStart = "05/01/2026", // Wrong format
                departEnd = "2026-05-15",
                cabinClass = "economy",
                maxStops = 1,
                createdAt = "2026-03-08T00:00:00Z",
                updatedAt = "2026-03-08T00:00:00Z",
            )
        val result = InvestigationValidator.validate(investigation)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("depart_start must be a valid date") })
    }

    @Test
    fun `max_stops greater than 2 fails validation`() {
        val investigation =
            Investigation(
                slug = "test-inv",
                origin = "LHR",
                destination = "NRT",
                departStart = "2026-05-01",
                departEnd = "2026-05-15",
                cabinClass = "economy",
                maxStops = 3,
                createdAt = "2026-03-08T00:00:00Z",
                updatedAt = "2026-03-08T00:00:00Z",
            )
        val result = InvestigationValidator.validate(investigation)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("max_stops must be between 0 and 2") })
    }

    // ==================== ValidationResult Tests ====================

    @Test
    fun `ValidationErrors toResult returns Success when valid`() {
        val errors = ValidationErrors(emptyList())
        assertTrue(errors.toResult() is ValidationResult.Success)
    }

    @Test
    fun `ValidationErrors toResult returns Failure when invalid`() {
        val errors = ValidationErrors(listOf("error1", "error2"))
        val result = errors.toResult()
        assertTrue(result is ValidationResult.Failure)
        assertTrue((result as ValidationResult.Failure).message.contains("error1"))
        assertTrue(result.message.contains("error2"))
    }
}
