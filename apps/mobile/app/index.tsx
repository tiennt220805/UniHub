import { Redirect } from 'expo-router'

import { useSession } from '@/shared/services/services'

export default function Index() {
  const signedIn = useSession((state) => state.session !== null)
  return <Redirect href={signedIn ? '/scan' : '/sign-in'} />
}
