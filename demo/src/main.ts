import './style.css'
import { detect, type Panel } from './detect'
import { renderOverlay, clearOverlay } from './render'

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
    <h1>ComicGuide — Panel Detection in the Browser</h1>
    <p class="lead">Comic panel detection running entirely client-side — no server, no upload.</p>

    <div class="controls">
      <select id="sample" aria-label="Example page"></select>
      <button id="upload-btn" type="button">Upload image</button>
      <input id="upload" type="file" accept="image/*" hidden>
      <label class="switch"><input id="toggle" type="checkbox" checked> Show panels</label>
      <span id="count" class="count"></span>
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
    <p class="hint">Drag &amp; drop your own comic page onto the image, or click “Upload image”.
       Everything is processed locally in your browser — nothing is ever uploaded.</p>
  </div>`

const sampleSel = app.querySelector<HTMLSelectElement>('#sample')!
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

let comics: ComicEntry[] = []
let currentIndex = -1
let lastPanels: Panel[] = []
let uploadUrl: string | null = null

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
    count.textContent = lastPanels.length === 0 ? 'no panels found' : `${lastPanels.length} panels`
  } catch (e) {
    count.textContent = 'detection error'
    console.error(e)
  }
}

function loadSrc(src: string) {
  pageImg.onload = runDetection
  pageImg.onerror = () => { count.textContent = 'image could not be loaded' }
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
  sampleSel.selectedIndex = currentIndex
  const entry = comics[currentIndex]
  setAttribution(entry)
  loadSrc(`${import.meta.env.BASE_URL}comics/${entry.file}`)
}

function showUpload(file: File) {
  if (!file.type.startsWith('image/')) { count.textContent = 'please choose an image file'; return }
  revokeUpload()
  uploadUrl = URL.createObjectURL(file)
  clearBtn.hidden = false
  setAttribution(null)
  loadSrc(uploadUrl)
}

fetch(`${import.meta.env.BASE_URL}comics.json`)
  .then((r) => r.json())
  .then((list: ComicEntry[]) => {
    comics = list
    list.forEach((c) => {
      const o = document.createElement('option')
      o.value = c.file
      o.textContent = `${c.title} (${c.year})`
      sampleSel.appendChild(o)
    })
    if (list.length) showSample(0)
  })
  .catch(() => { count.textContent = 'examples not found — please upload an image' })

sampleSel.addEventListener('change', () => showSample(sampleSel.selectedIndex))
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
