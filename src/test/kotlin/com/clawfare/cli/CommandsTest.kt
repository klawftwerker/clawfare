package com.clawfare.cli

import com.clawfare.db.ClawfareDatabase
import com.clawfare.db.FlightDto
import com.clawfare.db.FlightQueries
import com.clawfare.db.InvestigationDto
import com.clawfare.db.InvestigationQueries
import com.clawfare.db.PriceHistoryDto
import com.clawfare.db.PriceHistoryQueries
import com.clawfare.model.FlightValidator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant

/**
 * Integration tests for CLI commands.
 * Uses in-memory SQLite database with shared connection.
 */
class CommandsTest {
    private lateinit var outStream: ByteArrayOutputStream
    private lateinit var errStream: ByteArrayOutputStream
    private val originalOut = System.out
    private val originalErr = System.err

    @BeforeEach
    fun setup() {
        // Connect to shared in-memory database and create tables
        ClawfareDatabase.connectInMemory(createSchema = true)

        // Capture output
        outStream = ByteArrayOutputStream()
        errStream = ByteArrayOutputStream()
        System.setOut(PrintStream(outStream))
        System.setErr(PrintStream(errStream))
    }

    @AfterEach
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        ClawfareDatabase.dropTables()
    }

    private fun stdout(): String = outStream.toString()

    private fun stderr(): String = errStream.toString()

    /**
     * Execute CLI command with pre-configured database.
     * The command will use the already-connected database.
     */
    private fun execute(vararg args: String): Int {
        val clawfareCmd = ClawfareCommand()
        val cmd = CommandLine(clawfareCmd)
        return cmd.execute(*args)
    }

    // ========================================================================
    // Investigation Tests
    // ========================================================================

    @Test
    fun `inv new creates investigation`() {
        val exitCode =
            execute(
                "inv",
                "new",
                "tokyo-may-2026",
                "--origin",
                "LHR",
                "--dest",
                "NRT",
                "--depart",
                "2026-05-01:2026-05-15",
                "--return",
                "2026-05-15:2026-05-30",
                "--cabin",
                "business",
            )

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertTrue(stdout().contains("Created investigation"))
        assertTrue(stdout().contains("tokyo-may-2026"))

        // Verify in database
        val inv = InvestigationQueries.getBySlug("tokyo-may-2026")
        assertNotNull(inv)
        assertEquals("LHR", inv!!.origin)
        assertEquals("NRT", inv.destination)
        assertEquals("business", inv.cabinClass)
    }

    @Test
    fun `inv new rejects duplicate slug`() {
        // Create first investigation
        createTestInvestigation("tokyo-may-2026")

        val exitCode =
            execute(
                "inv",
                "new",
                "tokyo-may-2026",
                "--origin",
                "LHR",
                "--dest",
                "NRT",
                "--depart",
                "2026-05-01:2026-05-15",
            )

        assertEquals(1, exitCode)
        assertTrue(stderr().contains("already exists"))
    }

    @Test
    fun `inv list shows all investigations`() {
        createTestInvestigation("tokyo-may-2026")
        createTestInvestigation("paris-june-2026")

        val exitCode = execute("inv", "list")

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertTrue(stdout().contains("tokyo-may-2026"))
        assertTrue(stdout().contains("paris-june-2026"))
    }

    @Test
    fun `inv list empty returns message`() {
        val exitCode = execute("inv", "list")

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertTrue(stdout().contains("No investigations found"))
    }

    @Test
    fun `inv show displays investigation details`() {
        createTestInvestigation("tokyo-may-2026")

        val exitCode = execute("inv", "show", "tokyo-may-2026")

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertTrue(stdout().contains("tokyo-may-2026"))
        assertTrue(stdout().contains("LHR → NRT"))
    }

    @Test
    fun `inv show returns error for unknown slug`() {
        val exitCode = execute("inv", "show", "unknown")

        assertEquals(1, exitCode)
        assertTrue(stderr().contains("not found"))
    }

    @Test
    fun `inv delete removes investigation`() {
        createTestInvestigation("tokyo-may-2026")

        val exitCode = execute("inv", "delete", "tokyo-may-2026", "--force")

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertTrue(stdout().contains("Deleted"))
        assertNull(InvestigationQueries.getBySlug("tokyo-may-2026"))
    }

    @Test
    fun `inv delete requires force when flights exist`() {
        createTestInvestigation("tokyo-may-2026")
        createTestFlight("tokyo-may-2026", "https://example.com/flight1")

        val exitCode = execute("inv", "delete", "tokyo-may-2026")

        assertEquals(1, exitCode)
        assertTrue(stdout().contains("Use --force"))
        assertNotNull(InvestigationQueries.getBySlug("tokyo-may-2026"))
    }

    @Test
    fun `inv delete with force removes flights too`() {
        createTestInvestigation("tokyo-may-2026")
        createTestFlight("tokyo-may-2026", "https://example.com/flight1")

        val exitCode = execute("inv", "delete", "tokyo-may-2026", "--force")

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertNull(InvestigationQueries.getBySlug("tokyo-may-2026"))
        assertTrue(FlightQueries.listByInvestigation("tokyo-may-2026").isEmpty())
    }

    // ========================================================================
    // Flight Tests
    // ========================================================================

    private val testOutboundJson =
        """
        {
            "depart_airport": "LHR",
            "arrive_airport": "NRT",
            "depart_time": "2026-05-01T10:00:00Z",
            "arrive_time": "2026-05-02T06:00:00Z",
            "duration_minutes": 720,
            "stops": 0,
            "legs": [{
                "flight_number": "123",
                "airline": "British Airways",
                "airline_code": "BA",
                "depart_airport": "LHR",
                "arrive_airport": "NRT",
                "depart_time": "2026-05-01T10:00:00Z",
                "arrive_time": "2026-05-02T06:00:00Z",
                "duration_minutes": 720
            }]
        }
        """.trimIndent()

    private val testReturnJson =
        """
        {
            "depart_airport": "NRT",
            "arrive_airport": "LHR",
            "depart_time": "2026-05-15T10:00:00Z",
            "arrive_time": "2026-05-15T18:00:00Z",
            "duration_minutes": 780,
            "stops": 0,
            "legs": [{
                "flight_number": "124",
                "airline": "British Airways",
                "airline_code": "BA",
                "depart_airport": "NRT",
                "arrive_airport": "LHR",
                "depart_time": "2026-05-15T10:00:00Z",
                "arrive_time": "2026-05-15T18:00:00Z",
                "duration_minutes": 780
            }]
        }
        """.trimIndent()

    @Test
    fun `flight add creates flight`() {
        createTestInvestigation("tokyo-may-2026")

        val exitCode =
            execute(
                "flight",
                "add",
                "tokyo-may-2026",
                "--link",
                "https://example.com/flight",
                "--source",
                "google_flights",
                "--price",
                "2847",
                "--currency",
                "GBP",
                "--market",
                "UK",
                "--outbound",
                testOutboundJson,
                "--return",
                testReturnJson,
            )

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertTrue(stdout().contains("Added flight"))

        // Verify in database
        val flights = FlightQueries.listByInvestigation("tokyo-may-2026")
        assertEquals(1, flights.size)
        assertEquals(2847.0, flights[0].priceAmount)
    }

    @Test
    fun `flight add rejects duplicate link`() {
        createTestInvestigation("tokyo-may-2026")
        createTestFlight("tokyo-may-2026", "https://example.com/flight")

        val exitCode =
            execute(
                "flight",
                "add",
                "tokyo-may-2026",
                "--link",
                "https://example.com/flight",
                "--source",
                "google_flights",
                "--price",
                "2847",
                "--currency",
                "GBP",
                "--market",
                "UK",
                "--outbound",
                testOutboundJson,
                "--return",
                testReturnJson,
            )

        assertEquals(1, exitCode)
        assertTrue(stderr().contains("already exists"))
    }

    @Test
    fun `flight add rejects invalid investigation`() {
        val exitCode =
            execute(
                "flight",
                "add",
                "unknown-investigation",
                "--link",
                "https://example.com/flight",
                "--source",
                "google_flights",
                "--price",
                "2847",
                "--currency",
                "GBP",
                "--market",
                "UK",
                "--outbound",
                testOutboundJson,
            )

        assertEquals(1, exitCode)
        assertTrue(stderr().contains("not found"))
    }

    @Test
    fun `flight list shows flights`() {
        createTestInvestigation("tokyo-may-2026")
        createTestFlight("tokyo-may-2026", "https://example.com/flight1", 2500.0)
        createTestFlight("tokyo-may-2026", "https://example.com/flight2", 2800.0)

        val exitCode = execute("flight", "list", "tokyo-may-2026")

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertTrue(stdout().contains("LHR→NRT"))
    }

    @Test
    fun `flight list sorts by price`() {
        createTestInvestigation("tokyo-may-2026")
        createTestFlight("tokyo-may-2026", "https://example.com/flight1", 2800.0)
        createTestFlight("tokyo-may-2026", "https://example.com/flight2", 2500.0)

        val exitCode = execute("flight", "list", "tokyo-may-2026", "--sort", "price")

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        // First flight in output should be cheaper
        val lines = stdout().lines().filter { it.contains("£") }
        assertTrue(lines.isNotEmpty())
    }

    @Test
    fun `flight list filters by max price`() {
        createTestInvestigation("tokyo-may-2026")
        createTestFlight("tokyo-may-2026", "https://example.com/flight1", 2500.0)
        createTestFlight("tokyo-may-2026", "https://example.com/flight2", 3000.0)

        val exitCode =
            execute(
                "flight",
                "list",
                "tokyo-may-2026",
                "--max-price",
                "2600",
            )

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertTrue(stdout().contains("2500"))
        assertFalse(stdout().contains("3000"))
    }

    @Test
    fun `flight show displays details`() {
        createTestInvestigation("tokyo-may-2026")
        val flightId = createTestFlight("tokyo-may-2026", "https://example.com/flight")

        val exitCode = execute("flight", "show", flightId)

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertTrue(stdout().contains(flightId))
        assertTrue(stdout().contains("LHR → NRT"))
    }

    @Test
    fun `flight show returns error for unknown id`() {
        val exitCode = execute("flight", "show", "unknown")

        assertEquals(1, exitCode)
        assertTrue(stderr().contains("not found"))
    }

    @Test
    fun `flight delete removes flight`() {
        createTestInvestigation("tokyo-may-2026")
        val flightId = createTestFlight("tokyo-may-2026", "https://example.com/flight")

        val exitCode = execute("flight", "delete", flightId)

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertTrue(stdout().contains("Deleted"))
        assertNull(FlightQueries.getById(flightId))
    }

    @Test
    fun `flight tag adds tag`() {
        createTestInvestigation("tokyo-may-2026")
        val flightId = createTestFlight("tokyo-may-2026", "https://example.com/flight")

        val exitCode = execute("flight", "tag", flightId, "shortlist")

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertTrue(stdout().contains("Added tag"))

        val flight = FlightQueries.getById(flightId)
        assertNotNull(flight)
        assertTrue(flight!!.tags?.contains("shortlist") ?: false)
    }

    @Test
    fun `flight untag removes tag`() {
        createTestInvestigation("tokyo-may-2026")
        val flightId = createTestFlight("tokyo-may-2026", "https://example.com/flight")
        // First add the tag
        val flight = FlightQueries.getById(flightId)!!
        FlightQueries.update(flight.copy(tags = Output.encodeTags(listOf("shortlist", "reviewed"))))

        val exitCode = execute("flight", "untag", flightId, "shortlist")

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertTrue(stdout().contains("Removed tag"))

        val updatedFlight = FlightQueries.getById(flightId)
        val tags = updatedFlight!!.tags?.let { Output.parseTags(it) } ?: emptyList()
        assertFalse(tags.contains("shortlist"))
        assertTrue(tags.contains("reviewed"))
    }

    @Test
    fun `flight price updates price and records history`() {
        createTestInvestigation("tokyo-may-2026")
        val flightId = createTestFlight("tokyo-may-2026", "https://example.com/flight", 2500.0)

        val exitCode =
            execute(
                "flight",
                "price",
                flightId,
                "--amount",
                "2300",
            )

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertTrue(stdout().contains("Updated price"))

        // Verify price updated
        val flight = FlightQueries.getById(flightId)
        assertEquals(2300.0, flight!!.priceAmount)

        // Verify history recorded
        val history = PriceHistoryQueries.getByFlightId(flightId)
        assertEquals(2, history.size) // Initial + update
        assertEquals(2300.0, history.last().amount)
    }

    @Test
    fun `flight list filters by tag`() {
        createTestInvestigation("tokyo-may-2026")
        val id1 = createTestFlight("tokyo-may-2026", "https://example.com/flight1", 2500.0)
        createTestFlight("tokyo-may-2026", "https://example.com/flight2", 2800.0)

        // Tag one flight
        val flight = FlightQueries.getById(id1)!!
        FlightQueries.update(flight.copy(tags = Output.encodeTags(listOf("shortlist"))))

        val exitCode =
            execute(
                "flight",
                "list",
                "tokyo-may-2026",
                "--tag",
                "shortlist",
            )

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        val output = stdout()
        assertTrue(output.contains(id1))
    }

    // ========================================================================
    // Output Formatting Tests
    // ========================================================================

    @Test
    fun `formatPrice formats GBP correctly`() {
        assertEquals("£100.00", Output.formatPrice(100.0, "GBP"))
    }

    @Test
    fun `formatPrice formats USD correctly`() {
        assertEquals("$100.00", Output.formatPrice(100.0, "USD"))
    }

    @Test
    fun `formatPrice formats JPY without decimals`() {
        assertEquals("¥15000", Output.formatPrice(15000.0, "JPY"))
    }

    @Test
    fun `formatDuration formats hours and minutes`() {
        assertEquals("2h 30m", Output.formatDuration(150))
    }

    @Test
    fun `formatDuration handles hours only`() {
        assertEquals("2h", Output.formatDuration(120))
    }

    @Test
    fun `formatDuration handles minutes only`() {
        assertEquals("45m", Output.formatDuration(45))
    }

    @Test
    fun `parseTags handles valid JSON`() {
        val tags = Output.parseTags("""["shortlist","reviewed"]""")
        assertEquals(listOf("shortlist", "reviewed"), tags)
    }

    @Test
    fun `parseTags handles invalid JSON`() {
        val tags = Output.parseTags("invalid")
        assertEquals(emptyList<String>(), tags)
    }

    @Test
    fun `encodeTags creates valid JSON`() {
        val json = Output.encodeTags(listOf("a", "b"))
        assertEquals("""["a","b"]""", json)
    }

    // ========================================================================
    // Helper Functions
    // ========================================================================

    private fun createTestInvestigation(slug: String): InvestigationDto {
        val now = Instant.now().toString()
        val dto =
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
                createdAt = now,
                updatedAt = now,
            )
        return InvestigationQueries.create(dto)
    }

    private fun createTestFlight(
        investigationSlug: String,
        shareLink: String,
        price: Double = 2500.0,
    ): String {
        val id = FlightValidator.generateId(shareLink)
        val now = Instant.now().toString()

        val outboundJson =
            """
            {
                "depart_airport": "LHR",
                "arrive_airport": "NRT",
                "depart_time": "2026-05-01T10:00:00Z",
                "arrive_time": "2026-05-02T06:00:00Z",
                "duration_minutes": 720,
                "stops": 0,
                "legs": [{
                    "flight_number": "123",
                    "airline": "British Airways",
                    "airline_code": "BA",
                    "depart_airport": "LHR",
                    "arrive_airport": "NRT",
                    "depart_time": "2026-05-01T10:00:00Z",
                    "arrive_time": "2026-05-02T06:00:00Z",
                    "duration_minutes": 720
                }]
            }
            """.trimIndent()

        val dto =
            FlightDto(
                id = id,
                investigationSlug = investigationSlug,
                shareLink = shareLink,
                source = "google_flights",
                tripType = "round_trip",
                ticketStructure = "single",
                priceAmount = price,
                priceCurrency = "GBP",
                priceMarket = "UK",
                origin = "LHR",
                destination = "NRT",
                outboundJson = outboundJson,
                returnJson = outboundJson,
                capturedAt = now,
                priceCheckedAt = now,
            )
        FlightQueries.create(dto)

        // Add initial price history
        PriceHistoryQueries.create(
            PriceHistoryDto(
                flightId = id,
                amount = price,
                currency = "GBP",
                checkedAt = now,
            ),
        )

        return id
    }
}
