#!/bin/sh
set -eu

if [ -z "${DEVPILOT_MAVEN_PROXY_HOST:-}" ] || [ -z "${DEVPILOT_MAVEN_PROXY_PORT:-}" ]; then
  exec mvn "$@"
fi

settings_file=/tmp/devpilot-maven-settings.xml
cat > "$settings_file" <<EOF
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">
  <proxies>
    <proxy>
      <id>devpilot-docker-desktop</id>
      <active>true</active>
      <protocol>http</protocol>
      <host>${DEVPILOT_MAVEN_PROXY_HOST}</host>
      <port>${DEVPILOT_MAVEN_PROXY_PORT}</port>
      <nonProxyHosts>localhost|127.*</nonProxyHosts>
    </proxy>
  </proxies>
</settings>
EOF

retry_count=${DEVPILOT_MAVEN_RETRY_COUNT:-1}
MAVEN_OPTS="${MAVEN_OPTS:-} -Dmaven.artifact.threads=1 -Daether.transport.http.connectTimeout=30000 -Daether.transport.http.requestTimeout=120000 -Daether.transport.http.retryHandler.count=5"
export MAVEN_OPTS
attempt=1
while :; do
  if mvn --settings "$settings_file" "$@"; then
    exit 0
  else
    status=$?
  fi
  if [ "$attempt" -ge "$retry_count" ]; then
    exit "$status"
  fi
  echo "WARN Maven network step failed; retrying with the existing dependency cache ($attempt/$retry_count)." >&2
  find /root/.m2/repository -type f \( -name '*.lastUpdated' -o -name '*.part' \) -delete 2>/dev/null || true
  sleep $((attempt * 3))
  attempt=$((attempt + 1))
done
