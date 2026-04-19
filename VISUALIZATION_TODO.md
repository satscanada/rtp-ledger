# VISUALIZATION_TODO.md — End-to-End Flow Visualization

Feature branch: `feature/flow-visualization`

Goal: visualize metadata-only lifecycle for posting flow:
client -> NATS -> server/LMAX -> Chronicle Queue -> DB -> reply.

## Backlog

- [x] VIZ-01 Shared trace envelope in `shared` (stage enum + event record)
- [x] VIZ-02 Client trace emission at HTTP ingress + client LMAX publish
- [x] VIZ-03 Server trace emission at subscriber + server LMAX handler
- [x] VIZ-04 Drainer trace emission for DB flush commit/failure
- [x] VIZ-05 Client trace collector service + read APIs
- [x] VIZ-06 Stage-level Micrometer metrics (no correlation-id labels)
- [x] VIZ-07 Grafana dashboard row/panels for flow stages
- [x] VIZ-08 Validation checklist and STOP GATE notes

## STOP GATE per item

Each item should be implemented and verified independently before moving to the next.

## VIZ-08 Validation Checklist

- [x] Compile checks pass:
  - `mvn -pl shared -am compile`
  - `mvn -pl client -am compile`
  - `mvn -pl server -am compile`
- [x] Dashboard JSON syntax valid:
  - `python3 -m json.tool infra/grafana/dashboards/rtp-ledger.json`
- [x] Runtime trace flow verification:
  - [x] POST transaction returned `202` with correlationId `ee515add-850e-4312-8028-14416ee9c336`
  - [x] `GET /api/v1/ledger/trace/{correlationId}` returned full stage timeline including `DRAINER_BATCH_FLUSH_OK`
  - [x] `/api/v1/ledger/trace/recent` returned latest timelines with top stage `DRAINER_BATCH_FLUSH_OK`
  - [x] Prometheus trace counters present (`rtp_client_trace_events_total=6`, `rtp_server_trace_events_total=7`) for Grafana Row 4 queries
