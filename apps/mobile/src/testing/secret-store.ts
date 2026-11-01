import type { SecretStore } from '@/shared/secure/secret-store'

/** An in-memory stand-in for SecureStore; `entries` is what a keystore dump would show. */
export function memorySecretStore() {
  const entries = new Map<string, string>()
  const store: SecretStore & { entries: Map<string, string> } = {
    entries,
    get: async (key) => entries.get(key) ?? null,
    set: async (key, value) => void entries.set(key, value),
    remove: async (key) => void entries.delete(key),
  }
  return store
}
