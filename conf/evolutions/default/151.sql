# --- MapRoulette Scheme

# --- !Ups

-- Organizer requests (fork only, ORG-ONB, design/organizer-onboarding.md): a signed-in OSM user asks
-- to run campaigns, and a super-user approves (a new project, Write on it, the organizer list) or
-- declines with an optional note. One open request per user. The OSM account's creation date and
-- changeset count are a snapshot taken when the request is made. A request turns void when its
-- organizer is removed, so an old approval is never reused. Decisions are recorded in
-- mobile_admin_audit.
CREATE TABLE IF NOT EXISTS mobile_organizer_requests (
  id bigserial PRIMARY KEY,
  user_id bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  place text NOT NULL CHECK (char_length(place) BETWEEN 1 AND 200),
  subject text NOT NULL CHECK (char_length(subject) BETWEEN 1 AND 200),
  group_name text CHECK (group_name IS NULL OR char_length(group_name) BETWEEN 1 AND 200),
  policy_accepted_at timestamptz NOT NULL,
  osm_account_created timestamptz,
  osm_changesets integer CHECK (osm_changesets IS NULL OR osm_changesets >= 0),
  state text NOT NULL DEFAULT 'open' CHECK (state IN ('open', 'approved', 'declined', 'void')),
  created_at timestamptz NOT NULL DEFAULT NOW(),
  decided_by bigint REFERENCES users(id) ON DELETE SET NULL,
  decided_at timestamptz,
  note text CHECK (note IS NULL OR char_length(note) BETWEEN 1 AND 1000),
  project_id bigint REFERENCES projects(id) ON DELETE SET NULL,
  CHECK ((state = 'open') = (decided_at IS NULL))
);;

CREATE UNIQUE INDEX IF NOT EXISTS mobile_organizer_requests_open
  ON mobile_organizer_requests(user_id) WHERE state = 'open';;
CREATE INDEX IF NOT EXISTS mobile_organizer_requests_user
  ON mobile_organizer_requests(user_id, created_at DESC);;

# --- !Downs
DROP TABLE IF EXISTS mobile_organizer_requests;;
