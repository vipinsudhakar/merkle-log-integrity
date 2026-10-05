import { useLayoutEffect, useRef, useState } from 'react'

export interface TabItem<T extends string> {
  id: T
  number: string
  label: string
  ready: boolean
}

/**
 * Section navigation. A single ink rule slides under the active tab, so moving between views
 * reads as moving along one document rather than switching apps.
 */
export function Tabs<T extends string>({
  items,
  active,
  onChange,
}: {
  items: TabItem<T>[]
  active: T
  onChange: (id: T) => void
}) {
  const refs = useRef(new Map<T, HTMLButtonElement>())
  const [rule, setRule] = useState({ left: 0, width: 0 })

  useLayoutEffect(() => {
    const el = refs.current.get(active)
    if (el) setRule({ left: el.offsetLeft, width: el.offsetWidth })
  }, [active])

  return (
    <nav className="relative flex gap-gutter overflow-x-auto" aria-label="Sections">
      {items.map((t) => (
        <button
          key={t.id}
          ref={(el) => {
            if (el) refs.current.set(t.id, el)
          }}
          disabled={!t.ready}
          aria-current={active === t.id ? 'page' : undefined}
          onClick={() => onChange(t.id)}
          className={`flex items-baseline gap-2 py-3 text-small whitespace-nowrap transition-colors duration-150 ${
            active === t.id ? 'text-ink' : 'text-ink-faint hover:text-ink'
          } disabled:cursor-not-allowed disabled:opacity-45 disabled:hover:text-ink-faint`}
        >
          <span className="figures text-micro">{t.number}</span>
          <span className="font-medium">{t.label}</span>
          {!t.ready && <span className="kicker">soon</span>}
        </button>
      ))}
      <span
        aria-hidden
        className="absolute bottom-0 h-[2px] bg-ink transition-all duration-300"
        style={{ left: rule.left, width: rule.width, transitionTimingFunction: 'var(--ease-shift)' }}
      />
    </nav>
  )
}
