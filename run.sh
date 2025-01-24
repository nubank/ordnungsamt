#!/usr/bin/env bash

set -euo pipefail

org=$1
service=$2
default_branch=$3
service_directory=$4
migrations_directory=$5

clojure -J-Djavax.net.debug=all --report stderr -m ordnungsamt.core "$org" "$service" "$default_branch" "$service_directory" "$migrations_directory"
# clojure --report stderr -m ordnungsamt.core "$org" "$service" "$default_branch" "$service_directory" "$migrations_directory"
