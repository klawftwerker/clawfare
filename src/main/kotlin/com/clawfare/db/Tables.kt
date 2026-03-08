package com.clawfare.db

import org.jetbrains.exposed.sql.Table

/**
 * Exposed table definition for investigations.
 * An investigation tracks a flight search with date ranges and parameters.
 */
object Investigations : Table("investigations") {
    val slug = text("slug")
    val origin = text("origin")
    val destination = text("destination")
    val departStart = text("depart_start")
    val departEnd = text("depart_end")
    val returnStart = text("return_start").nullable()
    val returnEnd = text("return_end").nullable()
    val cabinClass = text("cabin_class").default("economy")
    val maxStops = integer("max_stops").default(1)
    // Config fields
    val maxPrice = double("max_price").nullable()
    val departAfter = text("depart_after").nullable()
    val departBefore = text("depart_before").nullable()
    val createdAt = text("created_at")
    val updatedAt = text("updated_at")

    override val primaryKey = PrimaryKey(slug)
}

/**
 * Exposed table definition for flights.
 * A flight is a specific itinerary with pricing captured during an investigation.
 */
object Flights : Table("flights") {
    val id = text("id")
    val investigationSlug = text("investigation_slug").references(Investigations.slug)
    val shareLink = text("share_link").uniqueIndex()
    val flightSource = text("source")
    val tripType = text("trip_type")
    val ticketStructure = text("ticket_structure")

    val priceAmount = double("price_amount")
    val priceCurrency = text("price_currency")
    val priceMarket = text("price_market")

    val origin = text("origin")
    val destination = text("destination")

    val outboundJson = text("outbound_json")
    val returnJson = text("return_json").nullable()

    val bookingClass = text("booking_class").nullable()
    val cabinMixed = integer("cabin_mixed").default(0)
    val notes = text("notes").nullable()
    val tags = text("tags").nullable()

    val capturedAt = text("captured_at")
    val priceCheckedAt = text("price_checked_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Exposed table definition for price history.
 * Tracks price changes for a flight over time.
 */
object PriceHistory : Table("price_history") {
    val id = integer("id").autoIncrement()
    val flightId = text("flight_id").references(Flights.id)
    val amount = double("amount")
    val currency = text("currency")
    val checkedAt = text("checked_at")

    override val primaryKey = PrimaryKey(id)
}
