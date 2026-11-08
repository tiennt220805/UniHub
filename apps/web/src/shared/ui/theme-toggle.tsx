import { Monitor, Moon, Sun } from 'lucide-react'

import type { ThemePreference } from '@/shared/lib/theme-storage'
import { useThemeStore } from '@/shared/store/theme-store'
import { Button } from '@/shared/ui/button'

const OPTIONS: { value: ThemePreference; label: string; icon: typeof Sun }[] = [
  { value: 'light', label: 'Sáng', icon: Sun },
  { value: 'dark', label: 'Tối', icon: Moon },
  { value: 'system', label: 'Theo hệ thống', icon: Monitor },
]

/** Light / dark / follow-the-system. The label is the accessible name; the pressed one says which is on. */
export function ThemeToggle() {
  const preference = useThemeStore((state) => state.preference)
  const setPreference = useThemeStore((state) => state.setPreference)

  return (
    <div role="group" aria-label="Chế độ hiển thị" className="flex items-center gap-0.5">
      {OPTIONS.map(({ value, label, icon: Icon }) => (
        <Button
          key={value}
          type="button"
          variant={preference === value ? 'secondary' : 'ghost'}
          size="icon-sm"
          aria-label={label}
          aria-pressed={preference === value}
          title={label}
          onClick={() => setPreference(value)}
        >
          <Icon />
        </Button>
      ))}
    </div>
  )
}
