#!/usr/bin/env bash
#
# Fetch the OpenTelemetry Java agent used by the :otel alias.
#
# The jar is large (~26 MB) and gitignored, so each checkout downloads it
# once. Bump AGENT_VERSION deliberately and keep opentelemetry-api in deps.edn
# aligned with the SDK that release targets (see its release notes; v2.32.0
# targets SDK 1.66.0), or the agent's API bridge is skipped and custom
# instrumentation silently no-ops.
set -euo pipefail

AGENT_VERSION="2.32.0"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
target="${here}/opentelemetry-javaagent.jar"

if [[ -f "${target}" ]]; then
  echo "Agent already present at ${target}"
  echo "Delete it and re-run to download again."
  exit 0
fi

url="https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${AGENT_VERSION}/opentelemetry-javaagent.jar"

echo "Downloading OpenTelemetry Java agent v${AGENT_VERSION}..."
curl -fL --retry 3 -o "${target}" "${url}"
echo "Saved to ${target}"
