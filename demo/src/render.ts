import type { Panel } from './detect'

const COLORS = ['#2d7dff', '#ff2d55', '#39ff14', '#ffb300', '#b026ff', '#00d4c8']

/** Baut ein Overlay (absolut positionierte Boxen + Nummern) über dem angezeigten Bild. */
export function renderOverlay(host: HTMLElement, panels: Panel[]): void {
  host.querySelectorAll('.panel-box').forEach((n) => n.remove())
  panels.forEach((p) => {
    const box = document.createElement('div')
    box.className = 'panel-box'
    const c = COLORS[(p.order - 1) % COLORS.length]
    box.style.cssText =
      `left:${p.left * 100}%;top:${p.top * 100}%;width:${p.width * 100}%;height:${p.height * 100}%;` +
      `border-color:${c};box-shadow:0 0 0 1px rgba(0,0,0,.6);`
    const badge = document.createElement('span')
    badge.className = 'panel-badge'
    badge.style.background = c
    badge.textContent = String(p.order)
    box.appendChild(badge)
    host.appendChild(box)
  })
}
