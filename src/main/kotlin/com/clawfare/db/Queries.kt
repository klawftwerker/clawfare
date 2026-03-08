package com.clawfare.db

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

// ============================================================================
// Data Transfer Objects
// ============================================================================

/**
 * Investigation data class for transfer between layers.
 */
data class InvestigationDto(
    val slug: String,
    val origin: String,
    val destination: String,
    val departStart: String,
    val departEnd: String,
    val returnStart: String? = null,
    val returnEnd: String? = null,
    val cabinClass: String = "economy",
    val maxStops: Int = 1,
    // Config fields
    val maxPrice: Double? = null,
    val departAfter: String? = null,
    val departBefore: String? = null,
    val createdAt: String = Instant.now().toString(),
    val updatedAt: String = Instant.now().toString(),
)

/**
 * Flight data class for transfer between layers.
 */
data class FlightDto(
    val id: String,
    val investigationSlug: String,
    val shareLink: String,
    val source: String,
    val tripType: String,
    val ticketStructure: String,
    val priceAmount: Double,
    val priceCurrency: String,
    val priceMarket: String,
    val origin: String,
    val destination: String,
    val outboundJson: String,
    val returnJson: String? = null,
    val bookingClass: String? = null,
    val cabinMixed: Boolean = false,
    val notes: String? = null,
    val tags: String? = null,
    val capturedAt: String = Instant.now().toString(),
    val priceCheckedAt: String = Instant.now().toString(),
)

/**
 * Price history entry data class.
 */
data class PriceHistoryDto(
    val id: Int? = null,
    val flightId: String,
    val amount: Double,
    val currency: String,
    val checkedAt: String = Instant.now().toString(),
)

// ============================================================================
// Investigation Queries
// ============================================================================

/**
 * Data access layer for investigations.
 */
object InvestigationQueries {
    /**
     * Create a new investigation.
     *
     * @return The created investigation
     * @throws IllegalStateException if slug already exists
     */
    fun create(dto: InvestigationDto): InvestigationDto =
        transaction {
            Investigations.insert {
                it[slug] = dto.slug
                it[origin] = dto.origin
                it[destination] = dto.destination
                it[departStart] = dto.departStart
                it[departEnd] = dto.departEnd
                it[returnStart] = dto.returnStart
                it[returnEnd] = dto.returnEnd
                it[cabinClass] = dto.cabinClass
                it[maxStops] = dto.maxStops
                it[maxPrice] = dto.maxPrice
                it[departAfter] = dto.departAfter
                it[departBefore] = dto.departBefore
                it[createdAt] = dto.createdAt
                it[updatedAt] = dto.updatedAt
            }
            dto
        }

    /**
     * Get investigation by slug.
     *
     * @return Investigation or null if not found
     */
    fun getBySlug(slug: String): InvestigationDto? =
        transaction {
            Investigations
                .selectAll()
                .where { Investigations.slug eq slug }
                .map { it.toInvestigationDto() }
                .singleOrNull()
        }

    /**
     * List all investigations.
     */
    fun listAll(): List<InvestigationDto> =
        transaction {
            Investigations
                .selectAll()
                .map { it.toInvestigationDto() }
        }

    /**
     * Update an existing investigation.
     *
     * @return Updated investigation or null if not found
     */
    fun update(dto: InvestigationDto): InvestigationDto? =
        transaction {
            val updated =
                Investigations.update({ Investigations.slug eq dto.slug }) {
                    it[origin] = dto.origin
                    it[destination] = dto.destination
                    it[departStart] = dto.departStart
                    it[departEnd] = dto.departEnd
                    it[returnStart] = dto.returnStart
                    it[returnEnd] = dto.returnEnd
                    it[cabinClass] = dto.cabinClass
                    it[maxStops] = dto.maxStops
                    it[maxPrice] = dto.maxPrice
                    it[departAfter] = dto.departAfter
                    it[departBefore] = dto.departBefore
                    it[updatedAt] = Instant.now().toString()
                }
            if (updated > 0) getBySlug(dto.slug) else null
        }

    /**
     * Update investigation config fields only.
     */
    fun updateConfig(
        slug: String,
        maxPrice: Double? = null,
        departAfter: String? = null,
        departBefore: String? = null,
    ): Boolean =
        transaction {
            val updates =
                Investigations.update({ Investigations.slug eq slug }) {
                    maxPrice?.let { value -> it[Investigations.maxPrice] = value }
                    departAfter?.let { value -> it[Investigations.departAfter] = value }
                    departBefore?.let { value -> it[Investigations.departBefore] = value }
                    it[updatedAt] = Instant.now().toString()
                }
            updates > 0
        }

    /**
     * Delete investigation by slug.
     * Note: This will fail if flights reference this investigation.
     *
     * @return true if deleted, false if not found
     */
    fun delete(slug: String): Boolean =
        transaction {
            Investigations.deleteWhere { Investigations.slug eq slug } > 0
        }

    private fun ResultRow.toInvestigationDto(): InvestigationDto =
        InvestigationDto(
            slug = this[Investigations.slug],
            origin = this[Investigations.origin],
            destination = this[Investigations.destination],
            departStart = this[Investigations.departStart],
            departEnd = this[Investigations.departEnd],
            returnStart = this[Investigations.returnStart],
            returnEnd = this[Investigations.returnEnd],
            cabinClass = this[Investigations.cabinClass],
            maxStops = this[Investigations.maxStops],
            maxPrice = this[Investigations.maxPrice],
            departAfter = this[Investigations.departAfter],
            departBefore = this[Investigations.departBefore],
            createdAt = this[Investigations.createdAt],
            updatedAt = this[Investigations.updatedAt],
        )
}

// ============================================================================
// Flight Queries
// ============================================================================

/**
 * Data access layer for flights.
 */
object FlightQueries {
    /**
     * Create a new flight.
     *
     * @return The created flight
     */
    fun create(dto: FlightDto): FlightDto =
        transaction {
            Flights.insert {
                it[id] = dto.id
                it[investigationSlug] = dto.investigationSlug
                it[shareLink] = dto.shareLink
                it[flightSource] = dto.source
                it[tripType] = dto.tripType
                it[ticketStructure] = dto.ticketStructure
                it[priceAmount] = dto.priceAmount
                it[priceCurrency] = dto.priceCurrency
                it[priceMarket] = dto.priceMarket
                it[origin] = dto.origin
                it[destination] = dto.destination
                it[outboundJson] = dto.outboundJson
                it[returnJson] = dto.returnJson
                it[bookingClass] = dto.bookingClass
                it[cabinMixed] = if (dto.cabinMixed) 1 else 0
                it[notes] = dto.notes
                it[tags] = dto.tags
                it[capturedAt] = dto.capturedAt
                it[priceCheckedAt] = dto.priceCheckedAt
            }
            dto
        }

    /**
     * Get flight by ID.
     *
     * @return Flight or null if not found
     */
    fun getById(id: String): FlightDto? =
        transaction {
            Flights
                .selectAll()
                .where { Flights.id eq id }
                .map { it.toFlightDto() }
                .singleOrNull()
        }

    /**
     * Get flight by ID prefix.
     * Returns the flight if exactly one matches the prefix.
     *
     * @return Flight or null if not found or ambiguous
     */
    fun getByIdPrefix(prefix: String): FlightDto? =
        transaction {
            val matches =
                Flights
                    .selectAll()
                    .where { Flights.id like "$prefix%" }
                    .map { it.toFlightDto() }

            if (matches.size == 1) matches.first() else null
        }

    /**
     * Get flight by share link.
     *
     * @return Flight or null if not found
     */
    fun getByShareLink(shareLink: String): FlightDto? =
        transaction {
            Flights
                .selectAll()
                .where { Flights.shareLink eq shareLink }
                .map { it.toFlightDto() }
                .singleOrNull()
        }

    /**
     * List all flights for an investigation.
     */
    fun listByInvestigation(investigationSlug: String): List<FlightDto> =
        transaction {
            Flights
                .selectAll()
                .where { Flights.investigationSlug eq investigationSlug }
                .map { it.toFlightDto() }
        }

    /**
     * List all flights.
     */
    fun listAll(): List<FlightDto> =
        transaction {
            Flights
                .selectAll()
                .map { it.toFlightDto() }
        }

    /**
     * Update an existing flight.
     *
     * @return Updated flight or null if not found
     */
    fun update(dto: FlightDto): FlightDto? =
        transaction {
            val updated =
                Flights.update({ Flights.id eq dto.id }) {
                    it[investigationSlug] = dto.investigationSlug
                    it[shareLink] = dto.shareLink
                    it[flightSource] = dto.source
                    it[tripType] = dto.tripType
                    it[ticketStructure] = dto.ticketStructure
                    it[priceAmount] = dto.priceAmount
                    it[priceCurrency] = dto.priceCurrency
                    it[priceMarket] = dto.priceMarket
                    it[origin] = dto.origin
                    it[destination] = dto.destination
                    it[outboundJson] = dto.outboundJson
                    it[returnJson] = dto.returnJson
                    it[bookingClass] = dto.bookingClass
                    it[cabinMixed] = if (dto.cabinMixed) 1 else 0
                    it[notes] = dto.notes
                    it[tags] = dto.tags
                    it[priceCheckedAt] = Instant.now().toString()
                }
            if (updated > 0) getById(dto.id) else null
        }

    /**
     * Update just the price of a flight.
     *
     * @return Updated flight or null if not found
     */
    fun updatePrice(
        id: String,
        amount: Double,
        currency: String,
    ): FlightDto? =
        transaction {
            val updated =
                Flights.update({ Flights.id eq id }) {
                    it[priceAmount] = amount
                    it[priceCurrency] = currency
                    it[priceCheckedAt] = Instant.now().toString()
                }
            if (updated > 0) getById(id) else null
        }

    /**
     * Delete flight by ID.
     * Note: This will also delete associated price history.
     *
     * @return true if deleted, false if not found
     */
    fun delete(id: String): Boolean =
        transaction {
            // Delete price history first
            PriceHistory.deleteWhere { flightId eq id }
            // Then delete flight
            Flights.deleteWhere { Flights.id eq id } > 0
        }

    /**
     * Delete all flights for an investigation.
     *
     * @return Number of flights deleted
     */
    fun deleteByInvestigation(investigationSlug: String): Int =
        transaction {
            // Get all flight IDs first
            val flightIds =
                Flights
                    .selectAll()
                    .where { Flights.investigationSlug eq investigationSlug }
                    .map { it[Flights.id] }

            // Delete price history for each flight
            flightIds.forEach { flightId ->
                PriceHistory.deleteWhere { PriceHistory.flightId eq flightId }
            }

            // Delete flights
            Flights.deleteWhere { Flights.investigationSlug eq investigationSlug }
        }

    private fun ResultRow.toFlightDto(): FlightDto =
        FlightDto(
            id = this[Flights.id],
            investigationSlug = this[Flights.investigationSlug],
            shareLink = this[Flights.shareLink],
            source = this[Flights.flightSource],
            tripType = this[Flights.tripType],
            ticketStructure = this[Flights.ticketStructure],
            priceAmount = this[Flights.priceAmount],
            priceCurrency = this[Flights.priceCurrency],
            priceMarket = this[Flights.priceMarket],
            origin = this[Flights.origin],
            destination = this[Flights.destination],
            outboundJson = this[Flights.outboundJson],
            returnJson = this[Flights.returnJson],
            bookingClass = this[Flights.bookingClass],
            cabinMixed = this[Flights.cabinMixed] == 1,
            notes = this[Flights.notes],
            tags = this[Flights.tags],
            capturedAt = this[Flights.capturedAt],
            priceCheckedAt = this[Flights.priceCheckedAt],
        )
}

// ============================================================================
// Price History Queries
// ============================================================================

/**
 * Data access layer for price history.
 */
object PriceHistoryQueries {
    /**
     * Record a price check.
     *
     * @return The created price history entry
     */
    fun create(dto: PriceHistoryDto): PriceHistoryDto =
        transaction {
            val insertedId =
                PriceHistory.insert {
                    it[flightId] = dto.flightId
                    it[amount] = dto.amount
                    it[currency] = dto.currency
                    it[checkedAt] = dto.checkedAt
                } get PriceHistory.id

            dto.copy(id = insertedId)
        }

    /**
     * Get all price history for a flight, ordered by check time.
     */
    fun getByFlightId(flightId: String): List<PriceHistoryDto> =
        transaction {
            PriceHistory
                .selectAll()
                .where { PriceHistory.flightId eq flightId }
                .orderBy(PriceHistory.checkedAt)
                .map { it.toPriceHistoryDto() }
        }

    /**
     * Get the latest price entry for a flight.
     */
    fun getLatest(flightId: String): PriceHistoryDto? =
        transaction {
            PriceHistory
                .selectAll()
                .where { PriceHistory.flightId eq flightId }
                .orderBy(PriceHistory.checkedAt, org.jetbrains.exposed.sql.SortOrder.DESC)
                .limit(1)
                .map { it.toPriceHistoryDto() }
                .singleOrNull()
        }

    /**
     * Delete all price history for a flight.
     *
     * @return Number of entries deleted
     */
    fun deleteByFlightId(flightId: String): Int =
        transaction {
            PriceHistory.deleteWhere { PriceHistory.flightId eq flightId }
        }

    private fun ResultRow.toPriceHistoryDto(): PriceHistoryDto =
        PriceHistoryDto(
            id = this[PriceHistory.id],
            flightId = this[PriceHistory.flightId],
            amount = this[PriceHistory.amount],
            currency = this[PriceHistory.currency],
            checkedAt = this[PriceHistory.checkedAt],
        )
}
