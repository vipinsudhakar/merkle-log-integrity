import { useEffect, useState, type CSSProperties } from 'react'
import { api, type DatasetInfo, type Params, type ProofView, type StageView, type TreeView } from '../api'
import { fmt, STRATEGY_ORDER, SUBJECTS } from '../strategies'
import { Field, Stat } from '../ui/Field'
import { Figure } from '../ui/Figure'
import { Hash } from '../ui/Hash'
import { Seal } from '../ui/Seal'
import { Section } from '../ui/Section'

interface Props {
  dataset: DatasetInfo
  strategy: string
  params: Params
  entryIndex: number
  onStrategyChange: (strategy: string) => void
  onEntryChange: (index: number) => void
}

/** Milliseconds per proof rung. Matches --dur-step in index.css. */
const STEP_MS = 140

/**
 * §2. One entry's two-stage inclusion proof, drawn on the real trees. The path climbs one level
 * per step, the trace row for that step appears with it, stage 2 starts when stage 1 reaches its
 * root, and the verdict is stamped when the climb ends: the O(log n) cost, watched rather than
 * stated. Every hash comes from MerkleVerifier.verifyWithTrace through the API.
 */
export function TreeProof({ dataset, strategy, params, entryIndex, onStrategyChange, onEntryChange }: Props) {
  const [proof, setProof] = useState<ProofView | null>(null)
  const [chunkTree, setChunkTree] = useState<TreeView | null>(null)
  const [superTree, setSuperTree] = useState<TreeView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [draft, setDraft] = useState(String(entryIndex))
  const [draftFor, setDraftFor] = useState(entryIndex)
  const [inputError, setInputError] = useState<string | null>(null)
  // Bumped by every Prove click, so proving the entry already on screen still re-verifies it
  // and replays the climb, instead of doing nothing.
  const [run, setRun] = useState(0)
  if (draftFor !== entryIndex) {
    // The entry changed from outside (a chunk click, "Random entry"): show it in the box.
    setDraftFor(entryIndex)
    setDraft(String(entryIndex))
  }

  useEffect(() => {
    let cancelled = false
    api
      .proof(dataset.id, entryIndex, strategy, params)
      .then(async (p) => {
        const [chunk, sup] = await Promise.all([
          api.chunkTree(dataset.id, p.chunkIndex, strategy, params),
          api.superTree(dataset.id, strategy, params).catch(() => null),
        ])
        if (!cancelled) {
          setError(null)
          setProof(p)
          setChunkTree(chunk)
          setSuperTree(sup)
        }
      })
      .catch((e: Error) => !cancelled && setError(e.message))
    return () => {
      cancelled = true
    }
  }, [dataset.id, entryIndex, strategy, params, run])

  const go = () => {
    const value = Number(draft.trim().replace(/,/g, ''))
    if (draft.trim() === '' || !Number.isInteger(value) || value < 0 || value >= dataset.size) {
      setInputError(`Enter a whole number from 0 to ${fmt(dataset.size - 1)}.`)
      return
    }
    setInputError(null)
    onEntryChange(value)
    setRun((r) => r + 1)
  }

  const random = () => {
    setInputError(null)
    onEntryChange(Math.floor(Math.random() * dataset.size))
    setRun((r) => r + 1)
  }

  // Timeline: stage 1 climbs its levels, then stage 2 climbs its levels, then the stamp.
  const stage1Levels = chunkTree ? chunkTree.levels.length - 1 : 0
  const stage2Levels = superTree ? superTree.levels.length - 1 : 0
  const stage2Start = stage1Levels * STEP_MS + 120
  const stampAt = stage2Start + stage2Levels * STEP_MS + 120
  const runKey = proof ? `${strategy}:${proof.entry.position}:${JSON.stringify(params)}:${run}` : ''

  return (
    <Section
      number="2"
      title="Proving one entry"
      lede="To check one log line you need its siblings, not the whole log: about log₂ n hashes. Stage 1 climbs the entry's chunk tree; stage 2 climbs the super-tree over the chunk roots."
    >
      <div className="mb-block flex flex-wrap items-end gap-x-gutter gap-y-5">
        <Field label="Strategy">
          <select className="field" value={strategy} onChange={(e) => onStrategyChange(e.target.value)}>
            {STRATEGY_ORDER.map((s) => (
              <option key={s} value={s}>
                {SUBJECTS[s].label}
                {SUBJECTS[s].ours ? ' (ours)' : ''}
              </option>
            ))}
          </select>
        </Field>
        <Field label={`Entry · 0–${fmt(dataset.size - 1)}`}>
          <input
            className={`field figures w-28 ${inputError ? 'border-tampered' : ''}`}
            inputMode="numeric"
            aria-invalid={inputError !== null}
            value={draft}
            onChange={(e) => {
              setDraft(e.target.value)
              setInputError(null)
            }}
            onKeyDown={(e) => e.key === 'Enter' && go()}
          />
        </Field>
        <div className="flex gap-2">
          <button className="btn-ink" onClick={go}>
            Prove
          </button>
          <button className="btn" onClick={random}>
            Random entry
          </button>
        </div>
        {inputError && <p className="basis-full text-small text-tampered">{inputError}</p>}
      </div>

      {error && (
        <p className="mb-block rounded-[3px] border border-tampered bg-tampered-wash px-4 py-3 text-small text-tampered">
          {error}
        </p>
      )}

      {proof && chunkTree && (
        <div key={runKey} className="space-y-section">
          <LedgerLine proof={proof} stampAt={stampAt} />
          <div className="grid gap-x-gutter gap-y-section xl:grid-cols-2">
            <Figure
              number="2"
              caption={
                <>
                  Stage 1, inside chunk {proof.chunkIndex} ({fmt(chunkTree.levels[0].length)} entries,{' '}
                  {fmt(chunkTree.start)}–{fmt(chunkTree.start + chunkTree.levels[0].length - 1)}). The entry is leaf{' '}
                  {proof.localIndex}; each step hashes the running value with one sibling.
                </>
              }
            >
              <TreeDiagram levels={chunkTree.levels} leaf={proof.localIndex} delayMs={0} />
              <Trace stage={proof.entryStage} label="chunk root" delayMs={0} />
            </Figure>
            <Figure
              number="3"
              caption={
                <>
                  Stage 2, inside the super-tree, whose {fmt(proof.chunkStage.leafCount)} leaves are the chunk roots. It
                  starts from the root stage 1 produced, and must end at the published super-root.
                </>
              }
            >
              {superTree ? (
                <TreeDiagram levels={superTree.levels} leaf={proof.chunkIndex} delayMs={stage2Start} />
              ) : (
                <p className="text-small text-ink-faint">The super-tree is too large to draw at this size.</p>
              )}
              <Trace stage={proof.chunkStage} label="super-root" delayMs={stage2Start} />
            </Figure>
          </div>
        </div>
      )}
    </Section>
  )
}

/** The entry being proved, set like a line in a ledger, with the verdict stamped beside it. */
function LedgerLine({ proof, stampAt }: { proof: ProofView; stampAt: number }) {
  const e = proof.entry
  return (
    <div className="arrive grid gap-x-gutter gap-y-5 border-y border-rule py-block lg:grid-cols-[minmax(0,1fr)_auto_auto_auto] lg:items-center">
      <div className="min-w-0">
        <div className="kicker">
          Entry {fmt(e.position)} · {e.level} · {e.source} · {e.timestamp.replace('T', ' ').slice(0, 23)}
        </div>
        <div className="mt-1.5 truncate font-display text-lede">{e.message}</div>
        <div className="mt-1 flex items-center gap-1.5 text-small text-ink-faint">
          leaf <Hash value={e.leafHash} chars={24} />
        </div>
      </div>
      <Stat label="Proof length" value={`${proof.totalSteps} hashes`} />
      <Stat label="Proof size" value={`${fmt(proof.sizeInBytes)} B`} />
      <Seal ok={proof.valid} delayMs={stampAt} okLabel="Verified" badLabel="Failed" />
    </div>
  )
}

/**
 * Draws a tree stored as levels (leaves first). Leaf i sits at x = i; a parent sits between its
 * children, or directly above a promoted odd node, so the drawing is the level-array structure
 * itself: sibling of i is i ^ 1, parent is i >> 1. The path is drawn in ink, one level per step.
 */
function TreeDiagram({ levels, leaf, delayMs }: { levels: string[][]; leaf: number; delayMs: number }) {
  const leafCount = levels[0].length
  const gap = leafCount > 128 ? 6 : leafCount > 48 ? 11 : leafCount > 16 ? 18 : 30
  const rowHeight = 40
  const pad = 12
  const width = Math.max(1, leafCount - 1) * gap + pad * 2
  const height = (levels.length - 1) * rowHeight + pad * 2

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

  // Walk up with index arithmetic, exactly as MerkleTree.generateProof does.
  const pathNodes: [number, number][] = []
  const siblings = new Set<string>()
  let index = leaf
  for (let l = 0; l < levels.length; l++) {
    pathNodes.push([l, index])
    if (l < levels.length - 1 && (index ^ 1) < levels[l].length) siblings.add(`${l}:${index ^ 1}`)
    index >>= 1
  }
  const onPath = new Set(pathNodes.map(([l, i]) => `${l}:${i}`))
  const pathD = pathNodes.map(([l, i], k) => `${k === 0 ? 'M' : 'L'}${xs[l][i]},${y(l)}`).join(' ')
  const r = leafCount > 128 ? 2 : leafCount > 48 ? 2.8 : 4

  return (
    <div className="mb-block">
      <div>
        {/* Natural size when it fits; otherwise scaled down to the figure, never clipped. */}
        <svg viewBox={`0 0 ${width} ${height}`} className="mx-auto block h-auto w-full" style={{ maxWidth: width }}>
          {levels.slice(1).map((level, li) =>
            level.map((_, j) => {
              const l = li + 1
              return [2 * j, 2 * j + 1]
                .filter((c) => c < levels[l - 1].length)
                .map((c) => (
                  <line
                    key={`${l}-${j}-${c}`}
                    x1={xs[l][j]}
                    y1={y(l)}
                    x2={xs[l - 1][c]}
                    y2={y(l - 1)}
                    stroke="var(--rule-strong)"
                    strokeWidth={0.75}
                  />
                ))
            }),
          )}
          <path
            d={pathD}
            pathLength={1}
            fill="none"
            stroke="var(--path)"
            strokeWidth={2.5}
            strokeLinejoin="round"
            className="draw"
            style={
              {
                '--draw-duration': `${(levels.length - 1) * STEP_MS}ms`,
                animationDelay: `${delayMs}ms`,
              } as CSSProperties
            }
          />
          {levels.map((level, l) =>
            level.map((hash, j) => {
              const key = `${l}:${j}`
              const isPath = onPath.has(key)
              const isSibling = siblings.has(key)
              const lit = isPath || isSibling
              return (
                <circle
                  key={key}
                  cx={xs[l][j]}
                  cy={y(l)}
                  r={lit ? r + 1.6 : r}
                  fill={isPath ? 'var(--path)' : isSibling ? 'var(--sibling)' : 'var(--ink-faint)'}
                  fillOpacity={lit ? 1 : 0.35}
                  className={lit ? 'arrive' : undefined}
                  style={lit ? { animationDelay: `${delayMs + Math.max(0, l - (isSibling ? 0 : 1)) * STEP_MS}ms` } : undefined}
                >
                  <title>{`level ${l}, node ${j}${isPath ? ' · path' : isSibling ? ' · sibling in the proof' : ''}\n${hash}`}</title>
                </circle>
              )
            }),
          )}
        </svg>
      </div>
      <div className="mt-3 flex flex-wrap gap-x-5 gap-y-1 text-small text-ink-faint">
        <span className="flex items-center gap-1.5">
          <span className="h-2 w-2 rounded-full bg-path" /> path to the root
        </span>
        <span className="flex items-center gap-1.5">
          <span className="h-2 w-2 rounded-full bg-sibling" /> siblings in the proof
        </span>
        <span className="figures text-micro">
          {levels.length - 1} levels · {fmt(levels[0].length)} leaves
        </span>
      </div>
    </div>
  )
}

/** Each rung of verification: running ‖ sibling (order set by the recorded side) → SHA-256 → next. */
function Trace({ stage, label, delayMs }: { stage: StageView; label: string; delayMs: number }) {
  const endAt = delayMs + stage.trace.length * STEP_MS
  return (
    <ol className="figures space-y-1 text-micro">
      {stage.trace.length === 0 && (
        <li className="font-sans text-small text-ink-faint">
          An empty proof: a single-leaf tree, so the leaf hash is the {label} itself.
        </li>
      )}
      {stage.trace.map((step, i) => (
        <li
          key={i}
          className="arrive grid grid-cols-[1.5rem_auto_auto_auto_auto_auto] items-center justify-start gap-x-2.5 border-b border-rule/70 py-1"
          style={{ animationDelay: `${delayMs + i * STEP_MS}ms` }}
        >
          <span className="text-ink-faint">{String(i + 1).padStart(2, '0')}</span>
          {step.side === 'left' ? (
            <>
              <Hash value={step.sibling} chars={8} className="text-sibling" />
              <span className="text-ink-faint">‖</span>
              <Hash value={step.before} chars={8} />
            </>
          ) : (
            <>
              <Hash value={step.before} chars={8} />
              <span className="text-ink-faint">‖</span>
              <Hash value={step.sibling} chars={8} className="text-sibling" />
            </>
          )}
          <span className="text-ink-faint">→</span>
          <Hash value={step.after} chars={8} className="text-path" />
        </li>
      ))}
      <li
        className={`arrive mt-2 flex flex-wrap items-center gap-x-2 rounded-[3px] px-2 py-1.5 ${
          stage.valid ? 'bg-verified-wash text-verified' : 'bg-tampered-wash text-tampered'
        }`}
        style={{ animationDelay: `${endAt}ms` }}
      >
        computed <Hash value={stage.computedRoot} chars={12} /> {stage.valid ? '=' : '≠'} {label}{' '}
        <Hash value={stage.expectedRoot} chars={12} />
      </li>
    </ol>
  )
}
