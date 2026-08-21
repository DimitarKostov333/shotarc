/**
 * Everything the phone sends is coerced here before it reaches the database.
 *
 * The ingest key is compiled into the APK, so anyone who unzips it can post whatever they like.
 * SQLite will not save us: a column declared INTEGER stores the string you hand it, so an
 * unchecked number reaches the dashboard as markup. Each helper returns a value of the type it
 * promises, or null — never the caller's input.
 */

/** Trimmed and capped, or null. Nothing typed on a phone needs more room than the cap. */
export function text(value, max) {
  if (typeof value !== 'string' && typeof value !== 'number') return null
  const s = String(value).trim()
  return s ? s.slice(0, max) : null
}

/** A finite number inside [min, max], or null. "12abc" is not a number and becomes null. */
export function number(value, min, max) {
  if (value === null || value === undefined || value === '' || typeof value === 'boolean') return null
  const n = Number(value)
  return Number.isFinite(n) && n >= min && n <= max ? n : null
}

/** As `number`, rounded. */
export function integer(value, min, max) {
  const n = number(value, min, max)
  return n === null ? null : Math.round(n)
}

/** The app's ids are UUIDs. Accept that shape and refuse anything else. */
const ID = /^[A-Za-z0-9._:-]{1,64}$/
export function identifier(value) {
  return typeof value === 'string' && ID.test(value.trim()) ? value.trim() : null
}

/** An ISO timestamp the database can sort on, or `fallback` when it is missing or nonsense. */
export function timestamp(value, fallback) {
  const s = text(value, 40)
  if (!s) return fallback
  const t = Date.parse(s)
  return Number.isNaN(t) ? fallback : new Date(t).toISOString()
}

/**
 * The flight profile: pairs of [distance, height] in metres. Capped in both directions so one
 * upload cannot carry a megabyte of numbers.
 */
export function track(value, maxPoints = 200) {
  if (!Array.isArray(value)) return null
  const points = []
  for (const point of value.slice(0, maxPoints)) {
    if (!Array.isArray(point) || point.length < 2) continue
    const x = number(point[0], -2000, 2000)
    const y = number(point[1], -2000, 2000)
    if (x !== null && y !== null) points.push([x, y])
  }
  return points.length ? points : null
}
