import { useQuery } from '@tanstack/react-query'

import { paymentKeys } from './keys'
import { getPayment } from './requests'

/** One payment, or nothing while `id` is null (no charge has been made yet). */
export const usePayment = (id: string | null) =>
  useQuery({
    queryKey: paymentKeys.detail(id ?? ''),
    queryFn: () => getPayment(id ?? ''),
    enabled: id !== null,
  })
