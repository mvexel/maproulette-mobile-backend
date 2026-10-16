# --- MapRoulette Scheme

# --- !Ups

-- Campaigns made by the campaign wizard (fork only): which question set and version made the
-- challenge, the search area and the event date the share card shows. Creation is also recorded in
-- mobile_admin_audit as campaign.create.
CREATE TABLE IF NOT EXISTS mobile_campaign_sources (
  challenge_id bigint PRIMARY KEY REFERENCES challenges(id) ON DELETE CASCADE,
  set_id text NOT NULL,
  set_version integer NOT NULL,
  place text NOT NULL,
  lat double precision NOT NULL,
  lon double precision NOT NULL,
  radius integer NOT NULL,
  event_date date,
  left_out integer NOT NULL DEFAULT 0,
  created_by bigint NOT NULL,
  created_at timestamptz NOT NULL DEFAULT NOW()
);;

# --- !Downs
DROP TABLE IF EXISTS mobile_campaign_sources;;
