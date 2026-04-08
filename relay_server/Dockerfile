FROM python:3.11-slim

LABEL org.opencontainers.image.source="https://github.com/Codename-11/hermes-relay"
LABEL org.opencontainers.image.description="Hermes Relay Server — WSS bridge for terminal and device control"

RUN groupadd -r relay && useradd -r -g relay -m relay

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

USER relay

EXPOSE 8767

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8767/health')"

ENTRYPOINT ["python", "-m", "relay_server"]
CMD ["--no-ssl"]
