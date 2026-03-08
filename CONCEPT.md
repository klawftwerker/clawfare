# Flight Search CLI - Concept

## Problem

Finding the best business class fare requires:
- Searching multiple sources (Google Flights, ITA Matrix, airline sites, OTAs)
- Comparing round-trips vs two one-ways
- Exploring flexible dates (±days, whole month)
- Exploring flexible trip lengths (10-21 days)
- Considering positioning clawfare (different origin airports)
- Tracking price changes over time

No single tool does this well. Manual process is tedious.

## Solution

A CLI that orchestrates comprehensive searches and presents unified results.

## Design Philosophy

**The CLI is a data layer AND investigation state manager.**

The calling agent (human or AI) performs searches using whatever tools it has:
- Browser automation (Playwright, Puppeteer)
- Direct API calls
- Manual observation

The CLI then:
1. **Tracks** what the agent has been asked to find (investigation scope)
2. **Records** what sources have been checked, when
3. **Ingests** price observations with full provenance
4. **Stores** them in a normalized schema
5. **Compares** options (RT vs 2×OW, across sources, over time)
6. **Suggests** what to do next based on gaps
7. **Alerts** when prices hit targets

This separation means:
- Agent can lose context, restart, or hand off — investigation state persists
- CLI knows exactly what's been checked and what hasn't
- Multiple agents can collaborate on the same investigation

## Example Usage

```bash
# === INVESTIGATION MANAGEMENT ===

# Start a new investigation
clawfare investigate new \
  --name "tokyo-may-2026" \
  --origin LHR --destination TYO \
  --depart 2026-05-01:2026-05-31 \
  --return-window 14-21 \
  --class business \
  --target-price 3000 \
  --sources google_flights,ita_matrix,jal.com,ana.com,finnair.com,ba.com,skyscanner

# Check investigation status
$ clawfare investigate status tokyo-may-2026

Investigation: tokyo-may-2026
Route: LHR → TYO (business class)
Dates: May 2026, 14-21 day trips
Target: £3,000
Created: 2h ago

SOURCE COVERAGE
┌─────────────────┬──────────┬────────────┬─────────────────────────────┐
│ Source          │ Status   │ Last Check │ Notes                       │
├─────────────────┼──────────┼────────────┼─────────────────────────────┤
│ google_clawfare  │ ✓ done   │ 30m ago    │ 12 prices recorded          │
│ ita_matrix      │ ✗ failed │ 45m ago    │ Timeout - retry recommended │
│ jal.com         │ ◷ stale  │ 2d ago     │ Needs refresh               │
│ ana.com         │ ○ pending│ never      │ Not yet checked             │
│ finnair.com     │ ○ pending│ never      │ Not yet checked             │
│ ba.com          │ ○ pending│ never      │ Not yet checked             │
│ skyscanner      │ ✓ done   │ 1h ago     │ 8 prices recorded           │
└─────────────────┴──────────┴────────────┴─────────────────────────────┘

DATE COVERAGE (round-trips)
May 1-7:  ░░░░░░░░░░ 0%   ← no data
May 8-14: ████████░░ 80%  
May 15-21:██████████ 100%
May 22-31:████░░░░░░ 40%

ONE-WAY COVERAGE
Outbound: ████░░░░░░ 40%  (need more for 2×OW comparison)
Return:   ██░░░░░░░░ 20%

BEST PRICE SO FAR: £4,105 (Finnair via HEL) — £1,105 above target

NEXT ACTIONS:
1. [high]   Check ana.com for round-trips
2. [high]   Check finnair.com direct (might beat aggregator price)
3. [high]   Search one-way returns on all sources
4. [medium] Retry ita_matrix (timed out earlier)
5. [medium] Search May 1-7 dates on google_clawfare
6. [low]    Refresh jal.com (data is 2 days old)

# Mark a source as checked (even if no results found)
clawfare investigate check tokyo-may-2026 \
  --source ana.com \
  --status done \
  --notes "No business availability for May dates"

# Mark a source as failed (will suggest retry)
clawfare investigate check tokyo-may-2026 \
  --source ita_matrix \
  --status failed \
  --notes "CAPTCHA blocked, need manual intervention"

# Record a price (automatically updates investigation)
clawfare add \
  --investigation tokyo-may-2026 \
  --source jal.com \
  --price 4500 \
  ...

# Get next task (machine-readable)
$ clawfare investigate next tokyo-may-2026 --output json

{
  "investigation": "tokyo-may-2026",
  "task": {
    "id": "task-001",
    "priority": "high",
    "action": "search",
    "source": "ana.com",
    "search_params": {
      "origin": "LHR",
      "destination": "TYO",
      "depart_range": ["2026-05-08", "2026-05-21"],
      "return_window_days": [14, 21],
      "class": "business",
      "type": "round_trip"
    },
    "hints": {
      "url": "https://www.ana.co.jp/en/gb/",
      "notes": "Select 'Europe' region, business class. May need to search each date individually."
    }
  },
  "remaining_tasks": 5,
  "investigation_progress": "45%"
}

# Mark task complete
clawfare investigate done tokyo-may-2026 --task task-001 --prices-added 3

# Skip a task (with reason)
clawfare investigate skip tokyo-may-2026 --task task-002 \
  --reason "Site requires login, don't have account"
```

# === PRICE RECORDING ===

```bash
# Record a price observation (agent searched Google Flights)
clawfare add \
  --origin LHR --destination TYO \
  --depart 2026-05-14 --return 2026-05-30 \
  --class business \
  --price 4376 --currency GBP \
  --source google_flights \
  --url "https://www.google.com/travel/flights/booking?tfs=..." \
  --carrier JAL --flight JL044 \
  --stops 0

# Record a one-way (agent searched airline direct)
clawfare add \
  --investigation tokyo-may-2026 \
  --depart 2026-05-14 \
  --class business \
  --price 2100 --currency GBP \
  --source jal.com \
  --carrier JAL --flight JL044 \
  --one-way

# Query: what's the best option?
clawfare best tokyo-may-2026
```

## Output

```
╭──────────────────────────────────────────────────────────────────╮
│  London → Tokyo  •  Business Class  •  May 2026                  │
╰──────────────────────────────────────────────────────────────────╯

BEST ROUND-TRIPS
┌─────────────┬───────────┬──────────┬────────┬────────────────────┐
│ Dates       │ Airline   │ Route    │ Price  │ Source             │
├─────────────┼───────────┼──────────┼────────┼────────────────────┤
│ May 8-22    │ Finnair   │ HEL      │ £3,890 │ Google Flights     │
│ May 14-30   │ JAL       │ Direct   │ £4,376 │ JAL.com            │
│ May 10-24   │ Qatar     │ DOH      │ £4,105 │ ITA Matrix         │
└─────────────┴───────────┴──────────┴────────┴────────────────────┘

BEST TWO ONE-WAYS
┌─────────────┬───────────────────────────┬────────┬───────────────┐
│ Dates       │ Outbound / Return         │ Total  │ Savings       │
├─────────────┼───────────────────────────┼────────┼───────────────┤
│ May 14 / 30 │ JAL out + ANA return      │ £3,650 │ £726 vs RT    │
│ May 8 / 25  │ Finnair out + JAL return  │ £3,420 │ £470 vs RT    │
└─────────────┴───────────────────────────┴────────┴───────────────┘

CHEAPEST BY DEPARTURE DATE
May 1  ████████████████████████████████████  £5,200
May 8  ████████████████████████████  £3,890  ← cheapest
May 14 ██████████████████████████████████  £4,376
May 21 █████████████████████████████████████  £5,100

PRICE HISTORY (this route, last 30 days)
£4,500 ┤
£4,000 ┤    ╭─╮     ╭──────
£3,500 ┤───╯  ╰─────╯
       └────────────────────
         Feb 8    Feb 22    Mar 8
```

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Calling Agent                               │
│  (Human, AI assistant, cron job)                                    │
│                                                                     │
│  Has: browser automation, API access, manual observation            │
└─────────────────────────────────────┬───────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        clawfare CLI                                 │
├─────────────────────────────────────────────────────────────────────┤
│  Commands                                                           │
│  ┌───────────┐ ┌──────────┐ ┌──────────┐                            │
│  │investigate│ │   add    │ │   best   │                            │
│  │           │ │          │ │          │                            │
│  │ new/status│ │ Ingest   │ │ Query    │                            │
│  │ next/done │ │ prices   │ │ best     │                            │
│  └───────────┘ └──────────┘ └──────────┘                            │
├─────────────────────────────────────────────────────────────────────┤
│  Core Logic                                                         │
│  - Itinerary normalization & deduplication                          │
│  - Source validation & provenance tracking                          │
│  - RT vs 2×OW comparison engine                                     │
│  - Currency handling                                                │
├─────────────────────────────────────────────────────────────────────┤
│  Storage (SQLite)                                                   │
│  - itineraries, segments, prices, alerts                            │
│  - Price history with full provenance                               │
└─────────────────────────────────────────────────────────────────────┘
```

## What the CLI Does NOT Do

- ❌ Browser automation / scraping
- ❌ CAPTCHA solving
- ❌ Rate limit management
- ❌ Session/cookie handling
- ❌ Site-specific parsing

The agent handles all of that. The CLI just needs structured input.

## Key Features

### 1. Price Ingestion (`clawfare add`)

Record a price observation. Required fields ensure the itinerary can be reconstructed later.

```bash
# Nonstop
clawfare add \
  --investigation tokyo-may-2026 \
  --depart 2026-05-14 --return 2026-05-30 \
  --price 4376 \
  --source google_flights \
  --url "https://..." \
  --outbound "JL044,LHR-HND" \
  --return "JL043,HND-LHR"

# With connections (include layover times)
clawfare add \
  --investigation tokyo-may-2026 \
  --depart 2026-05-14 --return 2026-05-30 \
  --price 4105 \
  --source google_flights \
  --url "https://..." \
  --outbound "AY1332,LHR-HEL;1h45m;AY73,HEL-HND" \
  --return "AY74,HND-HEL;2h10m;AY1331,HEL-LHR"
```

**Required fields:**
- `--investigation`
- `--depart`, `--return` (or `--one-way`)
- `--price` (GBP)
- `--source`
- `--url` (for sources that require it)
- `--outbound` — flight(s): `carrier+number,origin-dest` with layover times between connections
- `--return` — same (unless one-way)

Validates, normalizes, deduplicates, stores.

### 2. Best Option Query (`clawfare best`)

Query the best options found in an investigation.

```bash
$ clawfare best tokyo-may-2026
```

Output:
```json
{
  "investigation": "tokyo-may-2026",
  "best_round_trip": {
    "price": 4105,
    "currency": "GBP", 
    "dates": "2026-05-08 to 2026-05-22",
    "carrier": "Finnair",
    "source": "google_flights",
    "url": "https://..."
  },
  "best_two_one_ways": {
    "total": 3650,
    "outbound": {"price": 2100, "carrier": "JAL", "source": "jal.com"},
    "return": {"price": 1550, "carrier": "ANA", "source": "ana.com"},
    "savings_vs_rt": 455
  },
  "vs_target": {
    "target": 3000,
    "best": 3650,
    "gap": 650,
    "status": "above_target"
  },
  "data_freshness": "2h ago",
  "coverage": {
    "sources_done": 4,
    "sources_total": 7,
    "dates_searched": 12,
    "dates_in_range": 31
  }
}
```

The agent workflow is:
1. `clawfare investigate next tokyo-may-2026` → get next task
2. Perform the search (browser, API, manual)
3. `clawfare add --investigation tokyo-may-2026 ...` → record findings
4. `clawfare investigate done tokyo-may-2026 --task <id>` → mark complete
5. Repeat until `clawfare best tokyo-may-2026` shows target hit or investigation complete

## Implementation Phases

### Phase 1: Core
- [ ] CLI skeleton (Kotlin + picocli)
- [ ] SQLite schema
- [ ] `investigate new` / `status`
- [ ] `add` with validation
- [ ] `best` query

### Phase 2: Task Management  
- [ ] `investigate next` with task generation
- [ ] `investigate done` / `skip`
- [ ] Source tracking and priorities
- [ ] Default sources by route

### Phase 3: Comparison Logic
- [ ] Two one-way analysis
- [ ] RT vs 2×OW comparison in `best` output
- [ ] Target price tracking

## Language Choice

**Kotlin** — because:
- Daniel's preference (no Python for backend)
- Good for CLI (picocli library)
- Can share code with papiertiger patterns

## Open Questions

1. ~~**Currency handling**~~ — GBP only to start
2. ~~**Codeshares**~~ — Same flight, different vendors = different prices, keep both
3. ~~**Segment granularity**~~ — Required: carrier, flight number, duration, stops, layover times
4. ~~**Investigation lifecycle**~~ — Never auto-complete, investigations can always continue
5. ~~**Multi-passenger**~~ — Assume 1 passenger
