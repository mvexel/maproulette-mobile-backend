# --- !Ups
CREATE TABLE IF NOT EXISTS user_basemaps(
  id SERIAL NOT NULL PRIMARY KEY,
  created timestamp without time zone DEFAULT NOW(),
  modified timestamp without time zone DEFAULT NOW(),
  user_id INTEGER NOT NULL,
  url VARCHAR NOT NULL,
  name VARCHAR NOT NULL,
  overlay BOOLEAN DEFAULT FALSE,
  CONSTRAINT user_basemaps_user_id FOREIGN KEY (user_id)
    REFERENCES users(id) MATCH SIMPLE
    ON UPDATE CASCADE ON DELETE CASCADE
);;

SELECT create_index_if_not_exists('user_basemaps', 'user_basemaps_user', '(user_id)');;

-- copy existing user custom_basemap_url into new table
INSERT INTO user_basemaps (user_id, url, name)
SELECT id, custom_basemap_url, 'Custom'
FROM users WHERE custom_basemap_url IS NOT NULL AND custom_basemap_url != '';;

ALTER TABLE users
  DROP COLUMN custom_basemap_url;;

# --- !Downs

-- read add custom_basemap_url column
ALTER TABLE users
  ADD COLUMN custom_basemap_url VARCHAR;;

-- copy custom basemap urls back into old column
UPDATE users u SET custom_basemap_url = user_basemaps.url
FROM user_basemaps
WHERE u.id = user_basemaps.user_id AND user_basemaps.name = 'Custom' AND user_basemaps.url != '';;

-- Drop table user_basemaps
DROP TABLE user_basemaps;;
