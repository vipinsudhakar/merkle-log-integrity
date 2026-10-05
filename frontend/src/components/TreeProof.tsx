import { useEffect, useState } from 'react'
import { api, type DatasetInfo, type Params, type ProofView, type StageView, type TreeView } from '../api'
import { fmt, short, STRATEGY_ORDER, SUBJECTS } from '../strategies'

interface Props {
  dataset: DatasetInfo
  strategy: string
  params: Params
  entryIndex: number
  onStrategyChange: (strategy: string) => void
  onEntryChange: (index: number) => void
}

/**
 * One entry's two-stage inclusion proof, drawn on the actual trees: entry → chunk root inside its
 * chunk's tree, then chunk root → super-root inside the super-tree. Every hash shown comes from
 * the API, which takes it from MerkleVerifier.verifyWithTrace.
 */
export function TreeProof({ dataset, strategy, params, entryIndex, onStrategyChange, onEntryChange }: Props) {
  const [proof, setProof] = useState<ProofView | null>(null)
  const [chunkTree, setChunkTree] = useState<TreeView | null>(null)
  const [superTree, setSuperTree] = useState<TreeView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [draft, setDraft] = useState(String(entryIndex))
  const [draftFor, setDraftFor] = useState(entryIndex)
  if (draftFor !== entryIndex) {
    // The entry changed from outside (a chunk click, "Random entry"): show it in the box.
    setDraftFor(entryIndex)
    setDraft(String(entryIndex))
  }

  useEffect(() => {
    let cancelled = false
    setError(null)
    api
      .proof(dataset.id, entryIndex, strategy, params)
      .then(async (p) => {
        const [chunk, sup] = await Promise.all([
          api.chunkTree(dataset.id, p.chunkIndex, strategy, params),
          api.superTree(dataset.id, strategy, params).catch(() => null),
        ])
        if (!cancelled) {
          setProof(p)
          setChunkTree(chunk)
          setSuperTree(sup)
        }
      })
      .catch((e: Error) => !cancelled && setError(e.message))
    return () => {
      cancelled = true
    }
  }, [dataset.id, entryIndex, strategy, params])

  const go = () => {
    const value = Number(draft)
    if (Number.isInteger(value) && value >= 0 && value < dataset.size) onEntryChange(value)
  }

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end gap-3 rounded-xl border border-line bg-panel p-4">
        <label className="flex flex-col gap-1 text-xs text-muted">
          Strategy
          <select className="input" value={strategy} onChange={(e) => onStrategyChange(e.target.value)}>
            {STRATEGY_ORDER.map((s) => (
              <option key={s} value={s}>
                {SUBJECTS[s].label}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-xs text-muted">
          Entry (0–{fmt(dataset.size - 1)})
          <input
            className="input w-32 tabular-nums"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && go()}
          />
        </label>
        <button className="btn" onClick={go}>
          Prove
        </button>
        <button className="btn" onClick={() => onEntryChange(Math.floor(Math.random() * dataset.size))}>
          Random entry
        </button>
      </div>

      {error && <p className="rounded-lg bg-bad/10 p-3 text-sm text-bad">{error}</p>}

      {proof && chunkTree && (
        <>
          <EntryCard proof={proof} />
          <div className="grid gap-5 xl:grid-cols-2">
            <Panel
              title={`Stage 1 · entry → chunk ${proof.chunkIndex} root`}
              subtitle={`Chunk ${proof.chunkIndex} holds ${chunkTree.levels[0].length} entries (${fmt(chunkTree.start)}–${fmt(
                chunkTree.start + chunkTree.levels[0].length - 1,
              )}); the entry is leaf ${proof.localIndex}.`}
            >
              <TreeDiagram levels={chunkTree.levels} leaf={proof.localIndex} />
              <Trace stage={proof.entryStage} label="chunk root" />
            </Panel>
            <Panel
              title="Stage 2 · chunk root → super-root"
              subtitle={`The super-tree's leaves are the ${fmt(proof.chunkStage.leafCount)} chunk roots; this chunk is leaf ${proof.chunkIndex}.`}
            >
              {superTree ? (
                <TreeDiagram levels={superTree.levels} leaf={proof.chunkIndex} />
              ) : (
                <p className="text-sm text-muted">Super-tree too large to draw.</p>
              )}
              <Trace stage={proof.chunkStage} label="super-root" />
            </Panel>
          </div>
        </>
      )}
    </div>
  )
}

function EntryCard({ proof }: { proof: ProofView }) {
  const e = proof.entry
  return (
    <div className="flex flex-wrap items-center gap-x-6 gap-y-2 rounded-xl border border-line bg-panel p-4 text-sm">
      <div className="min-w-0 flex-1">
        <div className="text-xs text-muted">
          Entry #{fmt(e.position)} · {e.level} · {e.source} · {new Date(e.timestamp).toISOString().replace('T', ' ').slice(0, 23)}
        </div>
        <div className="truncate font-medium">{e.message}</div>
        <div className="hash mt-1 text-muted">leaf {e.leafHash}</div>
      </div>
      <Stat label="Proof length" value={`${proof.totalSteps} hashes`} />
      <Stat label="Proof size" value={`${proof.sizeInBytes} B`} />
      <div
        className={`rounded-lg px-3 py-2 text-sm font-semibold ${proof.valid ? 'bg-ok/10 text-ok' : 'bg-bad/10 text-bad'}`}
      >
        {proof.valid ? '✓ Verified against the super-root' : '✗ Verification failed'}
      </div>
    </div>
  )
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-xs text-muted">{label}</div>
      <div className="font-semibold tabular-nums">{value}</div>
    </div>
  )
}

function Panel({ title, subtitle, children }: { title: string; subtitle: string; children: React.ReactNode }) {
  return (
    <section className="min-w-0 rounded-xl border border-line bg-panel p-4">
      <h3 className="font-semibold">{title}</h3>
      <p className="mb-3 text-sm text-muted">{subtitle}</p>
      {children}
    </section>
  )
}

/**
 * Draws a tree stored as levels (leaves first). Leaf i sits at x = i; a parent sits between its
 * children, or directly above a promoted odd node, so the drawing is exactly the level-array
 * structure: sibling of i is i ^ 1, parent is i >> 1. The proof path is orange, the siblings the
 * proof supplies are green.
 */
function TreeDiagram({ levels, leaf }: { levels: string[][]; leaf: number }) {
  const leafCount = levels[0].length
  const gap = leafCount > 128 ? 7 : leafCount > 32 ? 14 : 28
  const rowHeight = 44
  const pad = 14
  const width = Math.max(1, leafCount - 1) * gap + pad * 2
  const height = (levels.length - 1) * rowHeight + pad * 2

  // x positions, level by level.
  const xs: number[][] = [levels[0].map((_, i) => pad + i * gap)]
  for (let l = 1; l < levels.length; l++) {
    xs.push(
      levels[l].map((_, j) => {
        const left = xs[l - 1][2 * j]
        const right = xs[l - 1][2 * j + 1]
        return right === undefined ? left : (left + right) / 2
      }),
    )
  }
  const y = (l: number) => height - pad - l * rowHeight

  // Path and siblings: walk up with index arithmetic, as the proof generator does.
  const path = new Set<string>()
  const siblings = new Set<string>()
  let index = leaf
  for (let l = 0; l < levels.length; l++) {
    path.add(`${l}:${index}`)
    if (l < levels.length - 1 && (index ^ 1) < levels[l].length) siblings.add(`${l}:${index ^ 1}`)
    index >>= 1
  }

  const radius = leafCount > 128 ? 2.5 : leafCount > 32 ? 3.5 : 5
  return (
    <div className="mb-4 overflow-x-auto rounded-lg border border-line bg-bg/50">
      <svg width={width} height={height} className="block min-w-full">
        {levels.slice(1).map((level, li) =>
          level.map((_, j) => {
            const l = li + 1
            const children = [2 * j, 2 * j + 1].filter((c) => c < levels[l - 1].length)
            return children.map((c) => {
              const onPath = path.has(`${l}:${j}`) && path.has(`${l - 1}:${c}`)
              return (
                <line
                  key={`${l}-${j}-${c}`}
                  x1={xs[l][j]}
                  y1={y(l)}
                  x2={xs[l - 1][c]}
                  y2={y(l - 1)}
                  stroke={onPath ? 'var(--color-path)' : 'var(--color-line)'}
                  strokeWidth={onPath ? 2.5 : 1}
                />
              )
            })
          }),
        )}
        {levels.map((level, l) =>
          level.map((hash, j) => {
            const key = `${l}:${j}`
            const fill = path.has(key) ? 'var(--color-path)' : siblings.has(key) ? 'var(--color-sibling)' : 'var(--color-muted)'
            const big = path.has(key) || siblings.has(key)
            return (
              <circle key={key} cx={xs[l][j]} cy={y(l)} r={big ? radius + 1.5 : radius} fill={fill} fillOpacity={big ? 1 : 0.35}>
                <title>{`level ${l}, node ${j}\n${hash}`}</title>
              </circle>
            )
          }),
        )}
      </svg>
      <div className="flex gap-4 border-t border-line px-3 py-1.5 text-xs text-muted">
        <span className="flex items-center gap-1.5">
          <span className="h-2.5 w-2.5 rounded-full bg-path" /> path to the root
        </span>
        <span className="flex items-center gap-1.5">
          <span className="h-2.5 w-2.5 rounded-full bg-sibling" /> siblings in the proof
        </span>
        <span>{levels.length - 1} levels · {fmt(levels[0].length)} leaves</span>
      </div>
    </div>
  )
}

/** Every rung of verification: running ‖ sibling (or sibling ‖ running) → SHA-256 → next. */
function Trace({ stage, label }: { stage: StageView; label: string }) {
  return (
    <div className="space-y-1.5">
      {stage.trace.length === 0 && (
        <p className="text-sm text-muted">Empty proof: a single-leaf tree, so the leaf hash is the {label} itself.</p>
      )}
      {stage.trace.map((step, i) => (
        <div key={i} className="hash flex flex-wrap items-center gap-x-2 rounded-md bg-bg/60 px-2 py-1">
          <span className="w-6 text-muted">{i + 1}</span>
          {step.side === 'left' ? (
            <>
              <span className="text-sibling">{short(step.sibling)}</span>‖<span>{short(step.before)}</span>
            </>
          ) : (
            <>
              <span>{short(step.before)}</span>‖<span className="text-sibling">{short(step.sibling)}</span>
            </>
          )}
          <span className="text-muted">→</span>
          <span className="text-path">{short(step.after)}</span>
        </div>
      ))}
      <div className={`hash rounded-md px-2 py-1.5 ${stage.valid ? 'bg-ok/10 text-ok' : 'bg-bad/10 text-bad'}`}>
        computed {short(stage.computedRoot, 16)} {stage.valid ? '=' : '≠'} expected {label} {short(stage.expectedRoot, 16)}
      </div>
    </div>
  )
}
