const BASE_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8080'

async function getJson(path) {
  const response = await fetch(`${BASE_URL}${path}`)
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  return response.json()
}

export const getContests = () => getJson('/api/contests')
export const getContest = (year) => getJson(`/api/contests/${year}`)
export const getContestDetails = (year) => getJson(`/api/contests/${year}/details`)
export const getVoteCountries = () => getJson('/api/votes/countries')
export const getVoteGraph = ({ from, to, countries, round, type }) => {
  const params = new URLSearchParams({
    from,
    to,
    countries: countries.join(','),
    round,
    type,
  })
  return getJson(`/api/votes/graph?${params}`)
}
