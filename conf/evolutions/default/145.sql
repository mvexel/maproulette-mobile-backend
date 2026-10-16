# --- MapRoulette Scheme

# --- !Ups

-- Admin app memberships (fork only, D8): who may hold a mobile:organize grant. One audited table
-- for every scoped admin role (BACKLOG A1), with organizer the first role. Which projects an organizer
-- runs comes from upstream project grants, not from here. Adding and removing rows is recorded in
-- mobile_admin_audit.
CREATE TABLE IF NOT EXISTS mobile_memberships (
  user_id bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role text NOT NULL CHECK (role IN ('organizer')),
  granted_by bigint NOT NULL,
  created_at timestamptz NOT NULL DEFAULT NOW(),
  PRIMARY KEY (user_id, role)
);;

# --- !Downs
DROP TABLE IF EXISTS mobile_memberships;;
