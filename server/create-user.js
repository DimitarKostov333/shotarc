// Create or update a dashboard account:  node create-user.js <username> <password>
import { openDatabase } from './db.js'
import { hashPassword } from './auth.js'
import { join } from 'node:path'

const [username, password] = process.argv.slice(2)
if (!username || !password) {
  console.error('usage: node create-user.js <username> <password>')
  process.exit(1)
}
const db = openDatabase(join(process.env.DATA_DIR ?? './data', 'golf.db'))
db.prepare(`
  INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)
  ON CONFLICT (username) DO UPDATE SET password_hash = excluded.password_hash
`).run(username, hashPassword(password), new Date().toISOString())
console.log(`account '${username}' ready`)
