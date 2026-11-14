-- starsite-quota 冻结转占用脚本（原子换位，单脚本无窗口）：frozen >= 转销量 →
-- frozen -= 转销量 且 occupied += 转销量（总量池内换位，不变式天然保持）返回 1；
-- 否则 0；键不存在 -1。
local frz = redis.call('HGET', KEYS[1], 'frozen')
if not frz then return -1 end
if tonumber(frz) >= tonumber(ARGV[1]) then
  local occ = tonumber(redis.call('HGET', KEYS[1], 'occupied')) or 0
  redis.call('HSET', KEYS[1],
    'frozen', tonumber(frz) - tonumber(ARGV[1]),
    'occupied', occ + tonumber(ARGV[1]))
  return 1
end
return 0
