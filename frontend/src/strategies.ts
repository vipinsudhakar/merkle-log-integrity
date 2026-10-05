// Display metadata for each subject. Colours are the CSS tokens in index.css, so every view and
// chart uses the same hue for the same strategy.

export interface SubjectMeta {
  label: string
  short: string
  color: string
  ours?: boolean
}

export const SUBJECTS: Record<string, SubjectMeta> = {
  'fixed-size': { label: 'Fixed-size', short: 'Fixed', color: 'var(--color-s-fixed)' },
  'time-window': { label: 'Time-window', short: 'Time', color: 'var(--color-s-time)' },
  entropy: { label: 'Entropy', short: 'Entropy', color: 'var(--color-s-entropy)' },
  'resource-aware': { label: 'Resource-aware (base paper)', short: 'Paper sizing', color: 'var(--color-s-paper)' },
  caac: { label: 'CAAC (ours)', short: 'CAAC', color: 'var(--color-s-caac)', ours: true },
  'paper-pipeline': { label: "Base paper's pipeline", short: 'Paper pipeline', color: 'var(--color-s-pipeline)' },
}

export const STRATEGY_ORDER = ['fixed-size', 'time-window', 'entropy', 'resource-aware', 'caac']

/** The paper's §5.3 stress profile: baseline, three stress windows, recovery. */
export const PRESSURE_PROFILES: Record<string, { label: string; value: string }> = {
  baseline: { label: 'Steady (P = 0.25)', value: '0.25' },
  stress: { label: "Paper's stress test", value: '0.25,0.85,0.85,0.85,0.25' },
}

export function short(hash: string, n = 10): string {
  return hash.slice(0, n) + '…'
}

export function fmt(n: number): string {
  return n.toLocaleString('en-US')
}
