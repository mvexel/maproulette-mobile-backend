# --- MapRoulette Scheme

# --- !Ups

-- One row per database. New deployments start with mobile task writes off.
CREATE TABLE mobile_write_policy (
    id integer PRIMARY KEY CHECK (id = 1),
    enabled boolean NOT NULL DEFAULT false,
    updated_by bigint,
    updated_at timestamptz NOT NULL DEFAULT NOW()
);;
INSERT INTO mobile_write_policy (id, enabled) VALUES (1, false);;

# --- !Downs

DROP TABLE mobile_write_policy;;
