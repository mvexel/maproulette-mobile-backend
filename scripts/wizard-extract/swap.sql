-- Run by load.sh with psql variables source, cut_at and box (minlon,minlat,maxlon,maxlat), and a
-- GeoJSON text sequence from osmium export on standard input. Builds the new table first; readers wait only for
-- the drop and rename at the end, and see the old table until the commit.
BEGIN;
CREATE TEMP TABLE wizard_osm_lines (line jsonb) ON COMMIT DROP;
-- CSV with quote and delimiter bytes JSON never holds, so each line arrives as written.
\copy wizard_osm_lines FROM pstdin WITH (FORMAT csv, QUOTE e'\x01', DELIMITER e'\x02')
CREATE TABLE wizard_osm_features_next (LIKE wizard_osm_features INCLUDING ALL);
INSERT INTO wizard_osm_features_next (kind, osm_id, tags, geom)
  SELECT line #>> '{properties,@type}', (line #>> '{properties,@id}')::bigint,
    (line -> 'properties') - '@type' - '@id',
    ST_PointOnSurface(ST_SetSRID(ST_GeomFromGeoJSON(line ->> 'geometry'), 4326))
  FROM wizard_osm_lines
  WHERE line #>> '{properties,@type}' IN ('node', 'way')
  ON CONFLICT DO NOTHING;
ANALYZE wizard_osm_features_next;
DROP TABLE wizard_osm_features;
ALTER TABLE wizard_osm_features_next RENAME TO wizard_osm_features;
INSERT INTO wizard_osm_extract (id, source, extract_at, loaded_at, features, coverage)
  SELECT 1, :'source', :'cut_at'::timestamptz, NOW(), COUNT(*),
    ST_MakeEnvelope(split_part(:'box', ',', 1)::float8, split_part(:'box', ',', 2)::float8,
      split_part(:'box', ',', 3)::float8, split_part(:'box', ',', 4)::float8, 4326)
  FROM wizard_osm_features
  ON CONFLICT (id) DO UPDATE SET source = EXCLUDED.source, extract_at = EXCLUDED.extract_at,
    loaded_at = EXCLUDED.loaded_at, features = EXCLUDED.features, coverage = EXCLUDED.coverage;
COMMIT;
