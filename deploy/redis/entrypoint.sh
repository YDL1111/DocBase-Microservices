#!/bin/sh
set -eu

cat > /tmp/users.acl <<EOF
user default off
user admin on >${REDIS_ADMIN_PASSWORD} ~* &* +@all
user iam on >${IAM_REDIS_PASSWORD} ~docbase:iam:* &* +@read +@write +@connection +@transaction +info -flushall -flushdb -config
user gateway on >${GATEWAY_REDIS_PASSWORD} ~docbase:gateway:* &* +@read +@write +@connection +@transaction +info -flushall -flushdb -config
user chat on >${CHAT_REDIS_PASSWORD} ~docbase:chat:* &* +@read +@write +@connection +@transaction +info -flushall -flushdb -config
EOF

exec redis-server /usr/local/etc/redis/redis.conf --aclfile /tmp/users.acl
