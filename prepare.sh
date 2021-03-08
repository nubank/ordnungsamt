#!/usr/bin/env bash

eval $(ssh-agent -s) && ssh-add -k ~/.ssh/id_rsa
clojure -Spom
clojure -A:build-uberjar
