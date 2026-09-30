#!/usr/bin/env bash
#
# revalidate.sh — smoke-test / revalidation matrix for the MCP Server Connector.
#
# Runs the documented acceptance matrix (SPEC.md §9, CONFIGURE.md §5) against a
# running AssemblyLine and reports pass/fail per case. Exits non-zero if any
# case fails, so it is usable in CI or a pre-deploy gate.
#
# Config via environment (all optional except the token when authMode=bearer):
#
#   BASE      base URL of the server              (default http://127.0.0.1:8443)
#   ENDPOINT  MCP endpoint path                   (default /mcp)
#   HEALTH    health-check path                   (default /health)
#   TOKEN     bearer token value (the value only, NOT "bearerToken=...")
#   ORIGIN    Origin header sent on the happy-path cases. When allowedOrigins is
#             set it must be one of the allowed values (a present-but-unlisted
#             Origin is rejected 403; a *missing* Origin is allowed through — see
#             case 10b and CONFIGURE.md §4). Default https://good.example
#   PROTO     MCP protocol version to send        (default 2025-06-18)
#   USERID    a seeded uid the happy-path tool cases look up (default alice)
#
# Cases 1-3 and 5-12 exercise the connector's transport + security matrix and are
# use-case-independent. Cases 4/4b call the UC1 Identity Service Desk tools
# (lookup_user, get_user_groups) against the Docker OpenLDAP seed; adjust them if
# your AL exposes a different catalog.
#
# Flags:
#   --log         tail the server log after the run (see LOGFILE)
#   --log-lines N how many trailing log lines to show (default 40)
#   -h|--help     this help
#
# Example:
#   TOKEN=6a1b... ORIGIN=https://good.example ./scripts/revalidate.sh --log
#
set -u

BASE="${BASE:-http://127.0.0.1:8443}"
ENDPOINT="${ENDPOINT:-/mcp}"
HEALTH="${HEALTH:-/health}"
TOKEN="${TOKEN:-}"
ORIGIN="${ORIGIN:-https://good.example}"
PROTO="${PROTO:-2025-06-18}"
USERID="${USERID:-alice}"          # a seeded uid the UC1 happy-path cases look up
LOGFILE="${LOGFILE:-./logs/ibmdi.log}"   # set to your solution dir's logs/ibmdi.log

SHOW_LOG=0
LOG_LINES=40
while [ $# -gt 0 ]; do
  case "$1" in
    --log) SHOW_LOG=1 ;;
    --log-lines) shift; LOG_LINES="${1:-40}" ;;
    -h|--help) sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
  shift
done

URL="$BASE$ENDPOINT"
CT="Content-Type: application/json"
AUTH="Authorization: Bearer $TOKEN"
ORI="Origin: $ORIGIN"

PASS=0
FAIL=0
if [ -t 1 ]; then G=$'\033[32m'; R=$'\033[31m'; Z=$'\033[0m'; else G=; R=; Z=; fi

# check <name> <expected-http-code> <expected-body-substr-or-empty> -- <curl args...>
check() {
  local name="$1" want="$2" bodywant="$3"; shift 3
  [ "$1" = "--" ] && shift
  local out code body
  out="$(curl -s -w $'\n%{http_code}' "$@")"
  code="${out##*$'\n'}"
  body="${out%$'\n'*}"
  local ok=1
  [ "$code" = "$want" ] || ok=0
  if [ -n "$bodywant" ] && ! printf '%s' "$body" | grep -qF "$bodywant"; then ok=0; fi
  if [ "$ok" = 1 ]; then
    PASS=$((PASS+1)); printf '%s PASS%s  %-42s HTTP %s\n' "$G" "$Z" "$name" "$code"
  else
    FAIL=$((FAIL+1)); printf '%s FAIL%s  %-42s HTTP %s (want %s%s)\n' "$R" "$Z" "$name" "$code" "$want" \
      "$( [ -n "$bodywant" ] && echo ", body~'$bodywant'" )"
    printf '        body: %s\n' "$(printf '%s' "$body" | head -c 300)"
  fi
}

rpc() { printf '{"jsonrpc":"2.0","id":%s,"method":"%s"%s}' "$1" "$2" "$3"; }

echo "Target: $URL   (health $BASE$HEALTH)   origin '$ORIGIN'"
[ -z "$TOKEN" ] && echo "${R}warning:${Z} TOKEN empty — bearer cases will not behave as documented"
echo

check "1  health (unauth)"                200 '"status":"ok"' -- "$BASE$HEALTH"
check "2  initialize"                      200 '"protocolVersion"' -- -X POST "$URL" -H "$CT" -H "$AUTH" -H "$ORI" \
  -d "$(printf '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"%s"}}' "$PROTO")"
check "3  tools/list"                      200 '"tools"' -- -X POST "$URL" -H "$CT" -H "$AUTH" -H "$ORI" \
  -d "$(rpc 2 tools/list '')"
check "4  tools/call lookup_user"          200 '"structuredContent"' -- -X POST "$URL" -H "$CT" -H "$AUTH" -H "$ORI" \
  -d "$(printf '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"lookup_user","arguments":{"userId":"%s"}}}' "$USERID")"
check "4b tools/call get_user_groups"      200 '"groups"' -- -X POST "$URL" -H "$CT" -H "$AUTH" -H "$ORI" \
  -d "$(printf '{"jsonrpc":"2.0","id":31,"method":"tools/call","params":{"name":"get_user_groups","arguments":{"userId":"%s"}}}' "$USERID")"
check "5  missing bearer -> 401"           401 '' -- -X POST "$URL" -H "$CT" -H "$ORI" \
  -d "$(rpc 4 tools/list '')"
check "6  wrong bearer -> 401"             401 '' -- -X POST "$URL" -H "$CT" -H "$ORI" -H "Authorization: Bearer WRONG" \
  -d "$(rpc 5 tools/list '')"
check "7  bad MCP-Protocol-Version -> 400" 400 '' -- -X POST "$URL" -H "$CT" -H "$AUTH" -H "$ORI" -H "MCP-Protocol-Version: 1999-01-01" \
  -d "$(rpc 6 tools/list '')"
check "8  non-POST GET -> 405"             405 '' -- "$URL" -H "$AUTH" -H "$ORI"
check "9  wrong endpointPath -> 404"       404 '' -- -X POST "$BASE/nope-not-a-path" -H "$CT" -H "$AUTH" -H "$ORI" \
  -d "$(rpc 7 tools/list '')"
check "10 disallowed Origin -> 403"        403 '' -- -X POST "$URL" -H "$CT" -H "$AUTH" -H "Origin: https://evil.example" \
  -d "$(rpc 8 tools/list '')"
check "10b missing Origin allowed -> 200"  200 '"tools"' -- -X POST "$URL" -H "$CT" -H "$AUTH" \
  -d "$(rpc 81 tools/list '')"
check "11 unknown tool -> isError"         200 '"isError":true' -- -X POST "$URL" -H "$CT" -H "$AUTH" -H "$ORI" \
  -d '{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"does_not_exist","arguments":{}}}'
check "12 notifications/initialized -> 202" 202 '' -- -X POST "$URL" -H "$CT" -H "$AUTH" -H "$ORI" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

echo
printf 'Result: %s%d passed%s, %s%d failed%s\n' "$G" "$PASS" "$Z" \
  "$( [ "$FAIL" -gt 0 ] && echo "$R" )" "$FAIL" "$Z"

if [ "$SHOW_LOG" = 1 ]; then
  echo
  if [ -r "$LOGFILE" ]; then
    echo "== last $LOG_LINES lines of $LOGFILE =="
    tail -n "$LOG_LINES" "$LOGFILE"
  else
    echo "log not readable: $LOGFILE"
  fi
fi

[ "$FAIL" -eq 0 ]
