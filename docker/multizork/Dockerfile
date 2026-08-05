# syntax=docker/dockerfile:1

# ----------------------
#  Dockerfile (multizork)
#  Description: Builds multizorkd with exploit-mitigation flags and runs it unprivileged on loopback.
#  Author: suinevere
#  Dependencies: debian:bookworm-slim, sqlite3
#  Globals: REPO_URL, REPO_REF
# ----------------------

FROM debian:bookworm-slim AS build
ARG REPO_URL=https://github.com/suinevere/zaturn.git
ARG REPO_REF=main
RUN apt-get update \
 && apt-get install -y --no-install-recommends git ca-certificates gcc libc6-dev libsqlite3-dev \
 && rm -rf /var/lib/apt/lists/*
ADD https://api.github.com/repos/suinevere/zaturn/commits/${REPO_REF} /tmp/commit.json
RUN git clone --depth 1 --branch "${REPO_REF}" "${REPO_URL}" /src
WORKDIR /src/saturn

# Control-flow protection is architecture-specific; Oracle's free tier is ARM as often as x86.
# A failure from -Werror=format-security is a real bug in the parser, not a flag to remove.
RUN set -eux; \
    case "$(uname -m)" in \
      x86_64)  CFI="-fcf-protection=full" ;; \
      aarch64) CFI="-mbranch-protection=standard" ;; \
      *)       CFI="" ;; \
    esac; \
    gcc -O2 -fPIE -pie \
        -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=2 \
        -fstack-protector-strong -fstack-clash-protection $CFI \
        -Wl,-z,relro,-z,now -Wl,-z,noexecstack \
        -Wformat -Werror=format-security \
        -Wall -Wextra \
        -o /multizorkd multizorkd.c -lsqlite3

FROM debian:bookworm-slim AS runtime
RUN apt-get update \
 && apt-get install -y --no-install-recommends libsqlite3-0 \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 1000 --user-group --home-dir /data --shell /usr/sbin/nologin multizork \
 && mkdir -p /data \
 && chown multizork:multizork /data

COPY --from=build /multizorkd /app/multizorkd
COPY --from=build /src/saturn/cd/data/Z3/ZORK1.Z3 /app/ZORK1.Z3

WORKDIR /data
VOLUME ["/data"]
USER multizork
EXPOSE 2323

CMD ["/app/multizorkd", "--port", "2323", "/app/ZORK1.Z3"]
