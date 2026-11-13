-- starsite-idempotent 落成功终态脚本（原子，双条件守卫与表形态 complete 同语义）：
-- status=PROCESSING 且 expire_at=本代认领值（代际令牌）→ HSET status=DONE + result + gmt_modified，
-- 返回 1；否则 0（行被回收换代——旧认领者终态弃写）。TTL 不动——键记忆窗口自认领起算，DONE 后自然剩余。
-- ARGV[3] = has-result 标志（"1"=写 result 字段，"0"=void 语义删除该字段——空载荷与无载荷区分）。
if redis.call('HGET', KEYS[1], 'status') == 'PROCESSING'
   and redis.call('HGET', KEYS[1], 'expire_at') == ARGV[1] then
  if ARGV[3] == '1' then
    redis.call('HSET', KEYS[1], 'status', 'DONE', 'result', ARGV[2], 'gmt_modified', ARGV[4])
  else
    redis.call('HDEL', KEYS[1], 'result')
    redis.call('HSET', KEYS[1], 'status', 'DONE', 'gmt_modified', ARGV[4])
  end
  return 1
end
return 0
