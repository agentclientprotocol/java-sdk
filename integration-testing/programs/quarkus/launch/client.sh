#!/usr/bin/env bash
# The Quarkus package has an agent only; its cells use another language's client.
echo "client.sh: the Quarkus package has no client program" >&2
exit 2
