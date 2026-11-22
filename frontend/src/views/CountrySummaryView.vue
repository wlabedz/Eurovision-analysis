<script setup>
import { computed, onActivated, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getCountrySummary, getVoteCountries } from '../api'

const FIRST_YEAR = 1956
const LAST_YEAR = 2026
const FIRST_SPLIT_YEAR = 2016
const RANKING_SIZE = 10

const YEARS = Array.from({ length: LAST_YEAR - FIRST_YEAR + 1 }, (_, i) => LAST_YEAR - i)
const YEAR_PRESETS = [
  { label: 'Last 5', from: LAST_YEAR - 4, to: LAST_YEAR },
  { label: 'Since 2016', from: FIRST_SPLIT_YEAR, to: LAST_YEAR },
  { label: 'Since 2000', from: 2000, to: LAST_YEAR },
  { label: 'All years', from: FIRST_YEAR, to: LAST_YEAR },
]
const VOTE_TYPES = [
  { id: 'total', label: 'Combined' },
  { id: 'jury', label: 'Jury' },
  { id: 'public', label: 'Televote' },
]
const STAGES = [
  { id: 'overall', label: 'All rounds' },
  { id: 'finals', label: 'Final' },
  { id: 'semifinals', label: 'Semi-finals' },
]
const METRICS = [
  { id: 'average', label: 'Average' },
  { id: 'points', label: 'Total' },
]
const MIN_PERFORMANCES = [1, 3, 5, 10]
const COLUMNS = [
  { id: 'name', label: 'From' },
  { id: 'overall', label: 'All rounds' },
  { id: 'finals', label: 'Final' },
  { id: 'semifinals', label: 'Semi-final' },
  { id: 'points', label: 'Total' },
]

const route = useRoute()
const router = useRouter()

const countries = ref([])
const countryCode = ref('PL')
const fromYear = ref(FIRST_SPLIT_YEAR)
const toYear = ref(LAST_YEAR)
const voteType = ref('total')
const stage = ref('overall')
const metric = ref('average')
const minPerformances = ref(3)
const sortKey = ref('overall')
const sortDescending = ref(true)

const summary = ref(null)
const loading = ref(true)
const error = ref('')
let requestId = 0
let debounceTimer

const countryOptions = computed(() => countries.value.filter((c) => c.code !== 'WLD'))
const canUseSplit = computed(() => toYear.value >= FIRST_SPLIT_YEAR)
const stageLabel = computed(() => STAGES.find((item) => item.id === stage.value).label)
const crossesScoringEras = computed(
  () => voteType.value === 'total' && fromYear.value < FIRST_SPLIT_YEAR && toYear.value >= FIRST_SPLIT_YEAR,
)
const periodLabel = computed(() =>
  fromYear.value === toYear.value ? `${fromYear.value}` : `${fromYear.value}–${toYear.value}`,
)

const metricValue = (voter) =>
  metric.value === 'average' ? voter[stage.value].average : voter[stage.value].points
const formatAverage = (stats) => (stats.performances ? stats.average.toFixed(2) : '–')
const formatMetric = (value) => (metric.value === 'average' ? value.toFixed(2) : `${value}`)

const eligibleVoters = computed(() =>
  (summary.value?.voters ?? []).filter(
    (voter) => voter[stage.value].performances >= minPerformances.value,
  ),
)

const rankedVoters = computed(() =>
  [...eligibleVoters.value].sort(
    (a, b) =>
      metricValue(b) - metricValue(a) ||
      b[stage.value].performances - a[stage.value].performances ||
      a.name.localeCompare(b.name),
  ),
)

// Top and bottom never share a country, even when only a few voters qualify.
const rankingSize = computed(() =>
  Math.min(RANKING_SIZE, Math.ceil(rankedVoters.value.length / 2)),
)
const mostGenerous = computed(() => rankedVoters.value.slice(0, rankingSize.value))
const leastGenerous = computed(() =>
  rankedVoters.value.slice(rankingSize.value).slice(-rankingSize.value).reverse(),
)

const chartMax = computed(() =>
  Math.max(1, ...[...mostGenerous.value, ...leastGenerous.value].map(metricValue)),
)
const benchmark = computed(() => {
  const voters = eligibleVoters.value
  if (!voters.length) return 0
  if (metric.value === 'points') {
    return voters.reduce((sum, voter) => sum + voter[stage.value].points, 0) / voters.length
  }
  const points = voters.reduce((sum, voter) => sum + voter[stage.value].points, 0)
  const performances = voters.reduce((sum, voter) => sum + voter[stage.value].performances, 0)
  return performances ? points / performances : 0
})
const benchmarkPosition = computed(() => `${Math.min(100, (benchmark.value / chartMax.value) * 100)}%`)

const tiles = computed(() => {
  if (!summary.value) return []
  return [
    { label: 'Average per performance', stats: summary.value.overall },
    { label: 'Average in the final', stats: summary.value.finals },
    { label: 'Average in semi-finals', stats: summary.value.semifinals },
  ]
})

const tableRows = computed(() => {
  const direction = sortDescending.value ? -1 : 1
  const value = (voter) => {
    if (sortKey.value === 'points') return voter.overall.points
    const stats = voter[sortKey.value]
    return stats.performances ? stats.average : null
  }

  return [...(summary.value?.voters ?? [])].sort((a, b) => {
    if (sortKey.value === 'name') return direction * a.name.localeCompare(b.name)

    // Countries with no shared performances stay last in both directions.
    const first = value(a)
    const second = value(b)
    if (first === null || second === null) {
      return (first === null) - (second === null) || a.name.localeCompare(b.name)
    }
    return direction * (first - second) || a.name.localeCompare(b.name)
  })
})

const ariaSort = (id) =>
  sortKey.value === id ? (sortDescending.value ? 'descending' : 'ascending') : 'none'

function sortBy(id) {
  if (sortKey.value === id) {
    sortDescending.value = !sortDescending.value
  } else {
    sortKey.value = id
    sortDescending.value = id !== 'name'
  }
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

function graphLink(voterCode) {
  return {
    name: 'voting-graph',
    query: {
      countries: `${countryCode.value},${voterCode}`,
      from: String(fromYear.value),
      to: String(toYear.value),
      round: 'all',
      type: voteType.value,
    },
  }
}

const requestParams = computed(() => ({
  country: countryCode.value,
  from: fromYear.value,
  to: toYear.value,
  type: voteType.value,
}))
const requestKey = computed(() => JSON.stringify(requestParams.value))

async function loadSummary() {
  const currentRequest = ++requestId
  loading.value = true
  error.value = ''

  try {
    const result = await getCountrySummary(requestParams.value)
    if (currentRequest === requestId) summary.value = result
  } catch {
    if (currentRequest === requestId) {
      summary.value = null
      error.value = 'No data available. Please try again later.'
    }
  } finally {
    if (currentRequest === requestId) loading.value = false
  }
}

async function loadCountries() {
  try {
    countries.value = await getVoteCountries()
  } catch {
    countries.value = []
  }
}

function readQuery() {
  const query = route.query

  if (typeof query.country === 'string' && query.country) {
    countryCode.value = query.country.toUpperCase()
  }

  const from = Number(query.from)
  const to = Number(query.to)
  if (Number.isInteger(from) && from >= FIRST_YEAR && from <= LAST_YEAR) fromYear.value = from
  if (Number.isInteger(to) && to >= FIRST_YEAR && to <= LAST_YEAR) toYear.value = to
  if (fromYear.value > toYear.value) fromYear.value = toYear.value

  if (VOTE_TYPES.some((item) => item.id === query.type)) voteType.value = query.type
  if (!canUseSplit.value) voteType.value = 'total'
}

function writeQuery() {
  if (route.name !== 'country-summary') return

  const query = {
    country: countryCode.value,
    from: String(fromYear.value),
    to: String(toYear.value),
    type: voteType.value,
  }
  const unchanged = Object.keys(query).every((key) => route.query[key] === query[key])
  if (!unchanged) router.replace({ query })
}

watch(canUseSplit, (allowed) => {
  if (!allowed) voteType.value = 'total'
})

watch(requestKey, () => {
  writeQuery()
  clearTimeout(debounceTimer)
  debounceTimer = setTimeout(loadSummary, 180)
})

readQuery()

onMounted(() => {
  loadCountries()
  loadSummary()
  writeQuery()
})

onActivated(() => {
  if (route.query.country) readQuery()
  else writeQuery()
})

onBeforeUnmount(() => clearTimeout(debounceTimer))
</script>

<template>
  <div class="details-page summary-page">
    <div class="backdrop" aria-hidden="true">
      <span class="orb orb-pink"></span>
      <span class="orb orb-blue"></span>
      <span class="orb orb-violet"></span>
    </div>

    <main class="content">
      <nav class="topbar" aria-label="Page navigation">
        <RouterLink class="pill" :to="{ name: 'contests' }">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 5-7 7 7 7" /></svg>
          All contests
        </RouterLink>
        <RouterLink class="pill" :to="{ name: 'voting-graph' }">Voting graph</RouterLink>
      </nav>

      <header class="hero">
        <p class="eyebrow">Eurovision archive</p>
        <h1>{{ summary?.name ?? 'Country Summary' }}</h1>
        <p class="lead">Who gives this country the most points, and who gives the fewest.</p>
      </header>

      <section class="panel filters" aria-label="Summary filters">
        <label class="field">
          <span>Country</span>
          <select v-model="countryCode">
            <option v-if="!countryOptions.length" :value="countryCode">{{ countryCode }}</option>
            <option v-for="country in countryOptions" :key="country.code" :value="country.code">
              {{ country.name }}
            </option>
          </select>
        </label>

        <label class="field">
          <span>From</span>
          <select :value="fromYear" @change="setFromYear($event.target.value)">
            <option v-for="year in YEARS" :key="year" :value="year">{{ year }}</option>
          </select>
        </label>

        <label class="field">
          <span>To</span>
          <select :value="toYear" @change="setToYear($event.target.value)">
            <option v-for="year in YEARS" :key="year" :value="year">{{ year }}</option>
          </select>
        </label>

        <div class="field">
          <span>Vote type</span>
          <div class="segmented" role="group" aria-label="Vote type">
            <button
              v-for="item in VOTE_TYPES"
              :key="item.id"
              type="button"
              :class="{ 'is-current': voteType === item.id }"
              :aria-pressed="voteType === item.id"
              :disabled="item.id !== 'total' && !canUseSplit"
              @click="voteType = item.id"
            >
              {{ item.label }}
            </button>
          </div>
        </div>

        <div class="presets">
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

        <p v-if="crossesScoringEras" class="notice">
          Since 2016 jury and televote are counted separately, so one country can give up to 24
          points. Averages across this period mix both scoring systems.
        </p>
        <p v-else-if="voteType !== 'total' && fromYear < FIRST_SPLIT_YEAR" class="notice">
          Jury and televote are split from 2016, so earlier years are not counted.
        </p>
      </section>

      <div v-if="error" class="panel empty" role="alert">
        <p>{{ error }}</p>
        <button class="retry" type="button" @click="loadSummary">Try again</button>
      </div>

      <div v-else-if="!summary" class="panel empty" role="status">Loading summary...</div>

      <div v-else-if="!summary.overall.performances" class="panel empty">
        {{ summary.name }} has no scored performances for this selection.
      </div>

      <div v-else class="results" :class="{ 'is-loading': loading }" :aria-busy="loading">
        <dl class="tiles">
          <div v-for="tile in tiles" :key="tile.label" class="panel tile">
            <dt>{{ tile.label }}</dt>
            <dd>{{ formatAverage(tile.stats) }}</dd>
            <p>
              {{ tile.stats.points }} points · {{ tile.stats.performances }}
              {{ tile.stats.performances === 1 ? 'performance' : 'performances' }}
            </p>
          </div>
        </dl>

        <section class="panel ranking">
          <div class="panel-heading">
            <div>
              <h2>Most and least points</h2>
              <p class="caption">
                {{ periodLabel }} · {{ stageLabel }} ·
                {{ metric === 'average' ? 'points per performance' : 'total points' }}
              </p>
            </div>
            <div class="ranking-controls">
              <div class="segmented" role="group" aria-label="Round">
                <button
                  v-for="item in STAGES"
                  :key="item.id"
                  type="button"
                  :class="{ 'is-current': stage === item.id }"
                  :aria-pressed="stage === item.id"
                  @click="stage = item.id"
                >
                  {{ item.label }}
                </button>
              </div>
              <div class="segmented" role="group" aria-label="Measure">
                <button
                  v-for="item in METRICS"
                  :key="item.id"
                  type="button"
                  :class="{ 'is-current': metric === item.id }"
                  :aria-pressed="metric === item.id"
                  @click="metric = item.id"
                >
                  {{ item.label }}
                </button>
              </div>
              <label class="min-field">
                <span>Min. shared performances</span>
                <select v-model.number="minPerformances">
                  <option v-for="value in MIN_PERFORMANCES" :key="value" :value="value">
                    {{ value }}
                  </option>
                </select>
              </label>
            </div>
          </div>

          <p v-if="!rankedVoters.length" class="message">
            No country meets this minimum. Lower it or widen the year range.
          </p>

          <template v-else>
            <p class="benchmark-note">
              <i aria-hidden="true"></i>
              Average across these {{ rankedVoters.length }} countries:
              <b>{{ benchmark.toFixed(2) }}</b>
            </p>

            <div class="charts">
              <article>
                <h3><i class="key most"></i>Gives the most</h3>
                <ol class="bars">
                  <li v-for="voter in mostGenerous" :key="voter.code">
                    <span class="name" :title="voter.name">{{ voter.name }}</span>
                    <span class="track" aria-hidden="true">
                      <i class="most" :style="{ width: `${(metricValue(voter) / chartMax) * 100}%` }"></i>
                      <em :style="{ left: benchmarkPosition }"></em>
                    </span>
                    <b>{{ formatMetric(metricValue(voter)) }}</b>
                    <small>{{ voter[stage].performances }}×</small>
                  </li>
                </ol>
              </article>

              <article v-if="leastGenerous.length">
                <h3><i class="key least"></i>Gives the least</h3>
                <ol class="bars">
                  <li v-for="voter in leastGenerous" :key="voter.code">
                    <span class="name" :title="voter.name">{{ voter.name }}</span>
                    <span class="track" aria-hidden="true">
                      <i class="least" :style="{ width: `${(metricValue(voter) / chartMax) * 100}%` }"></i>
                      <em :style="{ left: benchmarkPosition }"></em>
                    </span>
                    <b>{{ formatMetric(metricValue(voter)) }}</b>
                    <small>{{ voter[stage].performances }}×</small>
                  </li>
                </ol>
              </article>
            </div>
          </template>
        </section>

        <section class="panel table-panel">
          <div class="panel-heading">
            <div>
              <h2>Every voting country</h2>
              <p class="caption">
                Average points per performance. The number in brackets shows how many of
                {{ summary.name }}'s performances that country could vote on.
              </p>
            </div>
          </div>

          <div class="table-scroll" tabindex="0" aria-label="Voting countries table, scrollable">
            <table>
              <thead>
                <tr>
                  <th v-for="column in COLUMNS" :key="column.id" scope="col" :aria-sort="ariaSort(column.id)">
                    <button type="button" @click="sortBy(column.id)">
                      {{ column.label }}
                      <span v-if="sortKey === column.id" aria-hidden="true">
                        {{ sortDescending ? '↓' : '↑' }}
                      </span>
                    </button>
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="voter in tableRows" :key="voter.code">
                  <th scope="row">
                    <RouterLink :to="graphLink(voter.code)">{{ voter.name }}</RouterLink>
                  </th>
                  <td>{{ formatAverage(voter.overall) }} <small>({{ voter.overall.performances }})</small></td>
                  <td>{{ formatAverage(voter.finals) }} <small>({{ voter.finals.performances }})</small></td>
                  <td>{{ formatAverage(voter.semifinals) }} <small>({{ voter.semifinals.performances }})</small></td>
                  <td>{{ voter.overall.points }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </div>
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
  left: -18vmax;
  background: var(--pink);
}

.orb-blue {
  top: 20vmax;
  right: -30vmax;
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
  width: min(1180px, 100%);
  margin: 0 auto;
  padding: 18px 24px 80px;
  box-sizing: border-box;
}

.topbar {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}

.pill {
  display: inline-flex;
  align-items: center;
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
  white-space: nowrap;
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
  font-size: clamp(46px, 8.5vw, 104px);
  font-weight: 400;
  line-height: 1;
  letter-spacing: 0;
  overflow-wrap: anywhere;
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
  grid-template-columns: minmax(180px, 1.3fr) 110px 110px auto;
  align-items: end;
  gap: 12px;
}

.field {
  display: grid;
  gap: 4px;
  min-width: 0;
  color: var(--muted);
  font-size: 13px;
}

select {
  min-height: 42px;
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

.presets {
  display: flex;
  flex-wrap: wrap;
  grid-column: 1 / -1;
  gap: 5px;
}

.presets button {
  min-height: 34px;
  padding: 0 12px;
  border: 1px solid var(--glass-line);
  border-radius: 999px;
  color: var(--ink);
  background: rgba(255, 255, 255, 0.05);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}

.presets button:hover {
  background: var(--glass-strong);
}

.presets button.is-on {
  border-color: transparent;
  color: #fff;
  background: linear-gradient(120deg, var(--pink), var(--violet));
}

.notice {
  grid-column: 1 / -1;
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
  gap: 16px;
  min-width: 0;
  transition: opacity 0.2s ease;
}

.results.is-loading {
  opacity: 0.72;
}

.tiles {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin: 0;
}

.tile dt {
  color: var(--muted);
  font-size: 14px;
}

.tile dd {
  margin: 2px 0;
  font-size: clamp(32px, 4.4vw, 46px);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  line-height: 1.1;
}

.tile p {
  color: var(--muted);
  font-size: 14px;
}

.ranking .panel-heading {
  align-items: flex-start;
}

.caption,
.message {
  color: var(--muted);
  font-size: 14px;
}

.ranking-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.min-field {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--muted);
  font-size: 13px;
}

.min-field select {
  min-height: 38px;
}

.benchmark-note {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 7px;
  margin-bottom: 12px;
  color: var(--muted);
  font-size: 14px;
}

.benchmark-note i {
  width: 0;
  height: 16px;
  border-left: 2px dashed #fff;
}

.benchmark-note b {
  color: #fff;
}

.charts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 26px;
}

.charts article {
  min-width: 0;
}

h3 {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0 0 10px;
  color: #fff;
  font-size: 17px;
  font-weight: 600;
}

.key {
  width: 12px;
  height: 12px;
  border-radius: 3px;
}

.key.most,
.track i.most {
  background: var(--televote);
}

.key.least,
.track i.least {
  background: var(--jury);
}

.bars {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.bars li {
  display: grid;
  grid-template-columns: 124px minmax(0, 1fr) 50px 30px;
  align-items: center;
  gap: 8px;
  font-size: 14px;
}

.name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.track {
  position: relative;
  display: block;
  height: 14px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.08);
}

.track i {
  display: block;
  height: 100%;
  border-radius: 4px;
  transition: width 0.35s ease;
}

.track em {
  position: absolute;
  top: -3px;
  bottom: -3px;
  border-left: 2px dashed rgba(255, 255, 255, 0.86);
}

.bars b {
  font-variant-numeric: tabular-nums;
  text-align: right;
}

.bars small {
  color: var(--muted);
  font-size: 12px;
  text-align: right;
}

.table-scroll {
  max-height: 620px;
  overflow: auto;
  border: 1px solid var(--glass-line);
  border-radius: 14px;
  background: rgba(14, 8, 38, 0.64);
}

table {
  width: 100%;
  min-width: 620px;
  border-collapse: collapse;
  font-size: 14px;
  font-variant-numeric: tabular-nums;
}

thead th {
  position: sticky;
  top: 0;
  z-index: 2;
  background: #17102f;
}

thead th:first-child {
  left: 0;
  z-index: 3;
}

th button {
  width: 100%;
  min-height: 42px;
  padding: 0 10px;
  border: 0;
  color: #fff;
  background: transparent;
  font: inherit;
  font-weight: 600;
  text-align: right;
  white-space: nowrap;
  cursor: pointer;
}

th:first-child button {
  text-align: left;
}

th button:hover {
  background: var(--glass-strong);
}

tbody th {
  position: sticky;
  left: 0;
  z-index: 1;
  padding: 8px 10px;
  background: #17102f;
  font-weight: 500;
  text-align: left;
  white-space: nowrap;
}

tbody th a {
  color: #fff;
  text-decoration: underline;
  text-decoration-color: rgba(255, 255, 255, 0.34);
  text-underline-offset: 3px;
}

td {
  padding: 8px 10px;
  text-align: right;
  white-space: nowrap;
}

td small {
  color: var(--muted);
  font-size: 12px;
}

tbody tr {
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}

tbody tr:hover td {
  background: rgba(255, 255, 255, 0.07);
}

@keyframes drift {
  to {
    transform: translate(9vmax, 6vmax) scale(1.14);
  }
}

@media (prefers-reduced-motion: reduce) {
  .orb {
    animation: none;
  }

  .track i {
    transition: none;
  }
}

@media (max-width: 900px) {
  .filters {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  }

  .filters > .field:first-child,
  .filters > .field:nth-child(4) {
    grid-column: 1 / -1;
  }

  .filters .segmented {
    display: flex;
  }

  .filters .segmented button {
    flex: 1 0 auto;
  }

  .charts {
    grid-template-columns: minmax(0, 1fr);
  }

  .ranking-controls {
    justify-content: flex-start;
  }
}

@media (max-width: 640px) {
  .content {
    padding-inline: 16px;
  }

  .tiles {
    grid-template-columns: minmax(0, 1fr);
    gap: 8px;
  }

  .ranking-controls,
  .ranking-controls .segmented {
    width: 100%;
  }

  .ranking-controls .segmented {
    display: flex;
    box-sizing: border-box;
  }

  .ranking-controls .segmented button {
    flex: 1 0 auto;
    padding-inline: 8px;
  }

  .bars li {
    grid-template-columns: 96px minmax(0, 1fr) 46px 26px;
    gap: 6px;
  }
}
</style>
