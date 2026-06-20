const EMPTY_ROOM_TTL_MS = 60 * 1000;
const SOLO_ROOM_TTL_MS = 10 * 60 * 1000;
const CLIENT_TIMEOUT_MS = 45 * 1000;
const ALARM_INTERVAL_MS = 10 * 1000;
const STORAGE_KEY = "listen_together_room_snapshot";

const TYPE_SERVER_READY = "server_ready";
const TYPE_PEER_JOINED = "peer_joined";
const TYPE_PEER_LEFT = "peer_left";
const TYPE_ERROR = "error";
const TYPE_LEAVE = "leave";
const TYPE_PING = "ping";
const TYPE_PONG = "pong";
const TYPE_EVENT_ACK = "event_ack";

const TYPE_OWNER_COOKIE = "owner_cookie";
const TYPE_PLAYLIST = "playlist";
const TYPE_CURRENT_SONG = "current_song";
const TYPE_PLAY_STATE = "play_state";
const TYPE_SEEK = "seek";

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/" || url.pathname === "/health") {
      return jsonResponse({
        ok: true,
        service: "Music Listen Together Worker",
        durableObjectReady: !!env.LISTEN_TOGETHER_ROOM,
      });
    }

    if (url.pathname !== "/ws") {
      return new Response("Not found", { status: 404 });
    }

    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("Expected WebSocket", { status: 426 });
    }

    const room = (url.searchParams.get("room") || "").trim();
    if (!/^\d+$/.test(room)) {
      return new Response("Room must be numeric", { status: 400 });
    }

    if (!env.LISTEN_TOGETHER_ROOM) {
      return new Response("Missing Durable Object binding: LISTEN_TOGETHER_ROOM", { status: 500 });
    }

    const id = env.LISTEN_TOGETHER_ROOM.idFromName(room);
    return env.LISTEN_TOGETHER_ROOM.get(id).fetch(request);
  },
};

export class ListenTogetherRoom {
  constructor(state) {
    this.state = state;
    this.clients = new Map();
    this.members = new Map();
    this.ownerId = null;
    this.serverVersion = 0;
    this.soloSince = 0;
    this.emptySince = 0;

    this.ownerCookie = null;
    this.playlist = null;
    this.currentSong = null;
    this.playState = null;
    this.lastSeek = null;
    this.presence = new Map();

    this.state.blockConcurrencyWhile(async () => {
      const snapshot = await this.state.storage.get(STORAGE_KEY);
      if (snapshot) this.restoreSnapshot(snapshot);
    });
  }

  async fetch(request) {
    const url = new URL(request.url);
    const room = (url.searchParams.get("room") || "").trim();
    const clientId = (url.searchParams.get("clientId") || crypto.randomUUID()).trim();
    const deviceName = (url.searchParams.get("deviceName") || "NetEase Music User").trim();
    const mode = (url.searchParams.get("mode") || "join").trim();

    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("Expected WebSocket", { status: 426 });
    }

    if (await this.releaseIfExpired(Date.now())) {
      await this.state.storage.deleteAll();
    }

    const roleOrError = this.assignRole(clientId, mode);
    if (typeof roleOrError !== "string") {
      return this.acceptThenError(roleOrError.message);
    }

    this.closeSocketOnly(clientId);

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    server.accept();

    const now = Date.now();
    const clientInfo = {
      socket: server,
      clientId,
      deviceName,
      role: roleOrError,
      joinedAt: now,
      lastSeen: now,
    };
    this.clients.set(clientId, clientInfo);
    this.updateOccupancyTimers(now);

    server.addEventListener("message", (event) => this.handleMessage(clientId, event.data));
    server.addEventListener("close", () => this.handleDisconnect(clientId, false));
    server.addEventListener("error", () => this.handleDisconnect(clientId, false));

    this.send(server, {
      type: TYPE_SERVER_READY,
      room,
      role: roleOrError,
      senderId: "server",
      deviceName: "Cloudflare Worker",
      ts: now,
    });
    this.sendCachedState(server, roleOrError);

    this.broadcast(clientId, {
      type: TYPE_PEER_JOINED,
      senderId: "server",
      deviceName,
      role: roleOrError,
      ts: now,
    });

    this.refreshAlarm();
    void this.persistRoom();
    return new Response(null, { status: 101, webSocket: client });
  }

  assignRole(clientId, mode) {
    const knownRole = this.members.get(clientId);
    if (knownRole) {
      return knownRole;
    }

    if (mode === "create") {
      if (this.ownerId && this.ownerId !== clientId) {
        return { message: "Room already has an owner." };
      }
      this.ownerId = clientId;
      this.members.set(clientId, "owner");
      return "owner";
    }

    if (!this.ownerId) {
      return { message: "Room does not exist. Ask the owner to create it first." };
    }
    if (this.members.size >= 2) {
      return { message: "Room is full." };
    }

    this.members.set(clientId, "guest");
    return "guest";
  }

  handleMessage(clientId, raw) {
    let message;
    try {
      message = JSON.parse(raw);
    } catch {
      return;
    }

    const client = this.clients.get(clientId);
    if (!client) return;

    const now = Date.now();
    client.lastSeen = now;

    if (message.type === TYPE_PING) {
      const status = normalizePresenceStatus(message.status || {});
      this.presence.set(clientId, {
        ...status,
        clientId,
        deviceName: client.deviceName,
        role: client.role,
        updatedAt: now,
      });
      const peerStatus = this.getPeerPresence(clientId);
      this.send(client.socket, {
        type: TYPE_PONG,
        senderId: "server",
        deviceName: "Cloudflare Worker",
        peerDeviceName: peerStatus?.deviceName || "",
        peerStatus: peerStatus || null,
        clientSentAt: message.clientSentAt || 0,
        workerReceivedAt: now,
        workerSentAt: Date.now(),
        ts: Date.now(),
      });
      this.refreshAlarm();
      return;
    }

    if (message.type === TYPE_LEAVE) {
      this.broadcast(clientId, {
        type: TYPE_PEER_LEFT,
        senderId: clientId,
        deviceName: client.deviceName,
        ts: now,
      });
      void this.handleDisconnect(clientId, true);
      return;
    }

    if (this.isSyncEvent(message.type)) {
      this.handleSyncEvent(clientId, client, message, now);
      this.refreshAlarm();
      return;
    }

    this.refreshAlarm();
  }

  isSyncEvent(type) {
    return type === TYPE_OWNER_COOKIE ||
      type === TYPE_PLAYLIST ||
      type === TYPE_CURRENT_SONG ||
      type === TYPE_PLAY_STATE ||
      type === TYPE_SEEK;
  }

  handleSyncEvent(clientId, client, message, now) {
    if (message.type === TYPE_OWNER_COOKIE && client.role !== "owner") {
      this.sendError(client.socket, "Only the room owner can update owner cookie.");
      return;
    }

    const event = this.normalizeEvent(client, message, now);
    this.cacheEvent(event);
    this.broadcast(clientId, event);
    this.send(client.socket, {
      type: TYPE_EVENT_ACK,
      eventType: event.type,
      senderId: "server",
      deviceName: "Cloudflare Worker",
      serverVersion: event.serverVersion,
      ts: Date.now(),
    });
    void this.persistRoom();
  }

  normalizeEvent(client, message, now) {
    const payload = isObject(message.payload) ? message.payload : {};
    return {
      type: message.type,
      senderId: client.clientId,
      senderRole: client.role,
      deviceName: client.deviceName,
      serverVersion: ++this.serverVersion,
      clientVersion: Number(message.clientVersion || 0),
      clientSentAt: Number(message.clientSentAt || 0),
      serverAt: now,
      ts: now,
      payload: normalizePayload(message.type, payload),
    };
  }

  cacheEvent(event) {
    if (event.type === TYPE_OWNER_COOKIE) {
      this.ownerCookie = event;
      return;
    }
    if (event.type === TYPE_PLAYLIST) {
      this.playlist = event;
      return;
    }
    if (event.type === TYPE_CURRENT_SONG) {
      this.currentSong = event;
      this.playState = {
        ...event,
        type: TYPE_PLAY_STATE,
        payload: {
          playing: !!event.payload.playing,
          positionMs: Number(event.payload.positionMs || 0),
        },
      };
      return;
    }
    if (event.type === TYPE_PLAY_STATE) {
      this.playState = event;
      return;
    }
    if (event.type === TYPE_SEEK) {
      this.lastSeek = event;
      this.playState = {
        ...(this.playState || event),
        type: TYPE_PLAY_STATE,
        serverAt: event.serverAt,
        serverVersion: event.serverVersion,
        payload: {
          ...(this.playState?.payload || {}),
          positionMs: Number(event.payload.positionMs || 0),
        },
      };
    }
  }

  sendCachedState(socket, role) {
    if (role === "guest" && this.ownerCookie) {
      this.send(socket, this.asServerReplay(this.ownerCookie));
    }
    if (this.playlist) this.send(socket, this.asServerReplay(this.playlist));
    if (this.currentSong) this.send(socket, this.asServerReplay(this.currentSong));
    if (this.playState) this.send(socket, this.asServerReplay(this.playState));
    if (this.lastSeek) this.send(socket, this.asServerReplay(this.lastSeek));
  }

  asServerReplay(event) {
    return {
      ...event,
      replay: true,
      workerSentAt: Date.now(),
    };
  }

  async handleDisconnect(clientId, explicitLeave) {
    const client = this.clients.get(clientId);
    if (!client) return;

    this.closeSocketOnly(clientId);

    if (explicitLeave) {
      this.members.delete(clientId);
      if (clientId === this.ownerId) {
        await this.releaseRoom();
        return;
      }
    }

    this.updateOccupancyTimers(Date.now());
    this.refreshAlarm();
    await this.persistRoom();
  }

  async alarm() {
    const now = Date.now();
    for (const [clientId, client] of this.clients) {
      if (now - client.lastSeen > CLIENT_TIMEOUT_MS) {
        await this.handleDisconnect(clientId, false);
      }
    }

    this.updateOccupancyTimers(now);
    if (await this.releaseIfExpired(now)) return;

    this.refreshAlarm();
    await this.persistRoom();
  }

  updateOccupancyTimers(now = Date.now()) {
    if (this.clients.size >= 2) {
      this.soloSince = 0;
      this.emptySince = 0;
      return;
    }
    if (this.clients.size === 1) {
      this.soloSince ||= now;
      this.emptySince = 0;
      return;
    }
    if (this.members.size > 0 || this.ownerId) {
      this.emptySince ||= now;
      this.soloSince = 0;
      return;
    }
    this.soloSince = 0;
    this.emptySince = 0;
  }

  async releaseIfExpired(now) {
    if (this.clients.size === 0 && this.emptySince && now - this.emptySince >= EMPTY_ROOM_TTL_MS) {
      await this.releaseRoom();
      return true;
    }

    if (this.clients.size === 1 && this.soloSince && now - this.soloSince >= SOLO_ROOM_TTL_MS) {
      this.broadcast("", {
        type: TYPE_ERROR,
        senderId: "server",
        deviceName: "Cloudflare Worker",
        message: "Room closed because only one device stayed for more than 10 minutes.",
        ts: now,
      });
      await this.releaseRoom();
      return true;
    }

    return false;
  }

  refreshAlarm() {
    if (this.clients.size > 0 || this.members.size > 0 || this.ownerId) {
      this.state.storage.setAlarm(Date.now() + ALARM_INTERVAL_MS);
    }
  }

  closeSocketOnly(clientId) {
    const client = this.clients.get(clientId);
    if (!client) return;
    this.clients.delete(clientId);
    this.presence.delete(clientId);
    try {
      client.socket.close(1000, "disconnect");
    } catch {
    }
  }

  async releaseRoom() {
    for (const client of this.clients.values()) {
      try {
        client.socket.close(1000, "room-release");
      } catch {
      }
    }
    this.clients.clear();
    this.members.clear();
    this.ownerId = null;
    this.serverVersion = 0;
    this.soloSince = 0;
    this.emptySince = 0;
    this.ownerCookie = null;
    this.playlist = null;
    this.currentSong = null;
    this.playState = null;
    this.lastSeek = null;
    this.presence.clear();
    await this.state.storage.deleteAll();
  }

  persistRoom() {
    const snapshot = {
      ownerId: this.ownerId,
      members: Array.from(this.members.entries()),
      serverVersion: this.serverVersion,
      soloSince: this.soloSince,
      emptySince: this.emptySince,
      ownerCookie: this.ownerCookie,
      playlist: this.playlist,
      currentSong: this.currentSong,
      playState: this.playState,
      lastSeek: this.lastSeek,
    };
    return this.state.storage.put(STORAGE_KEY, snapshot).catch(() => {});
  }

  restoreSnapshot(snapshot) {
    this.ownerId = typeof snapshot.ownerId === "string" ? snapshot.ownerId : null;
    this.members = new Map(
      Array.isArray(snapshot.members)
        ? snapshot.members.filter((entry) => Array.isArray(entry) && entry.length === 2)
        : []
    );
    this.serverVersion = Number(snapshot.serverVersion || 0);
    this.soloSince = Number(snapshot.soloSince || 0);
    this.emptySince = Number(snapshot.emptySince || 0);
    this.ownerCookie = isObject(snapshot.ownerCookie) ? snapshot.ownerCookie : null;
    this.playlist = isObject(snapshot.playlist) ? snapshot.playlist : null;
    this.currentSong = isObject(snapshot.currentSong) ? snapshot.currentSong : null;
    this.playState = isObject(snapshot.playState) ? snapshot.playState : null;
    this.lastSeek = isObject(snapshot.lastSeek) ? snapshot.lastSeek : null;
  }

  broadcast(senderId, message) {
    const payload = { ...message, workerSentAt: Date.now() };
    for (const [clientId, client] of this.clients) {
      if (clientId === senderId) continue;
      this.send(client.socket, payload);
    }
  }

  getPeerPresence(clientId) {
    for (const [id, status] of this.presence) {
      if (id !== clientId) return status;
    }
    return null;
  }

  acceptThenError(message) {
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    server.accept();
    this.sendError(server, message);
    server.close(1000, message);
    return new Response(null, { status: 101, webSocket: client });
  }

  sendError(socket, message) {
    this.send(socket, {
      type: TYPE_ERROR,
      senderId: "server",
      deviceName: "Cloudflare Worker",
      message,
      ts: Date.now(),
    });
  }

  send(socket, message) {
    try {
      socket.send(JSON.stringify(message));
    } catch {
    }
  }
}

function normalizePayload(type, payload) {
  if (type === TYPE_OWNER_COOKIE) {
    return { cookie: String(payload.cookie || "") };
  }
  if (type === TYPE_PLAYLIST) {
    return {
      playlist: Array.isArray(payload.playlist) ? payload.playlist.map(normalizeSong) : [],
      currentMediaId: String(payload.currentMediaId || ""),
      currentIndex: Number(payload.currentIndex || 0),
      playing: !!payload.playing,
    };
  }
  if (type === TYPE_CURRENT_SONG) {
    return {
      song: normalizeSong(payload.song || {}),
      mediaId: String(payload.mediaId || payload.song?.mediaId || ""),
      index: Number(payload.index || 0),
      playing: !!payload.playing,
      positionMs: Number(payload.positionMs || 0),
    };
  }
  if (type === TYPE_PLAY_STATE) {
    return {
      playing: !!payload.playing,
      positionMs: Number(payload.positionMs || 0),
    };
  }
  if (type === TYPE_SEEK) {
    return { positionMs: Number(payload.positionMs || 0) };
  }
  return payload;
}

function normalizePresenceStatus(status) {
  return {
    title: String(status.title || ""),
    artist: String(status.artist || ""),
    mediaId: String(status.mediaId || ""),
    playing: !!status.playing,
    positionMs: Number(status.positionMs || 0),
  };
}

function normalizeSong(song) {
  return {
    mediaId: String(song.mediaId || ""),
    uri: String(song.uri || ""),
    title: String(song.title || ""),
    artist: String(song.artist || ""),
    album: String(song.album || ""),
    cover: String(song.cover || ""),
    duration: Number(song.duration || 0),
    songId: Number(song.songId || 0),
  };
}

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function jsonResponse(body) {
  return new Response(JSON.stringify(body), {
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}
