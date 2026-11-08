import { cva, type VariantProps } from "class-variance-authority"
import * as React from "react"

import { cn } from "@/shared/lib/utils"

// Knows a tone, not a status: the words ("Đã xác nhận") and the icon come from the feature that owns the status, and
// a status never rides on colour alone (web-design-system.md §5). Every pair is in design-tokens-contrast.test.ts.
const badgeVariants = cva(
  "inline-flex h-6 items-center gap-1.5 rounded-full px-2.5 text-xs font-semibold whitespace-nowrap [&_svg]:size-3.5 [&_svg]:shrink-0",
  {
    variants: {
      tone: {
        success: "bg-primary-soft text-primary-text",
        warning: "bg-warning-soft text-warning",
        destructive: "bg-destructive-soft text-destructive",
        info: "bg-info-soft text-info",
        muted: "bg-muted text-muted-foreground",
        highlight: "bg-highlight text-highlight-foreground",
      },
    },
    defaultVariants: { tone: "muted" },
  }
)

function Badge({
  className,
  tone,
  ...props
}: React.ComponentProps<"span"> & VariantProps<typeof badgeVariants>) {
  return <span data-slot="badge" data-tone={tone ?? "muted"} className={cn(badgeVariants({ tone }), className)} {...props} />
}

export { Badge }
