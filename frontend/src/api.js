const API_BASE = '/api'

async function parseJsonSafe(response) {
  const text = await response.text()
  if (!text) return {}
  try {
    return JSON.parse(text)
  } catch {
    return { message: text }
  }
}

function authHeaders(token, extra = {}) {
  return token ? { ...extra, Authorization: `Bearer ${token}` } : extra
}

export function getErrorMessage(status, body) {
  const detail = body?.message || body?.error || ''
  if (status === 401) return detail || '未登录或 Token 已失效，请重新登录。'
  if (status === 400) return detail || '请求参数不正确，请检查输入。'
  if (status === 403) return detail || '当前会话未登记或无权限访问。'
  if (status === 429) return detail || '请求过于频繁，请稍后再试。'
  return detail || `请求失败，HTTP ${status}`
}

async function requestJson(path, options = {}) {
  const response = await fetch(`${API_BASE}${path}`, options)
  const body = await parseJsonSafe(response)
  if (!response.ok) {
    const error = new Error(getErrorMessage(response.status, body))
    error.status = response.status
    error.body = body
    throw error
  }
  return body
}

export async function login(username, password) {
  return requestJson('/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
}

export async function createAnalysisConversation(token) {
  return requestJson('/analysis/conversations', {
    method: 'POST',
    headers: authHeaders(token),
  })
}

export async function uploadAnalysisKnowledge(token, file) {
  const form = new FormData()
  form.append('file', file)
  return requestJson('/knowledge/upload', {
    method: 'POST',
    headers: authHeaders(token),
    body: form,
  })
}

export async function listAnalysisKnowledge(token) {
  return requestJson('/analysis/knowledge', {
    headers: authHeaders(token),
  })
}

export async function deleteAnalysisKnowledge(token, fileId) {
  await fetch(`${API_BASE}/analysis/knowledge/${encodeURIComponent(fileId)}`, {
    method: 'DELETE',
    headers: authHeaders(token),
  }).then(async (response) => {
    if (!response.ok) {
      const body = await parseJsonSafe(response)
      const error = new Error(getErrorMessage(response.status, body))
      error.status = response.status
      error.body = body
      throw error
    }
  })
}

export async function getAnalysisProfile(token) {
  return requestJson('/analysis/profile', {
    headers: authHeaders(token),
  })
}

export async function resetAnalysisProfile(token, conversationId, clearChatMemory = false) {
  const params = new URLSearchParams()
  params.set('clearChatMemory', String(clearChatMemory))
  if (conversationId) params.set('conversationId', conversationId)
  await fetch(`${API_BASE}/analysis/profile?${params.toString()}`, {
    method: 'DELETE',
    headers: authHeaders(token),
  }).then(async (response) => {
    if (!response.ok) {
      const body = await parseJsonSafe(response)
      throw new Error(getErrorMessage(response.status, body))
    }
  })
}

export async function extractAnalysisProfileSuggestion(token, conversationId, saveAsPending = true) {
  return requestJson('/analysis/profile/extract-suggestion', {
    method: 'POST',
    headers: authHeaders(token, { 'Content-Type': 'application/json' }),
    body: JSON.stringify({ conversationId, saveAsPending }),
  })
}

export async function getPendingAnalysisProfile(token, conversationId) {
  return requestJson(`/analysis/profile/pending-extraction?conversationId=${encodeURIComponent(conversationId)}`, {
    headers: authHeaders(token),
  })
}

export async function confirmPendingAnalysisProfile(token, conversationId) {
  return requestJson('/analysis/profile/confirm-extraction', {
    method: 'POST',
    headers: authHeaders(token, { 'Content-Type': 'application/json' }),
    body: JSON.stringify({ conversationId }),
  })
}

export async function discardPendingAnalysisProfile(token, conversationId) {
  await fetch(`${API_BASE}/analysis/profile/pending-extraction?conversationId=${encodeURIComponent(conversationId)}`, {
    method: 'DELETE',
    headers: authHeaders(token),
  }).then(async (response) => {
    if (!response.ok) {
      const body = await parseJsonSafe(response)
      throw new Error(getErrorMessage(response.status, body))
    }
  })
}

export async function submitAnalysisFeedback(token, payload) {
  return requestJson('/analysis/feedback', {
    method: 'POST',
    headers: authHeaders(token, { 'Content-Type': 'application/json' }),
    body: JSON.stringify(payload),
  })
}

export async function listAnalysisFeedback(token, limit = 5) {
  return requestJson(`/analysis/feedback?limit=${limit}&offset=0`, {
    headers: authHeaders(token),
  })
}

export async function streamAnalysisChat(token, conversationId, query, handlers, signal) {
  const response = await fetch(`${API_BASE}/analysis/chat/${encodeURIComponent(conversationId)}`, {
    method: 'POST',
    headers: authHeaders(token, {
      Accept: 'text/event-stream',
      'Content-Type': 'application/json',
    }),
    body: JSON.stringify({ query }),
    signal,
  })

  if (!response.ok) {
    const body = await parseJsonSafe(response)
    const error = new Error(getErrorMessage(response.status, body))
    error.status = response.status
    error.body = body
    throw error
  }
  if (!response.body) {
    throw new Error('浏览器没有返回可读取的 SSE 响应体。')
  }

  await readSseStream(response, handlers)
}

export const createConversation = createAnalysisConversation
export const uploadKnowledge = uploadAnalysisKnowledge
export const listKnowledge = listAnalysisKnowledge
export const deleteKnowledge = deleteAnalysisKnowledge
export const getProfile = getAnalysisProfile
export const resetProfile = resetAnalysisProfile
export const extractProfileSuggestion = extractAnalysisProfileSuggestion
export const getPendingProfile = getPendingAnalysisProfile
export const confirmPendingProfile = confirmPendingAnalysisProfile
export const discardPendingProfile = discardPendingAnalysisProfile
export const submitFeedback = submitAnalysisFeedback
export const listFeedback = listAnalysisFeedback
export const streamChat = streamAnalysisChat

async function readSseStream(response, handlers) {
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let eventName = 'message'
  let dataLines = []

  const dispatch = () => {
    if (dataLines.length === 0) {
      eventName = 'message'
      return
    }
    const data = dataLines.join('\n')
    handlers.onEvent?.({ event: eventName || 'message', data })
    eventName = 'message'
    dataLines = []
  }

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop() ?? ''

    for (const raw of lines) {
      const line = raw.replace(/\r$/, '')
      if (line === '') {
        dispatch()
      } else if (line.startsWith('event:')) {
        eventName = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trimStart())
      } else if (line.startsWith(':')) {
        handlers.onComment?.(line.slice(1).trim())
      } else {
        handlers.onParseError?.(line)
      }
    }
  }

  buffer += decoder.decode()
  if (buffer.trim()) {
    const line = buffer.replace(/\r$/, '')
    if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trimStart())
    } else {
      handlers.onParseError?.(line)
    }
  }
  dispatch()
}
