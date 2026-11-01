import * as SecureStore from 'expo-secure-store'

/**
 * Where secrets live: the auth token, the cached verification key and the device id. On the device
 * this is the hardware-backed keystore (Expo SecureStore); nothing secret goes to SQLite, AsyncStorage
 * or a log (`checkin.md` §6). A small interface so the code that uses it is tested without a keystore.
 */
export interface SecretStore {
  get(key: string): Promise<string | null>
  set(key: string, value: string): Promise<void>
  remove(key: string): Promise<void>
}

export const expoSecretStore: SecretStore = {
  get: (key) => SecureStore.getItemAsync(key),
  set: (key, value) => SecureStore.setItemAsync(key, value),
  remove: (key) => SecureStore.deleteItemAsync(key),
}
