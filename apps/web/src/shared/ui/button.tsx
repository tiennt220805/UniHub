import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { Slot } from "radix-ui"

import { cn } from "@/shared/lib/utils"

// The one place a button gets its look (web-design-system.md §2, §6). Three things differ from the stock shadcn button, each for
// a measured reason: the focus ring is solid (a half-opacity ring falls under 3:1), the default hovers to `primary-hover` (the
// stock `primary/80` drops white-on-green below 4.5:1), and a press scales to 0.97 only when motion is allowed.
// Tailwind v4 already wraps `hover:` in `@media (hover: hover)`, so a tap does not leave a stuck hover.
const buttonVariants = cva(
  "group/button inline-flex shrink-0 items-center justify-center gap-2 rounded-lg border border-transparent text-sm font-semibold whitespace-nowrap transition-[transform,background-color,color,border-color] duration-150 ease-out-strong outline-none select-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background motion-safe:active:scale-[0.97] disabled:pointer-events-none disabled:opacity-50 aria-invalid:border-destructive [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
  {
    variants: {
      variant: {
        default: "bg-primary text-primary-foreground hover:bg-primary-hover",
        outline:
          "border-input bg-background text-foreground hover:bg-accent aria-expanded:bg-accent",
        secondary: "bg-secondary text-secondary-foreground hover:bg-accent aria-expanded:bg-accent",
        ghost: "text-foreground hover:bg-accent aria-expanded:bg-accent",
        // A tinted surface with red text, the way shadcn draws it; design-tokens-contrast.test.ts checks these tints.
        destructive:
          "bg-destructive/10 text-destructive hover:bg-destructive/15 dark:bg-destructive/20 dark:hover:bg-destructive/[.22]",
        link: "text-primary-text underline-offset-4 hover:underline",
      },
      size: {
        default: "h-10 px-4",
        xs: "h-8 gap-1.5 px-2.5 text-xs",
        sm: "h-9 px-3",
        // 44 px: the touch target of the student screens.
        lg: "h-11 px-5 text-base",
        icon: "size-10",
        "icon-xs": "size-8",
        "icon-sm": "size-9",
        "icon-lg": "size-11",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  }
)

function Button({
  className,
  variant = "default",
  size = "default",
  asChild = false,
  ...props
}: React.ComponentProps<"button"> &
  VariantProps<typeof buttonVariants> & {
    asChild?: boolean
  }) {
  const Comp = asChild ? Slot.Root : "button"

  return (
    <Comp
      data-slot="button"
      data-variant={variant}
      data-size={size}
      className={cn(buttonVariants({ variant, size, className }))}
      {...props}
    />
  )
}

export { Button, buttonVariants }
