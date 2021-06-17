#!/bin/bash

set -euo pipefail

dir=$(pwd)

cd examples/migrations/rename-things
clojure -m migration "$dir"
