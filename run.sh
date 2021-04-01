#!/usr/bin/env bash

set -euo pipefail

service=$1
default_branch=$2
migrations_directory=$3

git config --global user.name "ordnungsamt"
git config --global user.email "order-department@not-real.com"

java -jar /ordnungsamt.jar "$service" "$default_branch" "$migrations_directory"
