import { cn } from "@/shared/lib/utils"

/** The UniHub mark and name (D108: UniHub has an identity of its own). Decorative mark; the name is the text. */
export function Logo({ className }: { className?: string }) {
  return (
    <span data-slot="logo" className={cn("inline-flex items-center gap-2.5 text-lg font-bold text-foreground", className)}>
      <span aria-hidden="true" className="grid size-8 place-items-center rounded-lg bg-primary text-base text-primary-foreground">
        U
      </span>
      UniHub
    </span>
  )
}
