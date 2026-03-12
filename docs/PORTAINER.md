# Portainer Deployment

This repository already contains two Docker Compose files:

- `docker-compose.yaml`: pulls published Docker Hub images
- `docker-compose-build.yaml`: builds images from source

If you want Portainer to run **your GitHub fork with your changes**, use
`docker-compose.portainer.yaml`. It is intentionally simpler than the main
production compose:

- builds the app and Datomic directly from your repository
- exposes the app on a single TCP port
- uses named Docker volumes for Datomic persistence
- does not require the nginx sidecar or local certificate files

## Recommended Portainer stack settings

### Repository

- Repository URL: your GitHub repo URL
- Reference: your branch or tag
- Compose path: `docker-compose.portainer.yaml`

If the repository is private, configure Portainer with a GitHub personal
access token that has repository read access.

### Environment variables

Set these in the Portainer stack:

- `ADMIN_PASSWORD`: Datomic admin password
- `DATOMIC_PASSWORD`: Datomic application password
- `DATOMIC_URL`: `datomic:dev://datomic:4334/orcpub?password=<same as DATOMIC_PASSWORD>`
- `SIGNATURE`: long random JWT signing secret

Useful optional values:

- `PUBLIC_PORT`: external port to publish, default `8890`
- `PORT`: container port, default `8890`
- `CSP_POLICY`: `strict` by default
- `DEV_MODE`: leave empty for normal deployments

## First deploy

1. Create a new Portainer stack from your Git repository.
2. Point it at `docker-compose.portainer.yaml`.
3. Add the required environment variables.
4. Deploy the stack.
5. Wait for `datomic` to become healthy, then for `orcpub` to become healthy.
6. Open `http://<your-server>:<PUBLIC_PORT>`.

## Creating the first user

The stack file does not auto-create an application user. After the stack is up,
open a console in the `orcpub` container or run an exec action and create one
through the JVM tooling if needed. The existing repo scripts such as
`docker-user.sh` assume a local shell environment rather than Portainer.

If you prefer a shell-driven deployment instead of Portainer, use
`docker-compose.yaml` or `docker-compose-build.yaml` together with
`docker-setup.sh` and `docker-user.sh`.

## When to use the other compose files

- Use `docker-compose.yaml` when you want the published upstream image and do
  not need your fork's code.
- Use `docker-compose-build.yaml` when building from source outside Portainer.
