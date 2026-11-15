# Bazar.com: a multi-tier online book store

DOS Lab 1. Bazar.com sells four books through three small Java (Spark)
microservices that talk to each other over HTTP/REST and return JSON.

| Service  | Port | Job |
|----------|------|-----|
| frontend | 4568 | Public entry point: `search`, `info`, `purchase`. Also serves a small web page. |
| catalog  | 4567 | Owns the books (title, topic, price, stock). Query and update. |
| order    | 4569 | Owns the order log. `purchase` asks the catalog to sell one copy. |

The design, trade-offs and known limitations are in
[docs/design.md](docs/design.md). Sample output of a full run is in
`docs/client-output.txt` (what the client sees) and `docs/server-logs.txt`
(what the three containers printed).

## Run it with Docker (recommended)

```bash
docker compose up --build
```

Then open <http://localhost:4568> in a browser, or use curl:

```bash
curl http://localhost:4568/search/distributed%20systems
curl http://localhost:4568/info/2
curl -X POST http://localhost:4568/purchase/2
```

Stop with `docker compose down`. Data lives in `./data` (mounted into the
containers), so it survives restarts. `git checkout data` restores the
original catalog.

## Run without Docker

Needs JDK 25 and Maven.

```bash
mvn package -DskipTests
java -cp target/catalog-service-1.0-SNAPSHOT.jar org.example.CatalogService &
java -cp target/catalog-service-1.0-SNAPSHOT.jar org.example.OrderService &
java -cp target/catalog-service-1.0-SNAPSHOT.jar org.example.FrontendService &
```

## Run the tiers on different machines

Each service is configured through environment variables:

| Variable | Used by | Default | Meaning |
|----------|---------|---------|---------|
| `PORT` | all | 4567 / 4568 / 4569 | listening port |
| `DATA_DIR` | catalog, order | `data` | folder for `books.json` / `orders.json` |
| `CATALOG_URL` | order, frontend | `http://catalog:4567` (order), `http://localhost:4567` (frontend) | where the catalog runs |
| `ORDER_URL` | frontend | `http://localhost:4569` | where the order service runs |

Example: catalog on 10.0.0.2, order on 10.0.0.3, frontend on 10.0.0.1:

```bash
# machine 10.0.0.2
java -cp app.jar org.example.CatalogService
# machine 10.0.0.3
CATALOG_URL=http://10.0.0.2:4567 java -cp app.jar org.example.OrderService
# machine 10.0.0.1
CATALOG_URL=http://10.0.0.2:4567 ORDER_URL=http://10.0.0.3:4569 \
  java -cp app.jar org.example.FrontendService
```

## REST API

Frontend (what a client uses):

| Request | Reply |
|---------|-------|
| `GET /search/<topic>` | `[{"id":1,"title":"..."}, ...]` |
| `GET /search?topic=<topic>` | full book records (used by the web page) |
| `GET /info/<id>` | `{"title","quantity","price"}` plus `id`, `topic`, `stock` |
| `POST /purchase/<id>` | `201` + order, `404` unknown book, `409` out of stock |
| `PUT /catalog/books/<id>/price` | body `{"price": 12.5}` |
| `PUT /catalog/books/<id>/stock` | body `{"stock": 10}` to set, or `{"delta": 5}` / `{"delta": -2}` |

Catalog (`:4567`) offers the same `search`, `info`, price and stock calls plus
`GET /catalog/books` and `POST /catalog/books/<id>/purchase`. Order (`:4569`)
offers `POST /purchase/<id>` and `GET /orders`.

## Tests

With the system running:

```bash
bash tests/smoke-test.sh
```

It exercises every endpoint, the error cases, and 20 parallel purchases of a
book with 5 copies (exactly 5 must succeed). It changes stock and orders, so
run `git checkout data` afterwards.
