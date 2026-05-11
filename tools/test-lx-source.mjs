import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import vm from 'node:vm'

const repoRoot = process.cwd()
const preloadPath = path.join(repoRoot, 'app/src/main/assets/script/user-api-preload.js')
const scriptPath = process.argv[2]

if (!scriptPath) {
  console.error('Usage: node tools/test-lx-source.mjs <source.js> [source=wy] [quality=320k]')
  process.exit(2)
}

const source = process.argv[3] || 'wy'
const quality = process.argv[4] || '320k'
const key = `test-${Date.now()}`
const requestLog = []
let completed = false

process.on('uncaughtException', error => {
  if (completed) {
    console.log(`[late-error] ${error?.message || error}`)
    process.exit(0)
  }
  throw error
})

let initInfo = null
let topLevelResponse = null
let topLevelResolve

const done = new Promise(resolve => {
  topLevelResolve = resolve
})

const context = {
  console,
  URL,
  URLSearchParams,
  TextDecoder,
  TextEncoder,
  Uint8Array,
  ArrayBuffer,
  DataView,
  Math,
  Date,
  JSON,
  Promise,
  Error,
  AggregateError,
  setTimeout,
  clearTimeout,
}

context.globalThis = context
context.__lx_native_call__ = (actualKey, action, data) => {
  if (actualKey !== key) return null
  switch (action) {
    case 'init':
      initInfo = JSON.parse(data)
      console.log('[init]', JSON.stringify(initInfo))
      return null
    case 'request':
      handleHttpRequest(JSON.parse(data))
      return null
    case 'response':
      topLevelResponse = JSON.parse(data)
      topLevelResolve(topLevelResponse)
      return null
    case 'showUpdateAlert':
      console.log('[updateAlert]', data)
      return null
    default:
      console.log('[native]', action, data)
      return null
  }
}

context.__lx_native_call__set_timeout = (id, delay) => {
  setTimeout(() => {
    vm.runInContext(
      `globalThis.__lx_native__(${JSON.stringify(key)}, "__set_timeout__", ${JSON.stringify(id)})`,
      sandbox
    )
  }, Number(delay) || 0)
}

context.__lx_native_call__utils_str2b64 = value =>
  Buffer.from(String(value), 'utf8').toString('base64')
context.__lx_native_call__utils_b642buf = value =>
  JSON.stringify(Array.from(Buffer.from(String(value), 'base64')))
context.__lx_native_call__utils_str2md5 = value =>
  crypto.createHash('md5').update(String(value)).digest('hex')
context.__lx_native_call__utils_aes_encrypt = () => {
  throw new Error('aes_encrypt not implemented in desktop test harness')
}
context.__lx_native_call__utils_rsa_encrypt = () => {
  throw new Error('rsa_encrypt not implemented in desktop test harness')
}

const sandbox = vm.createContext(context)

async function handleHttpRequest(payload) {
  const { requestKey, url, options = {} } = payload
  const startedAt = Date.now()
  try {
    const method = String(options.method || 'GET').toUpperCase()
    const headers = options.headers || {}
    const init = { method, headers }
    if (method !== 'GET' && method !== 'HEAD') {
      if (options.body != null) {
        init.body = typeof options.body === 'string' ? options.body : JSON.stringify(options.body)
      } else if (options.form && typeof options.form === 'object') {
        init.body = new URLSearchParams(options.form).toString()
        init.headers = { 'content-type': 'application/x-www-form-urlencoded', ...headers }
      } else if (options.formData && typeof options.formData === 'object') {
        init.body = new URLSearchParams(options.formData).toString()
        init.headers = { 'content-type': 'application/x-www-form-urlencoded', ...headers }
      }
    }
    const controller = new AbortController()
    const timeout = setTimeout(
      () => controller.abort(),
      Math.min(Number(options.timeout) || 30000, 60000)
    )
    init.signal = controller.signal
    const response = await fetch(url, init)
    clearTimeout(timeout)
    const body = await response.text()
    const elapsed = Date.now() - startedAt
    requestLog.push({ url, status: response.status, elapsed, bytes: body.length })
    console.log(`[http] ${response.status} ${elapsed}ms ${url}`)
    const headersObj = {}
    response.headers.forEach((value, name) => { headersObj[name] = value })
    respondToScript('response', {
      requestKey,
      response: {
        statusCode: response.status,
        statusMessage: response.statusText,
        headers: headersObj,
        body,
      },
    })
  } catch (error) {
    const elapsed = Date.now() - startedAt
    requestLog.push({ url, error: error?.message || String(error), elapsed })
    console.log(`[http-error] ${elapsed}ms ${url} :: ${error?.message || error}`)
    respondToScript('response', {
      requestKey,
      error: error?.message || String(error),
    })
  }
}

function respondToScript(action, payload) {
  vm.runInContext(
    `globalThis.__lx_native__(${JSON.stringify(key)}, ${JSON.stringify(action)}, ${JSON.stringify(JSON.stringify(payload))})`,
    sandbox
  )
}

function run(code, filename) {
  return vm.runInContext(code, sandbox, { filename })
}

const preload = fs.readFileSync(preloadPath, 'utf8')
const userScript = fs.readFileSync(scriptPath, 'utf8')
run(preload, preloadPath)
run(
  `globalThis.lx_setup(${JSON.stringify(key)}, "desktop-test", "desktop-test", "", "", "", "", ${JSON.stringify(userScript)})`,
  'lx_setup.js'
)
run(userScript, scriptPath)

await waitFor(() => initInfo != null, 5000, 'source init')

const song = {
  name: 'Self Love',
  singer: 'Metro Boomin/Coi Leray',
  albumName: 'METRO BOOMIN PRESENTS SPIDER-MAN: ACROSS THE SPIDER-VERSE',
  interval: 189,
  source,
  // The Android app fills these with the Netease song id. Override using env LX_SONG_ID.
  id: process.env.LX_SONG_ID || '',
  songmid: process.env.LX_SONG_ID || '',
  hash: process.env.LX_SONG_ID || '',
}

const payload = {
  requestKey: 'desktop-main',
  data: {
    source,
    action: 'musicUrl',
    info: {
      type: quality,
      musicInfo: song,
    },
  },
}

console.log('[request]', JSON.stringify(payload.data))
respondToScript('request', payload)

const response = await Promise.race([
  done,
  new Promise(resolve => setTimeout(() => resolve({ timeout: true }), 90000)),
])
completed = true

console.log('[result]', JSON.stringify(response, null, 2))
console.log('[http-summary]', JSON.stringify(requestLog, null, 2))

async function waitFor(predicate, timeoutMs, label) {
  const started = Date.now()
  while (!predicate()) {
    if (Date.now() - started > timeoutMs) {
      throw new Error(`Timeout waiting for ${label}`)
    }
    await new Promise(resolve => setTimeout(resolve, 20))
  }
}
