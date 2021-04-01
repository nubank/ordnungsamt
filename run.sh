#!/usr/bin/env bash

set -euo pipefail

service=$1
default_branch=$2
migrations_directory=$3

ls

pwd

# java -jar /ordnungsamt.jar "$service" "$default_branch" "$migrations_directory"
java -jar /ordnungsamt.jar "$service" "$default_branch" service-migrations
