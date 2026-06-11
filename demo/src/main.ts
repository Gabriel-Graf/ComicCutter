import './style.css'
import { detect, type Panel } from './detect'
import { renderOverlay } from './render'

interface ComicEntry { file: string; title: string; year: number }

const app = document.querySelector<HTMLDivElement>('#app')!
app.innerHTML = `
  <div class="wrap">
    <h1>ComicGuide — Panel-Erkennung im Browser</h1>
    <div class="controls">
      <select id="sample"></select>
      <label>Upload <input id="upload" type="file" accept="image/*" hidden></label>
      <label><input id="rtl" type="checkbox"> Rechts→Links (Manga)</label>
      <span id="count" class="hint"></span>
    </div>
    <div id="stage" class="stage"><img id="page" alt="Comicseite"></div>
    <p class="hint">Bild ziehen &amp; ablegen oder „Upload" — die Erkennung läuft komplett lokal im Browser.</p>
  </div>`

const sampleSel = app.querySelector<HTMLSelectElement>('#sample')!
const uploadInput = app.querySelector<HTMLInputElement>('#upload')!
const rtlBox = app.querySelector<HTMLInputElement>('#rtl')!
const stage = app.querySelector<HTMLDivElement>('#stage')!
const pageImg = app.querySelector<HTMLImageElement>('#page')!
const count = app.querySelector<HTMLSpanElement>('#count')!

function runDetection() {
  if (!pageImg.naturalWidth) return
  try {
    const panels: Panel[] = detect(pageImg, pageImg.naturalWidth, pageImg.naturalHeight, rtlBox.checked)
    renderOverlay(stage, panels)
    count.textContent = panels.length === 0 ? 'keine Panels gefunden' : `${panels.length} Panels`
  } catch (e) {
    count.textContent = 'Fehler bei der Erkennung'
    console.error(e)
  }
}

function loadSrc(src: string) {
  pageImg.onload = runDetection
  pageImg.onerror = () => { count.textContent = 'Bild konnte nicht geladen werden' }
  pageImg.src = src
}

fetch(`${import.meta.env.BASE_URL}comics.json`)
  .then((r) => r.json())
  .then((list: ComicEntry[]) => {
    list.forEach((c, i) => {
      const o = document.createElement('option')
      o.value = c.file
      o.textContent = `${c.title} (${c.year})`
      if (i === 0) o.selected = true
      sampleSel.appendChild(o)
    })
    if (list.length) loadSrc(`${import.meta.env.BASE_URL}comics/${list[0].file}`)
  })
  .catch(() => { count.textContent = 'Beispiele nicht gefunden — bitte Bild hochladen' })

sampleSel.addEventListener('change', () =>
  loadSrc(`${import.meta.env.BASE_URL}comics/${sampleSel.value}`),
)
rtlBox.addEventListener('change', runDetection)

uploadInput.addEventListener('change', () => {
  const f = uploadInput.files?.[0]
  if (!f) return
  if (!f.type.startsWith('image/')) { count.textContent = 'Bitte eine Bilddatei wählen'; return }
  loadSrc(URL.createObjectURL(f))
})
app.querySelector('label')!.addEventListener('click', () => uploadInput.click())

;['dragover', 'dragenter'].forEach((ev) =>
  stage.addEventListener(ev, (e) => { e.preventDefault(); stage.classList.add('drop') }),
)
;['dragleave', 'drop'].forEach((ev) =>
  stage.addEventListener(ev, () => stage.classList.remove('drop')),
)
stage.addEventListener('drop', (e) => {
  e.preventDefault()
  const f = (e as DragEvent).dataTransfer?.files?.[0]
  if (f && f.type.startsWith('image/')) loadSrc(URL.createObjectURL(f))
})
