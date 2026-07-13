#!/bin/sh
set -e

BOOTSTRAP_SERVER="kafka-1:9092"

echo "Creating Kafka topics..."

/opt/kafka/bin/kafka-topics.sh --bootstrap-server ${BOOTSTRAP_SERVER} \
  --create --if-not-exists \
  --topic auth.events \
  --partitions 6 \
  --replication-factor 3

/opt/kafka/bin/kafka-topics.sh --bootstrap-server ${BOOTSTRAP_SERVER} \
  --create --if-not-exists \
  --topic auth.events.ledger.dlt \
  --partitions 6 \
  --replication-factor 3

echo "Kafka topics created or already exist."

echo "Listing topics..."
/opt/kafka/bin/kafka-topics.sh --bootstrap-server ${BOOTSTRAP_SERVER} --list