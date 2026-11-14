-- starsite-quota 释放占用脚本（原子）：occupied >= 释放量 → occupied -= 释放量返回 1；
-- 否则 0（业务记账错乱信号——调用方翻译 StateException）；键不存在 -1。
local occ = redis.call('HGET', KEYS[1], 'occupied')
if not occ then return -1 end
if tonumber(occ) >= tonumber(ARGV[1]) then
  redis.call('HSET', KEYS[1], 'occupied', tonumber(occ) - tonumber(ARGV[1]))
  return 1
end
return 0
