#!/usr/bin/env bash

set -euo pipefail

cur_dir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" > /dev/null && pwd )"

service=$1
default_branch=$2
migrations_directory=$3

cd $cur_dir/src/ordnungsamt; clojure -m core "$service" "$default_branch" "$migrations_directory"
