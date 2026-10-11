# Monitoring: Prometheus on the server, Grafana on your machine

Production runs **Prometheus** (7 days or 512 MB of samples, 256 MiB memory limit) and **node-exporter**
next to Signals. Prometheus scrapes the backend (`/actuator/prometheus`), the Signals Agent (`/metrics`)
and the host every 15 seconds. Its port 9090 is bound to the server's loopback interface only: do not open
it in the firewall. You view it from **Grafana on your own machine** through an SSH tunnel.

## Open the dashboard

1. Start the tunnel and leave it running (replace the key and host with your deployment's):

   ```sh
   ssh -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 \
     -L 127.0.0.1:19090:127.0.0.1:9090 -i ~/.ssh/<deploy-key> ubuntu@<server>
   ```

   `http://127.0.0.1:19090/targets` should now list `backend`, `agent`, `node` and `prometheus` as UP.

2. Start Grafana (first time: `cp .env.example .env` and set `GRAFANA_ADMIN_PASSWORD`):

   ```sh
   docker compose -f docker-compose.grafana.yml up -d
   ```

3. Open `http://127.0.0.1:3010`, sign in, and choose **Signals › Signals Overview**.

Stopping the tunnel only stops Grafana's queries; Prometheus keeps collecting on the server.

## What the dashboard shows

| Section | Panels |
|---|---|
| Signals Agent | questions and failure rate in the selected range, P95/P50 request time, model and tool P95, Gemini tokens by kind (input, output, thinking, cached input), tool calls, answer references (supported vs unsupported), incomplete evidence coverage |
| Backend API | requests per second, P95 latency per endpoint, 4xx/5xx responses, JVM heap |
| Host | CPU, memory and swap, root disk, scrape target health |

Gemini token counts come from the API's usage metadata. Use Google AI Studio for billing.

## Check resource use on the server

```sh
docker stats --no-stream $(docker ps -q --filter name=prometheus) $(docker ps -q --filter name=node-exporter)
free -h
```
