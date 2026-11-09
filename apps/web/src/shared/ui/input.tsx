import * as React from "react"

import { cn } from "@/shared/lib/utils"

import { controlClasses } from "@/shared/ui/control-classes"

function Input({ className, type = "text", ...props }: React.ComponentProps<"input">) {
  return <input type={type} data-slot="input" className={cn(controlClasses, "h-10", className)} {...props} />
}

function Textarea({ className, ...props }: React.ComponentProps<"textarea">) {
  return <textarea data-slot="textarea" className={cn(controlClasses, "min-h-24 py-2", className)} {...props} />
}

export { Input, Textarea }
