import { cva, type VariantProps } from "class-variance-authority"
import type { LucideIcon } from "lucide-react"
import * as React from "react"

import { cn } from "@/shared/lib/utils"

// A message in a tinted box, always with an icon and words: a state never rides on colour alone (web-design-system.md §5).
// It knows a tone, not a status; the feature that owns the status picks the words, the icon and the tone. Every tone pair
// is the Badge's, so design-tokens-contrast.test.ts already covers them.
const noticeVariants = cva("flex items-start gap-2.5 rounded-lg p-3 text-sm leading-snug", {
  variants: {
    tone: {
      success: "bg-primary-soft text-primary-text",
      warning: "bg-warning-soft text-warning",
      destructive: "bg-destructive-soft text-destructive",
      info: "bg-info-soft text-info",
      muted: "bg-muted text-foreground",
    },
  },
  defaultVariants: { tone: "muted" },
})

type NoticeProps = Omit<React.ComponentProps<"div">, "children"> &
  VariantProps<typeof noticeVariants> & {
    icon: LucideIcon
    children: React.ReactNode
  }

/** `role="status"` for something that arrives while the user is looking, `role="alert"` for something that went wrong. */
function Notice({ tone, icon: Icon, className, children, ...props }: NoticeProps) {
  return (
    <div data-slot="notice" data-tone={tone ?? "muted"} className={cn(noticeVariants({ tone }), className)} {...props}>
      <Icon aria-hidden="true" className="mt-0.5 size-[18px] shrink-0" />
      <div className="min-w-0">{children}</div>
    </div>
  )
}

export { Notice }
