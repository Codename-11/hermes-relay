const sdk = `
export const PANES_AREA = 'panes'
export const SIDEBAR_NAV_AREA = 'sidebar.nav'
export const STATUSBAR_AREAS = { right: 'statusBar.right' }
export const PALETTE_AREA = 'palette'
export const Badge = 'Badge'
export const Button = 'Button'
export const Codicon = 'Codicon'
export const EmptyState = 'EmptyState'
export const ErrorState = 'ErrorState'
export const Input = 'Input'
export const StatusDot = 'StatusDot'
export const Tip = 'Tip'
export const host = { state: { profile: { get: () => 'default' } }, notify: () => {} }
export const useMutation = () => ({ mutate: () => {}, isPending: false })
export const usePluginI18n = () => key => key
export const useQuery = () => ({ data: null, error: null, isPending: false, refetch: () => {} })
export const useQueryClient = () => ({ invalidateQueries: () => {} })
export const useValue = atom => atom.get()
`

const react = `export const useState = initial => [initial, () => {}]`
const jsx = `
export const Fragment = Symbol.for('fixture.fragment')
export const jsx = (type, props) => ({ type, props: props || {} })
export const jsxs = jsx
`

export async function resolve(specifier, context, nextResolve) {
  if (specifier === '@hermes/plugin-sdk') {
    return { shortCircuit: true, url: `data:text/javascript,${encodeURIComponent(sdk)}` }
  }
  if (specifier === 'react') {
    return { shortCircuit: true, url: `data:text/javascript,${encodeURIComponent(react)}` }
  }
  if (specifier === 'react/jsx-runtime') {
    return { shortCircuit: true, url: `data:text/javascript,${encodeURIComponent(jsx)}` }
  }
  return nextResolve(specifier, context)
}
