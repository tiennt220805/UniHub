import { Redirect } from 'expo-router'

import { SignInScreen } from '@/features/sign-in'
import { useSession } from '@/shared/services/services'

export default function SignIn() {
  const signedIn = useSession((state) => state.session !== null)
  return signedIn ? <Redirect href="/scan" /> : <SignInScreen />
}
