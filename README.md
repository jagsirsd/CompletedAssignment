# demo

Spring Boot 3.2 / Java 17 service exposing a paginated, cached CRUD API for `Item`
resources backed by PostgreSQL, with a CQRS-style read/write split driven by Kafka
events. See [DECISIONS.md](DECISIONS.md) for why it's built this way and how to
reconfigure the caching/CQRS strategy.

## Stack

- Spring Boot 3.2 (Web, Data JPA, Cache, Actuator)
- PostgreSQL 15
- Redis 7 or Caffeine (in-process) for caching — configurable, see DECISIONS.md
- Apache Kafka (via Confluent images) for event publishing and read-side refresh
- Maven (wrapper included, no local Maven install required)

## Prerequisites

- JDK 17
- Docker and Docker Compose (for running dependencies, or the whole stack)

## Running locally

### Option A: Full stack via Docker Compose

Builds the app image and starts it alongside Postgres, Redis, Zookeeper, and Kafka:

```bash
docker compose up --build
```

The gRPC API is then available at `localhost:9090`; the REST API and Actuator
(health/info/metrics) are at `http://localhost:8080`.

### Option B: App on host, dependencies in Docker

Start only the infrastructure:

```bash
docker compose up db redis zookeeper kafka
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
| Datasource URL | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/mydb` |
| Datasource username | `SPRING_DATASOURCE_USERNAME` | `user` |
| Datasource password | `SPRING_DATASOURCE_PASSWORD` | `password` |
| Kafka bootstrap servers | `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| Redis host/port | `SPRING_DATA_REDIS_HOST` / `_PORT` | `localhost` / `6379` |

The caching backend and CQRS read-model depth are also configured here
(`app.cache.type`, `app.cqrs.read-mode`) — see [DECISIONS.md](DECISIONS.md) for the full
reference and the tradeoffs behind each option.

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

Writes from either API land in PostgreSQL and are captured off its WAL by Debezium (Kafka
Connect), published to the `mydb.public.items` Kafka topic, and consumed by
`cdc/CdcEventConsumer.java`, which refreshes the Redis-backed read side
(`readmodel/RedisCacheItemReadStore.java`) asynchronously — see
[DECISIONS.md](DECISIONS.md) for the consistency tradeoff this implies.

### Load testing

`scripts/live-load.sh` (gRPC) and `scripts/live-load-rest.sh` (REST) each drive one
persistent, connection-reusing client — `GrpcLoadClient`/`RestLoadClient`
(`src/main/java/com/example/demo/loadtest/`) — instead of spawning a new client process
per request. `db.write.duration` is tagged by `transport` (`grpc`/`rest`) so the two APIs'
write latency can be compared directly on the Grafana dashboard's "REST vs gRPC
Comparison" row.

## CI

`.github/workflows/build.yml` runs `./mvnw -s maven-settings.xml -B verify` with JDK 17 on
every push and pull request to `master`, and uploads Surefire test reports as a build
artifact.
