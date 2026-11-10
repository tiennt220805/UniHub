import { Inbox, TriangleAlert } from "lucide-react"
import * as React from "react"

import { cn } from "@/shared/lib/utils"

type StatePanelProps = {
  /** Replaces the default icon. */
  icon?: React.ReactNode
  /** The words come from the feature: `shared/ui` carries no domain copy. */
  title: string
  description?: React.ReactNode
  /** A button or link that gets the user out of the state ("Thử lại", "Xóa bộ lọc"). */
  action?: React.ReactNode
  className?: string
}

function Panel({
  icon,
  title,
  description,
  action,
  className,
  bubble,
  ...props
}: StatePanelProps & { bubble: string } & Omit<React.ComponentProps<"section">, "title">) {
  return (
    <section className={cn("grid justify-items-center gap-2 rounded-xl p-6 text-center", className)} {...props}>
      <div aria-hidden="true" className={cn("grid size-11 place-items-center rounded-full [&_svg]:size-5", bubble)}>
        {icon}
      </div>
      <h3 className="max-w-full text-base leading-6 font-semibold [overflow-wrap:anywhere]">{title}</h3>
      {description ? <p className="max-w-prose text-sm text-muted-foreground [overflow-wrap:anywhere]">{description}</p> : null}
      {action ? <div className="mt-1">{action}</div> : null}
    </section>
  )
}

/** Nothing to show yet, and not a failure: no results, an empty inbox. */
function EmptyState({ icon = <Inbox />, className, ...props }: StatePanelProps) {
  return (
    <Panel
      data-slot="empty-state"
      icon={icon}
      bubble="bg-muted text-muted-foreground"
      className={cn("border border-dashed border-input", className)}
      {...props}
    />
  )
}

/** Something failed. `role="alert"` so it is announced; the action is the way out ("Thử lại"). */
function ErrorState({ icon = <TriangleAlert />, className, ...props }: StatePanelProps) {
  return (
    <Panel
      data-slot="error-state"
      role="alert"
      icon={icon}
      bubble="bg-destructive-soft text-destructive"
      className={cn("border border-destructive", className)}
      {...props}
    />
  )
}

export { EmptyState, ErrorState }
