// Hand every phone that nobody owns to one account:  node adopt-installs.js <username>
//
// Rounds used to belong to the server rather than to a person, so a database from before that
// change has installs with no owner and they show on nobody's dashboard. Run this once, for the
// account whose phones they are.
import { openDatabase } from './db.js'
import { join } from 'node:path'

const [username] = process.argv.slice(2)
if (!username) {
  console.error('usage: node adopt-installs.js <username>')
  process.exit(1)
}

const db = openDatabase(join(process.env.DATA_DIR ?? './data', 'golf.db'))
if (!db.prepare('SELECT 1 FROM users WHERE username = ?').get(username)) {
  console.error(`no account '${username}' — make one with create-user.js first`)
  process.exit(1)
}

const orphans = db.prepare('SELECT COUNT(*) AS n FROM installs WHERE owner IS NULL').get().n
db.prepare('UPDATE installs SET owner = ? WHERE owner IS NULL').run(username)
console.log(`${orphans} unowned ${orphans === 1 ? 'phone now belongs' : 'phones now belong'} to '${username}'`)
