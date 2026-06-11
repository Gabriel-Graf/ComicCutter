import { detect } from './detect'

const app = document.querySelector<HTMLDivElement>('#app')!
const img = new Image()
img.onload = () => {
  const panels = detect(img, img.naturalWidth, img.naturalHeight, false)
  app.textContent = `Erkannt: ${panels.length} Panels`
}
img.onerror = () => { app.textContent = 'Bild-Ladefehler' }
img.src =
  'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M8AAAMBAQDJ/pLvAAAAAElFTkSuQmCC'
