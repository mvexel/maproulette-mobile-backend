# --- MapRoulette Scheme

# --- !Ups

-- The campaign wizard's nightly OSM extract (fork only, architecture/wizard-parquet.md): every
-- element the question sets could pick, with full tags and one point each (a way's point lies on
-- it), and one row saying when the extract was cut and the area it covers. The nightly load
-- (scripts/wizard-extract/load.sh) builds a new table and swaps it in within one transaction.
CREATE TABLE IF NOT EXISTS wizard_osm_features (
  kind text NOT NULL CHECK (kind IN ('node', 'way')),
  osm_id bigint NOT NULL,
  tags jsonb NOT NULL,
  geom geometry(Point, 4326) NOT NULL,
  PRIMARY KEY (kind, osm_id)
);;
CREATE INDEX IF NOT EXISTS wizard_osm_features_geog
  ON wizard_osm_features USING GIST ((geom::geography));;
CREATE TABLE IF NOT EXISTS wizard_osm_extract (
  id integer PRIMARY KEY CHECK (id = 1),
  source text NOT NULL,
  extract_at timestamptz NOT NULL,
  loaded_at timestamptz NOT NULL DEFAULT NOW(),
  features integer NOT NULL,
  coverage geometry(Geometry, 4326) NOT NULL
);;

# --- !Downs
DROP TABLE IF EXISTS wizard_osm_extract;;
DROP TABLE IF EXISTS wizard_osm_features;;
