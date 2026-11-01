/**
 * Where the Backend API is. A phone cannot reach `localhost` on the development machine: set
 * `EXPO_PUBLIC_API_BASE_URL` to the machine's LAN address (`http://192.168.x.x:8080`). Not a secret.
 */
export const env = {
  apiBaseUrl: (process.env.EXPO_PUBLIC_API_BASE_URL ?? 'http://localhost:8080').replace(/\/+$/, ''),
}
