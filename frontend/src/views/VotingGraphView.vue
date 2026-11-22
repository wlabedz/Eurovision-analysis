<script setup>
import { computed, onActivated, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getVoteCountries, getVoteGraph } from '../api'
import VoteNetwork from '../components/graph/VoteNetwork.vue'

const FIRST_YEAR = 1956
const LAST_YEAR = 2026
const FIRST_SPLIT_YEAR = 2016
const FIRST_SEMIFINAL_YEAR = 2004
const MAX_COUNTRIES = 16
const DEFAULT_COUNTRIES = ['PL', 'UA', 'LT', 'SE', 'IT', 'GB']

const YEARS = Array.from({ length: LAST_YEAR - FIRST_YEAR + 1 }, (_, i) => LAST_YEAR - i)
const YEAR_PRESETS = [
  { label: 'Last 5', from: LAST_YEAR - 4, to: LAST_YEAR },
  { label: 'Since 2016', from: FIRST_SPLIT_YEAR, to: LAST_YEAR },
  { label: 'Since 2000', from: 2000, to: LAST_YEAR },
  { label: 'All years', from: FIRST_YEAR, to: LAST_YEAR },
]
const COUNTRY_GROUPS = [
  { label: 'Nordics', codes: ['SE', 'NO', 'DK', 'FI', 'IS'] },
  { label: 'Baltics', codes: ['EE', 'LV', 'LT'] },
  { label: 'Balkans', codes: ['HR', 'RS', 'BA', 'SI', 'MK', 'ME', 'AL'] },
  { label: 'Big Five', codes: ['GB', 'FR', 'DE', 'IT', 'ES'] },
]
const ROUNDS = [
  { id: 'final', label: 'Final' },
  { id: 'semifinals', label: 'Semi-finals' },
  { id: 'all', label: 'All rounds' },
]
const VOTE_TYPES = [
  { id: 'total', label: 'Combined' },
  { id: 'jury', label: 'Jury' },
  { id: 'public', label: 'Televote' },
]

const route = useRoute()
const router = useRouter()

const countries = ref([])
const selectedCodes = ref([...DEFAULT_COUNTRIES])
const fromYear = ref(FIRST_SPLIT_YEAR)
const toYear = ref(LAST_YEAR)
const round = ref('final')
const voteType = ref('total')
const search = ref('')
const minPoints = ref(1)
const selection = ref(null)

const graph = ref(null)
const loading = ref(false)
const error = ref('')
const countriesError = ref('')
let requestId = 0
let debounceTimer

const canUseSplit = computed(() => toYear.value >= FIRST_SPLIT_YEAR)
const canUseSemifinals = computed(() => toYear.value >= FIRST_SEMIFINAL_YEAR)
const hasEnoughCountries = computed(() => selectedCodes.value.length >= 2)
const atLimit = computed(() => selectedCodes.value.length >= MAX_COUNTRIES)

const filteredCountries = computed(() => {
  const query = search.value.trim().toLowerCase()
  return countries.value.filter(
    (country) =>
      country.name.toLowerCase().includes(query) || country.code.toLowerCase().includes(query),
  )
})

const nameByCode = computed(() => new Map(countries.value.map((c) => [c.code, c.name])))
const nameOf = (code) => nameByCode.value.get(code) ?? code

const maxEdgePoints = computed(() => Math.max(1, ...(graph.value?.edges ?? []).map((e) => e.points)))
const visibleEdges = computed(() =>
  (graph.value?.edges ?? []).filter((edge) => edge.points >= minPoints.value),
)
const totalPoints = computed(() => visibleEdges.value.reduce((sum, edge) => sum + edge.points, 0))
const listMax = computed(() => Math.max(1, ...visibleEdges.value.map((edge) => edge.points)))

const selectedEdge = computed(() =>
  selection.value?.kind === 'edge'
    ? graph.value?.edges.find(
        (edge) => edge.from === selection.value.from && edge.to === selection.value.to,
      )
    : null,
)
const reverseEdge = computed(() =>
  selectedEdge.value
    ? graph.value.edges.find(
        (edge) => edge.from === selectedEdge.value.to && edge.to === selectedEdge.value.from,
      )
    : null,
)
const edgeYears = computed(() =>
  selectedEdge.value
    ? Object.entries(selectedEdge.value.byYear)
        .map(([year, points]) => ({ year: Number(year), points }))
        .sort((a, b) => b.year - a.year)
    : [],
)
const edgeYearMax = computed(() => Math.max(1, ...edgeYears.value.map((entry) => entry.points)))

const selectedNode = computed(() =>
  selection.value?.kind === 'node'
    ? graph.value?.nodes.find((node) => node.code === selection.value.code)
    : null,
)
const nodeGave = computed(() =>
  selectedNode.value ? visibleEdges.value.filter((e) => e.from === selectedNode.value.code) : [],
)
const nodeReceived = computed(() =>
  selectedNode.value ? visibleEdges.value.filter((e) => e.to === selectedNode.value.code) : [],
)
const nodeMax = computed(() =>
  Math.max(1, ...[...nodeGave.value, ...nodeReceived.value].map((edge) => edge.points)),
)

const periodLabel = computed(() =>
  fromYear.value === toYear.value ? `${fromYear.value}` : `${fromYear.value}–${toYear.value}`,
)

const requestParams = computed(() => ({
  from: fromYear.value,
  to: toYear.value,
  countries: [...selectedCodes.value],
  round: round.value,
  type: voteType.value,
}))
const requestKey = computed(() => JSON.stringify(requestParams.value))

function toggleCountry(code) {
  if (selectedCodes.value.includes(code)) {
    selectedCodes.value = selectedCodes.value.filter((item) => item !== code)
  } else if (!atLimit.value) {
    selectedCodes.value = [...selectedCodes.value, code]
  }
}

function addGroup(codes) {
  const known = codes.filter((code) => nameByCode.value.has(code))
  selectedCodes.value = [...new Set([...selectedCodes.value, ...known])].slice(0, MAX_COUNTRIES)
}

function setFromYear(value) {
  fromYear.value = Number(value)
  if (toYear.value < fromYear.value) toYear.value = fromYear.value
}

function setToYear(value) {
  toYear.value = Number(value)
  if (fromYear.value > toYear.value) fromYear.value = toYear.value
}

function applyPreset(preset) {
  fromYear.value = preset.from
  toYear.value = preset.to
}

function isTypeDisabled(type) {
  return type !== 'total' && !canUseSplit.value
}

function isRoundDisabled(id) {
  return id === 'semifinals' && !canUseSemifinals.value
}

function selectEdge(edge) {
  selection.value = { kind: 'edge', from: edge.from, to: edge.to }
}

async function loadGraph() {
  if (!hasEnoughCountries.value) {
    requestId++
    graph.value = null
    loading.value = false
    error.value = ''
    return
  }

  const currentRequest = ++requestId
  loading.value = true
  error.value = ''

  try {
    const result = await getVoteGraph(requestParams.value)
    if (currentRequest !== requestId) return

    graph.value = result
    const strongest = Math.max(1, ...result.edges.map((edge) => edge.points))
    if (minPoints.value > strongest) minPoints.value = 1

    const current = selection.value
    const stillExists =
      !current ||
      (current.kind === 'node' && result.nodes.some((node) => node.code === current.code)) ||
      (current.kind === 'edge' &&
        result.edges.some((edge) => edge.from === current.from && edge.to === current.to))
    if (!stillExists) selection.value = null
  } catch {
    if (currentRequest === requestId) {
      graph.value = null
      error.value = 'No data available. Please try again later.'
    }
  } finally {
    if (currentRequest === requestId) loading.value = false
  }
}

async function loadCountries() {
  countriesError.value = ''
  try {
    countries.value = await getVoteCountries()
  } catch {
    countriesError.value = 'Could not load the country list.'
  }
}

function readQuery() {
  const query = route.query

  if (typeof query.countries === 'string') {
    const codes = [...new Set(query.countries.toUpperCase().split(',').filter(Boolean))]
    if (codes.length) selectedCodes.value = codes.slice(0, MAX_COUNTRIES)
  }

  const from = Number(query.from)
  const to = Number(query.to)
  if (Number.isInteger(from) && from >= FIRST_YEAR && from <= LAST_YEAR) fromYear.value = from
  if (Number.isInteger(to) && to >= FIRST_YEAR && to <= LAST_YEAR) toYear.value = to
  if (fromYear.value > toYear.value) fromYear.value = toYear.value

  if (ROUNDS.some((item) => item.id === query.round)) round.value = query.round
  if (VOTE_TYPES.some((item) => item.id === query.type)) voteType.value = query.type
}

function writeQuery() {
  if (route.name !== 'voting-graph') return

  const query = {
    countries: selectedCodes.value.join(','),
    from: String(fromYear.value),
    to: String(toYear.value),
    round: round.value,
    type: voteType.value,
  }
  const unchanged = Object.keys(query).every((key) => route.query[key] === query[key])
  if (!unchanged) router.replace({ query })
}

watch(canUseSplit, (allowed) => {
  if (!allowed) voteType.value = 'total'
})

watch(canUseSemifinals, (allowed) => {
  if (!allowed && round.value === 'semifinals') round.value = 'final'
})

watch(requestKey, () => {
  writeQuery()
  clearTimeout(debounceTimer)
  debounceTimer = setTimeout(loadGraph, 220)
})

readQuery()
if (!canUseSplit.value) voteType.value = 'total'
if (!canUseSemifinals.value && round.value === 'semifinals') round.value = 'final'

onMounted(() => {
  loadCountries()
  loadGraph()
  writeQuery()
})

onActivated(() => {
  if (route.query.countries) readQuery()
  else writeQuery()
})

onBeforeUnmount(() => clearTimeout(debounceTimer))
</script>

<template>
  <div class="details-page graph-page">
    <div class="backdrop" aria-hidden="true">
      <span class="orb orb-pink"></span>
      <span class="orb orb-blue"></span>
      <span class="orb orb-violet"></span>
    </div>

    <main class="content">
      <RouterLink class="pill" :to="{ name: 'contests' }">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 5-7 7 7 7" /></svg>
        All contests
      </RouterLink>

      <header class="hero">
        <p class="eyebrow">Eurovision archive</p>
        <h1>Voting Graph</h1>
        <p class="lead">Choose countries and years to see who gave points to whom.</p>
      </header>

      <section class="panel filters" aria-label="Graph filters">
        <div class="filter-block countries-block">
          <div class="filter-title">
            <h2>Countries</h2>
            <span>{{ selectedCodes.length }}/{{ MAX_COUNTRIES }}</span>
            <button
              v-if="selectedCodes.length"
              class="text-button"
              type="button"
              @click="selectedCodes = []"
            >
              Clear
            </button>
          </div>

          <div class="quick">
            <button
              v-for="group in COUNTRY_GROUPS"
              :key="group.label"
              type="button"
              @click="addGroup(group.codes)"
            >
              + {{ group.label }}
            </button>
          </div>

          <label class="search">
            <span class="sr-only">Search countries</span>
            <input v-model="search" type="search" placeholder="Search country" />
          </label>

          <p v-if="countriesError" class="message" role="alert">
            {{ countriesError }}
            <button class="text-button" type="button" @click="loadCountries">Try again</button>
          </p>

          <div class="chips" role="group" aria-label="Choose countries">
            <button
              v-for="country in filteredCountries"
              :key="country.code"
              type="button"
              :class="{ 'is-on': selectedCodes.includes(country.code) }"
              :aria-pressed="selectedCodes.includes(country.code)"
              :disabled="atLimit && !selectedCodes.includes(country.code)"
              @click="toggleCountry(country.code)"
            >
              {{ country.name }}
            </button>
          </div>
        </div>

        <div class="filter-side">
          <div class="filter-block">
            <div class="filter-title"><h2>Years</h2></div>
            <div class="year-selects">
              <label>
                <span>From</span>
                <select :value="fromYear" @change="setFromYear($event.target.value)">
                  <option v-for="year in YEARS" :key="year" :value="year">{{ year }}</option>
                </select>
              </label>
              <label>
                <span>To</span>
                <select :value="toYear" @change="setToYear($event.target.value)">
                  <option v-for="year in YEARS" :key="year" :value="year">{{ year }}</option>
                </select>
              </label>
            </div>
            <div class="quick">
              <button
                v-for="preset in YEAR_PRESETS"
                :key="preset.label"
                type="button"
                :class="{ 'is-on': fromYear === preset.from && toYear === preset.to }"
                @click="applyPreset(preset)"
              >
                {{ preset.label }}
              </button>
            </div>
          </div>

          <div class="filter-block">
            <div class="filter-title"><h2>Round</h2></div>
            <div class="segmented" role="group" aria-label="Round">
              <button
                v-for="item in ROUNDS"
                :key="item.id"
                type="button"
                :class="{ 'is-current': round === item.id }"
                :aria-pressed="round === item.id"
                :disabled="isRoundDisabled(item.id)"
                @click="round = item.id"
              >
                {{ item.label }}
              </button>
            </div>
          </div>

          <div class="filter-block">
            <div class="filter-title"><h2>Vote type</h2></div>
            <div class="segmented" role="group" aria-label="Vote type">
              <button
                v-for="item in VOTE_TYPES"
                :key="item.id"
                type="button"
                :class="{ 'is-current': voteType === item.id }"
                :aria-pressed="voteType === item.id"
                :disabled="isTypeDisabled(item.id)"
                @click="voteType = item.id"
              >
                {{ item.label }}
              </button>
            </div>
            <p v-if="!canUseSplit" class="hint">Jury and televote are split from 2016.</p>
            <p v-else-if="voteType !== 'total' && fromYear < FIRST_SPLIT_YEAR" class="hint">
              Only 2016 onwards is counted for this vote type.
            </p>
          </div>
        </div>
      </section>

      <div v-if="!hasEnoughCountries" class="panel empty">
        Choose at least two countries to draw the graph.
      </div>

      <div v-else-if="error" class="panel empty" role="alert">
        <p>{{ error }}</p>
        <button class="retry" type="button" @click="loadGraph">Try again</button>
      </div>

      <div v-else-if="!graph" class="panel empty" role="status">Loading graph...</div>

      <section v-else class="results" :class="{ 'is-loading': loading }" :aria-busy="loading">
        <div class="panel graph-panel">
          <div class="graph-heading">
            <h2>{{ periodLabel }}</h2>
            <span v-if="loading" role="status">Updating...</span>
            <span v-else>{{ visibleEdges.length }} links · {{ totalPoints }} points</span>
          </div>

          <label v-if="graph.edges.length" class="threshold">
            <span>Show links with at least <b>{{ minPoints }}</b> {{ minPoints === 1 ? 'point' : 'points' }}</span>
            <input v-model.number="minPoints" type="range" min="1" :max="maxEdgePoints" />
          </label>

          <p v-if="!graph.edges.length" class="message">
            These countries did not exchange points in the selected period.
          </p>
          <VoteNetwork
            v-else
            :nodes="graph.nodes"
            :edges="visibleEdges"
            :selection="selection"
            @select="selection = $event"
          />
        </div>

        <aside class="panel side-panel">
          <template v-if="selectedEdge">
            <button class="text-button back-button" type="button" @click="selection = null">
              ← All links
            </button>
            <h2>{{ nameOf(selectedEdge.from) }} → {{ nameOf(selectedEdge.to) }}</h2>
            <p class="big"><b>{{ selectedEdge.points }}</b> points in {{ edgeYears.length }} {{ edgeYears.length === 1 ? 'year' : 'years' }}</p>
            <button
              v-if="reverseEdge"
              class="reverse"
              type="button"
              @click="selectEdge(reverseEdge)"
            >
              Reverse: {{ nameOf(reverseEdge.from) }} → {{ nameOf(reverseEdge.to) }}
              <b>{{ reverseEdge.points }}</b>
            </button>
            <p v-else class="hint">
              {{ nameOf(selectedEdge.to) }} gave {{ nameOf(selectedEdge.from) }} no points in this view.
            </p>

            <ol class="bars">
              <li v-for="entry in edgeYears" :key="entry.year">
                <RouterLink
                  :to="{
                    name: 'contest',
                    params: { year: String(entry.year) },
                    query: { tab: 'votes' },
                  }"
                >
                  {{ entry.year }}
                </RouterLink>
                <span class="track" aria-hidden="true">
                  <i :style="{ width: `${(entry.points / edgeYearMax) * 100}%` }"></i>
                </span>
                <b>{{ entry.points }}</b>
              </li>
            </ol>
          </template>

          <template v-else-if="selectedNode">
            <button class="text-button back-button" type="button" @click="selection = null">
              ← All links
            </button>
            <h2>{{ selectedNode.name }}</h2>
            <p class="big">
              Gave <b>{{ selectedNode.given }}</b> · Received <b>{{ selectedNode.received }}</b>
            </p>
            <RouterLink
              class="summary-link"
              :to="{
                name: 'country-summary',
                query: {
                  country: selectedNode.code,
                  from: String(fromYear),
                  to: String(toYear),
                  type: voteType,
                },
              }"
            >
              Full country summary →
            </RouterLink>

            <h3><i class="key gave"></i>Gave to</h3>
            <p v-if="!nodeGave.length" class="hint">No points in this view.</p>
            <ol v-else class="bars">
              <li v-for="edge in nodeGave" :key="edge.to">
                <button type="button" @click="selectEdge(edge)">{{ nameOf(edge.to) }}</button>
                <span class="track" aria-hidden="true">
                  <i class="gave" :style="{ width: `${(edge.points / nodeMax) * 100}%` }"></i>
                </span>
                <b>{{ edge.points }}</b>
              </li>
            </ol>

            <h3><i class="key received"></i>Received from</h3>
            <p v-if="!nodeReceived.length" class="hint">No points in this view.</p>
            <ol v-else class="bars">
              <li v-for="edge in nodeReceived" :key="edge.from">
                <button type="button" @click="selectEdge(edge)">{{ nameOf(edge.from) }}</button>
                <span class="track" aria-hidden="true">
                  <i class="received" :style="{ width: `${(edge.points / nodeMax) * 100}%` }"></i>
                </span>
                <b>{{ edge.points }}</b>
              </li>
            </ol>
          </template>

          <template v-else>
            <h2>All links</h2>
            <p class="hint">Highest points first. Select one for its year-by-year history.</p>
            <p v-if="!visibleEdges.length" class="hint">No links match this filter.</p>
            <ol v-else class="links">
              <li v-for="edge in visibleEdges" :key="`${edge.from}-${edge.to}`">
                <button type="button" @click="selectEdge(edge)">
                  <span class="link-name">{{ nameOf(edge.from) }} → {{ nameOf(edge.to) }}</span>
                  <b>{{ edge.points }}</b>
                  <span class="track" aria-hidden="true">
                    <i :style="{ width: `${(edge.points / listMax) * 100}%` }"></i>
                  </span>
                </button>
              </li>
            </ol>
          </template>
        </aside>
      </section>
    </main>
  </div>
</template>

<style scoped>
.backdrop {
  position: fixed;
  inset: 0;
  overflow: hidden;
  pointer-events: none;
}

.orb {
  position: absolute;
  width: 56vmax;
  height: 56vmax;
  border-radius: 50%;
  filter: blur(90px);
  opacity: 0.36;
  animation: drift 20s ease-in-out infinite alternate;
}

.orb-pink {
  top: -30vmax;
  right: -18vmax;
  background: var(--pink);
}

.orb-blue {
  top: 20vmax;
  left: -30vmax;
  background: var(--blue);
  opacity: 0.22;
  animation-delay: -7s;
}

.orb-violet {
  bottom: -34vmax;
  left: 24vw;
  background: var(--violet);
  animation-delay: -13s;
}

.content {
  position: relative;
  display: grid;
  gap: 16px;
  width: min(1240px, 100%);
  margin: 0 auto;
  padding: 18px 24px 80px;
  box-sizing: border-box;
}

.pill {
  display: inline-flex;
  align-items: center;
  justify-self: start;
  gap: 5px;
  min-height: 42px;
  padding: 0 14px;
  box-sizing: border-box;
  border: 1px solid var(--glass-line);
  border-radius: 999px;
  color: #fff;
  background: var(--glass);
  -webkit-backdrop-filter: blur(18px);
  backdrop-filter: blur(18px);
  font-size: 15px;
  font-weight: 500;
  text-decoration: none;
}

.pill:hover {
  background: var(--glass-strong);
}

.pill svg {
  width: 18px;
  height: 18px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2.2;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.hero {
  text-align: center;
}

.eyebrow {
  color: var(--muted);
  font-size: 13px;
  font-weight: 500;
  letter-spacing: 0.3em;
  text-transform: uppercase;
}

h1 {
  width: fit-content;
  max-width: 100%;
  margin: 0 auto;
  padding: 0.08em 0.15em 0.16em;
  font-family: 'Kaushan Script', 'Brush Script MT', cursive;
  font-size: clamp(50px, 9vw, 108px);
  font-weight: 400;
  line-height: 1;
  letter-spacing: 0;
  color: transparent;
  background: linear-gradient(100deg, #fff 8%, #ff8ac5 38%, #ff5eae 55%, #76dcff 88%);
  background-clip: text;
  -webkit-background-clip: text;
  filter: drop-shadow(0 8px 26px rgba(255, 46, 147, 0.4));
}

.lead {
  color: var(--muted);
  font-size: 17px;
}

.filters {
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(320px, 0.75fr);
  gap: 22px;
}

.filter-side {
  display: grid;
  align-content: start;
  gap: 14px;
}

.filter-block {
  display: grid;
  align-content: start;
  gap: 8px;
  min-width: 0;
}

.filter-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
  color: var(--muted);
  font-size: 13px;
}

.filter-title h2,
.side-panel h3 {
  margin: 0;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
}

.text-button {
  min-height: 28px;
  padding: 0 4px;
  border: 0;
  color: #ffb4da;
  background: transparent;
  font: inherit;
  font-size: 14px;
  text-decoration: underline;
  text-underline-offset: 3px;
  cursor: pointer;
}

.filter-title .text-button {
  margin-left: auto;
}

.quick,
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.quick button,
.chips button {
  min-height: 34px;
  padding: 0 11px;
  border: 1px solid var(--glass-line);
  border-radius: 999px;
  color: var(--ink);
  background: rgba(255, 255, 255, 0.05);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;
}

.quick button:hover,
.chips button:hover:not(:disabled) {
  border-color: rgba(255, 255, 255, 0.5);
  background: var(--glass-strong);
}

.quick button.is-on,
.chips button.is-on {
  border-color: transparent;
  color: #fff;
  background: linear-gradient(120deg, var(--pink), var(--violet));
}

.chips {
  max-height: 188px;
  padding-right: 4px;
  overflow-y: auto;
}

.chips button:disabled {
  opacity: 0.38;
  cursor: not-allowed;
}

.search input {
  width: 100%;
  min-height: 40px;
  padding: 0 12px;
  box-sizing: border-box;
  border: 1px solid var(--glass-line);
  border-radius: 10px;
  color: #fff;
  background: rgba(14, 8, 38, 0.6);
  font: inherit;
  font-size: 16px;
}

.search input::placeholder {
  color: var(--muted);
}

.year-selects {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.year-selects label {
  display: grid;
  gap: 3px;
  color: var(--muted);
  font-size: 13px;
}

select {
  min-height: 40px;
  padding: 0 8px;
  border: 1px solid var(--glass-line);
  border-radius: 10px;
  color: #fff;
  background: #21174a;
  font: inherit;
  font-size: 16px;
}

.segmented button:disabled {
  opacity: 0.38;
  cursor: not-allowed;
}

.hint,
.message {
  color: var(--muted);
  font-size: 14px;
}

.empty {
  display: grid;
  justify-items: center;
  gap: 12px;
  padding: 40px 18px;
  color: var(--muted);
  text-align: center;
}

.retry {
  min-height: 44px;
  padding: 0 22px;
  border: 0;
  border-radius: 999px;
  color: #fff;
  background: linear-gradient(120deg, var(--pink), var(--violet));
  font: inherit;
  font-weight: 600;
  cursor: pointer;
}

.results {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 360px;
  align-items: start;
  gap: 16px;
  transition: opacity 0.2s ease;
}

.results.is-loading {
  opacity: 0.72;
}

.graph-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 4px 14px;
  margin-bottom: 8px;
  color: var(--muted);
  font-size: 14px;
}

.graph-heading h2,
.side-panel > h2 {
  margin: 0;
  color: #fff;
  font-family: 'Kaushan Script', 'Brush Script MT', cursive;
  font-size: clamp(27px, 3.4vw, 36px);
  font-weight: 400;
  letter-spacing: 0;
  line-height: 1.15;
}

.threshold {
  display: grid;
  gap: 2px;
  margin-bottom: 8px;
  color: var(--muted);
  font-size: 14px;
}

.threshold b {
  color: #fff;
}

.threshold input {
  width: 100%;
  height: 28px;
  margin: 0;
  accent-color: var(--pink);
}

.side-panel {
  display: grid;
  gap: 9px;
  max-height: 900px;
  overflow-y: auto;
}

.back-button {
  justify-self: start;
}

.big {
  color: var(--muted);
  font-size: 15px;
}

.big b {
  color: #fff;
  font-size: 24px;
}

.summary-link {
  justify-self: start;
  color: #ffb4da;
  font-size: 14px;
  text-underline-offset: 3px;
}

.reverse {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 42px;
  padding: 6px 12px;
  border: 1px solid var(--glass-line);
  border-radius: 10px;
  color: #fff;
  background: rgba(255, 255, 255, 0.06);
  font: inherit;
  font-size: 14px;
  text-align: left;
  cursor: pointer;
}

.reverse:hover {
  background: var(--glass-strong);
}

.side-panel h3 {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 5px;
}

.key {
  width: 12px;
  height: 12px;
  border-radius: 3px;
}

.key.gave,
.track i.gave {
  background: #3987e5;
}

.key.received,
.track i.received {
  background: #d55181;
}

.bars,
.links {
  display: grid;
  gap: 5px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.bars li {
  display: grid;
  grid-template-columns: 112px minmax(0, 1fr) 42px;
  align-items: center;
  gap: 8px;
  min-height: 28px;
  font-size: 14px;
}

.bars a,
.bars button {
  overflow: hidden;
  padding: 0;
  border: 0;
  color: #fff;
  background: transparent;
  font: inherit;
  text-align: left;
  text-decoration: underline;
  text-decoration-color: rgba(255, 255, 255, 0.34);
  text-overflow: ellipsis;
  text-underline-offset: 3px;
  white-space: nowrap;
  cursor: pointer;
}

.bars b {
  font-variant-numeric: tabular-nums;
  text-align: right;
}

.track {
  display: block;
  height: 7px;
  overflow: hidden;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.09);
}

.track i {
  display: block;
  height: 100%;
  border-radius: 4px;
  background: #ff78b8;
}

.links button {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 4px 8px;
  width: 100%;
  padding: 7px 8px;
  border: 0;
  border-radius: 9px;
  color: #fff;
  background: transparent;
  font: inherit;
  font-size: 14px;
  text-align: left;
  cursor: pointer;
}

.links button:hover {
  background: var(--glass-strong);
}

.link-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.links b {
  font-variant-numeric: tabular-nums;
}

.links .track {
  grid-column: 1 / -1;
}

@keyframes drift {
  to {
    transform: translate(-9vmax, 6vmax) scale(1.14);
  }
}

@media (prefers-reduced-motion: reduce) {
  .orb {
    animation: none;
  }
}

@media (max-width: 1000px) {
  .filters,
  .results {
    grid-template-columns: minmax(0, 1fr);
  }

  .side-panel {
    max-height: 560px;
  }
}

@media (max-width: 640px) {
  .content {
    padding-inline: 16px;
  }

  .chips {
    max-height: 150px;
  }

  .filter-side .segmented {
    display: flex;
  }

  .filter-side .segmented button {
    flex: 1 0 auto;
    padding-inline: 8px;
  }
}
</style>
