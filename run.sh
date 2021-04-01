#!/usr/bin/env bash

set -euo pipefail

service=$1
default_branch=$2
migrations_directory=$3

cd src/ordnungsamt; clojure -m core "$service" "$default_branch" "$migrations_directory"
