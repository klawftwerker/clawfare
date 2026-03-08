package com.clawfare.cli

import com.clawfare.db.ClawfareDatabase
import com.clawfare.db.FlightDto
import com.clawfare.db.FlightQueries
import com.clawfare.db.InvestigationDto
import com.clawfare.db.InvestigationQueries
import com.clawfare.db.PriceHistoryDto
import com.clawfare.db.PriceHistoryQueries
import com.clawfare.model.FlightSegment
import com.clawfare.model.FlightValidator
import kotlinx.serialization.json.Json
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.time.Instant
import java.util.concurrent.Callable

// ============================================================================
// Investigation Commands
// ============================================================================

/**
 * Investigation parent command.
 */
@Command(
    name = "inv",
    description = ["Manage flight investigations"],
    subcommands = [
        InvNewCommand::class,
        InvListCommand::class,
        InvShowCommand::class,
        InvDeleteCommand::class,
    ],
)
class InvCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: ClawfareCommand

    override fun call(): Int {
        println("Use 'clawfare inv --help' for available subcommands")
        return 0
    }
}

/**
 * Create a new investigation.
 */
@Command(
    name = "new",
    description = ["Create a new investigation"],
)
class InvNewCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: InvCommand

    @Parameters(index = "0", description = ["Investigation slug (e.g., tokyo-may-2026)"])
    lateinit var slug: String

    @Option(names = ["--origin", "-o"], required = true, description = ["Origin airport code"])
    lateinit var origin: String

    @Option(names = ["--dest", "-d"], required = true, description = ["Destination airport code"])
    lateinit var destination: String

    @Option(
        names = ["--depart"],
        required = true,
        description = ["Departure date range (YYYY-MM-DD:YYYY-MM-DD)"],
    )
    lateinit var depart: String

    @Option(names = ["--return"], description = ["Return date range (YYYY-MM-DD:YYYY-MM-DD)"])
    var returnRange: String? = null

    @Option(names = ["--cabin"], description = ["Cabin class (economy, business, first)"], defaultValue = "economy")
    var cabinClass: String = "economy"

    @Option(names = ["--max-stops"], description = ["Maximum number of stops"], defaultValue = "1")
    var maxStops: Int = 1

    override fun call(): Int {
        parent.parent.ensureDb()

        // Check if slug already exists
        if (InvestigationQueries.getBySlug(slug) != null) {
            Output.error("Investigation '$slug' already exists")
            return 1
        }

        // Parse date ranges
        val departParts = depart.split(":")
        if (departParts.size != 2) {
            Output.error("Invalid depart format. Use YYYY-MM-DD:YYYY-MM-DD")
            return 1
        }

        val (returnStart, returnEnd) =
            if (returnRange != null) {
                val parts = returnRange!!.split(":")
                if (parts.size != 2) {
                    Output.error("Invalid return format. Use YYYY-MM-DD:YYYY-MM-DD")
                    return 1
                }
                parts[0] to parts[1]
            } else {
                null to null
            }

        val now = Instant.now().toString()
        val dto =
            InvestigationDto(
                slug = slug,
                origin = origin.uppercase(),
                destination = destination.uppercase(),
                departStart = departParts[0],
                departEnd = departParts[1],
                returnStart = returnStart,
                returnEnd = returnEnd,
                cabinClass = cabinClass,
                maxStops = maxStops,
                createdAt = now,
                updatedAt = now,
            )

        try {
            InvestigationQueries.create(dto)
            Output.success("Created investigation: $slug")
            println(Output.formatInvestigation(dto))
            return 0
        } catch (e: Exception) {
            Output.error("Failed to create investigation: ${e.message}")
            return 1
        }
    }
}

/**
 * List all investigations.
 */
@Command(
    name = "list",
    description = ["List all investigations"],
)
class InvListCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: InvCommand

    @Option(names = ["--json"], description = ["Output as JSON"])
    var jsonOutput: Boolean = false

    override fun call(): Int {
        parent.parent.ensureDb()

        val investigations = InvestigationQueries.listAll()

        if (jsonOutput) {
            println(Output.toJson(investigations))
        } else {
            println(Output.formatInvestigationTable(investigations))
        }

        return 0
    }
}

/**
 * Show investigation details.
 */
@Command(
    name = "show",
    description = ["Show investigation details"],
)
class InvShowCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: InvCommand

    @Parameters(index = "0", description = ["Investigation slug"])
    lateinit var slug: String

    @Option(names = ["--json"], description = ["Output as JSON"])
    var jsonOutput: Boolean = false

    override fun call(): Int {
        parent.parent.ensureDb()

        val investigation = InvestigationQueries.getBySlug(slug)
        if (investigation == null) {
            Output.error("Investigation '$slug' not found")
            return 1
        }

        if (jsonOutput) {
            println(Output.toJson(investigation))
        } else {
            println(Output.formatInvestigation(investigation, detailed = true))

            // Also show flight count
            val flights = FlightQueries.listByInvestigation(slug)
            println("  Flights:  ${flights.size}")
        }

        return 0
    }
}

/**
 * Delete an investigation.
 */
@Command(
    name = "delete",
    description = ["Delete an investigation and all its flights"],
)
class InvDeleteCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: InvCommand

    @Parameters(index = "0", description = ["Investigation slug"])
    lateinit var slug: String

    @Option(names = ["--force", "-f"], description = ["Skip confirmation"])
    var force: Boolean = false

    override fun call(): Int {
        parent.parent.ensureDb()

        val investigation = InvestigationQueries.getBySlug(slug)
        if (investigation == null) {
            Output.error("Investigation '$slug' not found")
            return 1
        }

        // Count associated flights
        val flights = FlightQueries.listByInvestigation(slug)

        if (!force && flights.isNotEmpty()) {
            Output.warn("This will delete ${flights.size} flight(s). Use --force to confirm.")
            return 1
        }

        // Delete flights first
        if (flights.isNotEmpty()) {
            FlightQueries.deleteByInvestigation(slug)
        }

        // Delete investigation
        if (InvestigationQueries.delete(slug)) {
            Output.success("Deleted investigation '$slug' and ${flights.size} flight(s)")
            return 0
        } else {
            Output.error("Failed to delete investigation")
            return 1
        }
    }
}

// ============================================================================
// Flight Commands
// ============================================================================

/**
 * Flight parent command.
 */
@Command(
    name = "flight",
    description = ["Manage flights"],
    subcommands = [
        FlightAddCommand::class,
        FlightListCommand::class,
        FlightShowCommand::class,
        FlightDeleteCommand::class,
        FlightTagCommand::class,
        FlightUntagCommand::class,
        FlightPriceCommand::class,
    ],
)
class FlightCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: ClawfareCommand

    override fun call(): Int {
        println("Use 'clawfare flight --help' for available subcommands")
        return 0
    }
}

/**
 * Add a new flight.
 */
@Command(
    name = "add",
    description = ["Add a new flight to an investigation"],
)
class FlightAddCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Investigation slug"])
    lateinit var investigationSlug: String

    @Option(names = ["--link", "-l"], required = true, description = ["Share link URL"])
    lateinit var shareLink: String

    @Option(names = ["--source", "-s"], required = true, description = ["Data source (e.g., google_flights)"])
    lateinit var source: String

    @Option(names = ["--price", "-p"], required = true, description = ["Price amount"])
    var priceAmount: Double = 0.0

    @Option(names = ["--currency", "-c"], required = true, description = ["Price currency (e.g., GBP)"])
    lateinit var priceCurrency: String

    @Option(names = ["--market", "-m"], required = true, description = ["Price market (e.g., UK)"])
    lateinit var priceMarket: String

    @Option(names = ["--outbound"], required = true, description = ["Outbound segment JSON"])
    lateinit var outboundJson: String

    @Option(names = ["--return"], description = ["Return segment JSON"])
    var returnJson: String? = null

    @Option(names = ["--type"], description = ["Trip type (round_trip, one_way)"], defaultValue = "round_trip")
    var tripType: String = "round_trip"

    @Option(names = ["--structure"], description = ["Ticket structure (single, two_one_ways)"], defaultValue = "single")
    var ticketStructure: String = "single"

    @Option(names = ["--notes"], description = ["Notes"])
    var notes: String? = null

    @Option(names = ["--tags"], description = ["Comma-separated tags"])
    var tags: String? = null

    override fun call(): Int {
        parent.parent.ensureDb()

        // Verify investigation exists
        if (InvestigationQueries.getBySlug(investigationSlug) == null) {
            Output.error("Investigation '$investigationSlug' not found")
            return 1
        }

        // Check if flight with this link already exists
        if (FlightQueries.getByShareLink(shareLink) != null) {
            Output.error("Flight with this link already exists")
            return 1
        }

        // Parse outbound segment to get origin/destination
        val outbound: FlightSegment
        try {
            outbound = Json.decodeFromString(outboundJson)
        } catch (e: Exception) {
            Output.error("Invalid outbound JSON: ${e.message}")
            return 1
        }

        // Parse return if provided
        val returnSegment: FlightSegment? =
            returnJson?.let {
                try {
                    Json.decodeFromString(it)
                } catch (e: Exception) {
                    Output.error("Invalid return JSON: ${e.message}")
                    return 1
                }
            }

        // Check return is required for round_trip
        if (tripType == "round_trip" && returnSegment == null) {
            Output.error("Return segment required for round_trip")
            return 1
        }

        val id = FlightValidator.generateId(shareLink)
        val now = Instant.now().toString()

        val tagsJson = tags?.let { Output.encodeTags(it.split(",").map { t -> t.trim() }) }

        val dto =
            FlightDto(
                id = id,
                investigationSlug = investigationSlug,
                shareLink = shareLink,
                source = source,
                tripType = tripType,
                ticketStructure = ticketStructure,
                priceAmount = priceAmount,
                priceCurrency = priceCurrency.uppercase(),
                priceMarket = priceMarket.uppercase(),
                origin = outbound.departAirport,
                destination = outbound.arriveAirport,
                outboundJson = outboundJson,
                returnJson = returnJson,
                notes = notes,
                tags = tagsJson,
                capturedAt = now,
                priceCheckedAt = now,
            )

        try {
            FlightQueries.create(dto)

            // Add initial price to history
            PriceHistoryQueries.create(
                PriceHistoryDto(
                    flightId = id,
                    amount = priceAmount,
                    currency = priceCurrency.uppercase(),
                    checkedAt = now,
                ),
            )

            Output.success("Added flight: $id")
            println("  Price: ${Output.formatPrice(priceAmount, priceCurrency.uppercase())}")
            println("  Route: ${outbound.departAirport} → ${outbound.arriveAirport}")
            return 0
        } catch (e: Exception) {
            Output.error("Failed to add flight: ${e.message}")
            return 1
        }
    }
}

/**
 * List flights.
 */
@Command(
    name = "list",
    description = ["List flights for an investigation"],
)
class FlightListCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Investigation slug"])
    lateinit var investigationSlug: String

    @Option(names = ["--sort"], description = ["Sort by (price, date, route)"], defaultValue = "price")
    var sortBy: String = "price"

    @Option(names = ["--limit"], description = ["Limit number of results"])
    var limit: Int? = null

    @Option(names = ["--tag"], description = ["Filter by tag"])
    var tagFilter: String? = null

    @Option(names = ["--max-price"], description = ["Maximum price filter"])
    var maxPrice: Double? = null

    @Option(names = ["--json"], description = ["Output as JSON"])
    var jsonOutput: Boolean = false

    override fun call(): Int {
        parent.parent.ensureDb()

        // Verify investigation exists
        if (InvestigationQueries.getBySlug(investigationSlug) == null) {
            Output.error("Investigation '$investigationSlug' not found")
            return 1
        }

        var flights = FlightQueries.listByInvestigation(investigationSlug)

        // Apply filters
        if (tagFilter != null) {
            flights =
                flights.filter { flight ->
                    flight.tags?.let { Output.parseTags(it).contains(tagFilter) } ?: false
                }
        }

        if (maxPrice != null) {
            flights = flights.filter { it.priceAmount <= maxPrice!! }
        }

        // Sort
        flights =
            when (sortBy) {
                "price" -> flights.sortedBy { it.priceAmount }
                "date" -> flights.sortedByDescending { it.capturedAt }
                "route" -> flights.sortedBy { "${it.origin}-${it.destination}" }
                else -> flights.sortedBy { it.priceAmount }
            }

        // Limit
        if (limit != null) {
            flights = flights.take(limit!!)
        }

        if (jsonOutput) {
            println(Output.toJson(flights))
        } else {
            println(Output.formatFlightTable(flights))
        }

        return 0
    }
}

/**
 * Show flight details.
 */
@Command(
    name = "show",
    description = ["Show flight details"],
)
class FlightShowCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Flight ID"])
    lateinit var id: String

    @Option(names = ["--json"], description = ["Output as JSON"])
    var jsonOutput: Boolean = false

    @Option(names = ["--history"], description = ["Show price history"])
    var showHistory: Boolean = false

    override fun call(): Int {
        parent.parent.ensureDb()

        val flight = FlightQueries.getById(id)
        if (flight == null) {
            Output.error("Flight '$id' not found")
            return 1
        }

        if (jsonOutput) {
            println(Output.toJson(flight))
        } else {
            // Parse segments for display
            val outbound: FlightSegment? =
                try {
                    Json.decodeFromString(flight.outboundJson)
                } catch (_: Exception) {
                    null
                }

            val returnSeg: FlightSegment? =
                flight.returnJson?.let {
                    try {
                        Json.decodeFromString(it)
                    } catch (_: Exception) {
                        null
                    }
                }

            println(Output.formatFlight(flight, outbound, returnSeg))

            if (showHistory) {
                println("\nPrice History:")
                val history = PriceHistoryQueries.getByFlightId(id)
                println(Output.formatPriceHistory(history))
            }
        }

        return 0
    }
}

/**
 * Delete a flight.
 */
@Command(
    name = "delete",
    description = ["Delete a flight"],
)
class FlightDeleteCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Flight ID"])
    lateinit var id: String

    override fun call(): Int {
        parent.parent.ensureDb()

        val flight = FlightQueries.getById(id)
        if (flight == null) {
            Output.error("Flight '$id' not found")
            return 1
        }

        if (FlightQueries.delete(id)) {
            Output.success("Deleted flight: $id")
            return 0
        } else {
            Output.error("Failed to delete flight")
            return 1
        }
    }
}

/**
 * Tag a flight.
 */
@Command(
    name = "tag",
    description = ["Add a tag to a flight"],
)
class FlightTagCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Flight ID"])
    lateinit var id: String

    @Parameters(index = "1", description = ["Tag to add"])
    lateinit var tag: String

    override fun call(): Int {
        parent.parent.ensureDb()

        val flight = FlightQueries.getById(id)
        if (flight == null) {
            Output.error("Flight '$id' not found")
            return 1
        }

        val existingTags = flight.tags?.let { Output.parseTags(it) } ?: emptyList()

        if (tag in existingTags) {
            Output.warn("Flight already has tag '$tag'")
            return 0
        }

        val newTags = existingTags + tag
        val updatedFlight = flight.copy(tags = Output.encodeTags(newTags))

        if (FlightQueries.update(updatedFlight) != null) {
            Output.success("Added tag '$tag' to flight $id")
            return 0
        } else {
            Output.error("Failed to update flight")
            return 1
        }
    }
}

/**
 * Remove a tag from a flight.
 */
@Command(
    name = "untag",
    description = ["Remove a tag from a flight"],
)
class FlightUntagCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Flight ID"])
    lateinit var id: String

    @Parameters(index = "1", description = ["Tag to remove"])
    lateinit var tag: String

    override fun call(): Int {
        parent.parent.ensureDb()

        val flight = FlightQueries.getById(id)
        if (flight == null) {
            Output.error("Flight '$id' not found")
            return 1
        }

        val existingTags = flight.tags?.let { Output.parseTags(it) } ?: emptyList()

        if (tag !in existingTags) {
            Output.warn("Flight doesn't have tag '$tag'")
            return 0
        }

        val newTags = existingTags - tag
        val updatedFlight =
            flight.copy(
                tags = if (newTags.isEmpty()) null else Output.encodeTags(newTags),
            )

        if (FlightQueries.update(updatedFlight) != null) {
            Output.success("Removed tag '$tag' from flight $id")
            return 0
        } else {
            Output.error("Failed to update flight")
            return 1
        }
    }
}

/**
 * Update flight price.
 */
@Command(
    name = "price",
    description = ["Update flight price"],
)
class FlightPriceCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Flight ID"])
    lateinit var id: String

    @Option(names = ["--amount", "-a"], required = true, description = ["New price amount"])
    var amount: Double = 0.0

    @Option(names = ["--currency", "-c"], description = ["Price currency (defaults to existing)"])
    var currency: String? = null

    override fun call(): Int {
        parent.parent.ensureDb()

        val flight = FlightQueries.getById(id)
        if (flight == null) {
            Output.error("Flight '$id' not found")
            return 1
        }

        val newCurrency = (currency ?: flight.priceCurrency).uppercase()
        val oldPrice = Output.formatPrice(flight.priceAmount, flight.priceCurrency)
        val newPrice = Output.formatPrice(amount, newCurrency)

        // Update flight price
        if (FlightQueries.updatePrice(id, amount, newCurrency) == null) {
            Output.error("Failed to update price")
            return 1
        }

        // Record in price history
        PriceHistoryQueries.create(
            PriceHistoryDto(
                flightId = id,
                amount = amount,
                currency = newCurrency,
                checkedAt = Instant.now().toString(),
            ),
        )

        val change = amount - flight.priceAmount
        val changeStr =
            when {
                change > 0 -> "+${Output.formatPrice(change, newCurrency)}"
                change < 0 -> "-${Output.formatPrice(-change, newCurrency)}"
                else -> "no change"
            }

        Output.success("Updated price: $oldPrice → $newPrice ($changeStr)")
        return 0
    }
}

// ============================================================================
// Root Command
// ============================================================================

/**
 * Root command for clawfare CLI.
 */
@Command(
    name = "clawfare",
    version = ["clawfare 0.1.0"],
    description = ["Flight price investigation tracker"],
    mixinStandardHelpOptions = true,
    subcommands = [
        InvCommand::class,
        FlightCommand::class,
    ],
)
class ClawfareCommand : Callable<Int> {
    @Option(names = ["--db"], description = ["Database path"], defaultValue = "")
    var dbPath: String = ""

    private var dbInitialized = false

    /**
     * Ensure database is connected.
     * Uses existing connection if already initialized.
     */
    fun ensureDb() {
        if (!dbInitialized) {
            // Check if database is already connected (e.g., in tests)
            if (ClawfareDatabase.isInitialized()) {
                dbInitialized = true
                return
            }
            val path = if (dbPath.isBlank()) ClawfareDatabase.defaultPath else dbPath
            ClawfareDatabase.connect(path)
            dbInitialized = true
        }
    }

    override fun call(): Int {
        println("Flight price investigation tracker")
        println("Use 'clawfare --help' for available commands")
        return 0
    }
}
