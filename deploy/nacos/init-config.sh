#!/bin/sh
set -eu

base_url="${NACOS_BASE_URL:-http://nacos:8848/nacos}"
console_url="${NACOS_CONSOLE_URL:-http://nacos:8080}"
username="${NACOS_USERNAME:-nacos}"
password="${NACOS_PASSWORD:-change-me-nacos}"
namespace="${NACOS_NAMESPACE:-docbase-dev}"
group="${NACOS_GROUP:-DOCBASE_GROUP}"

echo "Waiting for Nacos API..."
attempt=0
until curl -fsS "${base_url}/v1/ns/operator/metrics" | grep -q '"status":"UP"'; do
  attempt=$((attempt + 1))
  if [ "${attempt}" -ge 60 ]; then
    echo "Nacos API did not become ready" >&2
    exit 1
  fi
  sleep 2
done

curl -fsS -X POST "${base_url}/v3/auth/user/admin" \
  --data-urlencode "password=${password}" >/dev/null || true

login_response="$(curl -fsS -X POST "${base_url}/v3/auth/user/login" \
  --data-urlencode "username=${username}" \
  --data-urlencode "password=${password}")"
access_token="$(printf '%s' "${login_response}" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')"

if [ -z "${access_token}" ]; then
  echo "Unable to authenticate to Nacos; configuration templates remain available under deploy/nacos/config-data" >&2
  exit 1
fi

namespace_exists="$(curl -fsS -G "${console_url}/v3/console/core/namespace/exist" \
  -H "Authorization: Bearer ${access_token}" \
  --data-urlencode "customNamespaceId=${namespace}" | sed -n 's/.*"data":\(true\|false\).*/\1/p')"

if [ "${namespace_exists}" = "false" ]; then
  curl -fsS -X POST "${console_url}/v3/console/core/namespace" \
    -H "Authorization: Bearer ${access_token}" \
    --data-urlencode "customNamespaceId=${namespace}" \
    --data-urlencode "namespaceName=${namespace}" \
    --data-urlencode "namespaceDesc=DocBase development namespace" >/dev/null
elif [ "${namespace_exists}" != "true" ]; then
  echo "Unable to determine whether Nacos namespace ${namespace} exists" >&2
  exit 1
fi

curl -fsS -G "${console_url}/v3/console/core/namespace" \
  -H "Authorization: Bearer ${access_token}" \
  --data-urlencode "namespaceId=${namespace}" >/dev/null
echo "Namespace ${namespace} is ready"

for file in /config-data/*.yaml; do
  data_id="$(basename "${file}")"
  curl -fsS -X POST "${base_url}/v1/cs/configs?accessToken=${access_token}" \
    --data-urlencode "tenant=${namespace}" \
    --data-urlencode "dataId=${data_id}" \
    --data-urlencode "group=${group}" \
    --data-urlencode "type=yaml" \
    --data-urlencode "content@${file}" >/dev/null
  echo "Published ${data_id}"
done
