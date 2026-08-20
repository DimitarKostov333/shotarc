#!/usr/bin/env python3
"""
Builds the course/hole asset the app ships, from OpenStreetMap.

Two front ends, one output:

    # small region, one query, no download
    python3 build_courses.py --overpass holes.json courses.json ../app/src/main/assets/courses.json

    # whole country, offline, re-runnable (needs pyosmium)
    wget https://download.geofabrik.de/africa/south-africa-latest.osm.pbf
    python3 build_courses.py --pbf south-africa-latest.osm.pbf ../app/src/main/assets/courses.json

Holes are golf=hole ways; par comes from the par tag where a mapper filled it in and from the
hole's length where they did not. Courses are leisure=golf_course polygons, and a hole belongs to
the course whose polygon contains its first node.

Data © OpenStreetMap contributors, ODbL 1.0.
"""
import json
import math
import sys
from collections import defaultdict

EARTH_R = 6371000.0
NEAR_COURSE_M = 400
MIN_HOLES = 9
MAX_LOOP = 18


def haversine(a, b):
    (lon1, lat1), (lon2, lat2) = a, b
    p1, p2 = math.radians(lat1), math.radians(lat2)
    h = (math.sin((p2 - p1) / 2) ** 2
         + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2)
    return 2 * EARTH_R * math.asin(math.sqrt(h))


def length_of(points):
    return sum(haversine(points[i], points[i + 1]) for i in range(len(points) - 1))


def par_from_length(metres):
    """Mappers tracing satellite imagery can see the fairway but not the scorecard."""
    if metres < 230:
        return 3
    if metres <= 430:
        return 4
    return 5


def contains(polygon, point):
    x, y = point
    inside = False
    for i in range(len(polygon)):
        x1, y1 = polygon[i]
        x2, y2 = polygon[(i + 1) % len(polygon)]
        if (y1 > y) != (y2 > y) and x < x1 + (y - y1) * (x2 - x1) / (y2 - y1):
            inside = not inside
    return inside


def edge_distance(polygon, point):
    return min(haversine(vertex, point) for vertex in polygon)


def polygon_area(points):
    """Shoelace in degrees — only ever compared against other polygons, never used as a size."""
    total = 0.0
    for i in range(len(points)):
        x1, y1 = points[i]
        x2, y2 = points[(i + 1) % len(points)]
        total += x1 * y2 - x2 * y1
    return abs(total) / 2


def split_into_loops(holes):
    """
    A club's polygon often covers several courses, so the hole numbers repeat. Walk each loop the
    way it is played — the next hole's tee is the one nearest this hole's green — and cut a new
    loop when the numbering starts again.
    """
    if not holes:
        return []
    counts = defaultdict(int)
    for hole in holes:
        counts[hole["n"]] += 1
    if max(counts.values()) == 1 and len(holes) <= MAX_LOOP:
        return [holes]

    remaining = sorted(holes, key=lambda h: h["n"])
    loops = []
    while remaining:
        current = remaining.pop(0)
        loop = [current]
        while len(loop) < MAX_LOOP:
            wanted = loop[-1]["n"] + 1
            candidates = [h for h in remaining if h["n"] == wanted]
            if not candidates:
                break
            green = loop[-1]["path"][-1]
            nearest = min(candidates, key=lambda h: haversine(green, h["path"][0]))
            remaining.remove(nearest)
            loop.append(nearest)
        loops.append(loop)
    return loops


def centroid(points):
    return (sum(p[0] for p in points) / len(points), sum(p[1] for p in points) / len(points))


def from_overpass(holes_file, courses_file):
    def geometry(element):
        return [(p["lon"], p["lat"]) for p in element.get("geometry", [])]

    holes = [
        {"tags": e.get("tags", {}), "points": geometry(e)}
        for e in json.load(open(holes_file))["elements"]
        if len(geometry(e)) >= 2
    ]
    courses = [
        {"tags": e.get("tags", {}), "points": geometry(e)}
        for e in json.load(open(courses_file))["elements"]
        if len(geometry(e)) >= 3
    ]
    return holes, courses


def from_pbf(path):
    import osmium

    class Golf(osmium.SimpleHandler):
        def __init__(self):
            super().__init__()
            self.holes = []
            self.courses = []

        def way(self, w):
            tags = dict(w.tags)
            hole = tags.get("golf") == "hole"
            course = tags.get("leisure") == "golf_course"
            if not (hole or course):
                return
            try:
                points = [(n.lon, n.lat) for n in w.nodes if n.location.valid()]
            except osmium.InvalidLocationError:
                return
            if hole and len(points) >= 2:
                self.holes.append({"tags": tags, "points": points})
            elif course and len(points) >= 3:
                self.courses.append({"tags": tags, "points": points})

    handler = Golf()
    handler.apply_file(path, locations=True, idx="flex_mem")
    return handler.holes, handler.courses


def build(holes, courses, out_path):
    print(f"golf=hole ways: {len(holes)}")
    named = [c for c in courses if c["tags"].get("name")]
    print(f"golf_course polygons: {len(courses)} ({len(named)} named)")
    for course in named:
        course["centre"] = centroid(course["points"])
        course["area"] = polygon_area(course["points"])

    grouped = defaultdict(list)
    unplaced = 0
    for hole in holes:
        start = hole["points"][0]
        # Club polygons often enclose the individual course polygons, so take the tightest fit.
        inside = [i for i, c in enumerate(named) if contains(c["points"], start)]
        home = min(inside, key=lambda i: named[i]["area"]) if inside else None
        if home is None and named:
            # A hole drawn just outside its own boundary is near the fence, not near the middle:
            # measuring to the centre would lose the far holes of a big course.
            nearest = min(range(len(named)), key=lambda i: edge_distance(named[i]["points"], start))
            if edge_distance(named[nearest]["points"], start) < NEAR_COURSE_M:
                home = nearest
        if home is None:
            unplaced += 1
            continue
        grouped[home].append(hole)
    print(f"holes placed: {sum(len(v) for v in grouped.values())}, unplaced: {unplaced}")

    estimated = 0
    built = []
    for index, holes_here in grouped.items():
        out_holes = []
        for hole in holes_here:
            tags = hole["tags"]
            metres = length_of(hole["points"])
            par = tags.get("par")
            try:
                par = int(par)
                par_known = True
            except (TypeError, ValueError):
                par = par_from_length(metres)
                par_known = False
                estimated += 1
            try:
                number = int(str(tags.get("ref", "")).strip())
            except ValueError:
                number = None
            out_holes.append({
                "n": number,
                "par": par,
                "known": par_known,
                "m": round(metres),
                "path": [[round(p[0], 6), round(p[1], 6)] for p in hole["points"]],
            })

        numbered = sorted((h for h in out_holes if h["n"]), key=lambda h: h["n"])
        spare = [h for h in out_holes if not h["n"]]
        for offset, hole in enumerate(spare):
            hole["n"] = len(numbered) + offset + 1
        course = named[index]
        loops = split_into_loops(numbered + spare)
        for loop_index, loop in enumerate(loops):
            if len(loop) < MIN_HOLES:
                continue
            name = course["tags"].get("name")
            if len(loops) > 1 and loop_index > 0:
                name = f"{name} ({loop_index + 1})"
            built.append({
                "name": name,
                "lat": round(centroid([h["path"][0] for h in loop])[1], 5),
                "lon": round(centroid([h["path"][0] for h in loop])[0], 5),
                "holes": loop,
            })

    built.sort(key=lambda c: c["name"])
    json.dump(
        {"attribution": "© OpenStreetMap contributors", "licence": "ODbL 1.0", "courses": built},
        open(out_path, "w"),
        separators=(",", ":"),
        ensure_ascii=False,
    )

    total_holes = sum(len(c["holes"]) for c in built)
    full = len([c for c in built if len(c["holes"]) >= 18])
    print(f"courses with {MIN_HOLES}+ holes: {len(built)} ({full} with a full 18)")
    print(f"holes written: {total_holes}, par estimated from length for {estimated}")
    for course in built:
        pars = sum(h["par"] for h in course["holes"])
        print(f"  {course['name']}: {len(course['holes'])} holes, par {pars}")


if __name__ == "__main__":
    if sys.argv[1] == "--overpass":
        holes, courses = from_overpass(sys.argv[2], sys.argv[3])
        build(holes, courses, sys.argv[4])
    elif sys.argv[1] == "--pbf":
        holes, courses = from_pbf(sys.argv[2])
        build(holes, courses, sys.argv[3])
    else:
        sys.exit(__doc__)
