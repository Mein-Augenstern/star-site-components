-- starsite-quota 调整总量脚本（原子，提额/降额/补货）：降额保护——新总量须覆盖
-- occupied + frozen（total + delta >= occ + frz）才应用返回 1；否则 0（不允许调出
-- 负可用量）；键不存在 -1。
local t = redis.call('HGET', KEYS[1], 'total')
if not t then return -1 end
local occ = tonumber(redis.call('HGET', KEYS[1], 'occupied')) or 0
local frz = tonumber(redis.call('HGET', KEYS[1], 'frozen')) or 0
if tonumber(t) + tonumber(ARGV[1]) >= occ + frz then
  redis.call('HSET', KEYS[1], 'total', tonumber(t) + tonumber(ARGV[1]))
  return 1
end
return 0
