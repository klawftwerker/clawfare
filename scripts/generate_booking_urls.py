#!/usr/bin/env python3
"""
Generate Google Flights booking URLs from clawfare flight data.

The tfs parameter is a protobuf-encoded flight itinerary. We reconstruct it
from the flight's JSON segment data (airports, dates, airlines, flight numbers).
"""

import sqlite3
import json
import base64
import struct
import sys
import os

DB_PATH = os.path.expanduser("~/.clawfare/data.db")


def encode_varint(value):
    """Encode an integer as a protobuf varint."""
    parts = []
    while value > 0x7F:
        parts.append((value & 0x7F) | 0x80)
        value >>= 7
    parts.append(value & 0x7F)
    return bytes(parts)


def encode_field(field_number, wire_type, data):
    """Encode a protobuf field."""
    tag = (field_number << 3) | wire_type
    return encode_varint(tag) + data


def encode_string(field_number, value):
    """Encode a string field (wire type 2)."""
    encoded = value.encode('utf-8')
    return encode_field(field_number, 2, encode_varint(len(encoded)) + encoded)


def encode_varint_field(field_number, value):
    """Encode a varint field (wire type 0)."""
    return encode_field(field_number, 0, encode_varint(value))


def build_leg(departure_airport, date, arrival_airport, airline_code, flight_number):
    """Build a protobuf leg message."""
    msg = b""
    msg += encode_string(1, departure_airport)  # from airport
    msg += encode_string(2, date)               # date
    msg += encode_string(3, arrival_airport)     # to airport
    msg += encode_string(5, airline_code)        # airline
    msg += encode_string(6, flight_number)       # flight number
    return msg


def build_segment(date, legs, origin, destination):
    """Build a protobuf segment (outbound or return)."""
    msg = b""
    msg += encode_string(2, date)  # segment date
    
    for leg in legs:
        leg_data = build_leg(
            leg['depart_airport'],
            leg['depart_time'][:10],
            leg['arrive_airport'],
            leg.get('airline_code', ''),
            leg.get('flight_number', ''),
        )
        msg += encode_field(4, 2, encode_varint(len(leg_data)) + leg_data)
    
    # Origin/destination markers
    msg += encode_string(13, origin)    # j field - origin
    msg += encode_string(14, destination)  # r field - destination
    
    return msg


def build_tfs(outbound_json, return_json, origin, destination):
    """Build the complete tfs protobuf parameter."""
    outbound = json.loads(outbound_json)
    
    msg = b""
    # Header fields
    msg += encode_varint_field(1, 28)  # field 1 = 28 (0x1c)
    msg += encode_varint_field(2, 2)   # field 2 = 2 (round trip)
    
    # Outbound segment
    out_date = outbound['depart_time'][:10]
    out_segment = build_segment(out_date, outbound['legs'], origin, destination)
    msg += encode_field(3, 2, encode_varint(len(out_segment)) + out_segment)
    
    # Return segment
    if return_json:
        ret = json.loads(return_json)
        ret_date = ret['depart_time'][:10]
        ret_segment = build_segment(ret_date, ret['legs'], destination, origin)
        msg += encode_field(3, 2, encode_varint(len(ret_segment)) + ret_segment)
    
    # Footer fields
    msg += encode_varint_field(8, 1)   # @1
    msg += encode_varint_field(9, 3)   # H3 (business class)
    msg += encode_varint_field(14, 1)  # p1
    
    # Cabin class indicator
    cabin = encode_varint_field(1, 0xFFFFFFFFFF)  # -1 as varint
    cabin_wrapper = encode_field(16, 2, encode_varint(len(cabin)) + cabin)
    msg += cabin_wrapper
    
    msg += encode_varint_field(19, 1)  # field 19 = 1
    
    return msg


def generate_url(outbound_json, return_json, origin, destination):
    """Generate a Google Flights booking URL."""
    tfs = build_tfs(outbound_json, return_json, origin, destination)
    tfs_b64 = base64.urlsafe_b64encode(tfs).decode('utf-8').rstrip('=')
    return f"https://www.google.com/travel/flights/booking?tfs={tfs_b64}&curr=GBP&hl=en&gl=gb"


def main():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    
    slug = sys.argv[1] if len(sys.argv) > 1 else "tokyo-may-2026"
    
    cursor = conn.execute("""
        SELECT id, origin, destination, outbound_json, return_json, trip_type,
               price_amount, price_currency
        FROM flights
        WHERE investigation_slug = ?
        ORDER BY price_amount ASC
    """, (slug,))
    
    for row in cursor:
        try:
            url = generate_url(row['outbound_json'], row['return_json'],
                             row['origin'], row['destination'])
            print(f"{row['id'][:8]}|{row['price_amount']}|{url}")
        except Exception as e:
            print(f"{row['id'][:8]}|ERROR|{e}", file=sys.stderr)
    
    conn.close()


if __name__ == "__main__":
    main()
