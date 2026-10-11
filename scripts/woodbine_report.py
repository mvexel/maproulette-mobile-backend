#!/usr/bin/env python3
"""Woodbine entrance doors: answers per entrance, pending vs published, outcomes.

Usage:
  python3 scripts/woodbine_report.py --survey woodbine-survey.json \
      --results results.csv [--pending pending.csv] --out woodbine

--survey   The survey file the challenge was imported from (entrances, their
           OSM tags at import, and the answer labels).
--results  The campaign's results export: admin > campaign > Download results,
           or GET /api/v2/mobile-admin/challenges/:id/results (CSV). It holds
           what was published to OSM and the outcomes of signed-in mappers.
--pending  Optional. Guests' answers in choice_pending, every state, exported by
           scripts/woodbine-pending.sql (read only). Without it the report
           takes the waiting answers from the results CSV's pending_count,
           pending_outcomes and pending:<question> columns, and leaves
           pending_history empty.

Writes <out>.csv and <out>.geojson, one row per entrance, and prints a summary.
Standard library only. Adapted from community/results/stop_report.py.
"""
import argparse
import csv
import json
from collections import Counter, defaultdict

KEYS = {"door-type": "door", "automatic-opening": "automatic_door"}
# choice_pending states (evolution 137) and what the report calls them.
PENDING_STATES = {
    "pending": "waiting",
    "published": "published",
    "skipped_stale": "skipped",
    "superseded": "skipped",
    "expired": "expired",
    "failed": "failed",
}


def read_results(path):
    """The results CSV starts with a '# notice' line before the header."""
    with open(path, newline="", encoding="utf-8-sig") as f:
        lines = [line for line in f if not line.startswith("#")]
    return {r["element"]: r for r in csv.DictReader(lines) if r.get("element")}


def read_pending(path):
    by_element = defaultdict(list)
    if not path:
        return by_element
    with open(path, newline="", encoding="utf-8-sig") as f:
        for r in csv.DictReader(f):
            answers = json.loads(r["answers"]) if r.get("answers") else {}
            by_element[r["element"]].append(
                {"state": r["state"], "answers": answers or {}, "outcome": r.get("outcome") or ""}
            )
    return by_element


def from_tally(cell):
    """'hinged 2;sliding 1' (results CSV) back to ['hinged', 'hinged', 'sliding']."""
    out = []
    for part in (cell or "").split(";"):
        value, _, n = part.rpartition(" ")
        if value and n.isdigit():
            out += [value] * int(n)
    return out


def waiting_from_results(r):
    """Waiting answers as the results CSV tallies them. Outcomes there are labels already."""
    doors = from_tally(r.get("pending:door-type"))
    autos = from_tally(r.get("pending:automatic-opening"))
    return {
        "count": int(r.get("pending_count") or 0),
        "door": doors,
        "automatic": autos,
        "outcomes": from_tally(r.get("pending_outcomes")),
    }


def labels(survey):
    out = {}
    for q in survey["questions"]:
        for o in q["options"]:
            out[(q["id"], o["id"])] = o["setTags"][KEYS[q["id"]]]
    for o in survey.get("outcomes", []):
        out[("outcome", o["id"])] = o["label"]
    return out


def tally(values):
    """'hinged 2; sliding 1', most common first; empty when there is nothing."""
    return "; ".join(f"{v} {n}" for v, n in Counter(values).most_common())


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--survey", required=True)
    p.add_argument("--results", required=True)
    p.add_argument("--pending")
    p.add_argument("--out", default="woodbine")
    a = p.parse_args()

    survey = json.load(open(a.survey, encoding="utf-8"))
    names = labels(survey)
    results = read_results(a.results)
    pending = read_pending(a.pending)

    rows = []
    for f in survey["features"]["features"]:
        tags = {k: v for k, v in f["properties"].items() if k != "@id"}
        element = f["properties"]["@id"]
        lon, lat = f["geometry"]["coordinates"][:2]
        r = results.get(element, {})
        if a.pending:
            waiting = [x for x in pending.get(element, []) if x["state"] == "pending"]
            w = {
                "count": len(waiting),
                "door": [x["answers"]["door-type"] for x in waiting if "door-type" in x["answers"]],
                "automatic": [x["answers"]["automatic-opening"] for x in waiting
                              if "automatic-opening" in x["answers"]],
                "outcomes": [names.get(("outcome", x["outcome"]), x["outcome"])
                             for x in waiting if x["outcome"]],
            }
        else:
            w = waiting_from_results(r)
        set_tags = dict(
            pair.split("=", 1) for pair in (r.get("tags_set") or "").split(";") if "=" in pair
        )
        row = {
            "osm_id": element,
            "entrance": tags.get("entrance", ""),
            "name": tags.get("name", ""),
            "lat": lat,
            "lon": lon,
            "door_at_import": tags.get("door", ""),
            "automatic_door_at_import": tags.get("automatic_door", ""),
            "task_status": r.get("status_name", "") or ("not imported" if not r else ""),
            "completed_at": r.get("completed_at", ""),
            "changeset_id": r.get("changeset_id", ""),
            "door_published": set_tags.get("door", ""),
            "automatic_door_published": set_tags.get("automatic_door", ""),
            "outcome": r.get("outcome_label", ""),
            "stale_reason": r.get("stale_reason", ""),
            "reports": r.get("report_count", ""),
            "pending_answers": w["count"],
            "pending_door": tally(names.get(("door-type", v), v) for v in w["door"]),
            "pending_automatic_door": tally(
                names.get(("automatic-opening", v), v) for v in w["automatic"]
            ),
            "pending_outcomes": tally(w["outcomes"]),
            "pending_history": tally(
                PENDING_STATES.get(x["state"], x["state"]) for x in pending.get(element, [])
            ),
        }
        # Guests who disagree with each other, or with what was published, need a look.
        disagree = [k for k in ("pending_door", "pending_automatic_door") if ";" in row[k]]
        published_vs_pending = [
            k for k, pk in (("door_published", "pending_door"),
                            ("automatic_door_published", "pending_automatic_door"))
            if row[k] and row[pk] and not row[pk].startswith(row[k] + " ")
        ]
        row["check"] = "; ".join(
            [f"{k.removeprefix('pending_')} answers differ" for k in disagree]
            + [f"{k.removesuffix('_published')} published differs from pending"
               for k in published_vs_pending]
            + (["outcome reported"] if row["outcome"] or row["pending_outcomes"] else [])
        )
        row["state"] = (
            "published" if row["changeset_id"]
            else "outcome" if row["outcome"]
            else "waiting" if w["count"]
            else "open"
        )
        rows.append(row)

    order = {"outcome": 0, "waiting": 1, "published": 2, "open": 3}
    rows.sort(key=lambda r: (r["check"] == "", order[r["state"]], r["osm_id"]))
    with open(a.out + ".csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]) if rows else ["osm_id"])
        w.writeheader()
        w.writerows(rows)
    feats = [{"type": "Feature",
              "geometry": {"type": "Point", "coordinates": [r["lon"], r["lat"]]},
              "properties": {k: v for k, v in r.items() if k not in ("lat", "lon")}}
             for r in rows]
    with open(a.out + ".geojson", "w", encoding="utf-8") as f:
        json.dump({"type": "FeatureCollection", "features": feats}, f)

    states = Counter(r["state"] for r in rows)
    print(f"{len(rows)} entrances: {states['published']} published, "
          f"{states['waiting']} with answers waiting, {states['outcome']} with an outcome, "
          f"{states['open']} not answered yet")
    print(f"pending answers: {sum(r['pending_answers'] for r in rows)}"
          + ("" if a.pending else " (from the results CSV; no --pending file given)"))
    print(f"need a look: {sum(r['check'] != '' for r in rows)}")


if __name__ == "__main__":
    main()
