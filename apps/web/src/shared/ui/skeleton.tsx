import * as React from "react"

import { cn } from "@/shared/lib/utils"

/** A placeholder block. Decorative: the screen around it says it is loading (an `aria-busy` region or a status line). */
function Skeleton({ className, ...props }: React.ComponentProps<"div">) {
  return <div aria-hidden="true" data-slot="skeleton" className={cn("rounded-md bg-muted motion-safe:animate-pulse", className)} {...props} />
}

export { Skeleton }
