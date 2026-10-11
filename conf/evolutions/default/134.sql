# --- MapRoulette Scheme

# --- !Ups

-- The answers of a done choice submission, {"<question id>": "<option id>"}, for the campaign
-- results export. NULL for outcomes and for submissions recorded before this column existed.
ALTER TABLE mobile_choice_submissions ADD COLUMN answers jsonb;;

# --- !Downs

ALTER TABLE mobile_choice_submissions DROP COLUMN IF EXISTS answers;;
