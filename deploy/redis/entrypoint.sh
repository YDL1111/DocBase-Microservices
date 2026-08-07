#!/bin/sh
set -eu

# Redis 7 ACL with Key Pattern read/write separation:
# %RW~ = read-write access to keys matching pattern
# %R~  = read-only access to keys matching pattern
# This ensures business services can only READ IAM auth_version keys, not modify them.
cat > /tmp/users.acl <<EOF
user default off
user admin on >${REDIS_ADMIN_PASSWORD} ~* &* +@all
user iam on >${IAM_REDIS_PASSWORD} %RW~docbase:iam:* &* +@read +@write +@connection +@transaction +eval +evalsha +info -flushall -flushdb -config
user gateway on >${GATEWAY_REDIS_PASSWORD} %RW~docbase:gateway:* %R~docbase:iam:token:auth:* &* +@read +@write +@connection +@transaction +info -flushall -flushdb -config
user chat on >${CHAT_REDIS_PASSWORD} %RW~docbase:chat:* %R~docbase:iam:token:auth:* &* +@read +@write +@connection +@transaction +eval +evalsha +info -flushall -flushdb -config
user knowledge on >${KNOWLEDGE_REDIS_PASSWORD} %RW~docbase:knowledge:* %R~docbase:iam:token:auth:* &* +@read +@write +@connection +@transaction +info -flushall -flushdb -config
user ingest on >${INGEST_REDIS_PASSWORD} %RW~docbase:ingest:* %R~docbase:iam:token:auth:* &* +@read +@write +@connection +@transaction +info -flushall -flushdb -config
EOF

exec redis-server /usr/local/etc/redis/redis.conf --aclfile /tmp/users.acl
