import { useEffect, useRef, useState } from 'react'

const HEX = '0123456789abcdef'

interface Props {
  value: string
  /** Characters shown before the ellipsis; the full hash is in the tooltip and on copy. */
  chars?: number
  className?: string
}

/**
 * A SHA-256 hash as evidence: monospaced, truncated, click to copy.
 *
 * When the value changes, the characters scramble and resolve left to right. That is the
 * avalanche effect made visible: change one byte of a log entry and every character of the hash
 * changes. It runs only on change, never on first render, and not at all under reduced motion.
 */
export function Hash({ value, chars = 10, className = '' }: Props) {
  const shown = value.slice(0, chars)
  const [display, setDisplay] = useState(shown)
  const [copied, setCopied] = useState(false)
  const previous = useRef(shown)

  useEffect(() => {
    if (previous.current === shown) return
    previous.current = shown
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      setDisplay(shown)
      return
    }
    const started = performance.now()
    const duration = 420
    let frame = 0
    const tick = (now: number) => {
      const settled = Math.floor(((now - started) / duration) * shown.length)
      setDisplay(Array.from(shown, (c, i) => (i < settled ? c : HEX[Math.floor(Math.random() * 16)])).join(''))
      if (settled < shown.length) frame = requestAnimationFrame(tick)
    }
    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [shown])

  const copy = () => {
    navigator.clipboard?.writeText(value).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 1200)
    })
  }

  return (
    <button
      type="button"
      onClick={copy}
      title={`${value}\nclick to copy`}
      className={`figures relative cursor-copy rounded-[2px] px-0.5 text-left transition-colors duration-150 hover:bg-paper-sunk ${className}`}
    >
      {display}
      {value.length > chars && <span className="text-ink-faint">…</span>}
      <span
        aria-live="polite"
        className={`kicker pointer-events-none absolute -top-5 left-0 whitespace-nowrap transition-all duration-200 ${
          copied ? 'translate-y-0 opacity-100' : 'translate-y-1 opacity-0'
        }`}
      >
        copied
      </span>
    </button>
  )
}
