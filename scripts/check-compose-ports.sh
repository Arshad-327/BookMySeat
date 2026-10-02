#!/usr/bin/env bash
#
# check-compose-ports.sh - fails if docker-compose.yml publishes a port it must not.
#
#   ./scripts/check-compose-ports.sh
#
# WHY THIS IS A CHECK AND NOT A COMMENT
# booking-service and event-service take X-User-Id and X-User-Role on trust. That is safe
# only while the gateway is the one way to reach them. One `ports:` line under
# booking-service undoes it: anything on the machine can then book as any user. The
# compose file says so in a comment; a comment does not fail a build. This does.
#
# It checks three things, against the RESOLVED configuration (`docker compose config`),
# so anchors, includes and overrides are all taken into account:
#
#   1. None of the internal services publishes ANY port.
#   2. None of the forbidden port numbers is published by ANY service.
#   3. Every port that is published is bound to 127.0.0.1.
#
# It names what it found. Needs docker (with the compose plugin) and jq; it does not need
# the stack to be running, or any image to be built.

set -euo pipefail

cd "$(dirname "$0")/.."

# Services that must not be reachable from the host at all.
INTERNAL_SERVICES="auth-service event-service booking-service notification-service"
# Host ports that must never be published, whoever asks: the four internal services, and
# the gateway's management port, which serves actuator health with details.
FORBIDDEN_PORTS="8081 8082 8083 8085 8090"

for tool in docker jq; do
    command -v "$tool" >/dev/null 2>&1 || { echo "check-compose-ports: $tool is required" >&2; exit 2; }
done

config="$(docker compose -f docker-compose.yml config --format json)"

# One line per published port: service <TAB> host_ip <TAB> published <TAB> target
published="$(printf '%s' "$config" | jq -r '
    .services | to_entries[] | .key as $service
    | (.value.ports // [])[]
    | [$service, (.host_ip // "0.0.0.0"), (.published // "" | tostring), (.target | tostring)]
    | @tsv')"

failures=0
fail() {
    echo "FAIL  $1" >&2
    failures=$((failures + 1))
}

while IFS=$'\t' read -r service host_ip host_port container_port; do
    [ -z "$service" ] && continue

    for internal in $INTERNAL_SERVICES; do
        if [ "$service" = "$internal" ]; then
            fail "$service publishes container port $container_port on $host_ip:$host_port. \
$service must publish NOTHING: it trusts X-User-Id, and is safe only while the gateway is the one way in."
        fi
    done

    for forbidden in $FORBIDDEN_PORTS; do
        if [ "$host_port" = "$forbidden" ]; then
            fail "host port $forbidden is published by $service ($host_ip:$host_port -> $container_port). \
$forbidden is internal and must never reach the host."
        fi
    done

    if [ "$host_ip" != "127.0.0.1" ]; then
        fail "$service publishes $host_port on $host_ip, not on 127.0.0.1. \
Every published port must be loopback-only."
    fi
done <<EOF
$published
EOF

if [ "$failures" -gt 0 ]; then
    echo "check-compose-ports: $failures problem(s) in docker-compose.yml" >&2
    exit 1
fi

echo "OK    internal services publish nothing: $INTERNAL_SERVICES"
echo "OK    forbidden host ports are not published: $FORBIDDEN_PORTS"
echo "OK    published, all on 127.0.0.1:"
printf '%s\n' "$published" | while IFS=$'\t' read -r service host_ip host_port container_port; do
    [ -z "$service" ] && continue
    printf '        %-22s %s:%s -> %s\n' "$service" "$host_ip" "$host_port" "$container_port"
done
