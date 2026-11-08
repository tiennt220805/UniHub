import * as React from "react"
import { CircleX } from "lucide-react"

import { cn } from "@/shared/lib/utils"

/** A label, its control and the hint or error under it. The caller wires `htmlFor` and `aria-describedby`. */
function Field({ className, ...props }: React.ComponentProps<"div">) {
  return <div data-slot="field" className={cn("grid gap-1.5", className)} {...props} />
}

function Label({ className, ...props }: React.ComponentProps<"label">) {
  return <label data-slot="label" className={cn("text-sm font-medium text-foreground", className)} {...props} />
}

function FieldHint({ className, ...props }: React.ComponentProps<"p">) {
  return <p data-slot="field-hint" className={cn("text-xs text-muted-foreground", className)} {...props} />
}

/** An error next to its field (web-design-system.md §5): an icon and words, never colour alone. */
function FieldError({ className, children, ...props }: React.ComponentProps<"p">) {
  return (
    <p data-slot="field-error" className={cn("flex items-start gap-1.5 text-sm text-destructive", className)} {...props}>
      <CircleX aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
      <span>{children}</span>
    </p>
  )
}

export { Field, FieldError, FieldHint, Label }
