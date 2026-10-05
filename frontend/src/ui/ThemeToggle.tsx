import { useEffect, useState } from 'react'

type Theme = 'light' | 'dark'

function initialTheme(): Theme {
  try {
    const saved = localStorage.getItem('theme')
    if (saved === 'light' || saved === 'dark') return saved
  } catch {
    // storage unavailable: fall through to the default
  }
  // Light by default: the review is shown on a projector, where paper beats night mode.
  return 'light'
}

/** Paper or night ledger, remembered per browser. */
export function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>(initialTheme)

  useEffect(() => {
    document.documentElement.dataset.theme = theme
    try {
      localStorage.setItem('theme', theme)
    } catch {
      // not fatal
    }
  }, [theme])

  return (
    <button
      type="button"
      onClick={() => setTheme(theme === 'light' ? 'dark' : 'light')}
      className="kicker rounded-[3px] border border-rule px-2.5 py-1.5 transition-colors duration-150 hover:border-ink hover:text-ink"
      aria-label={`Switch to ${theme === 'light' ? 'night' : 'paper'} theme`}
    >
      {theme === 'light' ? '◐ Night' : '◑ Paper'}
    </button>
  )
}
