
* DB
docker exec -it payment-platform-postgres bash
psql -U postgres -d postgres

  \l          -- list databases
  \c auth_db  -- switch database
  \dt         -- list tables in current DB
  SELECT now();
  \q