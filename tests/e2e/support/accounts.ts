import { execFileSync } from 'node:child_process'

// Passwords are read from the cluster's Secret when first needed and kept in this process only: never written to
// a file, a report or a log (the Secret is the one place they live, create-secrets.sh).
const cache = new Map<string, string>()

function secret(key: string): string {
  const hit = cache.get(key)
  if (hit !== undefined) return hit
  // A run against the local stack (Backend API from `bootRun` behind a `vite preview`, no cluster) has no Secret to read:
  // the same variable the backend's seeder takes, from the environment. Never a default in source.
  const fromEnvironment = process.env[key]
  if (fromEnvironment !== undefined && fromEnvironment !== '') {
    cache.set(key, fromEnvironment)
    return fromEnvironment
  }
  const encoded = execFileSync(
    'kubectl',
    ['-n', 'unihub', 'get', 'secret', 'backend-api-secrets', '-o', `jsonpath={.data.${key}}`],
    { encoding: 'utf8' },
  )
  const value = Buffer.from(encoded, 'base64').toString('utf8')
  if (value === '') throw new Error(`the Secret has no ${key}`)
  cache.set(key, value)
  return value
}

export type Account = { email: string; password: string }

// One Student per engine (D100): the per-user rate limit and "already registered" state must not collide.
const STUDENT_OF_ENGINE: Record<string, string> = {
  chromium: 'student1@dev.unihub.local',
  firefox: 'student2@dev.unihub.local',
  webkit: 'student3@dev.unihub.local',
}

export function studentFor(engine: string): Account {
  // A local stack may have only one Student whose password is known; E2E_STUDENT_EMAIL makes every engine use that one.
  const email = process.env.E2E_STUDENT_EMAIL || STUDENT_OF_ENGINE[engine]
  if (email === undefined) throw new Error(`no Student account for the project "${engine}"`)
  return { email, password: secret('UNIHUB_DEV_SEED_STUDENT_PASSWORD') }
}

export const organizer = (): Account => ({
  email: 'organizer1@dev.unihub.local',
  password: secret('UNIHUB_DEV_SEED_ORGANIZER_PASSWORD'),
})

export const admin = (): Account => ({
  email: 'admin1@dev.unihub.local',
  password: secret('UNIHUB_DEV_SEED_ADMIN_PASSWORD'),
})
