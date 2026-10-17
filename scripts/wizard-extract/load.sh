#!/usr/bin/env bash
# Loads the campaign wizard's nightly OSM extract into the backend database (evolution 149,
# architecture/wizard-parquet.md). Downloads the extract, keeps only what the question sets in
# conf/mobile/question-sets.json could pick (full tags), and swaps wizard_osm_features in one
# transaction, so the wizard reads either last night's table or tonight's, never half of one.
#
# Needs curl, jq, osmium-tool and psql. The database comes from the usual libpq variables
# (PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD) or DATABASE_URL.
#
#   EXTRACT_URL   the .osm.pbf to load (default: Geofabrik's Utah extract)
#   QUESTION_SETS the library to filter by (default: the repo's conf/mobile/question-sets.json)
#   EXTRACT_FILE  a local .osm.pbf to load instead of downloading (tests)
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
url="${EXTRACT_URL:-https://download.geofabrik.de/north-america/us/utah-latest.osm.pbf}"
sets="${QUESTION_SETS:-$here/../../conf/mobile/question-sets.json}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

if [[ -n "${EXTRACT_FILE:-}" ]]; then
  cp "$EXTRACT_FILE" "$work/extract.osm.pbf"
else
  curl -fsSL --retry 3 -A "StreetTally (+https://streettally.osm.lol; martijn@osm.lol)" \
    -o "$work/extract.osm.pbf" "$url"
fi

# One osmium expression per set and element type, on the set's most selective filter tag. The
# backend re-checks every filter and question, so a wider pick here only costs rows.
mapfile -t expressions < <(jq -r '
  .sets[] | .filter as $f
  | ($f.tags | to_entries | sort_by(.value == null, .key) | .[0]) as $t
  | $f.elements[]
  | "\(.[0:1])/\($t.key)\(if $t.value == null then "" else "=\($t.value)" end)"' "$sets" | sort -u)

osmium tags-filter --overwrite -o "$work/picked.osm.pbf" "$work/extract.osm.pbf" "${expressions[@]}"
# -R is not used: ways need their nodes for a geometry. Untagged nodes are not exported.
osmium export --overwrite -f geojsonseq -x print_record_separator=false \
  --attributes type,id -o "$work/picked.geojsonseq" "$work/picked.osm.pbf"

cut_at="$(osmium fileinfo -g header.option.osmosis_replication_timestamp "$work/extract.osm.pbf")"
[[ -n "$cut_at" ]] || cut_at="$(osmium fileinfo -e -g data.timestamp.last "$work/extract.osm.pbf")"
# The area the extract covers: its header box, or the box around what it holds.
box="$(osmium fileinfo -g header.boxes "$work/extract.osm.pbf" | head -1 | tr -d '()')"
[[ -n "$box" ]] || box="$(osmium fileinfo -e -g data.bbox "$work/extract.osm.pbf" | tr -d '()')"

psql ${DATABASE_URL:+"$DATABASE_URL"} -v ON_ERROR_STOP=1 -q \
  -v source="$url" -v cut_at="$cut_at" -v box="$box" \
  -f "$here/swap.sql" < "$work/picked.geojsonseq"
echo "Wizard extract loaded: $(psql ${DATABASE_URL:+"$DATABASE_URL"} -At -c \
  "SELECT features || ' elements, cut ' || extract_at FROM wizard_osm_extract")"
