const WebSocket = require('ws')
const { randomUUID } = require('crypto')

const PORT = process.env.PORT || 8080
const wss = new WebSocket.Server({ port: PORT })

// senderId -> { recipientId, messages: [], subscriber: WebSocket | null }
const queues = new Map()
// recipientId -> senderId (reverse lookup)
const byRecipient = new Map()

function send(ws, obj) {
  if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj))
}

wss.on('connection', ws => {
  ws.on('message', raw => {
    let msg
    try { msg = JSON.parse(raw) } catch { return }

    switch (msg.type) {
      case 'NEW': {
        const recipientId = randomUUID()
        const senderId = randomUUID()
        queues.set(senderId, { recipientId, messages: [], subscriber: null })
        byRecipient.set(recipientId, senderId)
        send(ws, { type: 'IDS', recipientId, senderId })
        break
      }

      case 'SUB': {
        const senderId = byRecipient.get(msg.recipientId)
        if (!senderId) { send(ws, { type: 'ERR', code: 'NOT_FOUND' }); return }
        const q = queues.get(senderId)
        q.subscriber = ws
        // flush queued messages
        q.messages.splice(0).forEach(m => send(ws, m))
        send(ws, { type: 'OK' })
        break
      }

      case 'SEND': {
        const q = queues.get(msg.senderId)
        if (!q) { send(ws, { type: 'ERR', code: 'NOT_FOUND' }); return }
        const envelope = { type: 'MSG', msgId: randomUUID(), data: msg.data, ts: Date.now() }
        if (q.subscriber?.readyState === WebSocket.OPEN) {
          send(q.subscriber, envelope)
        } else if (q.messages.length < 100) {
          q.messages.push(envelope)
        }
        send(ws, { type: 'OK' })
        break
      }

      case 'ACK': break  // message already delivered — no response needed
    }
  })

  ws.on('close', () => {
    for (const q of queues.values()) {
      if (q.subscriber === ws) q.subscriber = null
    }
  })
})

console.log(`TruSTI relay listening on ws://0.0.0.0:${PORT}`)
