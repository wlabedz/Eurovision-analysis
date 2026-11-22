<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getContestDetails, getContests } from '../api'
import ResultsPanel from '../components/contest/ResultsPanel.vue'
import StatsPanel from '../components/contest/StatsPanel.vue'
import VotesPanel from '../components/contest/VotesPanel.vue'
import {
  ROUND_LABELS,
  availableVoteTypes,
  buildRows,
  sortRounds,
} from '../utils/contestStats'

const props = defineProps({
  year: { type: String, required: true },
})

const route = useRoute()
const router = useRouter()

const contest = ref(null)
const years = ref([])
const loading = ref(true)
const error = ref('')
const logoFailed = ref(false)
let requestId = 0

const rounds = computed(() =>
  contest.value
    ? sortRounds(contest.value.rounds).filter((round) => round.performances.length > 0)
    : [],
)
const isCancelled = computed(() => Boolean(contest.value) && rounds.value.length === 0)

const activeRound = computed(
  () => rounds.value.find((round) => round.name === route.query.round) ?? rounds.value[0],
)
const rows = computed(() =>
  activeRound.value ? buildRows(contest.value, activeRound.value) : [],
)
const hasVotes = computed(() => availableVoteTypes(rows.value).length > 0)

const tabs = computed(() => [
  { id: 'results', label: 'Results' },
  ...(hasVotes.value
    ? [
        { id: 'votes', label: 'Votes' },
        { id: 'stats', label: 'Statistics' },
      ]
    : []),
])
const activeTab = computed(() =>
  tabs.value.some((tab) => tab.id === route.query.tab) ? route.query.tab : 'results',
)

const winners = computed(() => {
  const finalRound = rounds.value.find((round) => round.name === 'final')
  return finalRound
    ? buildRows(contest.value, finalRound).filter((row) => row.place === 1)
    : []
})

const yearIndex = computed(() => years.value.indexOf(Number(props.year)))
const previousYear = computed(() => years.value[yearIndex.value - 1])
const nextYear = computed(() =>
  yearIndex.value >= 0 ? years.value[yearIndex.value + 1] : undefined,
)

const yearLink = (year) => ({
  name: 'contest',
  params: { year: String(year) },
  query: route.query.tab ? { tab: route.query.tab } : {},
})

function setQuery(values) {
  router.replace({ query: { ...route.query, ...values } })
}

async function loadContest() {
  const currentRequest = ++requestId
  loading.value = true
  error.value = ''
  logoFailed.value = false

  try {
    const details = await getContestDetails(props.year)
    if (currentRequest === requestId) contest.value = details
  } catch {
    if (currentRequest === requestId) {
      contest.value = null
      error.value = `No data available for ${props.year}.`
    }
  } finally {
    if (currentRequest === requestId) loading.value = false
  }
}

watch(() => props.year, loadContest, { immediate: true })

onMounted(async () => {
  try {
    const list = await getContests()
    years.value = list.map((item) => item.year).sort((a, b) => a - b)
  } catch {
    years.value = []
  }
})
</script>

<template>
  <div class="details-page">
    <div class="backdrop" aria-hidden="true">
      <span class="orb orb-pink"></span>
      <span class="orb orb-blue"></span>
      <span class="orb orb-violet"></span>
    </div>

    <main class="content">
      <nav class="topbar" aria-label="Contest navigation">
        <RouterLink class="pill" :to="{ name: 'contests' }">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 5-7 7 7 7" /></svg>
          All contests
        </RouterLink>
        <div class="year-nav">
          <RouterLink v-if="previousYear" class="pill" :to="yearLink(previousYear)">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 5-7 7 7 7" /></svg>
            {{ previousYear }}
          </RouterLink>
          <RouterLink v-if="nextYear" class="pill" :to="yearLink(nextYear)">
            {{ nextYear }}
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 5 7 7-7 7" /></svg>
          </RouterLink>
        </div>
      </nav>

      <div v-if="loading" class="state" role="status">
        <div class="skeleton" aria-hidden="true"></div>
        <p>Loading contest...</p>
      </div>

      <div v-else-if="error" class="state panel" role="alert">
        <p>{{ error }}</p>
        <button class="retry" type="button" @click="loadContest">Try again</button>
      </div>

      <template v-else-if="contest">
        <header class="hero">
          <div class="logo-plate">
            <img
              v-if="contest.logoUrl && !logoFailed"
              :src="contest.logoUrl"
              :alt="`Logo ${contest.year}`"
              @error="logoFailed = true"
            />
            <span v-else>{{ contest.year }}</span>
          </div>

          <div class="hero-copy">
            <p class="eyebrow">Eurovision {{ contest.year }}</p>
            <h1>{{ contest.city }}</h1>
            <p class="meta">{{ contest.arena }} · {{ contest.country }}</p>
            <p v-if="contest.slogan" class="slogan">“{{ contest.slogan }}”</p>

            <dl class="facts">
              <div v-if="winners.length">
                <dt>{{ winners.length > 1 ? 'Winners' : 'Winner' }}</dt>
                <dd>
                  <template v-for="(winner, index) in winners" :key="winner.id">
                    {{ winner.country }}{{ index < winners.length - 1 ? ', ' : '' }}
                  </template>
                </dd>
              </div>
              <div v-if="winners.length === 1">
                <dt>Winning song</dt>
                <dd>{{ winners[0].artist }} · {{ winners[0].song }}</dd>
              </div>
              <div>
                <dt>Entries</dt>
                <dd>{{ contest.contestants.length }}</dd>
              </div>
              <div v-if="contest.presenters.length">
                <dt>Presenters</dt>
                <dd>{{ contest.presenters.join(', ') }}</dd>
              </div>
            </dl>
          </div>
        </header>

        <section v-if="isCancelled" class="panel">
          <div class="panel-heading">
            <h2>Contest cancelled</h2>
          </div>
          <p class="cancel-note">
            This contest did not take place, so there are no results or votes. These were the
            selected entries.
          </p>
          <ul class="entries">
            <li v-for="entry in contest.contestants" :key="entry.id">
              <strong>{{ contest.countries[entry.country] ?? entry.country }}</strong>
              <span>{{ entry.artist }} · {{ entry.song }}</span>
            </li>
          </ul>
        </section>

        <template v-else>
          <div class="toolbar">
            <div v-if="rounds.length > 1" class="segmented" role="group" aria-label="Round">
              <button
                v-for="round in rounds"
                :key="round.name"
                type="button"
                :class="{ 'is-current': activeRound?.name === round.name }"
                :aria-pressed="activeRound?.name === round.name"
                @click="setQuery({ round: round.name })"
              >
                {{ ROUND_LABELS[round.name] ?? round.name }}
              </button>
            </div>

            <div v-if="tabs.length > 1" class="segmented" role="group" aria-label="Section">
              <button
                v-for="tab in tabs"
                :key="tab.id"
                type="button"
                :class="{ 'is-current': activeTab === tab.id }"
                :aria-pressed="activeTab === tab.id"
                @click="setQuery({ tab: tab.id })"
              >
                {{ tab.label }}
              </button>
            </div>
          </div>

          <ResultsPanel v-if="activeTab === 'results'" :rows="rows" />
          <VotesPanel
            v-else-if="activeTab === 'votes'"
            :rows="rows"
            :countries="contest.countries"
          />
          <StatsPanel v-else :rows="rows" :countries="contest.countries" />
        </template>
      </template>
    </main>
  </div>
</template>

<style>
.details-page {
  --ink: #fff;
  --muted: rgba(235, 228, 255, 0.76);
  --pink: #ff2e93;
  --blue: #27c8ff;
  --violet: #7b3cff;
  --jury: #3987e5;
  --televote: #d55181;
  --glass: rgba(255, 255, 255, 0.08);
  --glass-strong: rgba(255, 255, 255, 0.14);
  --glass-line: rgba(255, 255, 255, 0.18);
  --shadow: 0 24px 60px rgba(4, 0, 24, 0.5);

  position: relative;
  min-height: 100svh;
  overflow-x: clip;
  color: var(--ink);
  background: #0b0620;
  font-family: 'Outfit', system-ui, sans-serif;
  text-align: left;
}

.details-page .panel {
  min-width: 0;
  padding: 18px;
  border: 1px solid var(--glass-line);
  border-radius: 22px;
  background: var(--glass);
  box-shadow: var(--shadow), inset 0 1px 0 rgba(255, 255, 255, 0.2);
  -webkit-backdrop-filter: blur(22px) saturate(1.4);
  backdrop-filter: blur(22px) saturate(1.4);
}

.details-page .panel-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px 16px;
  margin-bottom: 14px;
}

.details-page .panel-heading h2 {
  margin: 0;
  color: #fff;
  font-family: 'Kaushan Script', 'Brush Script MT', cursive;
  font-size: clamp(28px, 4vw, 38px);
  font-weight: 400;
  letter-spacing: 0;
}

.details-page .segmented {
  display: inline-flex;
  max-width: 100%;
  gap: 3px;
  padding: 4px;
  overflow-x: auto;
  border: 1px solid var(--glass-line);
  border-radius: 999px;
  background: var(--glass);
  -webkit-backdrop-filter: blur(18px);
  backdrop-filter: blur(18px);
  scrollbar-width: none;
}

.details-page .segmented button {
  flex: none;
  min-height: 38px;
  padding: 0 14px;
  border: 0;
  border-radius: 999px;
  color: var(--muted);
  background: transparent;
  font: inherit;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: color 0.2s ease, background 0.2s ease;
}

.details-page .segmented button:hover {
  color: #fff;
  background: var(--glass-strong);
}

.details-page .segmented button.is-current {
  color: #fff;
  background: linear-gradient(120deg, var(--pink), var(--violet));
}

.details-page button:focus-visible,
.details-page a:focus-visible,
.details-page select:focus-visible,
.details-page [tabindex='0']:focus-visible {
  outline: 2px solid var(--blue);
  outline-offset: 2px;
}

.details-page .sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
</style>

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
  opacity: 0.38;
  animation: drift 20s ease-in-out infinite alternate;
}

.orb-pink {
  top: -30vmax;
  left: -18vmax;
  background: var(--pink);
}

.orb-blue {
  top: 16vmax;
  right: -30vmax;
  background: var(--blue);
  opacity: 0.24;
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
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.year-nav {
  display: flex;
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
  transition: background 0.2s ease;
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
  display: grid;
  grid-template-columns: minmax(240px, 380px) minmax(0, 1fr);
  align-items: center;
  gap: 30px;
  padding: 12px 0 8px;
}

.logo-plate {
  display: grid;
  place-items: center;
  height: clamp(190px, 21vw, 248px);
  padding: 16px;
  box-sizing: border-box;
  border: 8px solid rgba(255, 255, 255, 0.16);
  border-radius: 26px;
  color: #4c4364;
  background: #fff;
  background-clip: padding-box;
  box-shadow: 0 26px 70px rgba(255, 46, 147, 0.34);
  font-size: 34px;
  font-weight: 700;
}

.logo-plate img {
  display: block;
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
  object-fit: contain;
}

.hero-copy {
  min-width: 0;
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
  margin: 0;
  padding: 0.06em 0.16em 0.16em 0;
  font-family: 'Kaushan Script', 'Brush Script MT', cursive;
  font-size: clamp(52px, 8vw, 96px);
  font-weight: 400;
  line-height: 1;
  letter-spacing: 0;
  overflow-wrap: anywhere;
  color: transparent;
  background: linear-gradient(100deg, #fff 8%, #ff8ac5 38%, #ff5eae 55%, #76dcff 82%);
  background-clip: text;
  -webkit-background-clip: text;
  filter: drop-shadow(0 8px 26px rgba(255, 46, 147, 0.4));
}

.meta {
  font-size: 17px;
}

.slogan {
  margin-top: 2px;
  color: #ffb4da;
  font-size: 16px;
}

.facts {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 28px;
  margin: 14px 0 0;
}

.facts > div {
  min-width: 0;
}

.facts dt {
  color: var(--muted);
  font-size: 12px;
}

.facts dd {
  margin: 0;
  font-size: 16px;
  font-weight: 500;
}

.toolbar {
  position: sticky;
  top: 10px;
  z-index: 20;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.state {
  display: grid;
  justify-items: center;
  gap: 16px;
  width: min(520px, 100%);
  margin: 40px auto;
  box-sizing: border-box;
  color: var(--muted);
  text-align: center;
}

.skeleton {
  width: min(400px, 100%);
  aspect-ratio: 4 / 3;
  border: 1px solid var(--glass-line);
  border-radius: 24px;
  background: linear-gradient(110deg, var(--glass) 30%, var(--glass-strong) 50%, var(--glass) 70%);
  background-size: 220% 100%;
  animation: shimmer 1.5s linear infinite;
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

.cancel-note {
  margin-bottom: 14px;
  color: var(--muted);
}

.entries {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.entries li {
  display: grid;
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid var(--glass-line);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.05);
}

.entries span {
  color: var(--muted);
  font-size: 14px;
}

@keyframes drift {
  to {
    transform: translate(9vmax, 6vmax) scale(1.14);
  }
}

@keyframes shimmer {
  from {
    background-position: 100% 0;
  }
  to {
    background-position: -100% 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .orb,
  .skeleton {
    animation: none;
  }
}

@media (max-width: 760px) {
  .content {
    padding-inline: 16px;
  }

  .hero {
    grid-template-columns: 1fr;
    gap: 14px;
  }

  .logo-plate {
    height: 190px;
  }

  .toolbar {
    position: static;
    align-items: stretch;
    flex-direction: column;
  }

  .toolbar .segmented {
    display: flex;
  }

  .toolbar .segmented button {
    flex: 1 0 auto;
    padding-inline: 10px;
  }

  .details-page .panel {
    padding: 12px;
  }
}
</style>
