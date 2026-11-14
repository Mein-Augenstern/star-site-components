-- starsite-quota 建户脚本（原子）：key 不存在 → HSET 三计数器（total 落库，occupied/frozen=0）返回 1；
-- 已存在 → 不动现有账面返回 0（幂等建户：保留现量，提降额走 adjust 脚本）。
-- hash 字段：total / occupied / frozen
if redis.call('EXISTS', KEYS[1]) == 0 then
  redis.call('HSET', KEYS[1], 'total', ARGV[1], 'occupied', 0, 'frozen', 0)
  return 1
end
return 0
