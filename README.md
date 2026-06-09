# Poseidon
A lightweight automated deployment server built with Spring Boot to manage Docker containers for all my services.

Poseidon is a deployment service, not a full CI/CD pipeline. Testing, building, and publishing are handled upstream.

Simply, Poseidon receives webhook events from GitHub Actions, pulls images from GHCR, and (re)deploys containers when necessary.

## Quickstart

Add a workflow file at `.github/workflows/` in your repository.
My reusable workflow is available at [`mia-mat/.github`](https://gh.mia.ws/.github/blob/master/.github/workflows/poseidon-deploy.yml). A simple setup may just call it:

```yaml
name: Deploy

on:
  push:
    branches: [ master ]

jobs:
  deploy:
    uses: mia-mat/.github/.github/workflows/poseidon-deploy.yml@master
    secrets: inherit
    permissions:
      contents: read
      packages: write
```

The workflow expects `POSEIDON_SECRET` and `POSEIDON_URL` to be available as secrets, where `POSEIDON_SECRET` corresponds to the `DEPLOY_WEBHOOK_SECRET` environment variable on the Poseidon instance.  
These need to be set at the repository or organisation level,  


To configure port binding and Phoenix routing, add labels to a Dockerfile at the root of your repository:

```dockerfile
LABEL internal-port="8080"
LABEL phoenix.source="app.mia.ws"
```

See [Phoenix Integration](#phoenix-integration) for all available labels.

## Deployment Payload

The `/deploy` endpoint accepts a `POST` with a JSON body signed using HMAC-SHA256 (see [Webhook Security](#webhook-security)).

```json
{
  "image": "ghcr.io/mia-mat/repo:master-abc1234",
  "ref": "refs/heads/master",
  "branch": "master",
  "repository": "mia-mat/repo",
  "repositoryId": "123456789",
  "repositoryOwner": "mia-mat",
  "repositoryOwnerId": "65930826",
  "repositoryName": "repo",
  "repositoryUrl": "git://github.com/mia-mat/repo.git"
}
```

All fields are strings and required.

## Webhook Security

Requests to `/deploy` must include an `X-Hub-Signature-256` header containing an HMAC-SHA256 signature of the raw request body, keyed with `DEPLOY_WEBHOOK_SECRET`:

```
X-Hub-Signature-256: sha256=<hex digest>
```

## Phoenix Integration
Poseidon is closely integrated with a dynamic reverse proxy, [Phoenix](https://gh.mia.ws/phoenix-core), allowing for automatic configuration of routing by Phoenix via Poseidon.

Dockerfile labels may be specified to configure deployment behaviour, and to pass onto Phoenix to update its route store:

| Label                               | Description                                                            |
|-------------------------------------|------------------------------------------------------------------------|
| `internal-port`                     | The port the container listens on internally to forward                |
| `phoenix.source`                    | The hostname/source for the Phoenix route                              |
| `phoenix.alias` / `phoenix.aliases` | Additional aliases for the Phoenix route                               |
| `phoenix.self`                      | Set to `true` if this image *is* Phoenix itself (uses a reserved port) |

If `internal-port` is set, Poseidon picks a random external port in the range 20000–40000 (collision-safe) and binds it. If no port label is present, the container is deployed without port binding and any existing Phoenix route is removed.

## Environment Variables

| Variable                | Required | Description                                                           |
|-------------------------|----------|-----------------------------------------------------------------------|
| `DEPLOY_WEBHOOK_SECRET` | Yes      | Shared secret for verifying GitHub webhook signatures                 |
| `GHCR_USERNAME`         | Yes      | GitHub username for authenticating with GHCR                          |
| `GHCR_TOKEN`            | Yes      | GitHub token (PAT or Actions token) for pulling images                |
| `PHOENIX_URL`           | Yes      | Base URL of Phoenix instance                                          |
| `PHOENIX_AUTH_TOKEN`    | No       | Auth token for Phoenix, if configured                                 |
| `SECRETS_DIR`           | No       | Directory containing per-container `.env` files (default: `/secrets`) |

## Per-Container Secrets
If a file named `<repository-name>.env` exists in `SECRETS_DIR`, Poseidon will inject those environment variables into the container at deploy time.  
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