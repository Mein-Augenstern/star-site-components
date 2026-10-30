-- starsite-lock Redis 争锁/重入脚本（原子；hash 结构：field=ownerId，value=持有层数——存储端重入计数）
-- 三态：key 不存在→首抢 HSET(owner,1)+PEXPIRE 返回 1；field 已存在（自己持有）→重入 HINCRBY+1+续期返回 1；否则他方持有返回 0
if redis.call('EXISTS', KEYS[1]) == 0 then
  redis.call('HSET', KEYS[1], ARGV[1], 1)
  redis.call('PEXPIRE', KEYS[1], ARGV[2]) return 1 end
if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 1 then
  redis.call('HINCRBY', KEYS[1], ARGV[1], 1)
  redis.call('PEXPIRE', KEYS[1], ARGV[2]) return 1 end
return 0
