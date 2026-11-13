-- starsite-idempotent 认领脚本（原子）：key 不存在 → 建 hash（PROCESSING + 本代 expire_at 代际令牌）
-- + PEXPIRE 键记忆窗口（TTL 到期即"键遗忘"——与表形态 expire_at 过期同语义）；key 已存在返回 0（他方持有）。
-- 时间值为定长 ISO 串（uuuu-MM-dd'T'HH:mm:ss.SSS）——Lua 字节序比较即时间序，免时区换算。
-- hash 字段：status / result / error / expire_at / host / gmt_modified
if redis.call('EXISTS', KEYS[1]) == 0 then
  redis.call('HSET', KEYS[1],
    'status', 'PROCESSING',
    'expire_at', ARGV[2],
    'host', ARGV[3],
    'gmt_modified', ARGV[4])
  redis.call('PEXPIRE', KEYS[1], ARGV[1])
  return 1
end
return 0
