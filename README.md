# demo (ScyllaDB variant)

Spring Boot 3.2 / Java 17 service exposing a paginated, cached CRUD API for `Item`
resources, backed by **ScyllaDB** instead of PostgreSQL. This branch swaps the datastore
underneath the same REST/gRPC transport layer used elsewhere in this repo's history — see
[DECISIONS.md](DECISIONS.md) for why, and for an important gap: **there is no CDC pipeline
on this branch** (Scylla's native CDC is a different integration than Debezium/Postgres
entirely, out of scope for this pass). The Redis read model is populated by a direct,
synchronous write from the command path — not eventually-consistent CDC replay.

## Stack

- Spring Boot 3.2 (Web, Data Cassandra, Cache, Actuator)
- ScyllaDB (single-node dev config) via Spring Data Cassandra (CQL-wire-compatible)
- Redis 7 for the read-model cache
- Maven (wrapper included, no local Maven install required)

## Prerequisites

- JDK 17
- Docker and Docker Compose (for running dependencies, or the whole stack)

## Running locally

### Option A: Full stack via Docker Compose

Builds the app image and starts it alongside Scylla and Redis. `scylla-init` creates the
keyspace/table once Scylla is healthy, before `app` starts (Spring Boot's Cassandra
auto-configuration doesn't create keyspaces itself):

```bash
docker compose up --build
```

The gRPC API is then available at `localhost:9090`; the REST API and Actuator
(health/info/metrics) are at `http://localhost:8080`.

### Option B: App on host, dependencies in Docker

Start only the infrastructure:

```bash
docker compose up scylla-init redis
```

Then run the app with the wrapper:

```bash
./mvnw spring-boot:run
```

## Building and testing

```bash
./mvnw -s maven-settings.xml verify
```

`maven-settings.xml` points dependency resolution at public Maven Central and is used by
both local builds and CI. `verify` compiles the code and runs the test suite
(`src/test/java`).

## Configuration

Configuration lives in `src/main/resources/application.yml`. Connection details default
to local values and can be overridden via environment variables (used by
`docker-compose.yml`):

| Property | Env var | Default |
|---|---|---|
| Cassandra/Scylla contact points | `SPRING_CASSANDRA_CONTACT_POINTS` | `localhost` |
| Cassandra/Scylla port | `SPRING_CASSANDRA_PORT` | `9042` |
| Local datacenter | `SPRING_CASSANDRA_LOCAL_DATACENTER` | `datacenter1` |
| Keyspace | `SPRING_CASSANDRA_KEYSPACE_NAME` | `demo` |
| Redis host/port | `SPRING_DATA_REDIS_HOST` / `_PORT` | `localhost` / `6379` |

Actuator health and info endpoints are exposed at `/actuator/health` and `/actuator/info`.

## API

Item CRUD is available over **both gRPC and REST**, calling the same
`ItemCommandService`/`ItemReadStore` layers underneath — the transport is just how a
request arrives, not a difference in behavior or consistency guarantees.

### gRPC

Defined in [`src/main/proto/item.proto`](src/main/proto/item.proto), served on port
`9090` with server reflection enabled (so tools like `grpcurl` don't need the `.proto`
file).

| RPC | Description |
|---|---|
| `ItemService/CreateItem` | Create an item |
| `ItemService/GetItem` | Get an item by id, `NOT_FOUND` status if missing, served from cache when available |
| `ItemService/ListItems` | Paginated list (`page`/`size`/`sort`, default size 20, max 100) |
| `ItemService/DeleteItem` | Delete an item, `NOT_FOUND` status if missing |

```bash
grpcurl -plaintext -d '{"name":"widget","description":"demo"}' \
  localhost:9090 item.v1.ItemService/CreateItem
```

### REST

Base path `/api/items` on port `8080`, alongside Actuator. The embedded Tomcat connector
has HTTP/2 cleartext (h2c) enabled (`config/Http2Config.java`) — a client that requests
HTTP/2 gets a persistent, multiplexed connection instead of one-connection-per-request,
matching gRPC's transport characteristics rather than plain HTTP/1.1 keep-alive.

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/items?page=0&size=20&sort=id,desc` | Paginated list (default size 20, max 100) |
| `GET` | `/api/items/{id}` | Get an item by id, `404` if missing, served from cache when available |
| `POST` | `/api/items` | Create an item |
| `DELETE` | `/api/items/{id}` | Delete an item, `404` if missing |

Writes from either API land in ScyllaDB, then `ItemCommandService` synchronously pushes
the same change into the Redis read side (`readmodel/RedisCacheItemReadStore.java`)
directly — there is no CDC pipeline on this branch. See [DECISIONS.md](DECISIONS.md) for
what that means and why.

### Load testing

`scripts/live-load.sh` (gRPC) and `scripts/live-load-rest.sh` (REST) each drive one
persistent, connection-reusing client — `GrpcLoadClient`/`RestLoadClient`
(`src/main/java/com/example/demo/loadtest/`) — instead of spawning a new client process
per request. `db.write.duration` is tagged by `transport` (`grpc`/`rest`) so the two APIs'
write latency can be compared directly on the Grafana dashboard's "REST vs gRPC
Comparison" row.

## CI

**Known gap on this branch**: `.github/workflows/build.yml`'s `e2e` job still references
`db`/`kafka`/`kafka-connect` — services this branch's `docker-compose.yml` no longer
defines — and its verification steps assume the Postgres/Debezium CDC pipeline. It will
fail as written. Not rewritten in this pass since it wasn't the priority (getting a working,
benchmarkable Scylla-backed system was); flagging it here rather than leaving it silently
broken. The `build` job (`./mvnw -s maven-settings.xml -B verify`) is unaffected.
