import './style.css'
import { detect, LIB_VERSION, type Panel } from './detect'
import { renderOverlay, clearOverlay } from './render'
import { createDropdown, type DropdownHandle } from './dropdown'

interface ComicEntry {
  file: string
  title: string
  year: number
  source: string
  license: string
  licenseUrl: string
}

const app = document.querySelector<HTMLDivElement>('#app')!
app.innerHTML = `
  <div class="wrap">
    <header class="head">
      <h1>ComicCutter <span class="ver" id="ver"></span></h1>
      <p class="lead">Comic panel detection running entirely client-side — no server, no upload.</p>
    </header>

    <div class="toolbar" role="toolbar" aria-label="Controls">
      <div class="tb-cell" id="sample" aria-label="Example page"></div>
      <span class="tb-sep"></span>
      <button id="upload-btn" class="tb-btn" type="button">
        <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
          <path d="M8 10.5V2.5M8 2.5 4.8 5.7M8 2.5l3.2 3.2M2.8 10v2.2a1 1 0 0 0 1 1h8.4a1 1 0 0 0 1-1V10"
            fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
        Upload
      </button>
      <input id="upload" type="file" accept="image/*" hidden>
      <span class="tb-sep"></span>
      <label class="tb-switch" title="Toggle panel overlay">
        <input id="toggle" type="checkbox" checked>
        <span class="tb-track"><span class="tb-knob"></span></span>
        <span class="tb-switch-label">Panels</span>
      </label>
      <span class="tb-sep"></span>
      <span id="count" class="tb-count" aria-live="polite"></span>
    </div>

    <div class="viewer">
      <button id="prev" class="nav" type="button" aria-label="Previous page">&#8249;</button>
      <div id="stage" class="stage">
        <img id="page" alt="Comic page">
        <button id="clear" class="clear-btn" type="button" aria-label="Remove image" hidden>&#10005;</button>
      </div>
      <button id="next" class="nav" type="button" aria-label="Next page">&#8250;</button>
    </div>

    <div id="attribution" class="attribution"></div>
    <p class="hint">Drag &amp; drop your own comic page onto the image, or click “Upload”.
       Everything is processed locally in your browser — nothing is ever uploaded.</p>
  </div>`

const sampleHost = app.querySelector<HTMLDivElement>('#sample')!
const uploadBtn = app.querySelector<HTMLButtonElement>('#upload-btn')!
const uploadInput = app.querySelector<HTMLInputElement>('#upload')!
const toggle = app.querySelector<HTMLInputElement>('#toggle')!
const stage = app.querySelector<HTMLDivElement>('#stage')!
const pageImg = app.querySelector<HTMLImageElement>('#page')!
const count = app.querySelector<HTMLSpanElement>('#count')!
const attribution = app.querySelector<HTMLDivElement>('#attribution')!
const prevBtn = app.querySelector<HTMLButtonElement>('#prev')!
const nextBtn = app.querySelector<HTMLButtonElement>('#next')!
const clearBtn = app.querySelector<HTMLButtonElement>('#clear')!
const verBadge = app.querySelector<HTMLSpanElement>('#ver')!

// Show the actually built library version (= release tag), so it's clear what was deployed.
verBadge.textContent = `v${LIB_VERSION}`

/** Sets the panel counter + status dot (loading | result | idle). */
function setCount(text: string, mode: 'loading' | 'result' | 'idle') {
  count.textContent = text
  count.classList.toggle('loading', mode === 'loading')
  count.classList.toggle('active', mode === 'result' && lastPanels.length > 0)
}

let comics: ComicEntry[] = []
let currentIndex = -1
let lastPanels: Panel[] = []
let uploadUrl: string | null = null
let dropdown: DropdownHandle | null = null

/** Source + license of the current example, shown below the image. */
function setAttribution(entry: ComicEntry | null) {
  if (entry) {
    const src = `<a href="${entry.source}" target="_blank" rel="noopener noreferrer">${entry.title}</a>`
    const lic = `<a href="${entry.licenseUrl}" target="_blank" rel="noopener noreferrer">${entry.license}</a>`
    attribution.innerHTML = `Source: ${src} · License: ${lic}`
  } else {
    attribution.textContent = 'Your image — processed locally, never uploaded.'
  }
}

function paintOverlay() {
  if (toggle.checked) renderOverlay(stage, lastPanels)
  else clearOverlay(stage)
}

function runDetection() {
  if (!pageImg.naturalWidth) return
  try {
    lastPanels = detect(pageImg, pageImg.naturalWidth, pageImg.naturalHeight)
    paintOverlay()
    setCount(lastPanels.length === 0 ? 'no panels' : `${lastPanels.length} panels`, 'result')
  } catch (e) {
    setCount('error', 'idle')
    console.error(e)
  }
}

function loadSrc(src: string) {
  // Remove the stale overlay IMMEDIATELY: otherwise the old boxes stay on top of the old image
  // until the synchronous detection of the NEXT image finishes — which feels heavily delayed.
  lastPanels = []
  clearOverlay(stage)
  setCount('loading', 'loading')
  pageImg.onload = () => {
    setCount('detecting', 'loading')
    // Let the new image render FIRST, THEN run detection (which blocks for several 100 ms) —
    // otherwise the new image only appears after the compute time. Double rAF = one paint in between.
    requestAnimationFrame(() => requestAnimationFrame(runDetection))
  }
  pageImg.onerror = () => { setCount('load error', 'idle') }
  pageImg.src = src
}

function revokeUpload() {
  if (uploadUrl) { URL.revokeObjectURL(uploadUrl); uploadUrl = null }
}

function showSample(index: number) {
  if (comics.length === 0) return
  currentIndex = (index + comics.length) % comics.length
  revokeUpload()
  clearBtn.hidden = true
  dropdown?.setIndex(currentIndex)
  const entry = comics[currentIndex]
  setAttribution(entry)
  loadSrc(`${import.meta.env.BASE_URL}comics/${entry.file}`)
}

function showUpload(file: File) {
  if (!file.type.startsWith('image/')) { setCount('not an image', 'idle'); return }
  revokeUpload()
  uploadUrl = URL.createObjectURL(file)
  clearBtn.hidden = false
  setAttribution(null)
  loadSrc(uploadUrl)
}

// no-cache: comics.json has a stable URL — otherwise the browser cache would show
// stale entries after an update (referencing files that no longer exist).
fetch(`${import.meta.env.BASE_URL}comics.json`, { cache: 'no-cache' })
  .then((r) => r.json())
  .then((list: ComicEntry[]) => {
    comics = list
    const labels = list.map((c) => `${c.title} (${c.year})`)
    dropdown = createDropdown(sampleHost, labels, (i) => showSample(i))
    if (list.length) showSample(0)
  })
  .catch(() => { setCount('no examples', 'idle') })

prevBtn.addEventListener('click', () => showSample(currentIndex - 1))
nextBtn.addEventListener('click', () => showSample(currentIndex + 1))
toggle.addEventListener('change', paintOverlay)

uploadBtn.addEventListener('click', () => uploadInput.click())
uploadInput.addEventListener('change', () => {
  const f = uploadInput.files?.[0]
  if (f) showUpload(f)
  uploadInput.value = ''
})
clearBtn.addEventListener('click', () => showSample(currentIndex < 0 ? 0 : currentIndex))

;['dragover', 'dragenter'].forEach((ev) =>
  stage.addEventListener(ev, (e) => { e.preventDefault(); stage.classList.add('drop') }),
)
;['dragleave', 'drop'].forEach((ev) =>
  stage.addEventListener(ev, () => stage.classList.remove('drop')),
)
stage.addEventListener('drop', (e) => {
  e.preventDefault()
  const f = (e as DragEvent).dataTransfer?.files?.[0]
  if (f) showUpload(f)
})
