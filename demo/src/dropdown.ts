/** Themed custom select replacing the native <select> — keyboard-aware, animated. */
export interface DropdownHandle {
  setIndex(i: number): void
}

export function createDropdown(
  host: HTMLElement,
  labels: string[],
  onSelect: (index: number) => void,
): DropdownHandle {
  let index = 0

  host.classList.add('dd')
  host.innerHTML = `
    <button type="button" class="dd-trigger" aria-haspopup="listbox" aria-expanded="false">
      <span class="dd-label"></span>
      <svg class="dd-chevron" width="12" height="12" viewBox="0 0 24 24" fill="none"
        stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <polyline points="6 9 12 15 18 9"/>
      </svg>
    </button>
    <ul class="dd-menu" role="listbox"></ul>`

  const trigger = host.querySelector<HTMLButtonElement>('.dd-trigger')!
  const labelEl = host.querySelector<HTMLSpanElement>('.dd-label')!
  const menu = host.querySelector<HTMLUListElement>('.dd-menu')!

  labels.forEach((label, i) => {
    const li = document.createElement('li')
    li.className = 'dd-option'
    li.setAttribute('role', 'option')
    li.innerHTML =
      `<span class="dd-num">${String(i + 1).padStart(2, '0')}</span><span class="dd-text"></span>`
    li.querySelector('.dd-text')!.textContent = label
    li.addEventListener('click', () => { close(); onSelect(i) })
    menu.appendChild(li)
  })
  const options = Array.from(menu.querySelectorAll<HTMLLIElement>('.dd-option'))

  function render() {
    labelEl.textContent = labels[index] ?? ''
    options.forEach((o, i) => o.classList.toggle('current', i === index))
  }

  function open() {
    host.classList.add('open')
    trigger.setAttribute('aria-expanded', 'true')
    options[index]?.scrollIntoView({ block: 'nearest' })
  }
  function close() {
    host.classList.remove('open')
    trigger.setAttribute('aria-expanded', 'false')
  }
  function toggle() { host.classList.contains('open') ? close() : open() }

  trigger.addEventListener('click', toggle)
  trigger.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); onSelect((index + 1) % labels.length) }
    else if (e.key === 'ArrowUp') { e.preventDefault(); onSelect((index - 1 + labels.length) % labels.length) }
    else if (e.key === 'Escape') close()
  })
  document.addEventListener('click', (e) => {
    if (!host.contains(e.target as Node)) close()
  })

  render()
  return { setIndex(i: number) { index = i; render() } }
}
