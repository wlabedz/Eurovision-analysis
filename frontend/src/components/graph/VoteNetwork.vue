<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps({
  nodes: { type: Array, required: true },
  edges: { type: Array, required: true },
  selection: { type: Object, default: null },
})

const emit = defineEmits(['select'])

const COLORS = { base: '#ff78b8', gave: '#3987e5', received: '#d55181', active: '#ffffff' }

const container = ref(null)
const width = ref(720)
const hoveredKey = ref('')
let resizeObserver

const edgeKey = (edge) => `${edge.from}>${edge.to}`
const size = computed(() => Math.max(280, Math.min(width.value, 760)))
const compact = computed(() => size.value < 520)

const layout = computed(() => {
  const count = props.nodes.length
  const center = size.value / 2
  const radius = center - (compact.value ? 30 : 104)
  const nodeRadius = Math.max(
    compact.value ? 13 : 16,
    Math.min(compact.value ? 18 : 25, radius * Math.sin(Math.PI / Math.max(count, 3)) * 0.62),
  )

  const positions = new Map(
    props.nodes.map((node, index) => {
      const angle = -Math.PI / 2 + (index / count) * Math.PI * 2
      const cos = Math.cos(angle)
      const sin = Math.sin(angle)
      return [
        node.code,
        {
          ...node,
          x: center + cos * radius,
          y: center + sin * radius,
          labelX: center + cos * (radius + nodeRadius + 9),
          labelY: center + sin * (radius + nodeRadius + 9) + 4,
          anchor: Math.abs(cos) < 0.18 ? 'middle' : cos > 0 ? 'start' : 'end',
        },
      ]
    }),
  )

  return { positions, nodeRadius }
})

const maxPoints = computed(() => Math.max(1, ...props.edges.map((edge) => edge.points)))

function edgeState(edge) {
  const selection = props.selection
  if (hoveredKey.value === edgeKey(edge)) return 'active'
  if (selection?.kind === 'edge') {
    return selection.from === edge.from && selection.to === edge.to ? 'active' : 'muted'
  }
  if (selection?.kind === 'node') {
    if (edge.from === selection.code) return 'gave'
    if (edge.to === selection.code) return 'received'
    return 'muted'
  }
  return 'base'
}

const drawnEdges = computed(() => {
  const { positions, nodeRadius } = layout.value
  const showAllLabels = props.edges.length <= 12

  return props.edges
    .map((edge) => {
      const start = positions.get(edge.from)
      const end = positions.get(edge.to)
      if (!start || !end) return null

      const dx = end.x - start.x
      const dy = end.y - start.y
      const distance = Math.hypot(dx, dy)
      const controlX = (start.x + end.x) / 2 - (dy / distance) * distance * 0.17
      const controlY = (start.y + end.y) / 2 + (dx / distance) * distance * 0.17

      const strength = Math.sqrt(edge.points / maxPoints.value)
      const strokeWidth = 1.2 + strength * (compact.value ? 5 : 8)
      const arrow = 7 + strokeWidth * 1.15

      const startAngle = Math.atan2(controlY - start.y, controlX - start.x)
      const endAngle = Math.atan2(end.y - controlY, end.x - controlX)
      const fromX = start.x + Math.cos(startAngle) * (nodeRadius + 2)
      const fromY = start.y + Math.sin(startAngle) * (nodeRadius + 2)
      const tipX = end.x - Math.cos(endAngle) * (nodeRadius + 3)
      const tipY = end.y - Math.sin(endAngle) * (nodeRadius + 3)
      const lineEndX = tipX - Math.cos(endAngle) * arrow * 0.8
      const lineEndY = tipY - Math.sin(endAngle) * arrow * 0.8
      const baseX = tipX - Math.cos(endAngle) * arrow
      const baseY = tipY - Math.sin(endAngle) * arrow
      const normalX = -Math.sin(endAngle) * arrow * 0.58
      const normalY = Math.cos(endAngle) * arrow * 0.58

      const state = edgeState(edge)
      const color = COLORS[state] ?? COLORS.base
      const opacity = state === 'muted' ? 0.06 : state === 'base' ? 0.3 + strength * 0.55 : 0.96

      return {
        ...edge,
        key: edgeKey(edge),
        state,
        color,
        opacity,
        strokeWidth,
        path: `M ${fromX} ${fromY} Q ${controlX} ${controlY} ${lineEndX} ${lineEndY}`,
        head: `${tipX},${tipY} ${baseX + normalX},${baseY + normalY} ${baseX - normalX},${baseY - normalY}`,
        curve: { fromX, fromY, controlX, controlY, tipX, tipY },
        showLabel: state === 'active' || state === 'gave' || state === 'received' || (state === 'base' && showAllLabels),
      }
    })
    .filter(Boolean)
    .sort((a, b) => {
      const rank = (edge) => (edge.state === 'muted' ? 0 : edge.state === 'active' ? 2 : 1)
      return rank(a) - rank(b) || a.points - b.points
    })
})

const drawnNodes = computed(() => [...layout.value.positions.values()])

function pointOnCurve({ fromX, fromY, controlX, controlY, tipX, tipY }, t) {
  const rest = 1 - t
  return {
    x: rest * rest * fromX + 2 * rest * t * controlX + t * t * tipX,
    y: rest * rest * fromY + 2 * rest * t * controlY + t * t * tipY,
  }
}

const labelWidth = (points) => String(points).length * 8 + 12

// Places each number on the first free spot along its arrow, strongest links first.
const labels = computed(() => {
  const placed = []
  const nodeClearance = layout.value.nodeRadius + 12
  const collisions = ({ x, y, width }) =>
    placed.filter((label) => Math.abs(label.x - x) < (label.width + width) / 2 + 2 && Math.abs(label.y - y) < 22).length +
    drawnNodes.value.filter((node) => Math.hypot(node.x - x, node.y - y) < nodeClearance + width / 4).length

  return drawnEdges.value
    .filter((edge) => edge.showLabel)
    .sort((a, b) => b.points - a.points)
    .map((edge) => {
      const preferred = edge.state === 'received' ? 0.3 : edge.state === 'gave' ? 0.7 : 0.5
      const offsets = [0, 0.07, -0.07, 0.14, -0.14, 0.21, -0.21, 0.28, -0.28]
      const width = labelWidth(edge.points)
      let best = null

      for (const offset of offsets) {
        const t = Math.min(0.88, Math.max(0.12, preferred + offset))
        const candidate = { ...pointOnCurve(edge.curve, t), width }
        const score = collisions(candidate)
        if (!best || score < best.score) best = { ...candidate, score }
        if (score === 0) break
      }

      placed.push(best)
      return { key: edge.key, points: edge.points, labelX: best.x, labelY: best.y, width }
    })
})

const hoveredEdge = computed(() => props.edges.find((edge) => edgeKey(edge) === hoveredKey.value))
const nameOf = (code) => layout.value.positions.get(code)?.name ?? code
const readout = computed(() => {
  if (!hoveredEdge.value) return 'Hover or tap an arrow, or choose a country.'
  const edge = hoveredEdge.value
  return `${nameOf(edge.from)} → ${nameOf(edge.to)}: ${edge.points} ${edge.points === 1 ? 'point' : 'points'}`
})

const isNodeSelected = (code) => props.selection?.kind === 'node' && props.selection.code === code
const isNodeDimmed = (code) => {
  const selection = props.selection
  if (selection?.kind === 'edge') return code !== selection.from && code !== selection.to
  if (selection?.kind !== 'node' || selection.code === code) return false
  return !props.edges.some(
    (edge) =>
      (edge.from === selection.code && edge.to === code) ||
      (edge.to === selection.code && edge.from === code),
  )
}

onMounted(() => {
  resizeObserver = new ResizeObserver(([entry]) => {
    width.value = entry.contentRect.width
  })
  resizeObserver.observe(container.value)
})

onBeforeUnmount(() => resizeObserver?.disconnect())
</script>

<template>
  <div ref="container" class="network">
    <div class="network-head">
      <p class="readout">{{ readout }}</p>
      <div v-if="selection?.kind === 'node'" class="legend">
        <span><i class="gave"></i>Gave</span>
        <span><i class="received"></i>Received</span>
      </div>
    </div>

    <svg
      :width="size"
      :height="size"
      :viewBox="`0 0 ${size} ${size}`"
      role="img"
      aria-label="Directed graph of points exchanged between the selected countries. The list next to it contains the same data."
      @click="emit('select', null)"
      @mouseleave="hoveredKey = ''"
    >
      <g>
        <g v-for="edge in drawnEdges" :key="edge.key" :opacity="edge.opacity">
          <path
            :d="edge.path"
            fill="none"
            :stroke="edge.color"
            :stroke-width="edge.strokeWidth"
            stroke-linecap="round"
          />
          <polygon :points="edge.head" :fill="edge.color" />
          <path
            class="hit"
            :d="edge.path"
            fill="none"
            stroke="transparent"
            :stroke-width="Math.max(16, edge.strokeWidth + 8)"
            @mouseenter="hoveredKey = edge.key"
            @mouseleave="hoveredKey = ''"
            @click.stop="emit('select', { kind: 'edge', from: edge.from, to: edge.to })"
          />
        </g>
      </g>

      <g class="edge-labels" aria-hidden="true">
        <g v-for="edge in labels" :key="edge.key" :transform="`translate(${edge.labelX} ${edge.labelY})`">
          <rect :x="-edge.width / 2" y="-10" :width="edge.width" height="20" rx="10" />
          <text text-anchor="middle" y="4">{{ edge.points }}</text>
        </g>
      </g>

      <g
        v-for="node in drawnNodes"
        :key="node.code"
        class="node"
        :class="{ 'is-selected': isNodeSelected(node.code), 'is-dimmed': isNodeDimmed(node.code) }"
        tabindex="0"
        role="button"
        :aria-label="`${node.name}: gave ${node.given}, received ${node.received} points`"
        @click.stop="emit('select', { kind: 'node', code: node.code })"
        @keydown.enter.prevent="emit('select', { kind: 'node', code: node.code })"
        @keydown.space.prevent="emit('select', { kind: 'node', code: node.code })"
      >
        <circle :cx="node.x" :cy="node.y" :r="layout.nodeRadius" />
        <text class="code" :x="node.x" :y="node.y + 4" text-anchor="middle">{{ node.code }}</text>
        <text v-if="!compact" class="name" :x="node.labelX" :y="node.labelY" :text-anchor="node.anchor">
          {{ node.name }}
        </text>
      </g>
    </svg>
  </div>
</template>

<style scoped>
.network {
  min-width: 0;
}

.network-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 6px 14px;
  min-height: 26px;
  margin-bottom: 6px;
}

.readout {
  font-size: 15px;
  font-weight: 500;
}

.legend {
  display: flex;
  gap: 14px;
  color: var(--muted);
  font-size: 13px;
}

.legend span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.legend i {
  width: 14px;
  height: 4px;
  border-radius: 2px;
}

.legend .gave {
  background: #3987e5;
}

.legend .received {
  background: #d55181;
}

svg {
  display: block;
  max-width: 100%;
  height: auto;
  margin: 0 auto;
  overflow: visible;
}

.hit {
  cursor: pointer;
  pointer-events: stroke;
}

.edge-labels {
  pointer-events: none;
}

.edge-labels rect {
  fill: #120b2c;
  stroke: rgba(255, 255, 255, 0.34);
}

.edge-labels text {
  fill: #fff;
  font-size: 12px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.node {
  cursor: pointer;
  outline: none;
  transition: opacity 0.2s ease;
}

.node circle {
  fill: #21174a;
  stroke: rgba(255, 255, 255, 0.62);
  stroke-width: 2;
  transition: fill 0.2s ease, stroke 0.2s ease;
}

.node:hover circle {
  fill: #3b2a7d;
  stroke: #fff;
}

.node:focus-visible circle {
  stroke: #27c8ff;
  stroke-width: 3;
}

.node.is-selected circle {
  fill: #ff2e93;
  stroke: #fff;
}

.node.is-dimmed {
  opacity: 0.32;
}

.code {
  fill: #fff;
  font-size: 12px;
  font-weight: 700;
  pointer-events: none;
}

.name {
  fill: #fff;
  font-size: 14px;
  font-weight: 500;
  pointer-events: none;
}
</style>
