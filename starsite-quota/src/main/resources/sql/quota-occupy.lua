-- starsite-quota 占用脚本（原子，不变式在条件内）：occupied + frozen + 增量 <= total
-- → occupied += 增量返回 1；否则 0（可用量不足——不变式在正确工作）。
-- 键不存在返回 -1（调用方翻译 NotFound——建户是业务决策，脚本不猜）。
local t = redis.call('HGET', KEYS[1], 'total')
if not t then return -1 end
local occ = tonumber(redis.call('HGET', KEYS[1], 'occupied')) or 0
local frz = tonumber(redis.call('HGET', KEYS[1], 'frozen')) or 0
if occ + frz + tonumber(ARGV[1]) <= tonumber(t) then
  redis.call('HSET', KEYS[1], 'occupied', occ + tonumber(ARGV[1]))
  return 1
end
return 0
