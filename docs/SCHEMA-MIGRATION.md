# Schema Migration: Prices to price_history

## Goal
Move prices from `flights` table to `price_history` table to support:
1. Multiple price observations per flight
2. Different sources (agents) recording prices
3. Proper sync between distributed databases

## Current Schema

### flights table
```sql
-- Has price columns:
price_amount DOUBLE PRECISION NOT NULL
price_currency TEXT NOT NULL
price_market TEXT NOT NULL
price_checked_at TEXT NOT NULL
```

### price_history table
```sql
id INTEGER PRIMARY KEY AUTOINCREMENT
flight_id TEXT NOT NULL
amount DOUBLE PRECISION NOT NULL
currency TEXT NOT NULL
checked_at TEXT NOT NULL
source TEXT DEFAULT 'kraftwerker'  -- Already added
```

## Target Schema

### flights table
```sql
-- Remove: price_amount, price_currency, price_checked_at
-- Keep: price_market (it's about the ticket, not observation)
-- Keep: stale (for marking flights needing refresh)
```

### price_history table
```sql
id INTEGER PRIMARY KEY AUTOINCREMENT
flight_id TEXT NOT NULL
amount DOUBLE PRECISION NOT NULL
currency TEXT NOT NULL
checked_at TEXT NOT NULL
source TEXT NOT NULL  -- Who recorded this price
price_market TEXT NOT NULL  -- Which market (UK, US, etc.)
```

## Code Changes Required

### 1. Database Layer (db/)
- [x] Tables.kt - Remove price columns from Flights object (already done previously)
- [ ] Queries.kt:
  - FlightDto: Remove priceAmount, priceCurrency, priceCheckedAt
  - Add FlightWithPrice: Wrapper combining FlightDto + latest price
  - FlightQueries: Add listWithPrices(), getWithPrice() 
  - Remove updatePrice(), updatePriceCheckedAt()
  - PriceHistoryDto: Add priceMarket field
  - PriceHistoryQueries: Update create() to include priceMarket

### 2. CLI Commands (cli/Commands.kt)
Most commands use FlightDto and expect price fields. Need to:
- FlightListCommand: Use listWithPrices() instead of listByInvestigation()
- FlightShowCommand: Use getWithPrice() instead of getById()
- FlightPriceCommand: Add to price_history instead of updating flight
- FlightRefreshCommand: Record new prices to price_history
- FlightValidateCommand: Use FlightWithPrice
- FlightDedupeCommand: Use FlightWithPrice for price comparisons
- FlightExportTasksCommand: Use FlightWithPrice
- FlightImportResultsCommand: Create price_history entries
- FlightMatchCommand: Use FlightWithPrice

### 3. Output (cli/Output.kt)
- formatFlight(): Accept FlightWithPrice
- formatFlightTable(): Accept List<FlightWithPrice>

### 4. Export/Import (export/, importer/)
- JsonExporter: Export flights + price_history separately
- JsonImporter: Create price_history entries on import
- TopNGenerator: Use FlightWithPrice

## Migration Steps

1. **Add source column to price_history** (if not exists)
2. **Migrate existing prices**: INSERT INTO price_history from flights
3. **Update Kotlin code** (all files above)
4. **Drop price columns** from flights table
5. **Test thoroughly**

## Sync Protocol

With this schema, sync becomes:
```json
{
  "flights": [/* static flight data */],
  "priceHistory": [
    {"flightId": "abc", "amount": 911, "checkedAt": "...", "source": "agent-a"},
    {"flightId": "abc", "amount": 945, "checkedAt": "...", "source": "agent-b"}
  ]
}
```

Merge rules:
- Flights: upsert by ID (static data)
- Price history: INSERT all, dedupe by (flight_id, checked_at, source)

## Backup
Before migration:
```bash
cp ~/.clawfare/data.db ~/.clawfare/data.db.backup-$(date +%Y%m%d-%H%M%S)
```
