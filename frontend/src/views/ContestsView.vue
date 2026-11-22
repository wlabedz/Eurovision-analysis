<script setup>
import {
  computed,
  nextTick,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  ref,
  watch,
} from 'vue'
import { getContests } from '../api'

const AUTOPLAY_MS = 5000
const VISIBLE_RANGE = 3

const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches

const contests = ref([])
const loading = ref(true)
const error = ref('')
const failedLogos = ref(new Set())

const root = ref(null)
const carousel = ref(null)
const stage = ref(null)
const activeIndex = ref(0)
const activeDecade = ref(null)
const dragX = ref(0)
const dragging = ref(false)
const userPaused = ref(reducedMotion)
const hovering = ref(false)
const keyboardFocus = ref(false)
const tabHidden = ref(document.hidden)
const viewActive = ref(true)

let autoplayTimer
let decadeObserver
let pointerStart = null
let justDragged = false

const sortedContests = computed(() =>
  [...contests.value].sort((a, b) => b.year - a.year),
)
const activeContest = computed(() => sortedContests.value[activeIndex.value])

const decades = computed(() => {
  const groups = new Map()

  sortedContests.value.forEach((contest, index) => {
    const decade = Math.floor(contest.year / 10) * 10
    if (!groups.has(decade)) groups.set(decade, [])
    groups.get(decade).push({ contest, index })
  })

  return [...groups].map(([decade, items]) => ({ decade, items }))
})

const stats = computed(() => {
  const list = sortedContests.value
  if (!list.length) return []

  return [
    { label: 'Contests', value: list.length },
    { label: 'Host cities', value: new Set(list.map((c) => c.city)).size },
    {
      label: 'Host countries',
      value: new Set(list.map((c) => c.country)).size,
      to: { name: 'host-countries' },
    },
    { label: 'Years of music', value: list[0].year - list[list.length - 1].year + 1 },
  ]
})

const slides = computed(() => {
  const total = sortedContests.value.length

  return sortedContests.value
    .map((contest, index) => {
      let offset = (index - activeIndex.value + total) % total
      if (offset > total / 2) offset -= total
      return { contest, index, offset, distance: Math.abs(offset) }
    })
    .filter((slide) => slide.distance <= VISIBLE_RANGE)
})

const sliderValue = computed(
  () => sortedContests.value.length - 1 - activeIndex.value,
)

const autoplaying = computed(
  () =>
    sortedContests.value.length > 1 &&
    viewActive.value &&
    !userPaused.value &&
    !hovering.value &&
    !keyboardFocus.value &&
    !dragging.value &&
    !tabHidden.value,
)

function goTo(index) {
  const total = sortedContests.value.length
  if (total) activeIndex.value = ((index % total) + total) % total
}

function slideStyle(slide) {
  return {
    '--offset': slide.offset,
    '--distance': slide.distance,
    '--turn': Math.sign(slide.offset),
    zIndex: 10 - slide.distance,
  }
}

function onSlideClick(slide) {
  if (!justDragged && slide.offset !== 0) goTo(slide.index)
}

function onSlider(event) {
  goTo(sortedContests.value.length - 1 - Number(event.target.value))
}

function onCarouselKey(event) {
  if (event.target.matches('input')) return
  if (event.key === 'ArrowLeft') goTo(activeIndex.value - 1)
  if (event.key === 'ArrowRight') goTo(activeIndex.value + 1)
}

function onFocusIn(event) {
  keyboardFocus.value = event.target.matches(':focus-visible')
}

function onFocusOut(event) {
  if (!carousel.value?.contains(event.relatedTarget)) keyboardFocus.value = false
}

function onPointerDown(event) {
  if (event.button !== 0) return
  pointerStart = { x: event.clientX, id: event.pointerId }
}

function onPointerMove(event) {
  if (!pointerStart || event.pointerId !== pointerStart.id) return

  const distance = event.clientX - pointerStart.x
  if (!dragging.value) {
    if (Math.abs(distance) < 8) return
    dragging.value = true
    stage.value.setPointerCapture(event.pointerId)
  }
  dragX.value = distance
}

function onPointerUp() {
  if (dragging.value) {
    const distance = dragX.value
    if (Math.abs(distance) >= 50) {
      const steps = Math.max(1, Math.round(Math.abs(distance) / 180))
      goTo(activeIndex.value - Math.sign(distance) * steps)
    }
    justDragged = true
    setTimeout(() => (justDragged = false))
  }
  pointerStart = null
  dragging.value = false
  dragX.value = 0
}

function tilt(event) {
  if (event.pointerType !== 'mouse' || reducedMotion) return

  const card = event.currentTarget
  const box = card.getBoundingClientRect()
  const x = (event.clientX - box.left) / box.width
  const y = (event.clientY - box.top) / box.height
  card.style.setProperty('--tilt-x', `${(0.5 - y) * 10}deg`)
  card.style.setProperty('--tilt-y', `${(x - 0.5) * 10}deg`)
  card.style.setProperty('--glare-x', `${x * 100}%`)
  card.style.setProperty('--glare-y', `${y * 100}%`)
}

function resetTilt(event) {
  const { style } = event.currentTarget
  style.removeProperty('--tilt-x')
  style.removeProperty('--tilt-y')
}

function onVisibilityChange() {
  tabHidden.value = document.hidden
}

function observeDecades() {
  decadeObserver?.disconnect()
  decadeObserver = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) activeDecade.value = Number(entry.target.dataset.decade)
      }
    },
    { rootMargin: '-30% 0px -60% 0px' },
  )
  root.value?.querySelectorAll('.decade').forEach((el) => decadeObserver.observe(el))
}

function scrollToDecade(decade) {
  root.value?.querySelector(`#d${decade}`)?.scrollIntoView()
}

async function loadContests() {
  loading.value = true
  error.value = ''

  try {
    contests.value = await getContests()
    activeIndex.value = 0
  } catch {
    error.value = 'No data available. Please try again later.'
  } finally {
    loading.value = false
  }

  await nextTick()
  observeDecades()
}

watch(
  [activeIndex, autoplaying],
  () => {
    clearTimeout(autoplayTimer)
    if (autoplaying.value) {
      autoplayTimer = setTimeout(() => goTo(activeIndex.value + 1), AUTOPLAY_MS)
    }
  },
  { immediate: true },
)

onMounted(() => {
  document.addEventListener('visibilitychange', onVisibilityChange)
  loadContests()
})

onActivated(() => {
  viewActive.value = true
})

// Kept-alive view: stop the carousel while another page is open.
onDeactivated(() => {
  viewActive.value = false
  hovering.value = false
  keyboardFocus.value = false
})

onBeforeUnmount(() => {
  clearTimeout(autoplayTimer)
  decadeObserver?.disconnect()
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<template>
  <div ref="root" class="page">
    <div class="backdrop" aria-hidden="true">
      <span class="orb orb-pink"></span>
      <span class="orb orb-blue"></span>
      <span class="orb orb-violet"></span>
      <Transition name="ambient">
        <img
          v-if="activeContest?.logoUrl && !failedLogos.has(activeContest.year)"
          :key="activeContest.year"
          class="ambient"
          :src="activeContest.logoUrl"
          alt=""
        />
      </Transition>
    </div>

    <main class="content">
      <header class="hero">
        <p class="eyebrow">Song contest archive</p>
        <h1>Eurovision</h1>
        <dl v-if="stats.length" class="stats">
          <div v-for="stat in stats" :key="stat.label" class="stat">
            <dd>{{ stat.value }}</dd>
            <dt>
              <RouterLink v-if="stat.to" class="stat-link" :to="stat.to">
                {{ stat.label }} →
              </RouterLink>
              <template v-else>{{ stat.label }}</template>
            </dt>
          </div>
        </dl>
        <div class="hero-links">
          <RouterLink class="graph-link" :to="{ name: 'voting-graph' }">
            Explore the voting graph →
          </RouterLink>
          <RouterLink class="graph-link" :to="{ name: 'country-summary' }">
            Country summary →
          </RouterLink>
        </div>
      </header>

      <div v-if="loading" class="state" role="status">
        <div class="skeleton" aria-hidden="true"></div>
        <p>Loading contests...</p>
      </div>

      <div v-else-if="error" class="state glass" role="alert">
        <p>{{ error }}</p>
        <button class="retry" type="button" @click="loadContests">Try again</button>
      </div>

      <template v-else-if="activeContest">
        <section
          ref="carousel"
          class="carousel"
          role="region"
          aria-roledescription="carousel"
          aria-label="Eurovision contests"
          @mouseenter="hovering = true"
          @mouseleave="hovering = false"
          @focusin="onFocusIn"
          @focusout="onFocusOut"
          @keydown="onCarouselKey"
        >
          <div
            ref="stage"
            class="stage"
            :class="{ 'is-dragging': dragging }"
            @pointerdown="onPointerDown"
            @pointermove="onPointerMove"
            @pointerup="onPointerUp"
            @pointercancel="onPointerUp"
          >
            <div class="track" :style="{ transform: `translateX(${dragX}px)` }">
              <article
                v-for="slide in slides"
                :key="slide.contest.year"
                class="slide"
                :class="{ 'is-active': slide.offset === 0 }"
                :data-distance="slide.distance"
                :style="slideStyle(slide)"
                :aria-hidden="slide.offset !== 0"
                @click="onSlideClick(slide)"
              >
                <div class="plate">
                  <img
                    v-if="slide.contest.logoUrl && !failedLogos.has(slide.contest.year)"
                    :src="slide.contest.logoUrl"
                    :alt="`Logo ${slide.contest.year}`"
                    draggable="false"
                    @error="failedLogos.add(slide.contest.year)"
                  />
                  <span v-else class="fallback">{{ slide.contest.year }}</span>
                </div>
                <div class="slide-copy">
                  <strong>{{ slide.contest.year }}</strong>
                  <span>{{ slide.contest.city }}, {{ slide.contest.country }}</span>
                  <small>{{ slide.contest.arena }}</small>
                  <em v-if="slide.contest.slogan">“{{ slide.contest.slogan }}”</em>
                  <RouterLink
                    v-if="slide.offset === 0"
                    class="slide-link"
                    :to="{ name: 'contest', params: { year: String(slide.contest.year) } }"
                    draggable="false"
                  >
                    Results &amp; votes →
                  </RouterLink>
                </div>
              </article>
            </div>
          </div>

          <div class="controls glass">
            <button
              class="icon-button"
              type="button"
              aria-label="Previous contest"
              @click="goTo(activeIndex - 1)"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 5-7 7 7 7" /></svg>
            </button>
            <button
              class="icon-button"
              type="button"
              :aria-label="userPaused ? 'Play carousel' : 'Pause carousel'"
              @click="userPaused = !userPaused"
            >
              <svg v-if="userPaused" viewBox="0 0 24 24" aria-hidden="true">
                <path class="solid" d="M8 5v14l11-7z" />
              </svg>
              <svg v-else viewBox="0 0 24 24" aria-hidden="true"><path d="M9 5v14M15 5v14" /></svg>
            </button>
            <button
              class="icon-button"
              type="button"
              aria-label="Next contest"
              @click="goTo(activeIndex + 1)"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 5 7 7-7 7" /></svg>
            </button>

            <label class="timeline">
              <span class="sr-only">Choose contest year</span>
              <span>{{ sortedContests[sortedContests.length - 1].year }}</span>
              <input
                type="range"
                min="0"
                :max="sortedContests.length - 1"
                :value="sliderValue"
                :aria-valuetext="String(activeContest.year)"
                @input="onSlider"
              />
              <span>{{ sortedContests[0].year }}</span>
            </label>

            <span class="progress" aria-hidden="true">
              <i v-if="autoplaying" :key="activeIndex" :style="{ animationDuration: `${AUTOPLAY_MS}ms` }"></i>
            </span>
          </div>

          <p class="sr-only" :aria-live="autoplaying ? 'off' : 'polite'">
            {{ activeContest.year }}, {{ activeContest.city }}, {{ activeContest.country }}
          </p>
        </section>

        <nav class="toc glass" aria-label="Decades">
          <a
            v-for="group in decades"
            :key="group.decade"
            :href="`#d${group.decade}`"
            :class="{ 'is-current': activeDecade === group.decade }"
            :aria-current="activeDecade === group.decade ? 'location' : undefined"
            @click.prevent="scrollToDecade(group.decade)"
          >
            {{ group.decade }}s
          </a>
        </nav>

        <section
          v-for="group in decades"
          :id="`d${group.decade}`"
          :key="group.decade"
          class="decade"
          :data-decade="group.decade"
        >
          <div class="decade-heading">
            <h2>{{ group.decade }}s</h2>
            <span>{{ group.items.length }} contests</span>
          </div>

          <ul class="grid">
            <li v-for="{ contest } in group.items" :key="contest.year" class="grid-item">
              <RouterLink
                class="card"
                :to="{ name: 'contest', params: { year: String(contest.year) } }"
                :aria-label="`Open Eurovision ${contest.year}, ${contest.city}`"
                @pointermove="tilt"
                @pointerleave="resetTilt"
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
                <strong>{{ contest.year }}</strong>
                <span class="place">{{ contest.city }}, {{ contest.country }}</span>
                <small v-if="contest.slogan">{{ contest.slogan }}</small>
              </RouterLink>
            </li>
          </ul>
        </section>
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
  left: -16vmax;
  background: var(--pink);
}

.orb-blue {
  top: 8vmax;
  right: -28vmax;
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

.ambient {
  position: absolute;
  top: 8vh;
  left: 50%;
  width: min(90vw, 1000px);
  height: 70vh;
  object-fit: cover;
  opacity: 0.3;
  filter: blur(70px) saturate(1.6);
  transform: translateX(-50%);
}

.ambient-enter-active,
.ambient-leave-active {
  transition: opacity 0.9s ease;
}

.ambient-enter-from,
.ambient-leave-to {
  opacity: 0;
}

.content {
  position: relative;
  width: min(1240px, 100%);
  margin: 0 auto;
  padding: 0 24px 80px;
  box-sizing: border-box;
}

.hero {
  padding: 44px 0 10px;
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
  padding: 0.08em 0.15em 0.16em;
  font-family: 'Kaushan Script', 'Brush Script MT', cursive;
  font-size: clamp(68px, 13vw, 164px);
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

.stat-link {
  color: #ffb4da;
  text-decoration: underline;
  text-decoration-color: rgba(255, 180, 218, 0.5);
  text-underline-offset: 3px;
  transition: color 0.2s ease;
}

.hero-links {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  margin-top: 14px;
}

.graph-link {
  display: inline-flex;
  align-items: center;
  min-height: 42px;
  padding: 0 18px;
  border: 1px solid rgba(255, 255, 255, 0.28);
  border-radius: 999px;
  color: #fff;
  background: rgba(255, 255, 255, 0.1);
  box-shadow: 0 10px 28px rgba(255, 46, 147, 0.24);
  -webkit-backdrop-filter: blur(18px);
  backdrop-filter: blur(18px);
  font-size: 15px;
  font-weight: 600;
  text-decoration: none;
  transition: background 0.2s ease;
}

.graph-link:hover {
  background: rgba(255, 255, 255, 0.2);
}

.stat-link:hover {
  color: #fff;
}

.glass {
  border: 1px solid var(--glass-line);
  background: var(--glass);
  box-shadow: var(--shadow), inset 0 1px 0 rgba(255, 255, 255, 0.2);
  -webkit-backdrop-filter: blur(22px) saturate(1.4);
  backdrop-filter: blur(22px) saturate(1.4);
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

.carousel {
  --slide-width: clamp(232px, 31vw, 390px);
  --step: clamp(112px, 21vw, 270px);

  padding-top: 18px;
}

.stage {
  position: relative;
  height: calc(var(--slide-width) * 0.62 + 232px);
  margin-inline: -24px;
  overflow: hidden;
  perspective: 1300px;
  cursor: grab;
  touch-action: pan-y;
  user-select: none;
}

.stage.is-dragging {
  cursor: grabbing;
}

.track {
  position: absolute;
  inset: 0;
  transform-style: preserve-3d;
}

.stage:not(.is-dragging) .track {
  transition: transform 0.35s cubic-bezier(0.2, 0.8, 0.2, 1);
}

.slide {
  position: absolute;
  top: 18px;
  left: 50%;
  width: var(--slide-width);
  margin-left: calc(var(--slide-width) / -2);
  padding: 10px 10px 16px;
  box-sizing: border-box;
  border: 1px solid var(--glass-line);
  border-radius: 26px;
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.2), rgba(255, 255, 255, 0.05));
  box-shadow: var(--shadow), inset 0 1px 0 rgba(255, 255, 255, 0.28);
  -webkit-backdrop-filter: blur(18px);
  backdrop-filter: blur(18px);
  opacity: calc(1 - var(--distance) * 0.18);
  transform:
    translateX(calc(var(--offset) * var(--step)))
    translateZ(calc(var(--distance) * -130px))
    rotateY(calc(var(--turn) * -32deg));
  transition:
    transform 0.7s cubic-bezier(0.2, 0.8, 0.2, 1),
    opacity 0.5s ease,
    box-shadow 0.5s ease;
  cursor: pointer;
}

.slide:not(.is-active) {
  background: linear-gradient(145deg, rgba(62, 41, 120, 0.94), rgba(25, 15, 62, 0.94));
}

.slide:not(.is-active) .plate {
  filter: brightness(0.78);
}

.slide[data-distance='3'] {
  opacity: 0;
  pointer-events: none;
}

.slide.is-active {
  box-shadow:
    0 28px 80px rgba(255, 46, 147, 0.38),
    0 0 0 1px rgba(255, 255, 255, 0.3),
    inset 0 1px 0 rgba(255, 255, 255, 0.4);
  cursor: inherit;
}

.plate {
  display: flex;
  align-items: center;
  justify-content: center;
  aspect-ratio: 16 / 10;
  padding: 14px;
  box-sizing: border-box;
  border-radius: 18px;
  background: #fff;
}

.plate img,
.logo img {
  display: block;
  width: 100%;
  height: 100%;
  min-width: 0;
  object-fit: contain;
}

.slide-copy {
  display: grid;
  gap: 1px;
  padding: 12px 8px 0;
  text-align: center;
}

.slide-copy strong {
  font-size: 30px;
  font-weight: 700;
  line-height: 1.1;
}

.slide-copy span {
  overflow: hidden;
  font-size: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.slide-copy small {
  overflow: hidden;
  color: var(--muted);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.slide-copy em {
  overflow: hidden;
  margin-top: 3px;
  color: #ffb4da;
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.slide-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  justify-self: center;
  min-height: 36px;
  margin-top: 8px;
  padding: 0 16px;
  border-radius: 999px;
  color: #fff;
  background: linear-gradient(120deg, var(--pink), var(--violet));
  font-size: 14px;
  font-weight: 600;
  text-decoration: none;
}

.controls {
  position: relative;
  display: flex;
  align-items: center;
  gap: 4px;
  width: min(680px, 100%);
  margin: 6px auto 0;
  padding: 6px 16px 6px 6px;
  box-sizing: border-box;
  overflow: hidden;
  border-radius: 999px;
}

.icon-button {
  display: grid;
  flex: none;
  place-items: center;
  width: 44px;
  height: 44px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  color: #fff;
  background: transparent;
  cursor: pointer;
  transition: background 0.2s ease, transform 0.2s ease;
}

.icon-button:hover {
  background: var(--glass-strong);
}

.icon-button:active {
  transform: scale(0.92);
}

.icon-button svg {
  width: 20px;
  height: 20px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2.2;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.icon-button .solid {
  fill: currentColor;
}

.timeline {
  display: flex;
  flex: 1;
  align-items: center;
  gap: 10px;
  min-width: 0;
  margin-left: 8px;
  color: var(--muted);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

.timeline input {
  flex: 1;
  min-width: 0;
  height: 44px;
  margin: 0;
  accent-color: var(--pink);
  cursor: pointer;
}

.progress {
  position: absolute;
  right: 26px;
  bottom: 0;
  left: 26px;
  height: 2px;
}

.progress i {
  display: block;
  height: 100%;
  border-radius: 2px;
  background: linear-gradient(90deg, var(--pink), var(--blue));
  transform-origin: left;
  animation: progress linear both;
}

.toc {
  position: sticky;
  top: 12px;
  z-index: 20;
  display: flex;
  gap: 4px;
  width: fit-content;
  max-width: 100%;
  margin: 44px auto 0;
  padding: 5px;
  box-sizing: border-box;
  overflow-x: auto;
  border-radius: 999px;
  scrollbar-width: none;
}

.toc a {
  flex: none;
  padding: 8px 15px;
  border-radius: 999px;
  color: var(--muted);
  font-size: 15px;
  font-weight: 500;
  text-decoration: none;
  transition: color 0.2s ease, background 0.2s ease;
}

.toc a:hover {
  color: #fff;
  background: var(--glass-strong);
}

.toc a.is-current {
  color: #fff;
  background: linear-gradient(120deg, var(--pink), var(--violet));
  box-shadow: 0 6px 18px rgba(255, 46, 147, 0.38);
}

.decade {
  padding-top: 48px;
  scroll-margin-top: 42px;
}

.decade-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--glass-line);
}

h2 {
  margin: 0;
  color: #fff;
  font-family: 'Kaushan Script', 'Brush Script MT', cursive;
  font-size: clamp(32px, 5vw, 46px);
  font-weight: 400;
  letter-spacing: 0;
}

.decade-heading span {
  color: var(--muted);
  font-size: 14px;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(212px, 1fr));
  gap: 18px;
  margin: 20px 0 0;
  padding: 0;
  list-style: none;
  perspective: 1000px;
}

.grid-item {
  min-width: 0;
}

.card {
  --tilt-x: 0deg;
  --tilt-y: 0deg;
  --glare-x: 50%;
  --glare-y: 0%;

  position: relative;
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  padding: 9px 9px 14px;
  box-sizing: border-box;
  overflow: hidden;
  border: 1px solid var(--glass-line);
  border-radius: 20px;
  color: #fff;
  background: var(--glass);
  box-shadow: 0 12px 30px rgba(4, 0, 24, 0.35), inset 0 1px 0 rgba(255, 255, 255, 0.18);
  -webkit-backdrop-filter: blur(12px);
  backdrop-filter: blur(12px);
  font: inherit;
  text-align: left;
  text-decoration: none;
  cursor: pointer;
  transform: rotateX(var(--tilt-x)) rotateY(var(--tilt-y));
  transition: transform 0.18s ease-out, box-shadow 0.25s ease, border-color 0.25s ease;
}

.card::after {
  position: absolute;
  inset: 0;
  background: radial-gradient(circle at var(--glare-x) var(--glare-y), rgba(255, 255, 255, 0.24), transparent 52%);
  content: '';
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.25s ease;
}

.card:hover {
  border-color: rgba(255, 255, 255, 0.42);
  box-shadow: 0 20px 44px rgba(255, 46, 147, 0.28), inset 0 1px 0 rgba(255, 255, 255, 0.3);
}

.card:hover::after {
  opacity: 1;
}

.logo {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 128px;
  margin-bottom: 11px;
  padding: 8px;
  box-sizing: border-box;
  border-radius: 13px;
  background: #fff;
}

.card strong {
  padding-inline: 4px;
  font-size: 20px;
  line-height: 1.2;
}

.place {
  padding-inline: 4px;
  font-size: 15px;
  line-height: 1.3;
}

.card small {
  padding: 3px 4px 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.3;
}

.fallback {
  color: #4c4364;
  font-size: 28px;
  font-weight: 700;
}

button:focus-visible,
a:focus-visible,
input:focus-visible {
  outline: 2px solid var(--blue);
  outline-offset: 2px;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}

:global(html) {
  background: #0b0620;
  color-scheme: dark;
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
    background-position: 0 0;
  }
}

@keyframes progress {
  from {
    transform: scaleX(0);
  }
  to {
    transform: scaleX(1);
  }
}

@keyframes reveal {
  from {
    opacity: 0;
    transform: translateY(32px) scale(0.96);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

@media (prefers-reduced-motion: no-preference) {
  :global(html) {
    scroll-behavior: smooth;
  }

  @supports (animation-timeline: view()) {
    .grid-item {
      animation: reveal linear both;
      animation-timeline: view();
      animation-range: entry 0% entry 32%;
    }
  }
}

@media (prefers-reduced-motion: reduce) {
  .orb,
  h1,
  .skeleton {
    animation: none;
  }

  .slide,
  .card,
  .track,
  .icon-button {
    transition: none;
  }
}

@media (max-width: 640px) {
  .content {
    padding-inline: 16px;
  }

  .hero {
    padding-top: 28px;
  }

  .eyebrow {
    font-size: 11px;
  }

  .stats {
    display: grid;
    grid-template-columns: repeat(2, auto);
    gap: 4px 22px;
  }

  .stage {
    margin-inline: -16px;
  }

  .controls {
    padding-right: 12px;
  }

  .icon-button {
    width: 40px;
    height: 44px;
  }

  .timeline {
    gap: 6px;
    margin-left: 2px;
    font-size: 12px;
  }

  .toc {
    margin-top: 30px;
  }

  .grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
  }

  .logo {
    height: 96px;
  }

  .card {
    padding: 7px 7px 11px;
    border-radius: 16px;
  }
}
</style>
