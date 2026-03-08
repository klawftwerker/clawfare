package com.clawfare.importer

import com.clawfare.db.ClawfareDatabase
import com.clawfare.db.FlightQueries
import com.clawfare.db.InvestigationDto
import com.clawfare.db.InvestigationQueries
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonImporterTest {
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        // Use a temp file for test database to avoid in-memory connection issues
        val dbPath = tempDir.resolve("test.db").toString()
        ClawfareDatabase.connect(path = dbPath, createSchema = true)
        // Create test investigation
        InvestigationQueries.create(
            InvestigationDto(
                slug = "test-investigation",
                origin = "LHR",
                destination = "NRT",
                departStart = "2026-05-01",
                departEnd = "2026-05-15",
                returnStart = "2026-05-15",
                returnEnd = "2026-05-30",
            ),
        )
    }

    @AfterEach
    fun teardown() {
        ClawfareDatabase.dropTables()
    }

    @Test
    fun `import valid round trip flight`() {
        val json =
            """
            [
              {
                "id": "b3232886b78e",
                "share_link": "https://www.google.com/travel/flights/booking?tfs=abc123",
                "source": "google_flights",
                "trip_type": "round_trip",
                "ticket_structure": "single",
                "price_currency": "GBP",
                "price_amount": 847.0,
                "price_market": "UK",
                "captured_at": "2026-03-08T03:15:00Z",
                "price_checked_at": "2026-03-08T03:15:00Z",
                "origin": "LHR",
                "destination": "NRT",
                "booking_class": "economy",
                "outbound": {
                  "depart_airport": "LHR",
                  "depart_time": "2026-05-10T11:30:00+01:00",
                  "arrive_airport": "NRT",
                  "arrive_time": "2026-05-11T07:45:00+09:00",
                  "duration_minutes": 735,
                  "stops": 0,
                  "legs": [
                    {
                      "flight_number": "JL402",
                      "airline": "Japan Airlines",
                      "airline_code": "JL",
                      "depart_airport": "LHR",
                      "depart_time": "2026-05-10T11:30:00+01:00",
                      "arrive_airport": "NRT",
                      "arrive_time": "2026-05-11T07:45:00+09:00",
                      "duration_minutes": 735,
                      "aircraft": "Boeing 787-9"
                    }
                  ]
                },
                "return": {
                  "depart_airport": "NRT",
                  "depart_time": "2026-05-20T10:30:00+09:00",
                  "arrive_airport": "LHR",
                  "arrive_time": "2026-05-20T15:45:00+01:00",
                  "duration_minutes": 795,
                  "stops": 0,
                  "legs": [
                    {
                      "flight_number": "JL401",
                      "airline": "Japan Airlines",
                      "airline_code": "JL",
                      "depart_airport": "NRT",
                      "depart_time": "2026-05-20T10:30:00+09:00",
                      "arrive_airport": "LHR",
                      "arrive_time": "2026-05-20T15:45:00+01:00",
                      "duration_minutes": 795,
                      "aircraft": "Boeing 787-9"
                    }
                  ]
                },
                "notes": "Direct JAL, good times",
                "tags": ["shortlist"]
              }
            ]
            """.trimIndent()

        val summary = JsonImporter.importFromJson(json, "test-investigation")

        assertEquals(1, summary.total)
        assertEquals(1, summary.imported)
        assertEquals(0, summary.skipped)
        assertEquals(0, summary.validationErrors)

        // Verify flight was stored
        val flights = FlightQueries.listByInvestigation("test-investigation")
        assertEquals(1, flights.size)
        assertEquals("b3232886b78e", flights.first().id)
        assertEquals(847.0, flights.first().priceAmount)
    }

    @Test
    fun `import one way flight`() {
        val json =
            """
            [
              {
                "id": "019979534e16",
                "share_link": "https://www.google.com/travel/flights/booking?tfs=oneway123",
                "source": "google_flights",
                "trip_type": "one_way",
                "ticket_structure": "single",
                "price_currency": "USD",
                "price_amount": 550.0,
                "price_market": "US",
                "captured_at": "2026-03-08T03:15:00Z",
                "price_checked_at": "2026-03-08T03:15:00Z",
                "origin": "LAX",
                "destination": "NRT",
                "outbound": {
                  "depart_airport": "LAX",
                  "depart_time": "2026-05-10T12:00:00-07:00",
                  "arrive_airport": "NRT",
                  "arrive_time": "2026-05-11T16:00:00+09:00",
                  "duration_minutes": 720,
                  "stops": 0,
                  "legs": [
                    {
                      "flight_number": "NH105",
                      "airline": "ANA",
                      "airline_code": "NH",
                      "depart_airport": "LAX",
                      "depart_time": "2026-05-10T12:00:00-07:00",
                      "arrive_airport": "NRT",
                      "arrive_time": "2026-05-11T16:00:00+09:00",
                      "duration_minutes": 720
                    }
                  ]
                }
              }
            ]
            """.trimIndent()

        val summary = JsonImporter.importFromJson(json, "test-investigation")

        assertEquals(1, summary.total)
        assertEquals(1, summary.imported)
        assertEquals(0, summary.validationErrors)
    }

    @Test
    fun `skip duplicate share link`() {
        val json =
            """
            [
              {
                "id": "e22763ac2cdf",
                "share_link": "https://www.google.com/travel/flights/booking?tfs=dup123",
                "source": "google_flights",
                "trip_type": "one_way",
                "ticket_structure": "single",
                "price_currency": "GBP",
                "price_amount": 400.0,
                "price_market": "UK",
                "captured_at": "2026-03-08T03:15:00Z",
                "price_checked_at": "2026-03-08T03:15:00Z",
                "origin": "LHR",
                "destination": "NRT",
                "outbound": {
                  "depart_airport": "LHR",
                  "depart_time": "2026-05-10T11:30:00+01:00",
                  "arrive_airport": "NRT",
                  "arrive_time": "2026-05-11T07:45:00+09:00",
                  "duration_minutes": 735,
                  "stops": 0,
                  "legs": [
                    {
                      "flight_number": "JL402",
                      "airline": "Japan Airlines",
                      "airline_code": "JL",
                      "depart_airport": "LHR",
                      "depart_time": "2026-05-10T11:30:00+01:00",
                      "arrive_airport": "NRT",
                      "arrive_time": "2026-05-11T07:45:00+09:00",
                      "duration_minutes": 735
                    }
                  ]
                }
              }
            ]
            """.trimIndent()

        // First import
        val summary1 = JsonImporter.importFromJson(json, "test-investigation")
        assertEquals(1, summary1.imported)

        // Second import - should skip
        val summary2 = JsonImporter.importFromJson(json, "test-investigation")
        assertEquals(0, summary2.imported)
        assertEquals(1, summary2.skipped)
        assertTrue(
            summary2.skippedResults
                .first()
                .reason
                .contains("Duplicate"),
        )
    }

    @Test
    fun `validation error for missing return on round trip`() {
        val json =
            """
            [
              {
                "id": "d4e5f6a1b2c3",
                "share_link": "https://www.google.com/travel/flights/booking?tfs=noreturn123",
                "source": "google_flights",
                "trip_type": "round_trip",
                "ticket_structure": "single",
                "price_currency": "GBP",
                "price_amount": 500.0,
                "price_market": "UK",
                "captured_at": "2026-03-08T03:15:00Z",
                "price_checked_at": "2026-03-08T03:15:00Z",
                "origin": "LHR",
                "destination": "NRT",
                "outbound": {
                  "depart_airport": "LHR",
                  "depart_time": "2026-05-10T11:30:00+01:00",
                  "arrive_airport": "NRT",
                  "arrive_time": "2026-05-11T07:45:00+09:00",
                  "duration_minutes": 735,
                  "stops": 0,
                  "legs": [
                    {
                      "flight_number": "JL402",
                      "airline": "Japan Airlines",
                      "airline_code": "JL",
                      "depart_airport": "LHR",
                      "depart_time": "2026-05-10T11:30:00+01:00",
                      "arrive_airport": "NRT",
                      "arrive_time": "2026-05-11T07:45:00+09:00",
                      "duration_minutes": 735
                    }
                  ]
                }
              }
            ]
            """.trimIndent()

        val summary = JsonImporter.importFromJson(json, "test-investigation")

        assertEquals(1, summary.total)
        assertEquals(0, summary.imported)
        assertEquals(1, summary.validationErrors)
        assertTrue(
            summary.errorResults
                .first()
                .errors
                .any { it.contains("return segment is required") },
        )
    }

    @Test
    fun `validation error for blocked airline`() {
        val json =
            """
            [
              {
                "id": "e5f6a1b2c3d4",
                "share_link": "https://www.google.com/travel/flights/booking?tfs=blocked123",
                "source": "google_flights",
                "trip_type": "one_way",
                "ticket_structure": "single",
                "price_currency": "GBP",
                "price_amount": 300.0,
                "price_market": "UK",
                "captured_at": "2026-03-08T03:15:00Z",
                "price_checked_at": "2026-03-08T03:15:00Z",
                "origin": "LHR",
                "destination": "PVG",
                "outbound": {
                  "depart_airport": "LHR",
                  "depart_time": "2026-05-10T11:30:00+01:00",
                  "arrive_airport": "PVG",
                  "arrive_time": "2026-05-11T07:45:00+08:00",
                  "duration_minutes": 700,
                  "stops": 0,
                  "legs": [
                    {
                      "flight_number": "MU502",
                      "airline": "China Eastern",
                      "airline_code": "MU",
                      "depart_airport": "LHR",
                      "depart_time": "2026-05-10T11:30:00+01:00",
                      "arrive_airport": "PVG",
                      "arrive_time": "2026-05-11T07:45:00+08:00",
                      "duration_minutes": 700
                    }
                  ]
                }
              }
            ]
            """.trimIndent()

        val summary = JsonImporter.importFromJson(json, "test-investigation")

        assertEquals(0, summary.imported)
        assertEquals(1, summary.validationErrors)
        assertTrue(
            summary.errorResults
                .first()
                .errors
                .any { it.contains("blocked airline") },
        )
    }

    @Test
    fun `validation error for invalid ID`() {
        // ID doesn't match sha256(share_link)[:12]
        val json =
            """
            [
              {
                "id": "wrongid12345",
                "share_link": "https://www.google.com/travel/flights/booking?tfs=validurl",
                "source": "google_flights",
                "trip_type": "one_way",
                "ticket_structure": "single",
                "price_currency": "GBP",
                "price_amount": 400.0,
                "price_market": "UK",
                "captured_at": "2026-03-08T03:15:00Z",
                "price_checked_at": "2026-03-08T03:15:00Z",
                "origin": "LHR",
                "destination": "NRT",
                "outbound": {
                  "depart_airport": "LHR",
                  "depart_time": "2026-05-10T11:30:00+01:00",
                  "arrive_airport": "NRT",
                  "arrive_time": "2026-05-11T07:45:00+09:00",
                  "duration_minutes": 735,
                  "stops": 0,
                  "legs": [
                    {
                      "flight_number": "JL402",
                      "airline": "Japan Airlines",
                      "airline_code": "JL",
                      "depart_airport": "LHR",
                      "depart_time": "2026-05-10T11:30:00+01:00",
                      "arrive_airport": "NRT",
                      "arrive_time": "2026-05-11T07:45:00+09:00",
                      "duration_minutes": 735
                    }
                  ]
                }
              }
            ]
            """.trimIndent()

        val summary = JsonImporter.importFromJson(json, "test-investigation")

        assertEquals(0, summary.imported)
        assertEquals(1, summary.validationErrors)
        assertTrue(
            summary.errorResults
                .first()
                .errors
                .any { it.contains("does not match expected") },
        )
    }

    @Test
    fun `import multiple flights with mixed results`() {
        val json =
            """
            [
              {
                "id": "d429ab63bdf7",
                "share_link": "https://www.google.com/travel/flights/booking?tfs=valid1",
                "source": "google_flights",
                "trip_type": "one_way",
                "ticket_structure": "single",
                "price_currency": "GBP",
                "price_amount": 400.0,
                "price_market": "UK",
                "captured_at": "2026-03-08T03:15:00Z",
                "price_checked_at": "2026-03-08T03:15:00Z",
                "origin": "LHR",
                "destination": "NRT",
                "outbound": {
                  "depart_airport": "LHR",
                  "depart_time": "2026-05-10T11:30:00+01:00",
                  "arrive_airport": "NRT",
                  "arrive_time": "2026-05-11T07:45:00+09:00",
                  "duration_minutes": 735,
                  "stops": 0,
                  "legs": [
                    {
                      "flight_number": "JL402",
                      "airline": "Japan Airlines",
                      "airline_code": "JL",
                      "depart_airport": "LHR",
                      "depart_time": "2026-05-10T11:30:00+01:00",
                      "arrive_airport": "NRT",
                      "arrive_time": "2026-05-11T07:45:00+09:00",
                      "duration_minutes": 735
                    }
                  ]
                }
              },
              {
                "id": "invalid",
                "share_link": "not-a-url",
                "source": "google_flights",
                "trip_type": "one_way",
                "ticket_structure": "single",
                "price_currency": "GBP",
                "price_amount": 300.0,
                "price_market": "UK",
                "captured_at": "2026-03-08T03:15:00Z",
                "price_checked_at": "2026-03-08T03:15:00Z",
                "origin": "LHR",
                "destination": "NRT",
                "outbound": {
                  "depart_airport": "LHR",
                  "depart_time": "2026-05-10T11:30:00+01:00",
                  "arrive_airport": "NRT",
                  "arrive_time": "2026-05-11T07:45:00+09:00",
                  "duration_minutes": 735,
                  "stops": 0,
                  "legs": [
                    {
                      "flight_number": "JL402",
                      "airline": "Japan Airlines",
                      "airline_code": "JL",
                      "depart_airport": "LHR",
                      "depart_time": "2026-05-10T11:30:00+01:00",
                      "arrive_airport": "NRT",
                      "arrive_time": "2026-05-11T07:45:00+09:00",
                      "duration_minutes": 735
                    }
                  ]
                }
              }
            ]
            """.trimIndent()

        val summary = JsonImporter.importFromJson(json, "test-investigation")

        assertEquals(2, summary.total)
        assertEquals(1, summary.imported)
        assertEquals(1, summary.validationErrors)
    }

    @Test
    fun `handle malformed JSON`() {
        val malformedJson = "{ this is not valid JSON }"

        val summary = JsonImporter.importFromJson(malformedJson, "test-investigation")

        assertEquals(0, summary.total)
        assertEquals(1, summary.validationErrors)
        assertTrue(
            summary.errorResults
                .first()
                .errors
                .any { it.contains("Failed to parse JSON") },
        )
    }

    @Test
    fun `handle empty array`() {
        val emptyJson = "[]"

        val summary = JsonImporter.importFromJson(emptyJson, "test-investigation")

        assertEquals(0, summary.total)
        assertEquals(0, summary.imported)
        assertEquals(0, summary.validationErrors)
    }

    @Test
    fun `import flight with connecting legs`() {
        val json =
            """
            [
              {
                "id": "ae65f463ee10",
                "share_link": "https://www.google.com/travel/flights/booking?tfs=connection123",
                "source": "google_flights",
                "trip_type": "one_way",
                "ticket_structure": "single",
                "price_currency": "GBP",
                "price_amount": 650.0,
                "price_market": "UK",
                "captured_at": "2026-03-08T03:15:00Z",
                "price_checked_at": "2026-03-08T03:15:00Z",
                "origin": "LHR",
                "destination": "NRT",
                "outbound": {
                  "depart_airport": "LHR",
                  "depart_time": "2026-05-10T08:00:00+01:00",
                  "arrive_airport": "NRT",
                  "arrive_time": "2026-05-11T09:00:00+09:00",
                  "duration_minutes": 900,
                  "stops": 1,
                  "legs": [
                    {
                      "flight_number": "BA007",
                      "airline": "British Airways",
                      "airline_code": "BA",
                      "depart_airport": "LHR",
                      "depart_time": "2026-05-10T08:00:00+01:00",
                      "arrive_airport": "HND",
                      "arrive_time": "2026-05-11T05:00:00+09:00",
                      "duration_minutes": 720
                    },
                    {
                      "flight_number": "NH2345",
                      "airline": "ANA",
                      "airline_code": "NH",
                      "depart_airport": "HND",
                      "depart_time": "2026-05-11T07:00:00+09:00",
                      "arrive_airport": "NRT",
                      "arrive_time": "2026-05-11T09:00:00+09:00",
                      "duration_minutes": 120
                    }
                  ]
                }
              }
            ]
            """.trimIndent()

        val summary = JsonImporter.importFromJson(json, "test-investigation")

        assertEquals(1, summary.imported)
        assertEquals(0, summary.validationErrors)

        val flights = FlightQueries.listByInvestigation("test-investigation")
        assertEquals(1, flights.size)
    }
}
