-- starsite-idempotent 条件回收脚本（原子，三合一与表形态 reclaim 同语义）：
--   key 不存在 → -1（键遗忘——调用方回认领路径）；
--   FAILED（键释放）/ expire_at 已过（键遗忘）/ PROCESSING 且 gmt_modified 停滞（崩溃遗留）
--   → 满足其一即重置 PROCESSING（新代 expire_at 代际令牌 + 新 host）并重置 TTL，返回 1；
--   否则 0（在飞/已完成，不可回收）。
-- 时间值为定长 ISO 串，字节序比较即时间序。
if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
local status = redis.call('HGET', KEYS[1], 'status')
local expireAt = redis.call('HGET', KEYS[1], 'expire_at') or ''
local modified = redis.call('HGET', KEYS[1], 'gmt_modified') or ''
if status == 'FAILED'
   or expireAt < ARGV[4]
   or (status == 'PROCESSING' and modified < ARGV[5]) then
  redis.call('HDEL', KEYS[1], 'result', 'error')
  redis.call('HSET', KEYS[1],
    'status', 'PROCESSING',
    'expire_at', ARGV[2],
    'host', ARGV[3],
    'gmt_modified', ARGV[4])
  redis.call('PEXPIRE', KEYS[1], ARGV[1])
  return 1
end
return 0
