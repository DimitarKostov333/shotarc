import { scryptSync, randomBytes, timingSafeEqual, createHmac } from 'node:crypto'

const DAY = 86400_000
const SESSION_DAYS = 30
const COOKIE = 'sa_session'

// --- passwords: scrypt, salted, from Node's own crypto — no external dependency

export function hashPassword(password) {
  const salt = randomBytes(16)
  const dk = scryptSync(password, salt, 32)
  return `${salt.toString('hex')}:${dk.toString('hex')}`
}

export function verifyPassword(password, stored) {
  const [saltHex, hashHex] = String(stored).split(':')
  if (!saltHex || !hashHex) return false
  const dk = scryptSync(password, Buffer.from(saltHex, 'hex'), 32)
  const expected = Buffer.from(hashHex, 'hex')
  return dk.length === expected.length && timingSafeEqual(dk, expected)
}

// --- sessions: a signed, stateless cookie (HMAC-SHA256), so restarts don't log anyone out

function sign(payload, secret) {
  const body = Buffer.from(JSON.stringify(payload)).toString('base64url')
  const sig = createHmac('sha256', secret).update(body).digest('base64url')
  return `${body}.${sig}`
}

function unsign(token, secret) {
  if (!token || !token.includes('.')) return null
  const [body, sig] = token.split('.')
  const expected = createHmac('sha256', secret).update(body).digest('base64url')
  if (expected.length !== sig.length || !timingSafeEqual(Buffer.from(expected), Buffer.from(sig))) return null
  try {
    const payload = JSON.parse(Buffer.from(body, 'base64url').toString())
    if (!payload.exp || payload.exp < Date.now()) return null
    return payload
  } catch {
    return null
  }
}

export function makeSessionCookie(username, secret, nowMs, secure = false) {
  const token = sign({ u: username, exp: nowMs + SESSION_DAYS * DAY }, secret)
  return `${COOKIE}=${token}; HttpOnly; SameSite=Lax; Path=/${secure ? '; Secure' : ''}; Max-Age=${SESSION_DAYS * DAY / 1000}`
}

export function clearSessionCookie() {
  return `${COOKIE}=; HttpOnly; SameSite=Lax; Path=/; Max-Age=0`
}

function readCookie(req, name) {
  const header = req.headers.cookie
  if (!header) return null
  for (const part of header.split(';')) {
    const [k, ...v] = part.trim().split('=')
    if (k === name) return v.join('=')
  }
  return null
}

export function currentUser(req, secret) {
  const payload = unsign(readCookie(req, COOKIE), secret)
  return payload?.u ?? null
}

/**
 * The dashboard pages: a signed session cookie from a real account, and nothing else. Anyone
 * without one is sent to the login page.
 */
export function requirePage(secret) {
  return (req, res, next) => {
    if (currentUser(req, secret)) return next()
    res.redirect(`/login?next=${encodeURIComponent(req.originalUrl)}`)
  }
}

/**
 * The read-only JSON API, which scripts also use: a session cookie, or the dashboard key in the
 * `x-dashboard-key` header. Not the query string — that leaks the key into logs and Referer.
 * Either way the caller ends up as a named account in `req.viewer`, so the same row-level
 * scoping applies; the key alone is not an account and gets nowhere.
 */
export function requireApi({ secret, dashboardKey, keyActsAs }) {
  return (req, res, next) => {
    const user = currentUser(req, secret)
    if (user) { req.viewer = user; return next() }
    const offered = req.get('x-dashboard-key')
    if (dashboardKey && offered && timingEqual(offered, dashboardKey) && keyActsAs) {
      req.viewer = keyActsAs
      return next()
    }
    res.status(401).json({ error: 'unauthorised' })
  }
}

function timingEqual(a, b) {
  const x = Buffer.from(String(a))
  const y = Buffer.from(String(b))
  return x.length === y.length && timingSafeEqual(x, y)
}
