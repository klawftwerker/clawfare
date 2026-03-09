package com.clawfare.db

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueriesTest {
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        // Use a temp file for test database to avoid in-memory connection issues
        val dbPath = tempDir.resolve("test.db").toString()
        ClawfareDatabase.connect(path = dbPath, createSchema = true)
    }

    @AfterEach
    fun teardown() {
        ClawfareDatabase.dropTables()
    }

    // ========================================================================
    // Investigation Tests
    // ========================================================================

    @Test
    fun `create investigation`() {
        val dto = createTestInvestigation("tokyo-2026")
        val result = InvestigationQueries.create(dto)

        assertEquals(dto.slug, result.slug)
        assertEquals(dto.origin, result.origin)
        assertEquals(dto.destination, result.destination)
    }

    @Test
    fun `get investigation by slug`() {
        val dto = createTestInvestigation("tokyo-2026")
        InvestigationQueries.create(dto)

        val result = InvestigationQueries.getBySlug("tokyo-2026")

        assertNotNull(result)
        assertEquals("tokyo-2026", result.slug)
        assertEquals("LHR", result.origin)
        assertEquals("NRT", result.destination)
    }

    @Test
    fun `get investigation by slug returns null for non-existent`() {
        val result = InvestigationQueries.getBySlug("non-existent")
        assertNull(result)
    }

    @Test
    fun `list all investigations`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        InvestigationQueries.create(createTestInvestigation("paris-2026"))

        val results = InvestigationQueries.listAll()

        assertEquals(2, results.size)
        assertTrue(results.any { it.slug == "tokyo-2026" })
        assertTrue(results.any { it.slug == "paris-2026" })
    }

    @Test
    fun `update investigation`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))

        val updated =
            InvestigationQueries.update(
                createTestInvestigation("tokyo-2026").copy(
                    cabinClass = "business",
                    maxStops = 2,
                ),
            )

        assertNotNull(updated)
        assertEquals("business", updated.cabinClass)
        assertEquals(2, updated.maxStops)
    }

    @Test
    fun `update non-existent investigation returns null`() {
        val result =
            InvestigationQueries.update(
                createTestInvestigation("non-existent"),
            )
        assertNull(result)
    }

    @Test
    fun `delete investigation`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))

        val deleted = InvestigationQueries.delete("tokyo-2026")

        assertTrue(deleted)
        assertNull(InvestigationQueries.getBySlug("tokyo-2026"))
    }

    @Test
    fun `delete non-existent investigation returns false`() {
        val deleted = InvestigationQueries.delete("non-existent")
        assertTrue(!deleted)
    }

    // ========================================================================
    // Flight Tests
    // ========================================================================

    @Test
    fun `create flight`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        val dto = createTestFlight("abc123", "tokyo-2026")

        val result = FlightQueries.create(dto)

        assertEquals(dto.id, result.id)
        assertEquals(dto.shareLink, result.shareLink)
        assertEquals(dto.priceAmount, result.priceAmount)
    }

    @Test
    fun `get flight by id`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        FlightQueries.create(createTestFlight("abc123", "tokyo-2026"))

        val result = FlightQueries.getById("abc123")

        assertNotNull(result)
        assertEquals("abc123", result.id)
        assertEquals(2500.0, result.priceAmount)
    }

    @Test
    fun `get flight by id returns null for non-existent`() {
        val result = FlightQueries.getById("non-existent")
        assertNull(result)
    }

    @Test
    fun `get flight by share link`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        FlightQueries.create(createTestFlight("abc123", "tokyo-2026"))

        val result = FlightQueries.getByShareLink("https://google.com/flights/abc123")

        assertNotNull(result)
        assertEquals("abc123", result.id)
    }

    @Test
    fun `list flights by investigation`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        InvestigationQueries.create(createTestInvestigation("paris-2026"))

        FlightQueries.create(createTestFlight("abc123", "tokyo-2026"))
        FlightQueries.create(createTestFlight("def456", "tokyo-2026"))
        FlightQueries.create(createTestFlight("ghi789", "paris-2026"))

        val results = FlightQueries.listByInvestigation("tokyo-2026")

        assertEquals(2, results.size)
        assertTrue(results.all { it.investigationSlug == "tokyo-2026" })
    }

    @Test
    fun `list all flights`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        FlightQueries.create(createTestFlight("abc123", "tokyo-2026"))
        FlightQueries.create(createTestFlight("def456", "tokyo-2026"))

        val results = FlightQueries.listAll()

        assertEquals(2, results.size)
    }

    @Test
    fun `update flight`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        FlightQueries.create(createTestFlight("abc123", "tokyo-2026"))

        val updated =
            FlightQueries.update(
                createTestFlight("abc123", "tokyo-2026").copy(
                    priceAmount = 2200.0,
                    notes = "Price dropped!",
                ),
            )

        assertNotNull(updated)
        assertEquals(2200.0, updated.priceAmount)
        assertEquals("Price dropped!", updated.notes)
    }

    @Test
    fun `update non-existent flight returns null`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))

        val result =
            FlightQueries.update(
                createTestFlight("non-existent", "tokyo-2026"),
            )
        assertNull(result)
    }

    @Test
    fun `update price via dto`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        val flight = FlightQueries.create(createTestFlight("abc123", "tokyo-2026"))

        val updated = FlightQueries.update(flight.copy(priceAmount = 1999.0, priceCurrency = "GBP"))

        assertNotNull(updated)
        assertEquals(1999.0, updated!!.priceAmount)
        assertEquals("GBP", updated.priceCurrency)
    }

    @Test
    fun `delete flight`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        FlightQueries.create(createTestFlight("abc123", "tokyo-2026"))

        val deleted = FlightQueries.delete("abc123")

        assertTrue(deleted)
        assertNull(FlightQueries.getById("abc123"))
    }

    @Test
    fun `delete flight also deletes price history`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        FlightQueries.create(createTestFlight("abc123", "tokyo-2026"))
        PriceHistoryQueries.create(createTestPriceHistory("abc123", 2500.0))
        PriceHistoryQueries.create(createTestPriceHistory("abc123", 2400.0))

        FlightQueries.delete("abc123")

        assertEquals(0, PriceHistoryQueries.getByFlightId("abc123").size)
    }

    @Test
    fun `delete flights by investigation`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        FlightQueries.create(createTestFlight("abc123", "tokyo-2026"))
        FlightQueries.create(createTestFlight("def456", "tokyo-2026"))

        val deleted = FlightQueries.deleteByInvestigation("tokyo-2026")

        assertEquals(2, deleted)
        assertEquals(0, FlightQueries.listByInvestigation("tokyo-2026").size)
    }

    @Test
    fun `delete flights by investigation also deletes price history`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        FlightQueries.create(createTestFlight("abc123", "tokyo-2026"))
        PriceHistoryQueries.create(createTestPriceHistory("abc123", 2500.0))

        FlightQueries.deleteByInvestigation("tokyo-2026")

        assertEquals(0, PriceHistoryQueries.getByFlightId("abc123").size)
    }

    @Test
    fun `cabin mixed stored correctly`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        FlightQueries.create(
            createTestFlight("abc123", "tokyo-2026").copy(cabinMixed = true),
        )

        val result = FlightQueries.getById("abc123")

        assertNotNull(result)
        assertTrue(result.cabinMixed)
    }

    // ========================================================================
    // Price History Tests
    // ========================================================================

    @Test
    fun `create price history`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        FlightQueries.create(createTestFlight("abc123", "tokyo-2026"))

        val dto = createTestPriceHistory("abc123", 2500.0)
        val result = PriceHistoryQueries.create(dto)

        assertNotNull(result.id)
        assertEquals(dto.flightId, result.flightId)
        assertEquals(dto.amount, result.amount)
    }

    @Test
    fun `get price history by flight id`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        FlightQueries.create(createTestFlight("abc123", "tokyo-2026"))

        PriceHistoryQueries.create(createTestPriceHistory("abc123", 2500.0, "2024-01-01T00:00:00Z"))
        PriceHistoryQueries.create(createTestPriceHistory("abc123", 2400.0, "2024-01-02T00:00:00Z"))
        PriceHistoryQueries.create(createTestPriceHistory("abc123", 2300.0, "2024-01-03T00:00:00Z"))

        val results = PriceHistoryQueries.getByFlightId("abc123")

        assertEquals(3, results.size)
        // Should be ordered by checkedAt
        assertEquals(2500.0, results[0].amount)
        assertEquals(2400.0, results[1].amount)
        assertEquals(2300.0, results[2].amount)
    }

    @Test
    fun `get latest price history`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        FlightQueries.create(createTestFlight("abc123", "tokyo-2026"))

        PriceHistoryQueries.create(createTestPriceHistory("abc123", 2500.0, "2024-01-01T00:00:00Z"))
        PriceHistoryQueries.create(createTestPriceHistory("abc123", 2200.0, "2024-01-03T00:00:00Z"))
        PriceHistoryQueries.create(createTestPriceHistory("abc123", 2400.0, "2024-01-02T00:00:00Z"))

        val latest = PriceHistoryQueries.getLatest("abc123")

        assertNotNull(latest)
        assertEquals(2200.0, latest.amount) // Most recent by checkedAt
    }

    @Test
    fun `get latest returns null for no history`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        FlightQueries.create(createTestFlight("abc123", "tokyo-2026"))

        val latest = PriceHistoryQueries.getLatest("abc123")

        assertNull(latest)
    }

    @Test
    fun `delete price history by flight id`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        FlightQueries.create(createTestFlight("abc123", "tokyo-2026"))

        PriceHistoryQueries.create(createTestPriceHistory("abc123", 2500.0))
        PriceHistoryQueries.create(createTestPriceHistory("abc123", 2400.0))

        val deleted = PriceHistoryQueries.deleteByFlightId("abc123")

        assertEquals(2, deleted)
        assertEquals(0, PriceHistoryQueries.getByFlightId("abc123").size)
    }

    // ========================================================================
    // Helper Functions
    // ========================================================================

    private fun createTestInvestigation(slug: String): InvestigationDto =
        InvestigationDto(
            slug = slug,
            origin = "LHR",
            destination = "NRT",
            departStart = "2026-05-01",
            departEnd = "2026-05-15",
            returnStart = "2026-05-15",
            returnEnd = "2026-05-30",
            cabinClass = "economy",
            maxStops = 1,
            createdAt = "2024-01-01T00:00:00Z",
            updatedAt = "2024-01-01T00:00:00Z",
        )

    private fun createTestFlight(
        id: String,
        investigationSlug: String,
    ): FlightDto =
        FlightDto(
            id = id,
            investigationSlug = investigationSlug,
            shareLink = "https://google.com/flights/$id",
            source = "google_flights",
            tripType = "round_trip",
            ticketStructure = "single",
            priceAmount = 2500.0,
            priceCurrency = "GBP",
            priceMarket = "UK",
            origin = "LHR",
            destination = "NRT",
            outboundJson = """{"depart_airport":"LHR","arrive_airport":"NRT","legs":[]}""",
            returnJson = """{"depart_airport":"NRT","arrive_airport":"LHR","legs":[]}""",
            bookingClass = "Y",
            cabinMixed = false,
            notes = null,
            tags = null,
            capturedAt = "2024-01-01T00:00:00Z",
            priceCheckedAt = "2024-01-01T00:00:00Z",
        )

    private fun createTestPriceHistory(
        flightId: String,
        amount: Double,
        checkedAt: String = "2024-01-01T00:00:00Z",
    ): PriceHistoryDto =
        PriceHistoryDto(
            flightId = flightId,
            amount = amount,
            currency = "GBP",
            checkedAt = checkedAt,
        )
}
