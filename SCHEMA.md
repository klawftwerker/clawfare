# Flight Search Database Schema

## Core Challenge

A "flight option" is not a simple entity. It's:
- A specific itinerary (possibly multi-segment)
- At a specific price point
- From a specific source
- At a specific moment in time
- In a specific cabin class
- With specific fare rules

And we need to compare:
- Same itinerary across sources
- Round-trips vs two one-ways
- Different dates for flexibility analysis
- Prices over time for tracking

## Entity Model

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│     Search      │────<│  SearchResult   │────<│    Itinerary    │
└─────────────────┘     └─────────────────┘     └─────────────────┘
                                                        │
                                                        │
                                               ┌────────┴────────┐
                                               ▼                 ▼
                                        ┌───────────┐     ┌───────────┐
                                        │  Segment  │     │   Price   │
                                        └───────────┘     └───────────┘
```

## Tables

### investigations
The overarching search task — what we're trying to find.

```sql
CREATE TABLE investigations (
    id              TEXT PRIMARY KEY,  -- User-friendly slug: "tokyo-may-2026"
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,
    
    -- Route
    origin          TEXT NOT NULL,     -- IATA code or city code
    destination     TEXT NOT NULL,
    
    -- Date constraints
    depart_from     TEXT NOT NULL,     -- 2026-05-01
    depart_to       TEXT NOT NULL,     -- 2026-05-31
    return_min_days INTEGER,           -- 14
    return_max_days INTEGER,           -- 21
    
    -- Options
    cabin_class     TEXT NOT NULL,     -- business
    passengers      INTEGER DEFAULT 1,
    
    -- Goal
    target_price    INTEGER,           -- In minor units
    target_currency TEXT,
    
    -- Status
    status          TEXT DEFAULT 'active',  -- active, paused, completed, abandoned
    best_price_id   TEXT REFERENCES prices(id),
    
    -- Metadata
    notes           TEXT
);
```

### Default Sources

Sources are configured per route/cabin class, with sensible defaults.

```kotlin
data class SourceDefaults(
    val aggregators: List<String>,      // Always check these
    val airlinesDirect: List<String>,   // Route-specific airlines
    val regional: List<String>          // Region-specific OTAs
)

// Core aggregators — always included
val AGGREGATORS = listOf("google_flights", "skyscanner")

// Airlines by route (origin region → destination region)
val AIRLINE_DEFAULTS = mapOf(
    "UK-JP" to listOf("jal.com", "ana.com", "ba.com", "finnair.com"),
    "UK-US" to listOf("ba.com", "virgin-atlantic.com", "aa.com", "united.com", "delta.com"),
    "US-EU" to listOf("aa.com", "united.com", "delta.com", "lufthansa.com", "airfrance.com"),
    "UK-ME" to listOf("emirates.com", "qatar.com", "etihad.com", "ba.com"),
    // ... etc
)

// Premium cabin specialists (when class is business/first)
val PREMIUM_SOURCES = listOf("ita_matrix")

// Regional OTAs (can sometimes find different inventory)
val REGIONAL_OTAS = mapOf(
    "JP" to listOf("jtb.co.jp", "his-j.com"),
    "UK" to listOf("expedia.co.uk", "lastminute.com"),
    // ... etc
)

fun getDefaultSources(
    origin: String,
    destination: String,
    cabinClass: String
): List<String> {
    val sources = mutableListOf<String>()
    
    // 1. Always include aggregators
    sources.addAll(AGGREGATORS)
    
    // 2. Add premium sources for business/first
    if (cabinClass in listOf("business", "first")) {
        sources.addAll(PREMIUM_SOURCES)
    }
    
    // 3. Add route-specific airlines
    val routeKey = "${origin.region()}-${destination.region()}"
    AIRLINE_DEFAULTS[routeKey]?.let { sources.addAll(it) }
    
    // 4. Optionally add regional OTAs
    REGIONAL_OTAS[destination.region()]?.let { sources.addAll(it) }
    
    return sources.distinct()
}
```

### CLI Default Source Usage

```bash
# Use defaults — CLI figures out sources based on route
clawfare investigate new \
  --name "tokyo-may-2026" \
  --origin LHR --destination TYO \
  --depart 2026-05-01:2026-05-31 \
  --class business

# Sources auto-populated:
# → google_flights, skyscanner (aggregators)
# → ita_matrix (premium cabin)
# → jal.com, ana.com, ba.com, finnair.com (UK-JP airlines)

# Override: only specific sources
clawfare investigate new \
  --name "quick-check" \
  --origin LHR --destination TYO \
  --sources google_flights,jal.com

# Override: add to defaults
clawfare investigate new \
  --name "thorough-check" \
  --origin LHR --destination TYO \
  --sources +emirates.com,+qatar.com   # '+' means "add to defaults"

# Override: remove from defaults  
clawfare investigate new \
  --name "skip-ita" \
  --origin LHR --destination TYO \
  --sources -ita_matrix               # '-' means "remove from defaults"
```

### investigation_sources
Which sources should be checked for this investigation.

```sql
CREATE TABLE investigation_sources (
    id                  TEXT PRIMARY KEY,
    investigation_id    TEXT NOT NULL REFERENCES investigations(id),
    source              TEXT NOT NULL,     -- google_flights, jal.com, etc.
    
    -- Priority (higher = check first)
    priority            INTEGER DEFAULT 50,
    
    -- Status tracking
    status              TEXT DEFAULT 'pending',  -- pending, in_progress, done, failed, skipped
    last_checked_at     INTEGER,
    next_check_after    INTEGER,           -- For stale detection
    
    -- Results
    prices_found        INTEGER DEFAULT 0,
    
    -- Notes
    notes               TEXT,              -- "CAPTCHA blocked", "No availability", etc.
    failure_reason      TEXT,
    
    UNIQUE(investigation_id, source)
);
```

Default source priorities:

| Source Type | Priority | Rationale |
|-------------|----------|-----------|
| `google_flights` | 100 | Best overview, check first |
| `ita_matrix` | 90 | Best fare details for premium cabins |
| Airline direct | 70 | May have exclusive inventory |
| `skyscanner` | 60 | Good backup aggregator |
| Regional OTAs | 30 | Usually same prices, check last |

### investigation_tasks
Specific tasks the agent should perform.

```sql
CREATE TABLE investigation_tasks (
    id                  TEXT PRIMARY KEY,
    investigation_id    TEXT NOT NULL REFERENCES investigations(id),
    
    -- Task definition
    task_type           TEXT NOT NULL,     -- search_rt, search_ow, refresh, verify
    source              TEXT,              -- Which source to check
    priority            TEXT DEFAULT 'medium',  -- high, medium, low
    
    -- Search parameters (JSON)
    search_params       TEXT,              -- {"origin":"LHR","dates":["2026-05-08"...],...}
    
    -- Hints for the agent
    hints               TEXT,              -- JSON with url, notes, etc.
    
    -- Status
    status              TEXT DEFAULT 'pending',  -- pending, in_progress, done, failed, skipped
    assigned_at         INTEGER,           -- When agent started this task
    completed_at        INTEGER,
    
    -- Results
    prices_added        INTEGER DEFAULT 0,
    result_notes        TEXT,
    skip_reason         TEXT,
    
    created_at          INTEGER NOT NULL,
    
    INDEX(investigation_id, status, priority)
);
```

### searches
What we asked for.

```sql
CREATE TABLE searches (
    id              TEXT PRIMARY KEY,  -- UUID
    created_at      INTEGER NOT NULL,  -- Unix timestamp
    
    -- Route
    origin          TEXT NOT NULL,     -- IATA code or city code
    destination     TEXT NOT NULL,
    
    -- Dates (nullable = flexible/one-way)
    depart_date     TEXT,              -- YYYY-MM-DD or NULL for range
    depart_from     TEXT,              -- Range start
    depart_to       TEXT,              -- Range end
    return_date     TEXT,
    return_from     TEXT,
    return_to       TEXT,
    
    -- Options
    cabin_class     TEXT NOT NULL,     -- economy, premium_economy, business, first
    passengers      INTEGER DEFAULT 1,
    trip_type       TEXT NOT NULL,     -- round_trip, one_way, multi_city
    
    -- Metadata
    label           TEXT,              -- User-friendly name "Tokyo May trip"
    status          TEXT DEFAULT 'pending'
);
```

### itineraries
A unique combination of flights, independent of price/source.

```sql
CREATE TABLE itineraries (
    id              TEXT PRIMARY KEY,  -- Hash of segments
    
    -- Summary (denormalized for fast queries)
    origin          TEXT NOT NULL,
    destination     TEXT NOT NULL,
    outbound_date   TEXT NOT NULL,     -- YYYY-MM-DD
    return_date     TEXT,              -- NULL for one-way
    
    -- Characteristics
    trip_type       TEXT NOT NULL,     -- round_trip, one_way
    total_duration  INTEGER,           -- Minutes
    total_stops     INTEGER,
    
    -- Carriers involved
    marketing_carriers  TEXT,          -- JSON array ["JL", "BA"]
    operating_carriers  TEXT,
    
    -- For deduplication
    segment_hash    TEXT NOT NULL UNIQUE
);
```

### segments
Individual flight legs.

```sql
CREATE TABLE segments (
    id              TEXT PRIMARY KEY,
    itinerary_id    TEXT NOT NULL REFERENCES itineraries(id),
    
    -- Position
    direction       TEXT NOT NULL,     -- outbound, return
    leg_index       INTEGER NOT NULL,  -- 0, 1, 2... within direction
    
    -- Flight details
    flight_number   TEXT,              -- JL041
    marketing_carrier TEXT NOT NULL,   -- JL
    operating_carrier TEXT,            -- JL (or codeshare)
    
    -- Departure
    depart_airport  TEXT NOT NULL,     -- LHR
    depart_time     TEXT NOT NULL,     -- ISO8601
    depart_terminal TEXT,
    
    -- Arrival
    arrive_airport  TEXT NOT NULL,     -- HND
    arrive_time     TEXT NOT NULL,
    arrive_terminal TEXT,
    
    -- Duration
    duration_minutes INTEGER,
    
    -- Equipment
    aircraft        TEXT,              -- 787-9
    
    UNIQUE(itinerary_id, direction, leg_index)
);
```

### prices
A price observation for an itinerary from a source at a point in time.

```sql
CREATE TABLE prices (
    id              TEXT PRIMARY KEY,
    itinerary_id    TEXT NOT NULL REFERENCES itineraries(id),
    search_id       TEXT REFERENCES searches(id),
    
    -- Price details
    amount          INTEGER NOT NULL,  -- In minor units (pence/cents)
    currency        TEXT NOT NULL,     -- GBP, USD, EUR
    cabin_class     TEXT NOT NULL,
    fare_class      TEXT,              -- Booking class: J, C, D, etc.
    
    -- Source
    source          TEXT NOT NULL,     -- google_flights, ita_matrix, jal.com
    source_url      TEXT,              -- Deep link (REQUIRED for some sources)
    source_url_required INTEGER DEFAULT 0,  -- Denormalized from source config
    
    -- Timing
    observed_at     INTEGER NOT NULL,  -- When we saw this price
    
    -- Fare details (when available)
    fare_basis      TEXT,              -- JOWGB, etc.
    refundable      INTEGER,           -- 0/1/NULL
    changeable      INTEGER,
    baggage_included INTEGER,
    
    -- For tracking
    INDEX(itinerary_id, observed_at)
);
```

### price_alerts
Track price thresholds for notifications.

```sql
CREATE TABLE price_alerts (
    id              TEXT PRIMARY KEY,
    search_id       TEXT NOT NULL REFERENCES searches(id),
    
    target_price    INTEGER NOT NULL,  -- Alert when below this
    currency        TEXT NOT NULL,
    
    status          TEXT DEFAULT 'active',  -- active, triggered, expired
    triggered_at    INTEGER,
    triggered_price_id TEXT REFERENCES prices(id),
    
    -- Notification
    notify_channel  TEXT,              -- telegram, email
    notify_target   TEXT               -- Chat ID, email address
);
```

## Two One-Way Comparisons

This is the tricky part. A "trip" might be:
1. A single round-trip itinerary
2. Two separate one-way itineraries combined

### trip_options
Represents a complete trip solution (may combine itineraries).

```sql
CREATE TABLE trip_options (
    id              TEXT PRIMARY KEY,
    search_id       TEXT NOT NULL REFERENCES searches(id),
    
    -- Type
    strategy        TEXT NOT NULL,     -- round_trip, two_one_ways, mixed_cabin
    
    -- Component itineraries
    outbound_itinerary_id   TEXT REFERENCES itineraries(id),
    return_itinerary_id     TEXT REFERENCES itineraries(id),  -- Same as outbound for RT
    
    -- Best known price (denormalized)
    best_price      INTEGER,
    best_currency   TEXT,
    best_source     TEXT,
    best_observed   INTEGER,
    
    -- Comparison
    vs_round_trip_savings   INTEGER,   -- Positive = cheaper than RT
    
    UNIQUE(search_id, outbound_itinerary_id, return_itinerary_id)
);
```

### trip_option_prices
Links trip options to their component prices.

```sql
CREATE TABLE trip_option_prices (
    id              TEXT PRIMARY KEY,
    trip_option_id  TEXT NOT NULL REFERENCES trip_options(id),
    
    -- Component prices
    outbound_price_id   TEXT NOT NULL REFERENCES prices(id),
    return_price_id     TEXT REFERENCES prices(id),  -- NULL if same as outbound (RT)
    
    -- Combined price
    total_amount    INTEGER NOT NULL,
    currency        TEXT NOT NULL,
    
    observed_at     INTEGER NOT NULL
);
```

## Queries

### Best price for a route (any date in range)
```sql
SELECT 
    i.outbound_date,
    i.return_date,
    MIN(p.amount) as best_price,
    p.source
FROM itineraries i
JOIN prices p ON p.itinerary_id = i.id
WHERE i.origin = 'LHR' 
  AND i.destination = 'TYO'
  AND i.outbound_date BETWEEN '2026-05-01' AND '2026-05-31'
  AND p.cabin_class = 'business'
GROUP BY i.outbound_date, i.return_date
ORDER BY best_price;
```

### Compare round-trip vs two one-ways
```sql
SELECT 
    rt.outbound_date,
    rt.return_date,
    rt_price.amount as round_trip_price,
    (ob_price.amount + ret_price.amount) as two_ow_price,
    rt_price.amount - (ob_price.amount + ret_price.amount) as savings
FROM itineraries rt
JOIN prices rt_price ON rt_price.itinerary_id = rt.id
-- Find matching one-ways
JOIN itineraries ob ON ob.origin = rt.origin 
    AND ob.destination = rt.destination 
    AND ob.outbound_date = rt.outbound_date
    AND ob.trip_type = 'one_way'
JOIN prices ob_price ON ob_price.itinerary_id = ob.id
JOIN itineraries ret ON ret.origin = rt.destination
    AND ret.destination = rt.origin
    AND ret.outbound_date = rt.return_date
    AND ret.trip_type = 'one_way'
JOIN prices ret_price ON ret_price.itinerary_id = ret.id
WHERE rt.trip_type = 'round_trip'
  AND rt_price.cabin_class = 'business'
  AND ob_price.cabin_class = 'business'
  AND ret_price.cabin_class = 'business';
```

### Price history for a route
```sql
SELECT 
    date(p.observed_at, 'unixepoch') as date,
    MIN(p.amount) as min_price,
    AVG(p.amount) as avg_price,
    MAX(p.amount) as max_price
FROM prices p
JOIN itineraries i ON i.id = p.itinerary_id
WHERE i.origin = 'LHR'
  AND i.destination = 'TYO'
  AND i.outbound_date BETWEEN '2026-05-01' AND '2026-05-31'
  AND p.cabin_class = 'business'
GROUP BY date(p.observed_at, 'unixepoch')
ORDER BY date;
```

## Indexes

```sql
-- Fast route lookups
CREATE INDEX idx_itineraries_route ON itineraries(origin, destination, outbound_date);

-- Price queries
CREATE INDEX idx_prices_itinerary ON prices(itinerary_id, cabin_class, observed_at);
CREATE INDEX idx_prices_observed ON prices(observed_at);

-- Search results
CREATE INDEX idx_trip_options_search ON trip_options(search_id, best_price);
```

## Deduplication Strategy

### Itinerary identity
Two itineraries are the same if they have the same:
- Segments (clawfare in same order)
- Dates and times

We hash the segment details to create `segment_hash`:
```kotlin
fun Itinerary.computeHash(): String {
    val segments = segments.sortedBy { "${it.direction}-${it.legIndex}" }
    val data = segments.joinToString("|") { s ->
        "${s.flightNumber}:${s.departTime}:${s.arriveTime}"
    }
    return sha256(data)
}
```

### Price deduplication
Same itinerary + same source + same price within time window = don't insert duplicate.

## Task Generation Logic

The CLI automatically generates and prioritizes tasks based on investigation state.

```kotlin
fun generateTasks(investigation: Investigation): List<Task> {
    val tasks = mutableListOf<Task>()
    
    // 1. Pending sources (never checked)
    for (source in investigation.sources.filter { it.status == "pending" }) {
        tasks.add(Task(
            type = "search_rt",
            source = source.id,
            priority = "high",
            reason = "Source not yet checked"
        ))
    }
    
    // 2. Failed sources (should retry)
    for (source in investigation.sources.filter { it.status == "failed" }) {
        tasks.add(Task(
            type = "retry",
            source = source.id,
            priority = "medium",
            reason = "Previous attempt failed: ${source.failureReason}"
        ))
    }
    
    // 3. Stale sources (checked but old)
    val staleThreshold = System.currentTimeMillis() - 24.hours
    for (source in investigation.sources.filter { 
        it.status == "done" && it.lastCheckedAt < staleThreshold 
    }) {
        tasks.add(Task(
            type = "refresh",
            source = source.id,
            priority = "low",
            reason = "Data is ${source.age} old"
        ))
    }
    
    // 4. Missing one-way data (for 2×OW comparison)
    val rtPrices = prices.filter { it.tripType == "round_trip" }
    val owOutbound = prices.filter { it.tripType == "one_way" && it.direction == "outbound" }
    val owReturn = prices.filter { it.tripType == "one_way" && it.direction == "return" }
    
    val datesNeedingOutboundOW = rtPrices.map { it.departDate } - owOutbound.map { it.date }
    val datesNeedingReturnOW = rtPrices.map { it.returnDate } - owReturn.map { it.date }
    
    if (datesNeedingOutboundOW.isNotEmpty()) {
        tasks.add(Task(
            type = "search_ow",
            priority = "high",
            reason = "Need one-way prices to compare with RT",
            searchParams = mapOf(
                "direction" to "outbound",
                "dates" to datesNeedingOutboundOW
            )
        ))
    }
    
    // 5. Date gaps (dates in range with no prices)
    val allDates = investigation.dateRange.toList()
    val coveredDates = prices.map { it.departDate }.toSet()
    val missingDates = allDates - coveredDates
    
    if (missingDates.isNotEmpty()) {
        tasks.add(Task(
            type = "search_rt",
            source = "google_flights",  // Best for date exploration
            priority = "medium",
            reason = "No data for ${missingDates.size} dates in range",
            searchParams = mapOf("dates" to missingDates)
        ))
    }
    
    // 6. Price verification (only one source for best price)
    val bestPrice = prices.minByOrNull { it.amount }
    if (bestPrice != null) {
        val sourcesWithThisItinerary = prices
            .filter { it.itineraryId == bestPrice.itineraryId }
            .map { it.source }
            .toSet()
        
        val uncheckedSources = investigation.sources
            .map { it.id }
            .filter { it !in sourcesWithThisItinerary }
        
        if (uncheckedSources.isNotEmpty()) {
            tasks.add(Task(
                type = "verify",
                priority = "low",
                reason = "Best price only confirmed on ${bestPrice.source}",
                searchParams = mapOf(
                    "itinerary" to bestPrice.itineraryId,
                    "sources" to uncheckedSources
                )
            ))
        }
    }
    
    return tasks.sortedByDescending { it.priority.weight }
}
```

### Task Priority Weights

| Priority | Weight | Triggers |
|----------|--------|----------|
| `high` | 100 | Unchecked sources, missing OW data |
| `medium` | 50 | Failed retries, date gaps |
| `low` | 10 | Stale refresh, price verification |

### Investigation Completion

An investigation is "complete enough" when:
1. All sources checked at least once (or skipped with reason)
2. One-way data exists for best RT dates
3. Best price is verified on 2+ sources
4. No high-priority tasks remain

```kotlin
fun Investigation.completionScore(): Int {
    val sourcesCovered = sources.count { it.status in listOf("done", "skipped") }
    val sourcesTotal = sources.size
    val hasOwComparison = /* check OW data exists */
    val bestPriceVerified = /* check multiple sources */
    
    return (sourcesCovered * 100 / sourcesTotal) 
        .coerceAtMost(if (hasOwComparison) 100 else 80)
        .coerceAtMost(if (bestPriceVerified) 100 else 90)
}
```

## Source Configuration

Sources have different requirements for what metadata must be captured.

### sources
```sql
CREATE TABLE sources (
    id              TEXT PRIMARY KEY,  -- google_flights, ita_matrix, jal.com
    name            TEXT NOT NULL,     -- Display name
    
    -- Requirements
    url_required    INTEGER DEFAULT 0, -- 1 = must provide source_url
    url_pattern     TEXT,              -- Regex to validate URL format
    
    -- Capabilities
    supports_one_way    INTEGER DEFAULT 1,
    supports_round_trip INTEGER DEFAULT 1,
    supports_multi_city INTEGER DEFAULT 0,
    
    -- Automation
    can_automate    INTEGER DEFAULT 0, -- 1 = we have a scraper
    scraper_class   TEXT               -- Kotlin class name for adapter
);
```

### Source URL Requirements

| Source | URL Required | URL Pattern | Notes |
|--------|--------------|-------------|-------|
| `google_flights` | **Yes** | `clawfare.google.com/...` | Share button URL, contains encoded itinerary |
| `ita_matrix` | **Yes** | `matrix.itasoftware.com/...` | Shareable link from results |
| `skyscanner` | **Yes** | `skyscanner.net/...` | Deep link to specific result |
| `jal.com` | No | — | Prices change, URL may not persist |
| `ana.com` | No | — | Same as above |
| `manual` | No | — | User-entered observation |

### CLI Validation

The CLI user may be a human OR an AI agent. Error messages must:
1. Explain what's wrong
2. Tell exactly how to fix it
3. Provide the corrected command when possible

```kotlin
data class SourceConfig(
    val id: String,
    val urlRequired: Boolean,
    val urlPattern: Regex?,
    val urlHint: String,           // How to get the URL
    val urlExample: String,        // Example URL format
    val alternativeSources: List<String>  // Fallbacks if this source is hard
)

val SOURCES = mapOf(
    "google_flights" to SourceConfig(
        id = "google_flights",
        urlRequired = true,
        urlPattern = Regex("""https://(www\.)?google\.com/travel/flights.*"""),
        urlHint = "In Google Flights: select flight → click 'Share' button → copy link",
        urlExample = "https://www.google.com/travel/flights/booking?tfs=CBwQ...",
        alternativeSources = listOf("skyscanner", "manual")
    ),
    "ita_matrix" to SourceConfig(
        id = "ita_matrix",
        urlRequired = true,
        urlPattern = Regex("""https://matrix\.itasoftware\.com/.*"""),
        urlHint = "In ITA Matrix: after search completes → click 'Share' in top right",
        urlExample = "https://matrix.itasoftware.com/search?...",
        alternativeSources = listOf("google_flights", "manual")
    ),
    "manual" to SourceConfig(
        id = "manual",
        urlRequired = false,
        urlPattern = null,
        urlHint = "No URL needed for manual entries",
        urlExample = "",
        alternativeSources = emptyList()
    )
)

fun validatePriceEntry(source: String, url: String?): Result<Unit, ValidationError> {
    val config = SOURCES[source] ?: return Err(ValidationError(
        code = "UNKNOWN_SOURCE",
        message = "Unknown source: $source",
        hint = "Valid sources: ${SOURCES.keys.joinToString(", ")}",
        suggestion = "Re-run with --source=${SOURCES.keys.first()}"
    ))
    
    if (config.urlRequired && url.isNullOrBlank()) {
        return Err(ValidationError(
            code = "URL_REQUIRED",
            message = "Source '$source' requires --url parameter",
            hint = config.urlHint,
            example = config.urlExample,
            suggestion = if (config.alternativeSources.isNotEmpty()) 
                "Alternative: use --source=${config.alternativeSources.first()} which doesn't require URL"
                else null
        ))
    }
    
    if (url != null && config.urlPattern != null && !config.urlPattern.matches(url)) {
        return Err(ValidationError(
            code = "URL_FORMAT_INVALID", 
            message = "URL doesn't match expected format for $source",
            hint = "Expected pattern: ${config.urlPattern.pattern}",
            example = config.urlExample,
            suggestion = "Check you copied the full URL including https://"
        ))
    }
    
    return Ok(Unit)
}

data class ValidationError(
    val code: String,
    val message: String,
    val hint: String,
    val example: String? = null,
    val suggestion: String? = null
)

// CLI output formatting
fun ValidationError.toCliOutput(): String = buildString {
    appendLine("Error [$code]: $message")
    appendLine()
    appendLine("How to fix:")
    appendLine("  $hint")
    if (example != null) {
        appendLine()
        appendLine("Example:")
        appendLine("  $example")
    }
    if (suggestion != null) {
        appendLine()
        appendLine("Alternative:")
        appendLine("  $suggestion")
    }
}
```

### Example CLI Output (Agent-Friendly)

```bash
$ clawfare add-price --source google_clawfare --price 4376 --route LHR-TYO ...

Error [URL_REQUIRED]: Source 'google_flights' requires --url parameter

How to fix:
  In Google Flights: select flight → click 'Share' button → copy link

Example:
  https://www.google.com/travel/flights/booking?tfs=CBwQ...

Alternative:
  use --source=manual which doesn't require URL
```

```bash
$ clawfare add-price --source google_clawfare --url "flights.google.com/..." ...

Error [URL_FORMAT_INVALID]: URL doesn't match expected format for google_clawfare

How to fix:
  Expected pattern: https://(www\.)?google\.com/travel/flights.*

Example:
  https://www.google.com/travel/flights/booking?tfs=CBwQ...

Alternative:
  Check you copied the full URL including https://
```

### JSON Output Mode (for programmatic use)

```bash
$ clawfare add-price --source google_clawfare --price 4376 --output json

{
  "success": false,
  "error": {
    "code": "URL_REQUIRED",
    "message": "Source 'google_flights' requires --url parameter",
    "hint": "In Google Flights: select flight → click 'Share' button → copy link",
    "example": "https://www.google.com/travel/flights/booking?tfs=CBwQ...",
    "suggestion": "use --source=manual which doesn't require URL",
    "retry_command": "clawfare add-price --source google_clawfare --price 4376 --route LHR-TYO --url <INSERT_URL_HERE>"
  }
}
```

The `retry_command` field gives agents an almost-complete command to retry with.

### Why URLs Matter

1. **Verification** — Can re-check if price is still valid
2. **Booking** — Quick access to complete purchase
3. **Deduplication** — Same URL = same observation
4. **Debugging** — Trace back issues to source

## Open Questions

1. **Currency normalization** — Store in original currency or convert to base?
   - Probably store original + conversion rate at observation time

2. **Segment identity** — Same flight can have different times seasonally
   - Key on flight number + date, update times if they drift

3. **Codeshares** — BA4600 = JL041, should they be same itinerary?
   - Probably yes, use operating carrier as canonical

4. **Mixed cabin** — Business out, Premium Economy return
   - Model as separate cabin_class per segment, not per itinerary
