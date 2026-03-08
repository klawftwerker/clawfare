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
import picocli.CommandLine
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
    mixinStandardHelpOptions = true,
    subcommands = [
        InvNewCommand::class,
        InvListCommand::class,
        InvShowCommand::class,
        InvDeleteCommand::class,
        InvResumeCommand::class,
        InvConfigCommand::class,
        InvUrlsCommand::class,
    ],
)
class InvCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: ClawfareCommand

    override fun call(): Int {
        CommandLine(this).usage(System.out)
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

/**
 * Resume an investigation - output context for continuing work.
 */
@Command(
    name = "resume",
    description = ["Output investigation context for resuming work"],
)
class InvResumeCommand : Callable<Int> {
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

        val flights = FlightQueries.listByInvestigation(slug)
        val priceHistory = flights.flatMap { f -> PriceHistoryQueries.getByFlightId(f.id) }

        if (jsonOutput) {
            val context =
                mapOf(
                    "investigation" to investigation,
                    "flights" to flights,
                    "price_history" to priceHistory,
                )
            println(Output.toJson(context))
        } else {
            println("=== RESUME: $slug ===")
            println()
            println("--- Investigation ---")
            println(Output.formatInvestigation(investigation, detailed = true))
            println()
            println("--- Flights (${flights.size}) ---")
            if (flights.isEmpty()) {
                println("No flights recorded yet.")
            } else {
                // Group by cabin class
                val byClass = flights.groupBy { it.bookingClass ?: "unknown" }
                byClass.forEach { (cabin, list) ->
                    println("\n$cabin:")
                    list.sortedBy { it.priceAmount }.forEach { f ->
                        val price = Output.formatPrice(f.priceAmount, f.priceCurrency)
                        val route = "${f.origin}→${f.destination}"
                        val stops = Output.parseStops(f.outboundJson)
                        println("  $price | $route | ${stops}stop | ${f.source} | ${f.id}")
                    }
                }

                // Price range
                val minPrice = flights.minOfOrNull { it.priceAmount }
                val maxPrice = flights.maxOfOrNull { it.priceAmount }
                val currency = flights.firstOrNull()?.priceCurrency ?: "GBP"
                println()
                println("Price range: ${Output.formatPrice(minPrice ?: 0.0, currency)} - ${Output.formatPrice(maxPrice ?: 0.0, currency)}")
            }

            // Flights needing refresh (checked > 24h ago)
            val staleFlights =
                flights.filter { f ->
                    val checkedAt = java.time.Instant.parse(f.priceCheckedAt)
                    val dayAgo = java.time.Instant.now().minusSeconds(86400)
                    checkedAt.isBefore(dayAgo)
                }
            if (staleFlights.isNotEmpty()) {
                println()
                println("--- Needs Price Refresh (${staleFlights.size}) ---")
                staleFlights.forEach { f ->
                    println("  ${f.id}: ${f.shareLink}")
                }
            }
        }

        return 0
    }
}

/**
 * Update investigation config.
 */
@Command(
    name = "config",
    description = ["Update investigation configuration"],
)
class InvConfigCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: InvCommand

    @Parameters(index = "0", description = ["Investigation slug"])
    lateinit var slug: String

    @Option(names = ["--max-price"], description = ["Maximum price to track"])
    var maxPrice: Double? = null

    @Option(names = ["--depart-after"], description = ["Minimum departure time (HH:MM)"])
    var departAfter: String? = null

    @Option(names = ["--depart-before"], description = ["Maximum departure time (HH:MM)"])
    var departBefore: String? = null

    @Option(names = ["--min-days"], description = ["Minimum trip duration in days"])
    var minTripDays: Int? = null

    @Option(names = ["--max-days"], description = ["Maximum trip duration in days"])
    var maxTripDays: Int? = null

    @Option(names = ["--must-include"], description = ["Date that must be included (YYYY-MM-DD)"])
    var mustIncludeDate: String? = null

    @Option(names = ["--max-layover"], description = ["Maximum layover in minutes"])
    var maxLayoverMinutes: Int? = null

    @Option(names = ["--show"], description = ["Show current config"])
    var showConfig: Boolean = false

    override fun call(): Int {
        parent.parent.ensureDb()

        val investigation = InvestigationQueries.getBySlug(slug)
        if (investigation == null) {
            Output.error("Investigation '$slug' not found")
            return 1
        }

        if (showConfig) {
            println("Investigation: $slug")
            println("  Max price: ${investigation.maxPrice ?: "not set"}")
            println("  Depart after: ${investigation.departAfter ?: "any"}")
            println("  Depart before: ${investigation.departBefore ?: "any"}")
            println("  Trip days: ${investigation.minTripDays ?: "?"}-${investigation.maxTripDays ?: "?"}")
            println("  Must include: ${investigation.mustIncludeDate ?: "not set"}")
            println("  Max layover: ${investigation.maxLayoverMinutes?.let { "${it}m" } ?: "not set"}")
            return 0
        }

        // Update config
        val updated =
            InvestigationQueries.updateConfig(
                slug,
                maxPrice = maxPrice,
                departAfter = departAfter,
                departBefore = departBefore,
                minTripDays = minTripDays,
                maxTripDays = maxTripDays,
                mustIncludeDate = mustIncludeDate,
                maxLayoverMinutes = maxLayoverMinutes,
            )

        if (updated) {
            Output.success("Updated config for '$slug'")
            return 0
        } else {
            Output.error("No changes made")
            return 1
        }
    }
}

/**
 * Generate Google Flights URLs for an investigation.
 */
@Command(
    name = "urls",
    description = ["Generate Google Flights search URLs for price checking"],
)
class InvUrlsCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: InvCommand

    @Parameters(index = "0", description = ["Investigation slug"])
    lateinit var slug: String

    @Option(names = ["--dates"], description = ["Specific date pairs: YYYY-MM-DD:YYYY-MM-DD,..."])
    var dates: String? = null

    @Option(names = ["--sample"], description = ["Generate N sample date combinations"])
    var sample: Int? = null

    override fun call(): Int {
        parent.parent.ensureDb()

        val inv = InvestigationQueries.getBySlug(slug)
        if (inv == null) {
            Output.error("Investigation '$slug' not found")
            return 1
        }

        val urls = mutableListOf<Pair<String, String>>()

        if (dates != null) {
            // Parse specific dates
            dates!!.split(",").forEach { pair ->
                val parts = pair.split(":")
                if (parts.size == 2) {
                    val url = buildGoogleFlightsUrl(inv, parts[0], parts[1])
                    urls.add(pair to url)
                }
            }
        } else {
            // Generate date combinations from investigation range
            val departStart = java.time.LocalDate.parse(inv.departStart)
            val departEnd = java.time.LocalDate.parse(inv.departEnd)
            val returnStart = inv.returnStart?.let { java.time.LocalDate.parse(it) }
            val returnEnd = inv.returnEnd?.let { java.time.LocalDate.parse(it) }

            // Build all valid date combinations
            val allCombos = mutableListOf<Pair<java.time.LocalDate, java.time.LocalDate?>>()

            var depart = departStart
            while (!depart.isAfter(departEnd)) {
                if (returnStart != null && returnEnd != null) {
                    // Round trip - all valid return dates
                    var ret = maxOf(returnStart, depart.plusDays(inv.minTripDays?.toLong() ?: 1))
                    val maxRet =
                        if (inv.maxTripDays != null) {
                            minOf(returnEnd, depart.plusDays(inv.maxTripDays!!.toLong()))
                        } else {
                            returnEnd
                        }
                    while (!ret.isAfter(maxRet)) {
                        allCombos.add(depart to ret)
                        ret = ret.plusDays(1)
                    }
                } else {
                    // One way
                    allCombos.add(depart to null)
                }
                depart = depart.plusDays(1)
            }

            // Sample or use all
            val selected =
                if (sample != null && sample!! < allCombos.size) {
                    allCombos.shuffled().take(sample!!)
                } else {
                    allCombos
                }

            selected.forEach { (dep, ret) ->
                val dateKey = if (ret != null) "$dep:$ret" else "$dep"
                val url = buildGoogleFlightsUrl(inv, dep.toString(), ret?.toString())
                urls.add(dateKey to url)
            }
        }

        // Output URLs
        if (urls.isEmpty()) {
            Output.warn("No date combinations generated")
            return 0
        }

        println("# Google Flights URLs for $slug (${urls.size} searches)")
        println()
        urls.sortedBy { it.first }.forEach { (dates, url) ->
            println("# $dates")
            println(url)
            println()
        }

        return 0
    }

    private fun buildGoogleFlightsUrl(
        inv: InvestigationDto,
        departDate: String,
        returnDate: String?,
    ): String {
        val cabin =
            when (inv.cabinClass.lowercase()) {
                "economy" -> "economy"
                "premium_economy" -> "premium+economy"
                "business" -> "business+class"
                "first" -> "first+class"
                else -> inv.cabinClass
            }

        return buildString {
            append("https://www.google.com/travel/flights")
            append("?q=Flights+from+${inv.origin}+to+${inv.destination}")
            append("+on+$departDate")
            if (returnDate != null) {
                append("+returning+$returnDate")
            }
            append("+$cabin")
            if (inv.maxStops == 0) {
                append("+nonstop")
            }
            append("&curr=GBP")
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
    mixinStandardHelpOptions = true,
    subcommands = [
        FlightAddCommand::class,
        FlightListCommand::class,
        FlightShowCommand::class,
        FlightDeleteCommand::class,
        FlightUpdateCommand::class,
        FlightTagCommand::class,
        FlightUntagCommand::class,
        FlightPriceCommand::class,
        FlightStaleCommand::class,
        FlightRefreshCommand::class,
        FlightValidateCommand::class,
        FlightImportCommand::class,
        FlightMatchCommand::class,
    ],
)
class FlightCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: ClawfareCommand

    override fun call(): Int {
        CommandLine(this).usage(System.out)
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

    @Option(names = ["--cabin"], description = ["Cabin class (economy, premium_economy, business, first)"])
    var bookingClass: String? = null

    @Option(names = ["--aircraft"], description = ["Aircraft type (e.g., 777-300, A350)"])
    var aircraftType: String? = null

    @Option(names = ["--fare"], description = ["Fare brand (e.g., Business Light, Business Saver)"])
    var fareBrand: String? = null

    @Option(names = ["--disqualified"], description = ["Disqualification reason (e.g., overnight layover)"])
    var disqualified: String? = null

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
                bookingClass = bookingClass,
                aircraftType = aircraftType,
                fareBrand = fareBrand,
                disqualified = disqualified,
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

        // Try exact match first, then prefix match
        val flight = FlightQueries.getById(id) ?: FlightQueries.getByIdPrefix(id)
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
                val history = PriceHistoryQueries.getByFlightId(flight.id)
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

        val flight = FlightQueries.getById(id) ?: FlightQueries.getByIdPrefix(id)
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
 * Update flight fields.
 */
@Command(
    name = "update",
    description = ["Update flight fields (notes, disqualified, cabin, aircraft, fare, outbound, return, link)"],
)
class FlightUpdateCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Flight ID (or prefix)"])
    lateinit var id: String

    @Option(names = ["--notes"], description = ["Update notes (use empty string to clear)"])
    var notes: String? = null

    @Option(names = ["--disqualified"], description = ["Set disqualification reason (use empty string to clear)"])
    var disqualified: String? = null

    @Option(names = ["--cabin"], description = ["Update cabin class (economy, premium_economy, business, first)"])
    var bookingClass: String? = null

    @Option(names = ["--aircraft"], description = ["Update aircraft type"])
    var aircraftType: String? = null

    @Option(names = ["--fare"], description = ["Update fare brand"])
    var fareBrand: String? = null

    @Option(names = ["--outbound"], description = ["Update outbound segment JSON"])
    var outboundJson: String? = null

    @Option(names = ["--return"], description = ["Update return segment JSON"])
    var returnJson: String? = null

    @Option(names = ["--link", "-l"], description = ["Update share link"])
    var shareLink: String? = null

    @Option(names = ["--source", "-s"], description = ["Update data source"])
    var source: String? = null

    @Option(names = ["--type"], description = ["Update trip type (round_trip, one_way)"])
    var tripType: String? = null

    @Option(names = ["--structure"], description = ["Update ticket structure (single, two_one_ways)"])
    var ticketStructure: String? = null

    override fun call(): Int {
        parent.parent.ensureDb()

        val flight = FlightQueries.getById(id) ?: FlightQueries.getByIdPrefix(id)
        if (flight == null) {
            Output.error("Flight '$id' not found")
            return 1
        }

        // Build updated DTO with only changed fields
        val updated = flight.copy(
            notes = notes ?: flight.notes,
            disqualified = if (disqualified == "") null else (disqualified ?: flight.disqualified),
            bookingClass = bookingClass ?: flight.bookingClass,
            aircraftType = aircraftType ?: flight.aircraftType,
            fareBrand = fareBrand ?: flight.fareBrand,
            outboundJson = outboundJson ?: flight.outboundJson,
            returnJson = if (returnJson == "") null else (returnJson ?: flight.returnJson),
            shareLink = shareLink ?: flight.shareLink,
            source = source ?: flight.source,
            tripType = tripType ?: flight.tripType,
            ticketStructure = ticketStructure ?: flight.ticketStructure,
        )

        // Check if anything actually changed
        if (updated == flight) {
            println("No changes specified")
            return 0
        }

        val result = FlightQueries.update(updated)
        if (result != null) {
            val changes = mutableListOf<String>()
            if (notes != null) changes.add("notes")
            if (disqualified != null) changes.add("disqualified")
            if (bookingClass != null) changes.add("cabin")
            if (aircraftType != null) changes.add("aircraft")
            if (fareBrand != null) changes.add("fare")
            if (outboundJson != null) changes.add("outbound")
            if (returnJson != null) changes.add("return")
            if (shareLink != null) changes.add("link")
            if (source != null) changes.add("source")
            if (tripType != null) changes.add("type")
            if (ticketStructure != null) changes.add("structure")

            Output.success("Updated flight ${flight.id}: ${changes.joinToString(", ")}")
            return 0
        } else {
            Output.error("Failed to update flight")
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

        val flight = FlightQueries.getById(id) ?: FlightQueries.getByIdPrefix(id)
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

        val flight = FlightQueries.getById(id) ?: FlightQueries.getByIdPrefix(id)
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

        val flight = FlightQueries.getById(id) ?: FlightQueries.getByIdPrefix(id)
        if (flight == null) {
            Output.error("Flight '$id' not found")
            return 1
        }

        val newCurrency = (currency ?: flight.priceCurrency).uppercase()
        val oldPrice = Output.formatPrice(flight.priceAmount, flight.priceCurrency)
        val newPrice = Output.formatPrice(amount, newCurrency)

        // Update flight price
        if (FlightQueries.updatePrice(flight.id, amount, newCurrency) == null) {
            Output.error("Failed to update price")
            return 1
        }

        // Record in price history
        PriceHistoryQueries.create(
            PriceHistoryDto(
                flightId = flight.id,
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

/**
 * Mark a flight as stale (needs price refresh).
 */
@Command(
    name = "stale",
    description = ["Mark a flight as stale (needs price refresh)"],
)
class FlightStaleCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Flight ID (or prefix)"])
    lateinit var id: String

    @Option(names = ["--clear"], description = ["Clear stale flag instead of setting it"])
    var clear: Boolean = false

    override fun call(): Int {
        parent.parent.ensureDb()

        val flight = FlightQueries.getById(id) ?: FlightQueries.getByIdPrefix(id)
        if (flight == null) {
            Output.error("Flight '$id' not found")
            return 1
        }

        val success = FlightQueries.markStale(flight.id, !clear)
        if (success) {
            if (clear) {
                Output.success("Cleared stale flag on ${flight.id}")
            } else {
                Output.success("Marked ${flight.id} as stale")
            }
            return 0
        } else {
            Output.error("Failed to update flight")
            return 1
        }
    }
}

/**
 * List flights needing price refresh.
 */
@Command(
    name = "refresh",
    description = ["List flights needing price refresh"],
)
class FlightRefreshCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Investigation slug"])
    lateinit var slug: String

    @Option(names = ["--hours"], description = ["Consider stale after N hours"], defaultValue = "24")
    var staleHours: Int = 24

    @Option(names = ["--links"], description = ["Output only share links (for scripting)"])
    var linksOnly: Boolean = false

    override fun call(): Int {
        parent.parent.ensureDb()

        val flights = FlightQueries.listByInvestigation(slug)
        if (flights.isEmpty()) {
            if (!linksOnly) Output.warn("No flights in '$slug'")
            return 0
        }

        // Get flights marked stale OR older than staleHours
        val cutoff = java.time.Instant.now().minusSeconds(staleHours.toLong() * 3600)
        val needsRefresh =
            flights.filter { f ->
                f.stale || java.time.Instant.parse(f.priceCheckedAt).isBefore(cutoff)
            }

        if (linksOnly) {
            needsRefresh.forEach { println(it.shareLink) }
        } else {
            if (needsRefresh.isEmpty()) {
                println("All ${flights.size} flights up to date")
            } else {
                println("Flights needing refresh (${needsRefresh.size}/${flights.size}):")
                println()
                needsRefresh.forEach { f ->
                    val age =
                        java.time.Duration.between(
                            java.time.Instant.parse(f.priceCheckedAt),
                            java.time.Instant.now(),
                        ).toHours()
                    val flag = if (f.stale) " [STALE]" else ""
                    println("${f.id.take(8)} | ${age}h ago$flag | ${f.shareLink}")
                }
            }
        }

        return 0
    }
}

/**
 * Validate flights in an investigation.
 */
@Command(
    name = "validate",
    description = ["Validate flights in an investigation"],
)
class FlightValidateCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Investigation slug"])
    lateinit var slug: String

    @Option(names = ["--fix"], description = ["Attempt to fix issues"])
    var fix: Boolean = false

    override fun call(): Int {
        parent.parent.ensureDb()

        val flights = FlightQueries.listByInvestigation(slug)
        if (flights.isEmpty()) {
            println("No flights to validate")
            return 0
        }

        var errors = 0
        var warnings = 0

        flights.forEach { f ->
            // Check required fields
            if (f.shareLink.isBlank()) {
                Output.error("[${f.id}] Missing share link")
                errors++
            }

            // Check segment JSON is valid
            try {
                Json.decodeFromString<FlightSegment>(f.outboundJson)
            } catch (e: Exception) {
                Output.error("[${f.id}] Invalid outbound JSON: ${e.message}")
                errors++
            }

            if (f.returnJson != null) {
                try {
                    Json.decodeFromString<FlightSegment>(f.returnJson!!)
                } catch (e: Exception) {
                    Output.error("[${f.id}] Invalid return JSON: ${e.message}")
                    errors++
                }
            }

            // Validate against rules
            val outbound = try { Json.decodeFromString<FlightSegment>(f.outboundJson) } catch (e: Exception) { null }
            val returnSeg = f.returnJson?.let { try { Json.decodeFromString<FlightSegment>(it) } catch (e: Exception) { null } }

            if (outbound != null) {
                // Check airline allowlist
                outbound.legs.forEach { leg ->
                    if (!FlightValidator.isAirlineAllowed(leg.airlineCode)) {
                        Output.warn("[${f.id}] Blocked airline: ${leg.airlineCode} ${leg.airline}")
                        warnings++
                    }
                }
            }

            if (returnSeg != null) {
                returnSeg.legs.forEach { leg ->
                    if (!FlightValidator.isAirlineAllowed(leg.airlineCode)) {
                        Output.warn("[${f.id}] Blocked airline: ${leg.airlineCode} ${leg.airline}")
                        warnings++
                    }
                }
            }

            // Check round trip has return
            if (f.tripType == "round_trip" && f.returnJson == null) {
                Output.error("[${f.id}] Round trip missing return segment")
                errors++
            }
        }

        println()
        if (errors == 0 && warnings == 0) {
            Output.success("All ${flights.size} flights valid")
        } else {
            println("Checked ${flights.size} flights: $errors errors, $warnings warnings")
        }

        return if (errors > 0) 1 else 0
    }
}

/**
 * Import/upsert flights from JSON.
 * Reads JSON flight data from stdin and matches against DB to update prices.
 */
@Command(
    name = "import",
    description = ["Import flights from JSON (updates prices for existing, optionally adds new)"],
)
class FlightImportCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Investigation slug"], defaultValue = "")
    var slug: String = ""

    @Option(names = ["--dry-run"], description = ["Show what would be updated without making changes"])
    var dryRun: Boolean = false

    @Option(names = ["--add-new"], description = ["Add new flights not in DB"])
    var addNew: Boolean = false

    @Option(names = ["--schema"], description = ["Print expected JSON schema and exit"])
    var showSchema: Boolean = false

    override fun call(): Int {
        if (showSchema) {
            println(
                """
                |Expected input: JSON array of flight objects
                |
                |[
                |  {
                |    "origin": "LHR",           // required - origin airport code
                |    "destination": "NRT",      // required - destination airport code  
                |    "departDate": "2026-05-10", // required - YYYY-MM-DD
                |    "airline": "Asiana Airlines", // required - airline name for matching
                |    "price": 920.00,           // required - price amount
                |    "currency": "GBP",         // optional - defaults to GBP
                |    "airlineCode": "OZ",       // optional - IATA code
                |    "returnDate": "2026-06-01", // optional - for round trips
                |    "departTime": "16:35",     // optional - HH:MM for stricter matching
                |    "link": "https://..."      // optional - booking link
                |  }
                |]
                |
                |Matching: Finds DB flights by origin + destination + departDate + airline name.
                """.trimMargin(),
            )
            return 0
        }

        if (slug.isBlank()) {
            Output.error("Investigation slug required (or use --schema)")
            return 1
        }

        parent.parent.ensureDb()

        val inv = InvestigationQueries.getBySlug(slug)
        if (inv == null) {
            Output.error("Investigation '$slug' not found")
            return 1
        }

        // Read JSON from stdin
        val input = generateSequence(::readLine).joinToString("\n")
        if (input.isBlank()) {
            Output.error("No input provided. Pipe JSON flight data to stdin.")
            return 1
        }

        // Parse flights
        val importedFlights =
            try {
                Json.decodeFromString<List<ImportFlight>>(input)
            } catch (e: Exception) {
                Output.error("Failed to parse input JSON: ${e.message}")
                return 1
            }

        if (importedFlights.isEmpty()) {
            Output.warn("No flights in input")
            return 0
        }

        println("Processing ${importedFlights.size} flights...")

        val dbFlights = FlightQueries.listByInvestigation(slug)
        var updated = 0
        var unchanged = 0
        var notFound = 0
        var added = 0

        importedFlights.forEach { imported ->
            // Find matching flight in DB
            val match = findMatchingFlight(dbFlights, imported)

            if (match != null) {
                // Check if price changed
                if (match.priceAmount != imported.price || match.priceCurrency != imported.currency) {
                    val oldPrice = Output.formatPrice(match.priceAmount, match.priceCurrency)
                    val newPrice = Output.formatPrice(imported.price, imported.currency)
                    val change = imported.price - match.priceAmount
                    val changeStr =
                        when {
                            change > 0 -> "↑ +${Output.formatPrice(change, imported.currency)}"
                            change < 0 -> "↓ -${Output.formatPrice(-change, imported.currency)}"
                            else -> "="
                        }

                    if (dryRun) {
                        println("[DRY RUN] ${match.id.take(8)}: $oldPrice → $newPrice ($changeStr)")
                    } else {
                        // Update price
                        FlightQueries.updatePrice(match.id, imported.price, imported.currency)
                        // Record history
                        PriceHistoryQueries.create(
                            PriceHistoryDto(
                                flightId = match.id,
                                amount = imported.price,
                                currency = imported.currency,
                                checkedAt = java.time.Instant.now().toString(),
                            ),
                        )
                        // Clear stale flag
                        FlightQueries.markStale(match.id, false)
                        println("${match.id.take(8)}: $oldPrice → $newPrice ($changeStr)")
                    }
                    updated++
                } else {
                    // Same price, just update checked timestamp
                    if (!dryRun) {
                        FlightQueries.updatePriceCheckedAt(match.id)
                        FlightQueries.markStale(match.id, false)
                    }
                    unchanged++
                }
            } else {
                // Flight not in DB
                if (addNew) {
                    if (dryRun) {
                        println("[DRY RUN] Would add: ${imported.airline} ${imported.origin}→${imported.destination} ${Output.formatPrice(imported.price, imported.currency)}")
                    } else {
                        // TODO: Add the flight
                        println("Adding new flights not yet implemented")
                    }
                    added++
                } else {
                    notFound++
                }
            }
        }

        println()
        println("Results: $updated updated, $unchanged unchanged, $notFound not matched" + if (addNew) ", $added added" else "")

        return 0
    }

    /**
     * Find a matching flight in the database.
     * Matches on: origin, destination, departure date, airline
     */
    private fun findMatchingFlight(
        dbFlights: List<FlightDto>,
        imported: ImportFlight,
    ): FlightDto? {
        return dbFlights.find { db ->
            val outbound = Output.parseSegment(db.outboundJson)
            if (outbound == null) return@find false

            val firstLeg = outbound.legs.firstOrNull() ?: return@find false

            // Match on key attributes
            db.origin == imported.origin &&
                db.destination == imported.destination &&
                firstLeg.departTime.startsWith(imported.departDate) &&
                (imported.airlineCode.isNotBlank() && firstLeg.airlineCode == imported.airlineCode ||
                    firstLeg.airline.equals(imported.airline, ignoreCase = true))
        }
    }
}

/**
 * Find flights matching given attributes.
 */
@Command(
    name = "match",
    description = ["Find flights matching given attributes"],
)
class FlightMatchCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Investigation slug"])
    lateinit var slug: String

    @Option(names = ["--origin", "-o"], required = true, description = ["Origin airport"])
    lateinit var origin: String

    @Option(names = ["--dest", "-d"], required = true, description = ["Destination airport"])
    lateinit var dest: String

    @Option(names = ["--depart"], required = true, description = ["Departure date (YYYY-MM-DD)"])
    lateinit var departDate: String

    @Option(names = ["--airline", "-a"], description = ["Airline code or name"])
    var airline: String? = null

    @Option(names = ["--time", "-t"], description = ["Departure time (HH:MM)"])
    var departTime: String? = null

    override fun call(): Int {
        parent.parent.ensureDb()

        val flights = FlightQueries.listByInvestigation(slug)
        if (flights.isEmpty()) {
            Output.error("No flights in '$slug'")
            return 1
        }

        val matches =
            flights.filter { f ->
                val outbound = Output.parseSegment(f.outboundJson) ?: return@filter false
                val firstLeg = outbound.legs.firstOrNull() ?: return@filter false

                // Required matches
                if (f.origin != origin.uppercase()) return@filter false
                if (f.destination != dest.uppercase()) return@filter false
                if (!firstLeg.departTime.startsWith(departDate)) return@filter false

                // Optional filters
                if (airline != null) {
                    val airlineMatch =
                        firstLeg.airlineCode.equals(airline, ignoreCase = true) ||
                            firstLeg.airline.contains(airline!!, ignoreCase = true)
                    if (!airlineMatch) return@filter false
                }

                if (departTime != null) {
                    val timeMatch = firstLeg.departTime.contains("T$departTime")
                    if (!timeMatch) return@filter false
                }

                true
            }

        if (matches.isEmpty()) {
            println("No matches found")
            return 1
        }

        println("Found ${matches.size} match(es):")
        println()
        matches.forEach { f ->
            val outbound = Output.parseSegment(f.outboundJson)!!
            val firstLeg = outbound.legs.first()
            println("${f.id}")
            println("  ${Output.formatPrice(f.priceAmount, f.priceCurrency)} - ${firstLeg.airline}")
            println("  ${f.origin}→${f.destination} on ${firstLeg.departTime.take(16)}")
            println("  Link: ${f.shareLink}")
            println()
        }

        return 0
    }
}

// ============================================================================
// Import Data Structure
// ============================================================================

/**
 * Flight data structure for import/upsert operations.
 */
@kotlinx.serialization.Serializable
data class ImportFlight(
    val origin: String,
    val destination: String,
    val departDate: String,
    val returnDate: String? = null,
    val airline: String,
    val airlineCode: String = "",
    val price: Double,
    val currency: String = "GBP",
    val departTime: String? = null,
    val link: String? = null,
)

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
        // No subcommand provided - show help
        CommandLine(this).usage(System.out)
        return 0
    }
}
