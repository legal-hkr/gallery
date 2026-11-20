#!/usr/bin/env python3
"""Builds Gallery's place data, app/src/main/assets/places.bin.gz (LOGIKA §17).

Sources, both free of conditions:
- Natural Earth 10m admin-0 countries and admin-1 regions (public domain): borders and names.
- Wikidata (CC0): human settlements (classes under Q486972) with coordinates, population and names.

Coverage (owner's decision D20): every settlement in Europe, elsewhere those of 1000 inhabitants and more.

Usage:
    python3 -m venv venv && venv/bin/pip install -r requirements.txt
    venv/bin/python build.py [--cache DIR] [--out FILE] [--box S,W,N,E] [--fetch-only]

Everything fetched is kept in the cache (default: ./cache), so a run that stops – Wikidata's query service limits
and times out – starts again where it stopped. Wikidata is asked in squares of 5 degrees through `wikibase:box`; a
square whose query times out is split in four.

The file's layout (little-endian; borders and bounding boxes in 1e-4 degrees, about 11 m):

    header, 72 bytes:
        0  magic "HNPL"            4  u16 format (3)        6  u16 levels of borders (4)
        8  u32 the data's date (yyyymmdd, shown in About)
        12 u32 strings  16 u32 countries  20 u32 regions  24 u32 settlements  28 u32 cells
        32 u32 offset of strings  36 countries  40 regions  44 cells  48 settlements  52 borders
        56 u32 grid cells per degree (4)  60 u32 reserved
        64 u32 parts of towns  68 u32 build: CRC-32 of everything after the header (the app places photos again when
           it changes; two builds of one day differ in it, not in the date)
    strings: u32 offsets[strings + 1] relative to the bytes that follow, then UTF-8 bytes
    country, 48 bytes: u32 name pl, en, local; i32 bbox west, south, east, north; u32 borders[4] (offsets from
        the borders' start, one per level); u8 continent (1 Europe, 0 other); 3 bytes padding. Countries have
        levels 0–2, their level 3 is level 2 again (a photo's country comes from its region)
    region, 48 bytes: u16 country; u16 padding; u32 name pl, en, local; i32 bbox[4]; u32 borders[4]. Regions have
        levels 2–3; 0–1 point at an empty border (the world and continents show countries only)
    cell, 8 bytes: u32 cell id ((lat + 90) * 4 as row * 1440 + (lon + 180) * 4), u32 first settlement; sorted by id,
        a cell's settlements run to the next cell's first
    settlement, 21 bytes: u16 lat, lon within its cell (0–65535 across the cell's quarter degree); u32 population;
        u24 name pl, en, local; u16 region, u16 country (0xFFFF none)
    border: u16 rings; per ring u32 points, i32 lon, lat of the first, then zigzag varint deltas of the rest
    parts, 8 bytes each, last in the file: u32 settlement, u32 its town (a settlement), sorted by settlement – the
        app records the town, so a town, its districts and its urban area are one place

A part of a town (a district, a quarter: a settlement located in another settlement, P131) keeps its own point but
takes its town's names and population, so a photo in a city's outskirts is named after the city.
"""

import argparse
import datetime
import gzip
import http.client
import io
import json
import os
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
import zlib

import numpy as np
import shapefile
import shapely
from shapely.geometry import MultiPolygon, Polygon, shape

USER_AGENT = "hnet-gallery-places/1.0 (offline gazetteer build; Wikidata CC0)"
SPARQL = "https://query.wikidata.org/sparql"
NATURAL_EARTH = "https://naciscdn.org/naturalearth/10m/cultural/{}.zip"
COUNTRIES = "ne_10m_admin_0_countries"
REGIONS = "ne_10m_admin_1_states_provinces"

# Douglas–Peucker tolerances in degrees: the world, a continent, a country, a region (the last also places photos)
LEVELS = [0.05, 0.01, 0.003, 0.0015]
COUNTRY_LEVELS = (0, 1, 2)
REGION_LEVELS = (2,)
BORDER_UNIT = 1e4
GRID = 4
TILE = 5.0
SMALLEST_TILE = TILE / 64
# A smallest square that times out is asked again this many times, a minute apart: the service's load comes and goes
RETRIES = 3
MIN_POPULATION = 1000
# Squares of the map where every settlement is fetched (Europe as far as its countries reach); elsewhere only those
# of MIN_POPULATION and more. Which settlements stay is decided by their country's continent afterwards.
EUROPE = (34.0, -32.0, 72.0, 70.0)
POLISH_REGION_PREFIX = "województwo "

# One row per settlement, grouped by the service: villages have a population for every census, several names and
# parents, and their combinations made answers of hundreds of megabytes that the service cut off
QUERY = """SELECT ?s (SAMPLE(?c) AS ?coord) (MAX(?p) AS ?pop) (SAMPLE(?lpl) AS ?pl) (SAMPLE(?len) AS ?en) (SAMPLE(?lmul) AS ?mul)
       (SAMPLE(?nat) AS ?native) (GROUP_CONCAT(DISTINCT STR(?par); separator=" ") AS ?parent) WHERE {{
  # In the written order: the square first, then each point's class (the service otherwise may start from every
  # settlement of the world; a dense square took 13 s with this, 19 s without, on 20 Nov 2026)
  hint:Query hint:optimizer "None" .
  SERVICE wikibase:box {{
    ?s wdt:P625 ?c .
    bd:serviceParam wikibase:cornerWest "Point({west} {south})"^^geo:wktLiteral .
    bd:serviceParam wikibase:cornerEast "Point({east} {north})"^^geo:wktLiteral .
  }}
  ?s wdt:P31/wdt:P279* wd:Q486972 .
  FILTER NOT EXISTS {{ ?s wdt:P576 [] }}
  {population}
  OPTIONAL {{ ?s rdfs:label ?lpl FILTER(LANG(?lpl) = "pl") }}
  OPTIONAL {{ ?s rdfs:label ?len FILTER(LANG(?len) = "en") }}
  OPTIONAL {{ ?s rdfs:label ?lmul FILTER(LANG(?lmul) = "mul") }}
  OPTIONAL {{ ?s wdt:P1705 ?nat }}
  OPTIONAL {{ ?s wdt:P131 ?par . ?par wdt:P31/wdt:P279* wd:Q486972 }}
}} GROUP BY ?s"""
WITH_POPULATION = "?s wdt:P1082 ?p . FILTER(?p >= {})".format(MIN_POPULATION)
ANY_POPULATION = "OPTIONAL { ?s wdt:P1082 ?p }"

NATIVE_NAMES = """SELECT ?s ?native ?mul WHERE {{
  VALUES ?s {{ {ids} }}
  OPTIONAL {{ ?s wdt:P1705 ?native }}
  OPTIONAL {{ ?s rdfs:label ?mul FILTER(LANG(?mul) = "mul") }}
}}"""


class TimedOut(Exception):
    pass


def ask(query, pause):
    """One SPARQL query, waiting out the service's limits; TimedOut when it can't answer in its time."""
    body = urllib.parse.urlencode({"query": query}).encode()
    broken = 0
    while True:
        request = urllib.request.Request(SPARQL, data=body, headers={"User-Agent": USER_AGENT, "Accept": "application/sparql-results+json"})
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                body_text = response.read()
            try:
                data = json.loads(body_text)
            except ValueError:
                # A long answer the service cut off at its time limit: the error text lands inside the JSON
                time.sleep(pause)
                raise TimedOut()
            time.sleep(pause)
            return data["results"]["bindings"]
        except urllib.error.HTTPError as error:
            text = error.read().decode("utf-8", "replace")
            if error.code == 429:
                wait = int(error.headers.get("Retry-After") or 65)
                print(f"  limited, waiting {wait} s", file=sys.stderr)
                time.sleep(wait)
                continue
            if error.code in (500, 502, 503, 504) and ("Timeout" in text or "timeout" in text or error.code != 500):
                time.sleep(pause)
                raise TimedOut()
            raise
        except (TimeoutError, ConnectionError, http.client.HTTPException, urllib.error.URLError) as error:
            # A dropped connection or no answer in our time: twice more, then as the service's own time-out (the square splits)
            broken += 1
            if broken > 2:
                raise TimedOut()
            print(f"  {type(error).__name__}: {error}, trying again in 30 s", file=sys.stderr)
            time.sleep(30)


def tiles(box, size):
    south, west, north, east = box
    lat = south
    while lat < north:
        lon = west
        while lon < east:
            yield (lat, lon, size)
            lon += size
        lat += size


def in_europe(lat, lon, size):
    s, w, n, e = EUROPE
    return lat < n and lat + size > s and lon < e and lon + size > w


def fetch_tile(cache, lat, lon, size, pause):
    """A square's rows, from the cache or Wikidata; split in four while its query times out."""
    path = os.path.join(cache, "wikidata", f"{lat:+08.3f}_{lon:+09.3f}_{size:06.3f}.json.gz")
    if os.path.exists(path):
        with gzip.open(path, "rt", encoding="utf-8") as f:
            return json.load(f)
    europe = in_europe(lat, lon, size)
    query = QUERY.format(west=lon, south=lat, east=lon + size, north=lat + size, population=ANY_POPULATION if europe else WITH_POPULATION)
    try:
        rows = ask(query, pause)
    except TimedOut:
        if size <= SMALLEST_TILE:
            rows = None
            for attempt in range(RETRIES):
                print(f"  {lat:+.3f} {lon:+.3f} ({size}) timed out at the smallest size, again in 60 s", file=sys.stderr)
                time.sleep(60)
                try:
                    rows = ask(query, pause)
                    break
                except TimedOut:
                    continue
            if rows is None:
                raise SystemExit(f"Wikidata can't answer for {lat}, {lon} even at {size} degrees; run again later, the cache keeps what's done")
            return store(path, rows, lat, lon, size)
        half = size / 2
        print(f"  {lat:+.3f} {lon:+.3f} ({size}) timed out, splitting", file=sys.stderr)
        rows = []
        for dlat in (0, half):
            for dlon in (0, half):
                rows += fetch_tile(cache, lat + dlat, lon + dlon, half, pause)
        # The parts are cached on their own
        return rows
    return store(path, rows, lat, lon, size)


def store(path, rows, lat, lon, size):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with gzip.open(path + ".part", "wt", encoding="utf-8") as f:
        json.dump(rows, f)
    os.replace(path + ".part", path)
    print(f"  {lat:+.3f} {lon:+.3f} ({size}): {len(rows)} rows", file=sys.stderr)
    return rows


def cached_rows(cache, lat, lon, size):
    """What the cache has of a square: the square itself, else its quarters as far as they were fetched."""
    path = os.path.join(cache, "wikidata", f"{lat:+08.3f}_{lon:+09.3f}_{size:06.3f}.json.gz")
    if os.path.exists(path):
        with gzip.open(path, "rt", encoding="utf-8") as f:
            return json.load(f)
    if size <= SMALLEST_TILE:
        return []
    half = size / 2
    return [row for dlat in (0, half) for dlon in (0, half) for row in cached_rows(cache, lat + dlat, lon + dlon, half)]


def natural_earth(cache, name):
    path = os.path.join(cache, name + ".zip")
    if not os.path.exists(path):
        print(f"Downloading {name}", file=sys.stderr)
        request = urllib.request.Request(NATURAL_EARTH.format(name), headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(request, timeout=300) as response, open(path + ".part", "wb") as f:
            f.write(response.read())
        os.replace(path + ".part", path)
    archive = zipfile.ZipFile(path)
    files = {ext: io.BytesIO(archive.read(name + "." + ext)) for ext in ("shp", "shx", "dbf")}
    reader = shapefile.Reader(shp=files["shp"], shx=files["shx"], dbf=files["dbf"], encoding="utf-8")
    return [(record.as_dict(), shape(geometry.__geo_interface__)) for record, geometry in zip(reader.records(), reader.shapes())]


def text(value):
    return (value or "").strip("\0 ").strip()


def coordinate(literal):
    # "Point(lon lat)"
    lon, lat = literal[literal.index("(") + 1:literal.index(")")].split()
    return float(lat), float(lon)


def settlements(rows):
    """Wikidata's rows (one per combination of values) as one settlement per item."""
    found = {}
    for row in rows:
        item = row["s"]["value"].rsplit("/", 1)[1]
        entry = found.get(item)
        if entry is None:
            lat, lon = coordinate(row["coord"]["value"])
            entry = found[item] = {"lat": lat, "lon": lon, "pop": 0, "pl": "", "en": "", "local": "", "parents": set()}
        if "pop" in row:
            try:
                entry["pop"] = max(entry["pop"], int(float(row["pop"]["value"])))
            except ValueError:
                pass
        for key, source in (("pl", "pl"), ("en", "en")):
            if source in row and not entry[key]:
                entry[key] = row[source]["value"]
        # The local name: the native label, else the multilingual one
        if "native" in row:
            entry["local"] = row["native"]["value"]
        elif "mul" in row and not entry["local"]:
            entry["local"] = row["mul"]["value"]
        if "parent" in row:
            # Grouped rows give every parent at once, space-separated (older cached rows: one)
            for parent in row["parent"]["value"].split():
                entry["parents"].add(parent.rsplit("/", 1)[1])
    return found


def native_names(cache, ids, pause):
    """Native names of countries and regions by their Wikidata ids (Natural Earth has no local names for countries)."""
    path = os.path.join(cache, "native-names.json")
    known = {}
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            known = json.load(f)
    missing = [i for i in ids if i and i not in known]
    for start in range(0, len(missing), 300):
        batch = missing[start:start + 300]
        rows = ask(NATIVE_NAMES.format(ids=" ".join("wd:" + i for i in batch)), pause)
        for i in batch:
            known.setdefault(i, "")
        for row in rows:
            item = row["s"]["value"].rsplit("/", 1)[1]
            if "native" in row:
                known[item] = row["native"]["value"]
            elif "mul" in row and not known[item]:
                known[item] = row["mul"]["value"]
        with open(path, "w", encoding="utf-8") as f:
            json.dump(known, f, ensure_ascii=False)
        print(f"  native names: {len(known)}", file=sys.stderr)
    return known


class Strings:
    def __init__(self):
        self.index = {}
        self.items = []

    def __call__(self, value):
        value = value or ""
        found = self.index.get(value)
        if found is None:
            found = self.index[value] = len(self.items)
            self.items.append(value)
        return found

    def pack(self):
        data = [s.encode("utf-8") for s in self.items]
        offsets, at = [], 0
        for d in data:
            offsets.append(at)
            at += len(d)
        offsets.append(at)
        return struct.pack(f"<{len(offsets)}I", *offsets) + b"".join(data)


def zigzag(value):
    return (value << 1) ^ (value >> 63)


def varint(value, out):
    while value >= 0x80:
        out.append((value & 0x7F) | 0x80)
        value >>= 7
    out.append(value)


def rings(geometry):
    polygons = geometry.geoms if isinstance(geometry, MultiPolygon) else [geometry] if isinstance(geometry, Polygon) else []
    for polygon in polygons:
        if polygon.is_empty:
            continue
        yield polygon.exterior.coords
        for hole in polygon.interiors:
            yield hole.coords


def pack_border(geometry, tolerance):
    simple = shapely.simplify(geometry, tolerance, preserve_topology=True)
    out = bytearray()
    kept = [list(r) for r in rings(simple) if len(r) >= 4]
    out += struct.pack("<H", len(kept))
    for ring in kept:
        points = [(round(x * BORDER_UNIT), round(y * BORDER_UNIT)) for x, y in ring]
        out += struct.pack("<Iii", len(points), points[0][0], points[0][1])
        px, py = points[0]
        for x, y in points[1:]:
            varint(zigzag(x - px), out)
            varint(zigzag(y - py), out)
            px, py = x, y
    return bytes(out)


def bbox(geometry):
    w, s, e, n = geometry.bounds
    return [int(np.floor(w * BORDER_UNIT)), int(np.floor(s * BORDER_UNIT)), int(np.ceil(e * BORDER_UNIT)), int(np.ceil(n * BORDER_UNIT))]


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    here = os.path.dirname(os.path.abspath(__file__))
    parser.add_argument("--cache", default=os.path.join(here, "cache"))
    parser.add_argument("--out", default=os.path.join(here, "..", "..", "app", "src", "main", "assets", "places.bin.gz"))
    parser.add_argument("--box", help="only this part of the world, S,W,N,E (a test file)")
    parser.add_argument("--fetch-only", action="store_true")
    parser.add_argument("--cached-only", action="store_true", help="build from what the cache has, asking Wikidata nothing (an interim file while a fetch runs)")
    parser.add_argument("--pause", type=float, default=2.0, help="seconds between Wikidata queries")
    args = parser.parse_args()
    os.makedirs(args.cache, exist_ok=True)

    countries = natural_earth(args.cache, COUNTRIES)
    regions = natural_earth(args.cache, REGIONS)
    box = tuple(float(v) for v in args.box.split(",")) if args.box else (-60.0, -180.0, 85.0, 180.0)

    print("Fetching settlements", file=sys.stderr)
    rows = []
    # A test box is asked as one square (split if it must be); squares of open sea aren't asked at all
    size = max(box[2] - box[0], box[3] - box[1]) if args.box else TILE
    land = shapely.STRtree([g for _, g in countries])
    squares = [t for t in tiles(box, size) if len(land.query(shapely.box(t[1], t[0], t[1] + t[2], t[0] + t[2]), predicate="intersects"))]
    for n, (lat, lon, size) in enumerate(squares):
        print(f"[{n + 1}/{len(squares)}]", file=sys.stderr)
        rows += cached_rows(args.cache, lat, lon, size) if args.cached_only else fetch_tile(args.cache, lat, lon, size, args.pause)
    found = settlements(rows)
    print(f"{len(found)} settlements fetched", file=sys.stderr)

    within = shapely.box(box[1], box[0], box[3], box[2])
    ids = [text(p.get("WIKIDATAID")) for p, _ in countries] + [text(p.get("wikidataid")) for p, g in regions if not args.box or g.intersects(within)]
    natives = native_names(args.cache, [] if args.cached_only else ids, args.pause)
    if args.fetch_only:
        return

    # Countries and regions: names, continent, borders
    strings = Strings()
    country_rows, country_of_code = [], {}
    for props, geometry in countries:
        code = text(props.get("ADM0_A3"))
        country_of_code[code] = len(country_rows)
        en = text(props.get("NAME_EN")) or text(props.get("NAME"))
        country_rows.append({
            "names": (text(props.get("NAME_PL")) or en, en, natives.get(text(props.get("WIKIDATAID"))) or en),
            "europe": text(props.get("CONTINENT")) == "Europe",
            "geometry": geometry,
        })
    country_tree = shapely.STRtree([c["geometry"] for c in country_rows])
    region_rows = []
    for props, geometry in regions:
        country = country_of_code.get(text(props.get("adm0_a3")))
        if country is None:
            hits = country_tree.query(geometry.representative_point(), predicate="intersects")
            country = int(hits[0]) if len(hits) else 0xFFFF
        en = text(props.get("name_en")) or text(props.get("name"))
        pl = text(props.get("name_pl")) or en
        if pl.startswith(POLISH_REGION_PREFIX):
            # Owner's decision D21: "małopolskie", not "województwo małopolskie"
            pl = pl[len(POLISH_REGION_PREFIX):]
        local = natives.get(text(props.get("wikidataid"))) or text(props.get("name_local")) or text(props.get("name")) or en
        region_rows.append({"country": country, "names": (pl, en, local), "geometry": geometry})
    region_tree = shapely.STRtree([r["geometry"] for r in region_rows])

    # Settlements: a town's parts take the town's names and population; then their region and country
    items = list(found)
    for item in items:
        entry = found[item]
        town, town_item, depth = entry, item, 0
        while depth < 3:
            parent = next((p for p in sorted(entry_parents(town)) if p in found and p != item), None)
            if parent is None:
                break
            town, town_item, depth = found[parent], parent, depth + 1
        if town is not entry:
            entry["names_from"] = town
            entry["town"] = town_item
    points = shapely.points(np.array([[found[i]["lon"], found[i]["lat"]] for i in items]))
    def locate(tree, n):
        where = np.full(n, 0xFFFF, dtype=np.int64)
        hits = tree.query(points, predicate="intersects")
        where[hits[0]] = hits[1]
        missing = np.nonzero(where == 0xFFFF)[0]
        if len(missing):
            # On a coast the borders' simplification can leave a settlement in the sea: the nearest within ~5 km
            near = tree.query_nearest(points[missing], max_distance=0.05)
            where[missing[near[0]]] = near[1]
        return where
    in_region = locate(region_tree, len(points))
    in_country = locate(country_tree, len(points))

    kept = []
    for k, item in enumerate(items):
        entry = found[item]
        source = entry.get("names_from", entry)
        population = max(entry["pop"], source["pop"])
        country = int(in_country[k])
        europe = country != 0xFFFF and country_rows[country]["europe"]
        if not europe and population < MIN_POPULATION:
            continue
        en = source["en"] or source["local"] or source["pl"]
        local = source["local"] or en
        pl = source["pl"] or local
        if not en:
            continue
        region = int(in_region[k])
        if region != 0xFFFF and country == 0xFFFF:
            country = region_rows[region]["country"]
        kept.append((entry["lat"], entry["lon"], population, pl, en, local, region, country, item, entry.get("town", item)))
    print(f"{len(kept)} settlements kept", file=sys.stderr)

    # Settlements by grid cell
    def cell(lat, lon):
        row = min(int((lat + 90) * GRID), 180 * GRID - 1)
        col = min(int((lon + 180) * GRID), 360 * GRID - 1)
        return row * 360 * GRID + col
    kept.sort(key=lambda s: (cell(s[0], s[1]), -s[2]))
    settlement_bytes = bytearray()
    cells = []
    for index, (lat, lon, population, pl, en, local, region, country, _, _) in enumerate(kept):
        c = cell(lat, lon)
        if not cells or cells[-1][0] != c:
            cells.append((c, index))
        # The point within its cell: two bytes an axis, under a metre at a quarter degree
        dlat = min(65535, max(0, round((lat + 90 - (c // (360 * GRID)) / GRID) * GRID * 65535)))
        dlon = min(65535, max(0, round((lon + 180 - (c % (360 * GRID)) / GRID) * GRID * 65535)))
        names = b"".join(struct.pack("<I", strings(n))[:3] for n in (pl, en, local))
        settlement_bytes += struct.pack("<HHI", dlat, dlon, min(population, 0xFFFFFFFF)) + names + struct.pack("<HH", region, country)
    cell_bytes = b"".join(struct.pack("<II", c, i) for c, i in cells)

    # The empty border first: levels a kind doesn't have point at it
    borders = bytearray(struct.pack("<H", 0))
    def border_offsets(geometry, levels):
        offsets = []
        # A test box keeps only the borders it touches (the test file stays small)
        far = args.box and not geometry.intersects(within)
        for level, tolerance in enumerate(LEVELS):
            if far or (level not in levels and level < min(levels)):
                offsets.append(0)
            elif level not in levels:
                offsets.append(offsets[-1])
            else:
                offsets.append(len(borders))
                borders.extend(pack_border(geometry, tolerance))
        return offsets
    country_bytes = bytearray()
    for c in country_rows:
        country_bytes += struct.pack("<III", *(strings(n) for n in c["names"]))
        country_bytes += struct.pack("<4i", *bbox(c["geometry"]))
        country_bytes += struct.pack("<4I", *border_offsets(c["geometry"], COUNTRY_LEVELS))
        country_bytes += struct.pack("<B3x", 1 if c["europe"] else 0)
    region_bytes = bytearray()
    for r in region_rows:
        region_bytes += struct.pack("<H2x", r["country"])
        region_bytes += struct.pack("<III", *(strings(n) for n in r["names"]))
        region_bytes += struct.pack("<4i", *bbox(r["geometry"]))
        region_bytes += struct.pack("<4I", *border_offsets(r["geometry"], REGION_LEVELS))

    # Parts of towns whose town stayed in the file
    position = {s[8]: i for i, s in enumerate(kept)}
    parts = sorted((i, position[s[9]]) for i, s in enumerate(kept) if s[9] != s[8] and s[9] in position)
    part_bytes = b"".join(struct.pack("<II", a, b) for a, b in parts)

    string_bytes = strings.pack()
    offset = 72
    sections = []
    for part in (string_bytes, country_bytes, region_bytes, cell_bytes, settlement_bytes, borders):
        sections.append(offset)
        offset += len(part)
    date = int(datetime.date.today().strftime("%Y%m%d"))
    body = string_bytes + bytes(country_bytes) + bytes(region_bytes) + cell_bytes + bytes(settlement_bytes) + bytes(borders) + part_bytes
    build = zlib.crc32(body) & 0xFFFFFFFF
    header = struct.pack("<4sHHI5I6IIIII", b"HNPL", 3, len(LEVELS), date, len(strings.items), len(country_rows), len(region_rows), len(kept), len(cells), *sections, GRID, 0, len(parts), build)
    assert len(header) == 72
    data = header + body
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    # No time in the gzip header: the same data gives the same file
    with open(args.out + ".part", "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", compresslevel=9, mtime=0, filename="") as f:
        f.write(data)
    os.replace(args.out + ".part", args.out)
    print(f"{args.out}: {len(data) / 1e6:.1f} MB, {os.path.getsize(args.out) / 1e6:.1f} MB compressed", file=sys.stderr)
    print(f"  strings {len(string_bytes) / 1e6:.1f} MB, settlements {len(settlement_bytes) / 1e6:.1f} MB, borders {len(borders) / 1e6:.1f} MB", file=sys.stderr)


def entry_parents(entry):
    return entry["parents"]


if __name__ == "__main__":
    main()
