-- starsite-quota 冻结脚本（原子，与 occupy 共享总量池）：occupied + frozen + 增量 <= total
-- → frozen += 增量返回 1；否则 0；键不存在 -1。
local t = redis.call('HGET', KEYS[1], 'total')
if not t then return -1 end
local occ = tonumber(redis.call('HGET', KEYS[1], 'occupied')) or 0
local frz = tonumber(redis.call('HGET', KEYS[1], 'frozen')) or 0
if occ + frz + tonumber(ARGV[1]) <= tonumber(t) then
  redis.call('HSET', KEYS[1], 'frozen', frz + tonumber(ARGV[1]))
  return 1
end
return 0
