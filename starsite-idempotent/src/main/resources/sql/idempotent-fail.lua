-- starsite-idempotent 落失败终态脚本（原子，双条件守卫同 complete）：
-- status=PROCESSING 且 expire_at=本代认领值 → HSET status=FAILED + error + gmt_modified，
-- 返回 1（失败即释放键——下一个同键新请求回收重执行）；否则 0（已换代，弃写）。
-- ARGV[3] = has-error 标志（error 理论非空，标志位与 complete 对称防御 null）。
if redis.call('HGET', KEYS[1], 'status') == 'PROCESSING'
   and redis.call('HGET', KEYS[1], 'expire_at') == ARGV[1] then
  if ARGV[3] == '1' then
    redis.call('HSET', KEYS[1], 'status', 'FAILED', 'error', ARGV[2], 'gmt_modified', ARGV[4])
  else
    redis.call('HDEL', KEYS[1], 'error')
    redis.call('HSET', KEYS[1], 'status', 'FAILED', 'gmt_modified', ARGV[4])
  end
  return 1
end
return 0
