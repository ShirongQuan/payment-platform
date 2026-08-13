-- KEYS[1] = redis key, e.g. fraud:sw:ip:1.2.3.4
-- ARGV[1] = nowMs
-- ARGV[2] = windowMs
-- ARGV[3] = member (unique event id)
-- ARGV[4] = ttlSeconds

local key = KEYS[1]
local nowMs = tonumber(ARGV[1])
local windowMs = tonumber(ARGV[2])
local member = ARGV[3]
local ttlSeconds = tonumber(ARGV[4])

local windowStart = nowMs - windowMs

-- 1) remove stale entries
redis.call('ZREMRANGEBYSCORE', key, 0, windowStart)

-- 2) add current event
redis.call('ZADD', key, nowMs, member)

-- 3) count active window events
local count = redis.call('ZCARD', key)

-- 4) keep key auto-cleaned when idle
redis.call('EXPIRE', key, ttlSeconds)

return count