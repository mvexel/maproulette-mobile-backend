# --- MapRoulette Scheme

# --- !Ups

-- Deleting a user deletes the guest they claimed. The person who claimed owns those answers, so
-- deleting the account removes the guest record with its tokens and links, as "delete my data"
-- (API plan 3.8) does for an unclaimed guest. Their choice_pending rows keep their task and
-- outcome without a guest (guest_id ON DELETE SET NULL in 137), like expired rows after a purge.
-- Before this, 135 had no ON DELETE action here, so deleting a user who claimed a guest failed.
ALTER TABLE mobile_guests DROP CONSTRAINT IF EXISTS mobile_guests_claimed_user_id_fkey;;
ALTER TABLE mobile_guests ADD CONSTRAINT mobile_guests_claimed_user_id_fkey
  FOREIGN KEY (claimed_user_id) REFERENCES users(id) ON DELETE CASCADE;;

# --- !Downs
ALTER TABLE mobile_guests DROP CONSTRAINT IF EXISTS mobile_guests_claimed_user_id_fkey;;
ALTER TABLE mobile_guests ADD CONSTRAINT mobile_guests_claimed_user_id_fkey
  FOREIGN KEY (claimed_user_id) REFERENCES users(id);;
