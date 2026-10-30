-- starsite-lock Redis 按层释放脚本（原子；与 lock-acquire.lua 配对）
-- 三态：field 不存在→非持有者返回 -1（无副作用）；HINCRBY -1 后仍>0→内层退出，续期返回 0（外层仍持锁，key 不删）；归零→DEL 返回 1（真正释放）
if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 0 then return -1 end
local count = redis.call('HINCRBY', KEYS[1], ARGV[1], -1)
if count > 0 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) return 0 end
redis.call('DEL', KEYS[1]) return 1
