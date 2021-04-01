#!/usr/bin/env bash

set -euo pipefail

service=$1
default_branch=$2
migrations_directory=$3

java -jar /ordnungsamt.jar "$service" "$default_branch" "$migrations_directory"
