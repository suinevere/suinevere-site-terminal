# syntax=docker/dockerfile:1

# ----------------------
#  Dockerfile (authproxy)
#  Description: Runs the Netlink shared-secret gate as an unprivileged, dependency-free container.
#  Author: suinevere
#  Dependencies: python:3.12-slim
#  Globals: N/A
# ----------------------
FROM python:3.12-slim

RUN useradd --system --uid 1001 --user-group --home-dir /nonexistent \
      --shell /usr/sbin/nologin authproxy

COPY authproxy.py /app/authproxy.py

USER authproxy
EXPOSE 2322

ENTRYPOINT ["python3", "-u", "/app/authproxy.py"]
