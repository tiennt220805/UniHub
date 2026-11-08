import * as React from "react"
import { Slot } from "radix-ui"

import { cn } from "@/shared/lib/utils"

// A bordered surface. Light mode adds a hairline shadow; dark mode drops it (it vanishes on the dark page) and
// leans on the border and the lighter `card` colour (web-design-system.md §4).
function Card({
  className,
  asChild = false,
  ...props
}: React.ComponentProps<"div"> & { asChild?: boolean }) {
  const Comp = asChild ? Slot.Root : "div"
  return (
    <Comp
      data-slot="card"
      className={cn("overflow-hidden rounded-xl border border-border bg-card text-card-foreground shadow-sm dark:shadow-none", className)}
      {...props}
    />
  )
}

function CardHeader({ className, ...props }: React.ComponentProps<"div">) {
  return <div data-slot="card-header" className={cn("grid gap-1 p-4", className)} {...props} />
}

/** An `h3` by default; a card inside a list under an `h2` is the usual case. Change the level with `asChild`. */
function CardTitle({
  className,
  asChild = false,
  ...props
}: React.ComponentProps<"h3"> & { asChild?: boolean }) {
  const Comp = asChild ? Slot.Root : "h3"
  return <Comp data-slot="card-title" className={cn("text-lg leading-6 font-semibold", className)} {...props} />
}

function CardDescription({ className, ...props }: React.ComponentProps<"p">) {
  return <p data-slot="card-description" className={cn("text-sm text-muted-foreground", className)} {...props} />
}

function CardContent({ className, ...props }: React.ComponentProps<"div">) {
  return <div data-slot="card-content" className={cn("px-4 pb-4", className)} {...props} />
}

function CardFooter({ className, ...props }: React.ComponentProps<"div">) {
  return <div data-slot="card-footer" className={cn("flex items-center gap-2 border-t border-border p-4", className)} {...props} />
}

export { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle }
