// Kotlin/JS facade — the Webpack dist is CommonJS with a default export (not named ESM).
// @ts-expect-error – generated bundle without type declarations
import cc from './kotlin/comic-cutter.js'

export interface Panel {
  left: number
  top: number
  width: number
  height: number
  order: number
}

const ccApi = cc as {
  detectPanels: (w: number, h: number, argb: Int32Array, rtl: boolean) => Panel[]
  libVersion: () => string
}
const detectPanelsKt = ccApi.detectPanels

/** Built library version (= release tag), shown in the demo. */
export const LIB_VERSION = ccApi.libVersion()

/**
 * Canvas memory guard for very large uploads only. The library itself downscales to its tuned
 * detection width (≤ 1000 px) before detecting, so normal comic pages pass through untouched here
 * (no double resample). Output stays normalized regardless.
 */
const MAX_EDGE = 2400

/**
 * Draws the image (downscaled if large) to an offscreen canvas, reads RGBA,
 * converts to an ARGB Int32Array and runs the Kotlin detector.
 * Reading order is fixed left-to-right — direction only affects numbering,
 * not the detected boxes.
 */
export function detect(image: CanvasImageSource, srcW: number, srcH: number): Panel[] {
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

  return detectPanelsKt(w, h, argb, false)
}
