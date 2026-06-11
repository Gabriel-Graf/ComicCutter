// Kotlin/JS-Fassade — Webpack-Dist ist CommonJS mit Default-Export (kein named ESM).
// @ts-expect-error – generiertes Bundle ohne Typdeklarationen
import cc from './kotlin/comic-cutter.js'

export interface Panel {
  left: number
  top: number
  width: number
  height: number
  order: number
}

const detectPanelsKt = (cc as {
  detectPanels: (w: number, h: number, argb: Int32Array, rtl: boolean) => Panel[]
}).detectPanels

/** Max. Kantenlänge vor Erkennung — drosselt die Laufzeit, Output bleibt normalisiert. */
const MAX_EDGE = 1500

/**
 * Zeichnet das Bild (ggf. runterskaliert) auf ein Offscreen-Canvas, liest RGBA,
 * konvertiert nach ARGB-Int32Array und ruft den Kotlin-Detektor.
 */
export function detect(image: CanvasImageSource, srcW: number, srcH: number, rtl: boolean): Panel[] {
  const scale = Math.min(1, MAX_EDGE / Math.max(srcW, srcH))
  const w = Math.max(1, Math.round(srcW * scale))
  const h = Math.max(1, Math.round(srcH * scale))

  const canvas = document.createElement('canvas')
  canvas.width = w
  canvas.height = h
  const ctx = canvas.getContext('2d', { willReadFrequently: true })!
  ctx.drawImage(image, 0, 0, w, h)
  const rgba = ctx.getImageData(0, 0, w, h).data

  const argb = new Int32Array(w * h)
  for (let i = 0, p = 0; i < argb.length; i++, p += 4) {
    argb[i] = (rgba[p + 3] << 24) | (rgba[p] << 16) | (rgba[p + 1] << 8) | rgba[p + 2]
  }

  return detectPanelsKt(w, h, argb, rtl)
}
