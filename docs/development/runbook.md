* DB
  docker exec -it payment-platform-postgres bash
  psql -U postgres -d postgres

  \l -- list databases
  \c auth_db -- switch database
  \dt -- list tables in current DB
  SELECT now();
  \q

redis:

docker exec -it redis redis-cli
SELECT 1
keys fraud:ip:*

auth-service networking note:

- `server.forward-headers-strategy=framework` is enabled.
- Deploy behind a trusted proxy/load balancer that strips inbound `X-Forwarded-*` headers from clients and sets sanitized values.
