import { useEffect, useMemo, useState } from 'react'
import { api, type DatasetInfo, type EntryView, type ForestView, type Params } from '../api'
import { fmt, PRESSURE_PROFILES, short, STRATEGY_ORDER, SUBJECTS } from '../strategies'

interface Props {
  dataset: DatasetInfo
  /** Opens the proof view at an entry: the first entry of the clicked chunk. */
  onOpenChunk: (strategy: string, entryIndex: number, params: Params) => void
}

const SPANS = [100, 250, 500, 2000]

/**
 * The same log stream cut by every strategy, one strip each. Chunk boundaries are drawn to scale
 * over a window of the stream, so you can see where each strategy decides to cut and why.
 */
export function ChunkingStrip({ dataset, onOpenChunk }: Props) {
  const [chunkSize, setChunkSize] = useState(64)
  const [pressure, setPressure] = useState<keyof typeof PRESSURE_PROFILES>('baseline')
  const [span, setSpan] = useState(250)
  const [viewStart, setViewStart] = useState(0)
  const [forests, setForests] = useState<Record<string, ForestView>>({})
  const [entries, setEntries] = useState<EntryView[]>([])
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  const n = dataset.size
  const viewSpan = Math.min(span, n)
  const start = Math.min(viewStart, Math.max(0, n - viewSpan))

  // Parameters per strategy. The pressure window is scaled to the dataset so the paper's
  // five-window stress profile always fits in the stream.
  const paramsFor = useMemo(() => {
    const pressureParams: Params = {
      pressureProfile: PRESSURE_PROFILES[pressure].value,
      pressureWindow: String(Math.max(1, Math.ceil(n / 5))),
    }
    return (strategy: string): Params => {
      if (strategy === 'fixed-size') return { chunkSize: String(chunkSize) }
      if (strategy === 'resource-aware' || strategy === 'caac') return pressureParams
      return {}
    }
  }, [chunkSize, pressure, n])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    Promise.all(STRATEGY_ORDER.map((s) => api.chunks(dataset.id, s, paramsFor(s))))
      .then((views) => {
        if (!cancelled) setForests(Object.fromEntries(views.map((v) => [v.strategy, v])))
      })
      .catch((e: Error) => !cancelled && setError(e.message))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [dataset.id, paramsFor])

  useEffect(() => {
    let cancelled = false
    if (viewSpan > 500) {
      setEntries([])
      return
    }
    api.entries(dataset.id, start, viewSpan).then((page) => !cancelled && setEntries(page.entries))
    return () => {
      cancelled = true
    }
  }, [dataset.id, start, viewSpan])

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end gap-4 rounded-xl border border-line bg-panel p-4">
        <Control label="Window">
          <select className="input" value={span} onChange={(e) => setSpan(Number(e.target.value))}>
            {SPANS.map((s) => (
              <option key={s} value={s}>
                {fmt(Math.min(s, n))} entries
              </option>
            ))}
          </select>
        </Control>
        <Control label={`Position: ${fmt(start)}–${fmt(start + viewSpan)} of ${fmt(n)}`} grow>
          <input
            type="range"
            min={0}
            max={Math.max(0, n - viewSpan)}
            step={Math.max(1, Math.floor(viewSpan / 10))}
            value={start}
            onChange={(e) => setViewStart(Number(e.target.value))}
            className="w-full accent-[var(--color-accent)]"
          />
        </Control>
        <Control label={`Fixed-size: ${chunkSize} entries`}>
          <input
            type="range"
            min={8}
            max={256}
            step={8}
            value={chunkSize}
            onChange={(e) => setChunkSize(Number(e.target.value))}
            className="accent-[var(--color-accent)]"
          />
        </Control>
        <Control label="Memory pressure (paper + CAAC)">
          <select className="input" value={pressure} onChange={(e) => setPressure(e.target.value as typeof pressure)}>
            {Object.entries(PRESSURE_PROFILES).map(([key, p]) => (
              <option key={key} value={key}>
                {p.label}
              </option>
            ))}
          </select>
        </Control>
      </div>

      {error && <p className="rounded-lg bg-[var(--color-bad)]/10 p-3 text-sm text-bad">{error}</p>}

      <div className="rounded-xl border border-line bg-panel p-4">
        <Axis start={start} span={viewSpan} />
        {entries.length > 0 && <EntryLane entries={entries} span={viewSpan} />}
        <div className={`mt-2 space-y-4 transition-opacity ${loading ? 'opacity-50' : ''}`}>
          {STRATEGY_ORDER.map((name) =>
            forests[name] ? (
              <StrategyRow
                key={name}
                forest={forests[name]}
                start={start}
                span={viewSpan}
                onOpen={(entry) => onOpenChunk(name, entry, paramsFor(name))}
              />
            ) : null,
          )}
        </div>
        <p className="mt-4 text-xs text-muted">
          Each block is one chunk, i.e. one Merkle tree; its root is a leaf of the super-tree. Click a chunk to open its
          tree. The top lane marks WARN/ERROR entries, whose payloads carry random-looking tokens (high entropy).
        </p>
      </div>
    </div>
  )
}

function StrategyRow({
  forest,
  start,
  span,
  onOpen,
}: {
  forest: ForestView
  start: number
  span: number
  onOpen: (entryIndex: number) => void
}) {
  const meta = SUBJECTS[forest.strategy]
  const end = start + span
  const visible = forest.chunks.filter((c) => c.start < end && c.start + c.size > start)
  const avg = forest.entryCount / Math.max(1, forest.chunkCount)

  return (
    <div>
      <div className="mb-1 flex flex-wrap items-baseline gap-x-4 gap-y-1 text-sm">
        <span className="flex items-center gap-2 font-medium">
          <span className="inline-block h-2.5 w-2.5 rounded-full" style={{ background: meta.color }} />
          {meta.label}
        </span>
        <span className="text-muted">
          {fmt(forest.chunkCount)} chunks · avg {avg.toFixed(1)} entries · super-tree depth {forest.superTreeDepth}
        </span>
        <span className="hash text-muted" title={forest.superRoot}>
          root {short(forest.superRoot, 12)}
        </span>
      </div>
      <svg
        viewBox={`0 0 ${span} 10`}
        preserveAspectRatio="none"
        className={`h-9 w-full overflow-visible rounded ${meta.ours ? 'ring-2 ring-[var(--color-accent)]/40' : ''}`}
      >
        {visible.map((c) => {
          const x = Math.max(c.start, start) - start
          const w = Math.min(c.start + c.size, end) - Math.max(c.start, start)
          return (
            <g key={c.index} className="cursor-pointer" onClick={() => onOpen(c.start)}>
              <rect
                x={x}
                y={0}
                width={w}
                height={10}
                fill={meta.color}
                fillOpacity={c.index % 2 === 0 ? 0.85 : 0.5}
                className="transition-[fill-opacity] hover:[fill-opacity:1]"
              >
                <title>
                  {`Chunk ${c.index}: entries ${c.start}–${c.start + c.size - 1} (${c.size}), tree depth ${c.depth}\nroot ${c.root}`}
                </title>
              </rect>
              {c.start >= start && (
                <line x1={x} x2={x} y1={0} y2={10} stroke="var(--color-panel)" strokeWidth={1.5} vectorEffect="non-scaling-stroke" />
              )}
            </g>
          )
        })}
      </svg>
    </div>
  )
}

function EntryLane({ entries, span }: { entries: EntryView[]; span: number }) {
  return (
    <svg viewBox={`0 0 ${span} 4`} preserveAspectRatio="none" className="mt-1 h-3 w-full">
      {entries.map((e, i) =>
        e.level === 'WARN' || e.level === 'ERROR' ? (
          <rect key={e.position} x={i} width={1} height={4} fill="var(--color-ink)" fillOpacity={0.7}>
            <title>{`#${e.position} ${e.level} ${e.message}`}</title>
          </rect>
        ) : null,
      )}
    </svg>
  )
}

function Axis({ start, span }: { start: number; span: number }) {
  const ticks = 5
  return (
    <div className="flex justify-between text-[11px] text-muted tabular-nums">
      {Array.from({ length: ticks + 1 }, (_, i) => (
        <span key={i}>{fmt(Math.round(start + (span * i) / ticks))}</span>
      ))}
    </div>
  )
}

function Control({ label, children, grow }: { label: string; children: React.ReactNode; grow?: boolean }) {
  return (
    <label className={`flex flex-col gap-1 text-xs text-muted ${grow ? 'min-w-56 flex-1' : ''}`}>
      {label}
      {children}
    </label>
  )
}
