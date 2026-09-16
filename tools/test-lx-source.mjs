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
// Mirrors SourceScriptCrypto.md5: the app URL-decodes the input before hashing.
context.__lx_native_call__utils_str2md5 = value => {
  let decoded = String(value)
  try {
    decoded = decodeURIComponent(decoded)
  } catch (error) {
    // keep the raw value when it is not valid percent-encoding
  }
  return crypto.createHash('md5').update(decoded).digest('hex')
}
// Mirrors SourceScriptCrypto.aesEncrypt. Note that the app maps "AES" to
// AES/ECB/PKCS5Padding, i.e. the same default as Cipher.getInstance("AES").
context.__lx_native_call__utils_aes_encrypt = (dataBase64, keyBase64, ivBase64, mode) => {
  try {
    const data = Buffer.from(String(dataBase64), 'base64')
    const key = Buffer.from(String(keyBase64), 'base64')
    const ivRaw = Buffer.from(String(ivBase64), 'base64')
    let algorithm
    let iv = null
    if (mode === 'AES' || mode === 'AES/ECB/PKCS5Padding' || mode === 'AES/ECB/PKCS7Padding') {
      algorithm = 'aes-128-ecb'
    } else if (mode === 'AES/CBC/PKCS5Padding' || mode === 'AES/CBC/PKCS7Padding') {
      algorithm = 'aes-128-cbc'
      iv = Buffer.alloc(16)
      ivRaw.copy(iv, 0, 0, Math.min(ivRaw.length, 16))
    } else {
      throw new Error(`unsupported aes mode: ${mode}`)
    }
    const cipher = crypto.createCipheriv(algorithm, key, iv)
    return Buffer.concat([cipher.update(data), cipher.final()]).toString('base64')
  } catch (error) {
    // The app swallows crypto failures and returns an empty string.
    console.log(`[aes-encrypt-failed] ${error?.message || error}`)
    return ''
  }
}
// Mirrors SourceScriptCrypto.rsaEncrypt.
context.__lx_native_call__utils_rsa_encrypt = (dataBase64, publicKeyBase64, padding) => {
  try {
    const data = Buffer.from(String(dataBase64), 'base64')
    const body = String(publicKeyBase64).replace(/\s+/g, '').match(/.{1,64}/g) || []
    const pem = `-----BEGIN PUBLIC KEY-----\n${body.join('\n')}\n-----END PUBLIC KEY-----\n`
    return crypto
      .publicEncrypt(
        {
          key: pem,
          padding: padding === 'RSA/ECB/NoPadding'
            ? crypto.constants.RSA_NO_PADDING
            : crypto.constants.RSA_PKCS1_PADDING,
        },
        data
      )
      .toString('base64')
  } catch (error) {
    console.log(`[rsa-encrypt-failed] ${error?.message || error}`)
    return ''
  }
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
    const bodyText = await response.text()
    // Mirrors SourceScriptHttp.parseBody: JSON-looking bodies become objects.
    const trimmed = bodyText.trim()
    let body = bodyText
    if (trimmed.startsWith('{') || trimmed.startsWith('[') || trimmed.startsWith('"')) {
      try {
        body = JSON.parse(trimmed)
      } catch (error) {
        body = bodyText
      }
    }
    const elapsed = Date.now() - startedAt
    requestLog.push({ url, status: response.status, elapsed, bytes: bodyText.length })
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

// Mirrors ThirdPartySourceScriptInfo: the app reads script metadata from the
// leading comment block instead of using the file name.
const SCRIPT_INFO_LIMITS = {
  name: 24,
  description: 36,
  version: 36,
  author: 56,
  homepage: 1024,
}

function parseScriptInfo(script) {
  const comment = /^\/\*[\s\S]+?\*\//.exec(script)?.[0] || ''
  const fields = {}
  for (const match of comment.matchAll(/^\s?\*\s?@(\w+)\s(.+)$/gm)) {
    const key = match[1]
    const value = match[2].trim()
    if (key in SCRIPT_INFO_LIMITS && value) {
      const limit = SCRIPT_INFO_LIMITS[key]
      fields[key] = value.length > limit ? `${value.slice(0, limit)}...` : value
    }
  }
  return {
    name: fields.name || '',
    description: fields.description || '',
    version: fields.version || '',
    author: fields.author || '',
    homepage: fields.homepage || '',
  }
}

const preload = fs.readFileSync(preloadPath, 'utf8')
const userScript = fs.readFileSync(scriptPath, 'utf8')
const scriptInfo = parseScriptInfo(userScript)
run(preload, preloadPath)
run(
  `globalThis.lx_setup(${JSON.stringify(key)}, "desktop-test", ${JSON.stringify(scriptInfo.name)}, ${JSON.stringify(scriptInfo.description)}, ${JSON.stringify(scriptInfo.version)}, ${JSON.stringify(scriptInfo.author)}, ${JSON.stringify(scriptInfo.homepage)}, ${JSON.stringify(userScript)})`,
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
