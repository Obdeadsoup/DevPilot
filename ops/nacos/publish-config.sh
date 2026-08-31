#!/bin/sh
set -eu

server="${DEVPILOT_NACOS_HTTP_URL:-http://nacos:8848}"
group="${DEVPILOT_NACOS_GROUP:-DEVPILOT}"

# Secret 不进入配置中心；这里只发布仓库内可公开的非敏感 YAML。
for data_id in devpilot-core.yml devpilot-gateway.yml; do
  response="$(curl --fail --silent --show-error \
    --request POST "${server}/nacos/v1/cs/configs" \
    --data-urlencode "dataId=${data_id}" \
    --data-urlencode "group=${group}" \
    --data-urlencode "type=yaml" \
    --data-urlencode "content@/config/${data_id}")"
  if [ "${response}" != "true" ]; then
    echo "Nacos rejected ${data_id}: ${response}" >&2
    exit 1
  fi
  echo "Published ${data_id} to group ${group}"
done

