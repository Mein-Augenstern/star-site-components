-- starsite-quota 解冻脚本（原子）：frozen >= 解冻量 → frozen -= 解冻量返回 1；
-- 否则 0；键不存在 -1。
local frz = redis.call('HGET', KEYS[1], 'frozen')
if not frz then return -1 end
if tonumber(frz) >= tonumber(ARGV[1]) then
  redis.call('HSET', KEYS[1], 'frozen', tonumber(frz) - tonumber(ARGV[1]))
  return 1
end
return 0
