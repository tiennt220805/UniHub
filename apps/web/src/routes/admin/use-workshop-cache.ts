import { useQueryClient } from '@tanstack/react-query'

import type { ManagedWorkshopDetail } from '@/features/administration'
import { workshopKeys } from '@/features/workshops'

/**
 * What a change to a workshop does to the catalog's and the detail page's caches (Rules #8, §7): the server's answer is
 * written into the detail, then the lists are read again. The administration feature drops its own lists; features do not
 * import each other, so this, the part that touches the workshop feature, is the route's.
 */
export function useWorkshopCache() {
  const queryClient = useQueryClient()
  return {
    /** The server answered with the workshop as it is now. */
    wrote: (workshop: ManagedWorkshopDetail) => {
      queryClient.setQueryData(workshopKeys.detail(workshop.id), workshop)
      void queryClient.invalidateQueries({ queryKey: workshopKeys.lists() })
    },
    /** Something may have changed under the user (a 409): read it again. */
    reread: (workshopId: string) => {
      void queryClient.invalidateQueries({ queryKey: workshopKeys.detail(workshopId) })
      void queryClient.invalidateQueries({ queryKey: workshopKeys.lists() })
    },
  }
}
