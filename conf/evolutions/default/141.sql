# --- MapRoulette Scheme

# --- !Ups

-- Live progress page (fork only, BACKLOG #12): one link-only page per campaign, reached by an
-- unguessable token rather than the challenge id. Rotating the token breaks the old link, and
-- turning the page off keeps the token but answers "turned off". Additive: one new table.
CREATE TABLE mobile_live_pages (
  challenge_id bigint PRIMARY KEY REFERENCES challenges(id) ON DELETE CASCADE,
  token        text NOT NULL UNIQUE,
  enabled      boolean NOT NULL DEFAULT true,
  -- Per-stop answers on the shared page. Off leaves them out of the response, not just the UI.
  show_answers boolean NOT NULL DEFAULT true,
  -- "People tonight" counts distinct savers since this time, and there is no count without it.
  event_start  timestamptz,
  -- Set: the page shows final totals and the date, with no recent or people counts.
  ended_at     timestamptz,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now()
);;

# --- !Downs
DROP TABLE IF EXISTS mobile_live_pages;;
