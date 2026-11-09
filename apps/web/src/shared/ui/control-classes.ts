// One class string for the text-like controls, so the native date and datetime-local inputs (D134) look like the rest.
// 16 px at every breakpoint: a control under 16 px makes iOS and iPadOS zoom the page on focus. The border is `input`
// (3:1 against the surface), never `border`, which is for decoration.
const controlClasses =
  "block w-full rounded-lg border border-input bg-card px-3 text-base text-foreground placeholder:text-muted-foreground outline-none transition-[border-color,box-shadow] duration-150 ease-out-strong focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background aria-invalid:border-destructive disabled:cursor-not-allowed disabled:opacity-50"

export { controlClasses }
