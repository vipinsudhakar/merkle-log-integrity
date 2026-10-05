import type { ReactNode } from 'react'

interface Props {
  /** Section number, as a paper would number it: "1", "2". */
  number: string
  title: string
  lede: ReactNode
  children: ReactNode
  aside?: ReactNode
}

/**
 * An editorial section: a mono numeral, a serif title and a one-paragraph lede, then the work.
 * Each view of the visualiser opens like a section of the paper it demonstrates.
 */
export function Section({ number, title, lede, children, aside }: Props) {
  return (
    <section className="arrive">
      <header className="mb-block grid gap-x-gutter gap-y-4 border-b border-rule pb-block lg:grid-cols-[minmax(0,1fr)_auto] lg:items-end">
        <div className="max-w-[62ch]">
          <div className="kicker mb-3">§ {number}</div>
          <h2 className="font-display text-title leading-[1.1] font-normal tracking-[-0.01em] text-ink">{title}</h2>
          <p className="mt-3 font-display text-lede leading-snug text-ink-soft italic">{lede}</p>
        </div>
        {aside && <div className="lg:pb-1">{aside}</div>}
      </header>
      {children}
    </section>
  )
}
