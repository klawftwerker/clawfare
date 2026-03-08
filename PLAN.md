# Clawfare Implementation Plan

CLI tool for flight price investigation tracking. Replaces manual JSON/YAML with proper data management.

## Architecture

```
clawfare/
├── src/main/kotlin/com/clawfare/
│   ├── Main.kt                 # CLI entry point (picocli)
│   ├── cli/
│   │   ├── Commands.kt         # Subcommand definitions
│   │   └── Output.kt           # Formatting (table, JSON, summary)
│   ├── db/
│   │   ├── Database.kt         # SQLite connection, migrations
│   │   ├── Tables.kt           # Exposed table definitions
│   │   └── Queries.kt          # Data access layer
│   ├── model/
│   │   ├── Flight.kt           # FlightEntry, FlightSegment, FlightLeg
│   │   ├── Investigation.kt    # Investigation config
│   │   └── Validation.kt       # Schema validation rules
│   ├── import/
│   │   └── JsonImporter.kt     # Import from existing flights.json
│   └── export/
│       └── TopNGenerator.kt    # Generate top-n.md rankings
└── src/test/kotlin/com/clawfare/
    ├── model/
    │   └── ValidationTest.kt
    ├── db/
    │   └── QueriesTest.kt
    └── cli/
        └── CommandsTest.kt
```

## Database Schema (SQLite)

### investigations
```sql
CREATE TABLE investigations (
    slug TEXT PRIMARY KEY,
    origin TEXT NOT NULL,
    destination TEXT NOT NULL,
    depart_start DATE NOT NULL,
    depart_end DATE NOT NULL,
    return_start DATE,
    return_end DATE,
    cabin_class TEXT DEFAULT 'economy',
    max_stops INTEGER DEFAULT 1,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
```

### flights
```sql
CREATE TABLE flights (
    id TEXT PRIMARY KEY,              -- sha256(share_link)[:12]
    investigation_slug TEXT NOT NULL,
    share_link TEXT NOT NULL UNIQUE,
    source TEXT NOT NULL,
    trip_type TEXT NOT NULL,          -- round_trip | one_way
    ticket_structure TEXT NOT NULL,   -- single | two_one_ways
    
    price_amount REAL NOT NULL,
    price_currency TEXT NOT NULL,
    price_market TEXT NOT NULL,
    
    origin TEXT NOT NULL,
    destination TEXT NOT NULL,
    
    outbound_json TEXT NOT NULL,      -- FlightSegment as JSON
    return_json TEXT,                 -- FlightSegment as JSON (null for one_way)
    
    booking_class TEXT,
    cabin_mixed INTEGER DEFAULT 0,
    notes TEXT,
    tags TEXT,                        -- JSON array
    
    captured_at TEXT NOT NULL,
    price_checked_at TEXT NOT NULL,
    
    FOREIGN KEY (investigation_slug) REFERENCES investigations(slug)
);
```

### price_history
```sql
CREATE TABLE price_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    flight_id TEXT NOT NULL,
    amount REAL NOT NULL,
    currency TEXT NOT NULL,
    checked_at TEXT NOT NULL,
    FOREIGN KEY (flight_id) REFERENCES flights(id)
);
```

## CLI Commands

### Investigation Management
```bash
# Create new investigation
clawfare inv new tokyo-may-2026 \
  --origin LHR --dest NRT \
  --depart 2026-05-01:2026-05-15 \
  --return 2026-05-15:2026-05-30 \
  --cabin business

# List investigations
clawfare inv list

# Show investigation details
clawfare inv show tokyo-may-2026

# Delete investigation (and all flights)
clawfare inv delete tokyo-may-2026
```

### Flight Management
```bash
# Add flight (interactive prompts or full args)
clawfare flight add tokyo-may-2026 \
  --link "https://google.com/travel/flights/..." \
  --source google_flights \
  --price 2847 --currency GBP --market UK \
  --outbound '{"depart_airport":"LHR",...}' \
  --return '{"depart_airport":"NRT",...}'

# Add from JSON file (batch import)
clawfare flight import tokyo-may-2026 --file flights.json

# List flights (with filters)
clawfare flight list tokyo-may-2026
clawfare flight list tokyo-may-2026 --sort price --limit 10
clawfare flight list tokyo-may-2026 --tag shortlist
clawfare flight list tokyo-may-2026 --max-price 3000

# Show flight details
clawfare flight show <id>

# Update price
clawfare flight price <id> --amount 2799 --currency GBP

# Tag flights
clawfare flight tag <id> shortlist
clawfare flight untag <id> shortlist

# Delete flight
clawfare flight delete <id>

# Bulk operations
clawfare flight tag-all tokyo-may-2026 --filter "price < 2500" reviewed
```

### Reporting
```bash
# Generate top-n markdown
clawfare report top tokyo-may-2026 --limit 10 > top-10.md

# Price comparison table
clawfare report prices tokyo-may-2026

# Export to JSON (for backup/sharing)
clawfare export tokyo-may-2026 --format json > flights.json
```

## Validation Rules (from schema.md)

### Required Fields
- share_link present and valid URL
- trip_type is "round_trip" or "one_way"  
- price_amount > 0
- price_currency is 3-letter code
- outbound segment fully populated
- outbound.legs has at least 1 leg
- return segment required if trip_type === "round_trip"

### Consistency Checks
- stops === legs.length - 1
- leg times are sequential
- first leg depart matches segment depart
- last leg arrive matches segment arrive
- origin matches outbound.depart_airport
- destination matches outbound.arrive_airport

### Airline Validation (from RULES.md)
- airline_code must be in allowed list
- blocked airlines reject immediately

## Implementation Phases

### Phase 1: Core Data Layer
1. Database setup with Exposed (migrations, connection pooling)
2. Model classes with kotlinx.serialization
3. Validation logic
4. Unit tests for validation

### Phase 2: CLI Framework
1. Picocli setup with subcommands
2. Investigation commands (new, list, show, delete)
3. Basic flight commands (add, list, show, delete)
4. Output formatting

### Phase 3: Import/Export
1. JSON importer (from existing flights.json)
2. JSON exporter
3. Top-N markdown generator

### Phase 4: Advanced Features
1. Price history tracking
2. Bulk operations
3. Filtering and sorting
4. Tags management

## Sub-Agent Task Breakdown

### Task 1: Database Layer
- Tables.kt (Exposed table definitions)
- Database.kt (connection, migrations)
- Queries.kt (CRUD operations)
- Tests

### Task 2: Model & Validation
- Flight.kt, Investigation.kt (data classes)
- Validation.kt (all rules from schema.md)
- Tests with edge cases

### Task 3: CLI Commands
- Main.kt with picocli
- Commands.kt (all subcommands)
- Output.kt (formatting)
- Integration tests

### Task 4: Import/Export
- JsonImporter.kt
- TopNGenerator.kt
- Tests

## Notes

- SQLite file: `~/.clawfare/data.db` (or configurable)
- All timestamps ISO 8601
- IDs generated as sha256(share_link)[:12]
- Segments stored as JSON blobs (complex nested structure)
- Keep CLI fast: lazy DB connection, minimal startup
