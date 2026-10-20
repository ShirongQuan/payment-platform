#!/usr/bin/env bash

cd ../docker || exit

echo "Payment Platform — Infrastructure Status"
echo "---------------------------------"

# docker compose ps --format json prints one JSON object per line (NDJSON),
# so we slurp (-s) it into an array before processing with jq.
# --all is needed so that one-shot containers (e.g. kafka-init) that have
# already exited are still included in the output.
docker compose ps --all --format json \
  | jq -s -r '(["CONTAINER", "STATUS"] | @tsv),
              (.[] | [.Name,
                      (if .Health != "" and .Health != null then
                         .Health
                       elif .State == "running" then
                         "running"
                       else
                         .State
                       end)] | @tsv)' \
  | column -t -s $'\t'

echo "---------------------------------"


