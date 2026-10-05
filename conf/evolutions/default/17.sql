# --- MapRoulette Scheme

# --- !Ups
-- We need to reset all the API Keys
UPDATE users SET api_key = null;;

# --- !Downs
