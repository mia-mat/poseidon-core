# Poseidon
A lightweight automated deployment server built with Spring Boot to manage Docker containers for all my services.

Poseidon is a deployment service, not a full CI/CD pipeline. Testing, building, and publishing are handled upstream.   

Simply, Poseidon receives webhook events from GitHub Actions, pulls images from GHCR, and (re)deploys containers when necessary.

## Phoenix Integration
Poseidon is closely integrated with my dynamic reverse proxy, Phoenix, allowing for automatic configuration of routing by Phoenix via Poseidon.

Dockerfile labels may be specified to configure deployment behaviour, and to pass onto Phoenix to update its route store:

| Label                               | Description                                                            |
|-------------------------------------|------------------------------------------------------------------------|
| `internal-port`                     | The port the container listens on internally to forward                |
| `phoenix.source`                    | The hostname/source for the Phoenix route                              |
| `phoenix.alias` / `phoenix.aliases` | Additional aliases for the Phoenix route                               |
| `phoenix.self`                      | Set to `true` if this image *is* Phoenix itself (uses a reserved port) |

If `internal-port` is set, Poseidon picks a random external port in the range 20000–40000 (collision-safe) and binds it. If no port label is present, the container is deployed without port binding and any existing Phoenix route is removed.


## Environment variables

| Variable                | Required | Description                                                           |
|-------------------------|----------|-----------------------------------------------------------------------|
| `DEPLOY_WEBHOOK_SECRET` | Yes      | Shared secret for verifying GitHub webhook signatures                 |
| `GHCR_USERNAME`         | Yes      | GitHub username for authenticating with GHCR                          |
| `GHCR_TOKEN`            | Yes      | GitHub token (PAT or Actions token) for pulling images                |
| `PHOENIX_URL`           | Yes      | Base URL of Phoenix instance                                          |
| `PHOENIX_AUTH_TOKEN`    | No       | Auth token for Phoenix, if configured                                 |
| `SECRETS_DIR`           | No       | Directory containing per-container `.env` files (default: `/secrets`) |

## Per-container secrets
If a file named `<container-name>.env` exists in `SECRETS_DIR`, Poseidon will inject those environment variables into the container at deploy time.  
A `.env` file is formatted as such: one `KEY=VALUE` per line, lines starting with `#` are ignored.

## Java API and Model
Model classes and a handy Java API client for Poseidon can be found at [poseidon-api](https://gh.mia.ws/poseidon-api)

## REST API

| Endpoint                       | Method | Description                                   |
|--------------------------------|--------|-----------------------------------------------|
| `/deploy`                      | `POST` | GitHub webhook target - triggers a deployment |
| `/api/version`                 | `GET`  | Returns the running Poseidon version          |
| `/api/containers`              | `GET`  | Lists all containers on the Docker host       |
| `/api/containers/event-stream` | `GET`  | SSE stream of container state changes         |