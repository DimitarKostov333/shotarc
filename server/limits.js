/**
 * Fixed-window counters held in memory: enough to blunt a script hammering a form or the download,
 * and cheap enough to sit in front of every route. It is per process and forgets on restart; a
 * determined attacker spread across many addresses needs something in front of nginx instead.
 */
export function limiter({ max, windowMs, cap = 20_000 }) {
  const seen = new Map()

  function prune(nowMs) {
    for (const [key, entry] of seen) if (entry.until <= nowMs) seen.delete(key)
    // Still bloated means the keys are being manufactured faster than they expire; start over
    // rather than grow without bound.
    if (seen.size > cap) seen.clear()
  }

  return {
    /** Counts one hit against `key`. False once it is over its allowance for this window. */
    take(key, nowMs = Date.now()) {
      if (seen.size > cap) prune(nowMs)
      const entry = seen.get(key)
      if (!entry || entry.until <= nowMs) {
        seen.set(key, { n: 1, until: nowMs + windowMs })
        return true
      }
      entry.n += 1
      return entry.n <= max
    },

    /** Forget a key — called when a login succeeds, so one typo does not count against you. */
    reset(key) {
      seen.delete(key)
    },

    /** Seconds until this key's window rolls over, for the Retry-After header. */
    retryAfter(key, nowMs = Date.now()) {
      const entry = seen.get(key)
      return entry && entry.until > nowMs ? Math.ceil((entry.until - nowMs) / 1000) : 0
    },
  }
}

/** Turns a limiter into middleware. `key` picks what to count by; `onBlock` writes the refusal. */
export function guard(rule, key, onBlock) {
  return (req, res, next) => {
    const id = key(req)
    if (rule.take(id)) return next()
    res.setHeader('Retry-After', String(rule.retryAfter(id)))
    onBlock(req, res)
  }
}
