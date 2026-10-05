#!/bin/bash

# Get the certificate for openstreetmap
openssl s_client -showcerts -connect "www.openstreetmap.org:443" -servername www.openstreetmap.org </dev/null | sed -ne '/-BEGIN CERTIFICATE-/,/-END CERTIFICATE-/p' > ../conf/osm.pem
keytool -delete -alias www.openstreetmap.org -keystore ../conf/osmcacerts -storepass openstreetmap
keytool -importcert -noprompt -trustcacerts -alias www.openstreetmap.org -file ../conf/osm.pem -keystore ../conf/osmcacerts -storepass openstreetmap

# Get the certificate for dev openstreetmap servers
openssl s_client -showcerts -connect "master.apis.dev.openstreetmap.org:443" -servername master.apis.dev.openstreetmap.org </dev/null | sed -ne '/-BEGIN CERTIFICATE-/,/-END CERTIFICATE-/p' > ../conf/osm_dev.pem
keytool -delete -alias master.apis.dev.openstreetmap.org -keystore ../conf/osmcacerts_dev -storepass openstreetmap
keytool -importcert -noprompt -trustcacerts -alias master.apis.dev.openstreetmap.org -file ../conf/osm_dev.pem -keystore ../conf/osmcacerts_dev -storepass openstreetmap
