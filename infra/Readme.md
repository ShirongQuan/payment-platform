- How to test whether containers are ready?

How to test Redis

1. Check container is healthy
   bash
   docker compose ps
2. Ping Redis from inside the container
   bash
   docker exec -it payment-platform-redis redis-cli PING
   Expected result:

Text
PONG

3. Write and read a test key
   bash
   docker exec -it payment-platform-redis redis-cli SET test-key hello
   docker exec -it payment-platform-redis redis-cli GET test-key
   Expected result for GET:

Text
hello
Using redis-cli inside the container is directly aligned with Redis’s Docker guidance. (redis.io)

How to test Prometheus

1. Open the UI
   Go to:

Text
http://localhost:9090
Prometheus’s Docker image exposes the web UI on port 9090 by default, and it reads configuration from the provided
config file. (prometheus.io)

2. Confirm targets are up
   Open:

Text
http://localhost:9090/targets
You should see at least the prometheus target as UP if the config mounted correctly. Prometheus uses the configuration
file to define scrape targets. (prometheus.io)

3. Run a basic query
   In the Prometheus UI, query:

Text
up
Expected:

prometheus should return value 1
later your Spring Boot services will also appear there when you add actuator metrics and scrape config. Spring Boot
exposes Prometheus-formatted metrics through the actuator Prometheus endpoint when configured. (docs.spring.io)

How to test Grafana

1. Open Grafana
   Go to:

Text
http://localhost:3000
Grafana’s Docker docs use port 3000 by default, and the default login is admin/admin unless you override it, which this
compose file does explicitly to the same values. (grafana.com)

2. Log in
   username: admin
   password: admin
3. Add Prometheus as a data source
   Inside Grafana:

Connections / Data Sources
Add data source
Choose Prometheus
URL:
Text
http://prometheus:9090
Because Grafana and Prometheus are on the same Compose network, prometheus is the correct hostname, similar to using
service names like postgres between containers. Prometheus’s config model and Docker deployment support container-based
networking like this. (prometheus.io)

4. Click “Save & test”
   Expected result:

Grafana says the data source is working

export GRAFANA_ADMIN_USER="admin"
export GRAFANA_ADMIN_PASSWORD="password"
docker compose -f infra/docker/docker-compose.yml up -d grafana

How to test logs if something fails
Use:

bash
docker compose logs redis
docker compose logs prometheus
docker compose logs grafana
Or stream all:

bash
docker compose logs -f

// create Kafka topic  
chmod +x kafka/create-topics.sh 