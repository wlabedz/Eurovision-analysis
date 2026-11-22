<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { getContests } from '../api'

// 2020 had a host, but the contest was cancelled, so it does not count as hosted.
const CANCELLED_YEARS = new Set([2020])

const SORT_OPTIONS = [
  { id: 'hosted', label: 'Most hosted' },
  { id: 'latest', label: 'Latest' },
  { id: 'name', label: 'A–Z' },
]

const contests = ref([])
const loading = ref(true)
const error = ref('')
const failedLogos = ref(new Set())
const search = ref('')
const sortBy = ref('hosted')
const openCountries = ref(new Set())

const isCancelled = (contest) => CANCELLED_YEARS.has(contest.year)
const rowId = (name) => `host-${name.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`

const rankedCountries = computed(() => {
  const groups = new Map()

  for (const contest of contests.value) {
    if (!groups.has(contest.country)) groups.set(contest.country, [])
    groups.get(contest.country).push(contest)
  }

  const countries = [...groups].map(([name, editions]) => {
    const sorted = [...editions].sort((a, b) => b.year - a.year)
    const held = sorted.filter((contest) => !isCancelled(contest))

    return {
      name,
      editions: sorted,
      count: held.length,
      first: held[held.length - 1]?.year,
      latest: held[0]?.year,
      cities: new Set(held.map((contest) => contest.city)).size,
      logoContest: held[0] ?? sorted[0],
    }
  })

  countries.sort((a, b) => b.count - a.count || b.latest - a.latest)

  let rank = 0
  return countries.map((country, index) => {
    if (index === 0 || country.count !== countries[index - 1].count) rank = index + 1
    return { ...country, rank }
  })
})

const podium = computed(() => rankedCountries.value.slice(0, 3))
const maxCount = computed(() => rankedCountries.value[0]?.count ?? 1)

const stats = computed(() => {
  const list = rankedCountries.value
  if (!list.length) return []

  return [
    { label: 'Host countries', value: list.length },
    { label: 'Contests held', value: list.reduce((sum, country) => sum + country.count, 0) },
    { label: 'One-time hosts', value: list.filter((country) => country.count === 1).length },
  ]
})

const visibleCountries = computed(() => {
  const query = search.value.trim().toLowerCase()
  const list = rankedCountries.value.filter((country) =>
    country.name.toLowerCase().includes(query),
  )

  if (sortBy.value === 'latest') return [...list].sort((a, b) => b.latest - a.latest)
  if (sortBy.value === 'name') return [...list].sort((a, b) => a.name.localeCompare(b.name))
  return list
})

function toggle(name) {
  if (openCountries.value.has(name)) openCountries.value.delete(name)
  else openCountries.value.add(name)
}

async function showCountry(name) {
  search.value = ''
  openCountries.value.add(name)
  await nextTick()
  document.getElementById(rowId(name))?.scrollIntoView({ block: 'start' })
}

function timesLabel(count) {
  return count === 1 ? 'time' : 'times'
}

function yearRange(country) {
  return country.first === country.latest
    ? `${country.latest}`
    : `${country.first}–${country.latest}`
}

async function loadContests() {
  loading.value = true
  error.value = ''

  try {
    contests.value = await getContests()
  } catch {
    error.value = 'No data available. Please try again later.'
  } finally {
    loading.value = false
  }
}

onMounted(loadContests)
</script>

<template>
  <div class="page">
    <div class="backdrop" aria-hidden="true">
      <span class="orb orb-pink"></span>
      <span class="orb orb-blue"></span>
      <span class="orb orb-violet"></span>
    </div>

    <main class="content">
      <RouterLink class="back glass" :to="{ name: 'contests' }">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 5-7 7 7 7" /></svg>
        All contests
      </RouterLink>

      <header class="hero">
        <p class="eyebrow">Eurovision archive</p>
        <h1>Host Countries</h1>
        <dl v-if="stats.length" class="stats">
          <div v-for="stat in stats" :key="stat.label" class="stat">
            <dd>{{ stat.value }}</dd>
            <dt>{{ stat.label }}</dt>
          </div>
        </dl>
      </header>

      <div v-if="loading" class="state" role="status">
        <div class="skeleton" aria-hidden="true"></div>
        <p>Loading host countries...</p>
      </div>

      <div v-else-if="error" class="state glass" role="alert">
        <p>{{ error }}</p>
        <button class="retry" type="button" @click="loadContests">Try again</button>
      </div>

      <template v-else>
        <section class="podium" aria-label="Top host countries">
          <button
            v-for="(country, index) in podium"
            :key="country.name"
            class="podium-card"
            :class="`place-${index + 1}`"
            type="button"
            @click="showCountry(country.name)"
          >
            <span class="medal">#{{ country.rank }}</span>
            <span class="plate">
              <img
                v-if="country.logoContest.logoUrl && !failedLogos.has(country.logoContest.year)"
                :src="country.logoContest.logoUrl"
                :alt="`Logo ${country.logoContest.year}`"
                @error="failedLogos.add(country.logoContest.year)"
              />
              <span v-else class="fallback">{{ country.logoContest.year }}</span>
            </span>
            <strong>{{ country.name }}</strong>
            <span class="podium-count">
              <b>{{ country.count }}</b> {{ timesLabel(country.count) }}
            </span>
            <small>{{ yearRange(country) }}</small>
          </button>
        </section>

        <div class="toolbar glass">
          <label class="search">
            <span class="sr-only">Search host countries</span>
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <circle cx="11" cy="11" r="6.5" />
              <path d="m16 16 4.5 4.5" />
            </svg>
            <input v-model="search" type="search" placeholder="Search country" />
          </label>

          <div class="sort" role="group" aria-label="Sort countries">
            <button
              v-for="option in SORT_OPTIONS"
              :key="option.id"
              type="button"
              :class="{ 'is-current': sortBy === option.id }"
              :aria-pressed="sortBy === option.id"
              @click="sortBy = option.id"
            >
              {{ option.label }}
            </button>
          </div>
        </div>

        <p v-if="!visibleCountries.length" class="empty" role="status">
          No host country matches “{{ search }}”.
        </p>

        <ol v-else class="list">
          <li
            v-for="country in visibleCountries"
            :id="rowId(country.name)"
            :key="country.name"
            class="row"
            :class="{ 'is-open': openCountries.has(country.name) }"
          >
            <button
              class="row-head"
              type="button"
              :aria-expanded="openCountries.has(country.name)"
              :aria-controls="`${rowId(country.name)}-editions`"
              @click="toggle(country.name)"
            >
              <span class="rank">{{ country.rank }}</span>
              <span class="row-main">
                <strong>{{ country.name }}</strong>
                <span class="bar" aria-hidden="true">
                  <i :style="{ width: `${(country.count / maxCount) * 100}%` }"></i>
                </span>
              </span>
              <span class="range">{{ yearRange(country) }}</span>
              <span class="count">
                <b>{{ country.count }}</b> {{ timesLabel(country.count) }}
              </span>
              <svg class="chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg>
            </button>

            <ul
              v-if="openCountries.has(country.name)"
              :id="`${rowId(country.name)}-editions`"
              class="editions"
            >
              <li v-for="contest in country.editions" :key="contest.year">
                <RouterLink
                  class="edition"
                  :to="{ name: 'contest', params: { year: String(contest.year) } }"
                >
                <span class="logo">
                  <img
                    v-if="contest.logoUrl && !failedLogos.has(contest.year)"
                    :src="contest.logoUrl"
                    :alt="`Logo ${contest.year}`"
                    loading="lazy"
                    @error="failedLogos.add(contest.year)"
                  />
                  <span v-else class="fallback">{{ contest.year }}</span>
                </span>
                <span class="edition-year">
                  <strong>{{ contest.year }}</strong>
                  <em v-if="isCancelled(contest)">Cancelled</em>
                </span>
                <span>{{ contest.city }}</span>
                <small>{{ contest.arena }}</small>
                </RouterLink>
              </li>
            </ul>
          </li>
        </ol>
      </template>
    </main>
  </div>
</template>

<style scoped>
.page {
  --ink: #fff;
  --muted: rgba(235, 228, 255, 0.72);
  --pink: #ff2e93;
  --blue: #27c8ff;
  --violet: #7b3cff;
  --glass: rgba(255, 255, 255, 0.075);
  --glass-strong: rgba(255, 255, 255, 0.13);
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
  opacity: 0.42;
  animation: drift 20s ease-in-out infinite alternate;
}

.orb-pink {
  top: -28vmax;
  right: -16vmax;
  background: var(--pink);
}

.orb-blue {
  top: 12vmax;
  left: -28vmax;
  background: var(--blue);
  opacity: 0.26;
  animation-delay: -7s;
}

.orb-violet {
  bottom: -32vmax;
  left: 22vw;
  background: var(--violet);
  animation-delay: -13s;
}

.content {
  position: relative;
  width: min(1080px, 100%);
  margin: 0 auto;
  padding: 20px 24px 80px;
  box-sizing: border-box;
}

.glass {
  border: 1px solid var(--glass-line);
  background: var(--glass);
  box-shadow: var(--shadow), inset 0 1px 0 rgba(255, 255, 255, 0.2);
  -webkit-backdrop-filter: blur(22px) saturate(1.4);
  backdrop-filter: blur(22px) saturate(1.4);
}

.back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 42px;
  padding: 0 16px 0 10px;
  box-sizing: border-box;
  border-radius: 999px;
  color: #fff;
  font-size: 15px;
  font-weight: 500;
  text-decoration: none;
  transition: background 0.2s ease;
}

.back:hover {
  background: var(--glass-strong);
}

.back svg,
.search svg,
.chevron {
  flex: none;
  width: 20px;
  height: 20px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2.2;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.hero {
  padding: 14px 0 30px;
  text-align: center;
}

.eyebrow {
  color: var(--muted);
  font-size: 13px;
  font-weight: 500;
  letter-spacing: 0.32em;
  text-transform: uppercase;
}

h1 {
  margin: 0;
  padding: 0.1em 0.15em 0.18em;
  font-family: 'Kaushan Script', 'Brush Script MT', cursive;
  font-size: clamp(50px, 10vw, 118px);
  font-weight: 400;
  line-height: 1;
  letter-spacing: 0;
  color: transparent;
  background: linear-gradient(100deg, #fff 8%, #ff8ac5 30%, var(--pink) 45%, var(--blue) 68%, #fff 92%);
  background-size: 220% 100%;
  background-clip: text;
  -webkit-background-clip: text;
  filter: drop-shadow(0 8px 30px rgba(255, 46, 147, 0.42));
  animation: shimmer 9s ease-in-out infinite alternate;
}

.stats {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px 38px;
  margin: 0;
}

.stat {
  display: flex;
  align-items: baseline;
  gap: 7px;
}

.stat dd {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.stat dt {
  color: var(--muted);
  font-size: 14px;
}

.state {
  display: grid;
  justify-items: center;
  gap: 16px;
  width: min(520px, 100%);
  margin: 40px auto;
  padding: 24px;
  box-sizing: border-box;
  border-radius: 22px;
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

.podium {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  align-items: end;
  gap: 18px;
}

.podium-card {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  min-width: 0;
  padding: 10px 10px 18px;
  border: 1px solid var(--glass-line);
  border-radius: 24px;
  color: #fff;
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.18), rgba(255, 255, 255, 0.05));
  box-shadow: var(--shadow), inset 0 1px 0 rgba(255, 255, 255, 0.28);
  -webkit-backdrop-filter: blur(18px);
  backdrop-filter: blur(18px);
  font: inherit;
  cursor: pointer;
  transition: transform 0.25s ease, box-shadow 0.25s ease;
}

.podium-card:hover {
  box-shadow: 0 26px 64px rgba(255, 46, 147, 0.34), inset 0 1px 0 rgba(255, 255, 255, 0.36);
  transform: translateY(-6px);
}

.place-1 {
  order: 2;
  padding-bottom: 34px;
  box-shadow: 0 28px 80px rgba(255, 46, 147, 0.38), inset 0 1px 0 rgba(255, 255, 255, 0.4);
}

.place-2 {
  order: 1;
}

.place-3 {
  order: 3;
}

.medal {
  position: absolute;
  top: 18px;
  left: 18px;
  z-index: 1;
  padding: 3px 10px;
  border-radius: 999px;
  color: #fff;
  background: linear-gradient(120deg, var(--pink), var(--violet));
  box-shadow: 0 6px 16px rgba(123, 60, 255, 0.45);
  font-size: 14px;
  font-weight: 700;
}

.plate {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: clamp(130px, 17vw, 180px);
  margin-bottom: 10px;
  padding: 12px;
  box-sizing: border-box;
  border-radius: 16px;
  background: #fff;
}

.place-1 .plate {
  height: clamp(160px, 21vw, 224px);
}

.plate img,
.logo img {
  display: block;
  width: 100%;
  height: 100%;
  min-width: 0;
  object-fit: contain;
}

.podium-card strong {
  max-width: 100%;
  font-size: 22px;
  font-weight: 600;
  line-height: 1.2;
  text-align: center;
}

.podium-count {
  color: #ffb4da;
  font-size: 15px;
}

.podium-count b {
  color: #fff;
  font-size: 30px;
  font-weight: 700;
}

.podium-card small {
  color: var(--muted);
  font-size: 13px;
}

.toolbar {
  position: sticky;
  top: 12px;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 34px;
  padding: 5px;
  border-radius: 999px;
}

.search {
  display: flex;
  flex: 1;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding-left: 13px;
  color: var(--muted);
}

.search input {
  width: 100%;
  min-width: 0;
  height: 40px;
  padding: 0 8px;
  border: 0;
  border-radius: 999px;
  outline-offset: -2px;
  color: #fff;
  background: transparent;
  font: inherit;
  font-size: 16px;
}

.search input::placeholder {
  color: var(--muted);
}

.sort {
  display: flex;
  flex: none;
  gap: 3px;
}

.sort button {
  min-height: 40px;
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

.sort button:hover {
  color: #fff;
  background: var(--glass-strong);
}

.sort button.is-current {
  color: #fff;
  background: linear-gradient(120deg, var(--pink), var(--violet));
}

.empty {
  padding: 40px 0;
  color: var(--muted);
  text-align: center;
}

.list {
  display: grid;
  gap: 10px;
  margin: 18px 0 0;
  padding: 0;
  list-style: none;
}

.row {
  overflow: hidden;
  border: 1px solid var(--glass-line);
  border-radius: 18px;
  background: var(--glass);
  box-shadow: 0 10px 26px rgba(4, 0, 24, 0.3), inset 0 1px 0 rgba(255, 255, 255, 0.16);
  -webkit-backdrop-filter: blur(12px);
  backdrop-filter: blur(12px);
  scroll-margin-top: 76px;
  transition: border-color 0.2s ease, background 0.2s ease;
}

.row:hover,
.row.is-open {
  border-color: rgba(255, 255, 255, 0.36);
  background: var(--glass-strong);
}

.row-head {
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr) 104px 86px 22px;
  align-items: center;
  gap: 12px;
  width: 100%;
  min-height: 66px;
  padding: 9px 16px;
  border: 0;
  color: #fff;
  background: transparent;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.rank {
  color: var(--muted);
  font-size: 18px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  text-align: center;
}

.row-main {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.row-main strong {
  overflow: hidden;
  font-size: 17px;
  font-weight: 600;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bar {
  display: block;
  height: 5px;
  overflow: hidden;
  border-radius: 5px;
  background: rgba(255, 255, 255, 0.12);
}

.bar i {
  display: block;
  height: 100%;
  border-radius: 5px;
  background: linear-gradient(90deg, var(--pink), var(--blue));
  transform-origin: left;
  animation: grow 0.8s cubic-bezier(0.2, 0.8, 0.2, 1) both;
}

.range {
  color: var(--muted);
  font-size: 14px;
  font-variant-numeric: tabular-nums;
  text-align: right;
}

.count {
  color: var(--muted);
  font-size: 13px;
  text-align: right;
  white-space: nowrap;
}

.count b {
  color: #fff;
  font-size: 22px;
  font-weight: 700;
}

.chevron {
  color: var(--muted);
  transition: transform 0.25s ease;
}

.is-open .chevron {
  transform: rotate(180deg);
}

.editions {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(170px, 1fr));
  gap: 12px;
  margin: 0;
  padding: 4px 16px 16px;
  list-style: none;
  animation: open 0.3s ease both;
}

.edition {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-width: 0;
  padding: 7px 7px 11px;
  box-sizing: border-box;
  border: 1px solid var(--glass-line);
  border-radius: 14px;
  color: #fff;
  background: rgba(255, 255, 255, 0.06);
  font-size: 14px;
  line-height: 1.3;
  text-decoration: none;
  transition: border-color 0.2s ease, background 0.2s ease;
}

.edition:hover {
  border-color: rgba(255, 255, 255, 0.46);
  background: var(--glass-strong);
}

.logo {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 96px;
  margin-bottom: 8px;
  padding: 7px;
  box-sizing: border-box;
  border-radius: 10px;
  background: #fff;
}

.edition-year {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.edition-year strong {
  font-size: 18px;
}

.edition-year em {
  padding: 1px 7px;
  border-radius: 999px;
  color: #ffd2e8;
  background: rgba(255, 46, 147, 0.26);
  font-size: 12px;
  font-style: normal;
}

.edition small {
  color: var(--muted);
  font-size: 12px;
}

.fallback {
  color: #4c4364;
  font-size: 26px;
  font-weight: 700;
}

button:focus-visible,
a:focus-visible,
input:focus-visible {
  outline: 2px solid var(--blue);
  outline-offset: 2px;
}

.row-head:focus-visible {
  outline-offset: -3px;
  border-radius: 17px;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}

@keyframes drift {
  to {
    transform: translate(-9vmax, 6vmax) scale(1.14);
  }
}

@keyframes shimmer {
  from {
    background-position: 100% 0;
  }
  to {
    background-position: 0 0;
  }
}

@keyframes grow {
  from {
    transform: scaleX(0);
  }
  to {
    transform: scaleX(1);
  }
}

@keyframes open {
  from {
    opacity: 0;
    transform: translateY(-6px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .orb,
  h1,
  .skeleton,
  .bar i,
  .editions {
    animation: none;
  }

  .podium-card,
  .chevron {
    transition: none;
  }

  .podium-card:hover {
    transform: none;
  }
}

@media (max-width: 760px) {
  .content {
    padding-inline: 16px;
  }

  .podium {
    grid-template-columns: 1fr;
  }

  .podium-card {
    display: grid;
    grid-template-columns: 112px minmax(0, 1fr) auto;
    grid-template-rows: auto auto;
    column-gap: 14px;
    align-items: center;
    padding: 8px 14px 8px 8px;
    text-align: left;
  }

  .place-1,
  .place-2,
  .place-3 {
    order: 0;
    padding-bottom: 8px;
  }

  .medal {
    top: 12px;
    left: 12px;
    padding: 1px 7px;
    font-size: 12px;
  }

  .plate,
  .place-1 .plate {
    grid-row: 1 / 3;
    height: 78px;
    margin: 0;
    padding: 7px;
  }

  .podium-card strong {
    align-self: end;
    font-size: 18px;
    text-align: left;
  }

  .podium-count {
    grid-column: 3;
    grid-row: 1 / 3;
  }

  .podium-card small {
    align-self: start;
    grid-column: 2;
    grid-row: 2;
  }

  .toolbar {
    position: static;
    flex-direction: column;
    align-items: stretch;
    gap: 2px;
    border-radius: 24px;
  }

  .row {
    scroll-margin-top: 12px;
  }

  .search {
    flex: none;
  }

  .sort button {
    flex: 1;
    padding-inline: 6px;
  }

  .row-head {
    grid-template-columns: 26px minmax(0, 1fr) auto 20px;
    gap: 8px;
    padding-inline: 10px;
  }

  .range {
    display: none;
  }

  .editions {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
    padding: 4px 10px 10px;
  }

  .logo {
    height: 78px;
  }
}
</style>
