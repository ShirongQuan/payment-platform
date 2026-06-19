# Grafana Git-Managed Dashboards

This folder stores Grafana provisioning files and dashboard JSON files so dashboard changes can be committed to git.

## Structure

- `provisioning/datasources/prometheus.yml`: provisions Prometheus datasource.
- `provisioning/dashboards/dashboards.yml`: tells Grafana where dashboard JSON files are.
- `dashboards/*.json`: dashboard definitions tracked in git.
- `scripts/export-dashboard.sh`: export a dashboard from a running Grafana to JSON.

## How it works

`docker-compose.yml` mounts this folder into the Grafana container:

- `/etc/grafana/provisioning`
- `/var/lib/grafana/dashboards`

On startup, Grafana loads datasource and dashboards from these files.

## Update workflow (recommended)

1. Edit dashboard in Grafana UI.
2. Export dashboard JSON to this repo folder.
3. Commit the JSON change to git.
4. Restart Grafana to reload if needed.

Example export:

```bash
./infra/grafana/scripts/export-dashboard.sh service-overview ./infra/grafana/dashboards/service-overview.json
```

