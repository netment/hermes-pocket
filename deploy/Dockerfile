FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive \
    HERMES_HOME=/root/.hermes \
    MYBOT_HOST=0.0.0.0 \
    MYBOT_PORT=8643

# Install dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3.11 python3.11-venv python3.11-dev \
    curl ca-certificates git \
    && rm -rf /var/lib/apt/lists/* \
    && update-alternatives --install /usr/bin/python3 python3 /usr/bin/python3.11 1

# Install Hermes Agent
RUN curl -fsSL https://raw.githubusercontent.com/NousResearch/hermes-agent/main/scripts/install.sh | bash

# Install aiohttp (required by mybot plugin) into Hermes's venv
RUN . /root/.hermes/hermes-agent/venv/bin/activate && pip3 install aiohttp

# Copy mybot plugin
COPY mybot/ /root/.hermes/plugins/mybot/

# Copy entrypoint
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 8643

ENTRYPOINT ["/entrypoint.sh"]
