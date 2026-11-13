// One STOMP connection for the whole app. Components subscribe to destinations; subscriptions
// survive reconnects (they are re-registered on every connect), and a token change reconnects
// so private queues are authorized for the signed-in user.

import { Client, type StompSubscription } from "@stomp/stompjs";
import { useEffect, useRef, useSyncExternalStore } from "react";
import type { StreamEvent } from "./liveState";

type Listener = (event: StreamEvent) => void;
type Status = "connecting" | "live" | "offline";

const listeners = new Map<string, Set<Listener>>();
const active = new Map<string, StompSubscription>();
const statusListeners = new Set<() => void>();
let status: Status = "connecting";
let token: string | null = null;
let client: Client | null = null;

function setStatus(next: Status) {
  status = next;
  statusListeners.forEach((l) => l());
}

function brokerUrl(): string {
  const scheme = window.location.protocol === "https:" ? "wss" : "ws";
  return `${scheme}://${window.location.host}/ws`;
}

function attach(destination: string) {
  if (!client?.connected || active.has(destination)) return;
  const subscription = client.subscribe(destination, (frame) => {
    let event: StreamEvent;
    try {
      event = JSON.parse(frame.body) as StreamEvent;
    } catch {
      return;
    }
    listeners.get(destination)?.forEach((l) => l(event));
  });
  active.set(destination, subscription);
}

function connect() {
  client?.deactivate();
  active.clear();
  setStatus("connecting");
  const next = new Client({
    brokerURL: brokerUrl(),
    connectHeaders: token ? { Authorization: `Bearer ${token}` } : {},
    reconnectDelay: 2000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    onConnect: () => {
      if (client !== next) return;
      active.clear();
      setStatus("live");
      listeners.forEach((set, destination) => {
        if (set.size > 0) attach(destination);
      });
    },
    // A replaced client (token change) closing must not mark the new connection offline.
    onWebSocketClose: () => {
      if (client !== next) return;
      active.clear();
      setStatus("offline");
    },
    onStompError: () => {
      if (client === next) setStatus("offline");
    },
  });
  client = next;
  next.activate();
}

export function setStreamToken(next: string | null) {
  if (next === token && client) return;
  token = next;
  connect();
}

/** Subscribes for the component's lifetime; the handler always sees the latest props/state. */
export function useStream(destination: string | null, handler: Listener) {
  const handlerRef = useRef(handler);
  handlerRef.current = handler;

  useEffect(() => {
    if (!destination) return;
    if (!client) connect();
    const listener: Listener = (event) => handlerRef.current(event);
    let set = listeners.get(destination);
    if (!set) {
      set = new Set();
      listeners.set(destination, set);
    }
    set.add(listener);
    attach(destination);
    return () => {
      set!.delete(listener);
      if (set!.size === 0) {
        active.get(destination)?.unsubscribe();
        active.delete(destination);
        listeners.delete(destination);
      }
    };
  }, [destination]);
}

export function useStreamStatus(): Status {
  return useSyncExternalStore(
    (onChange) => {
      statusListeners.add(onChange);
      return () => statusListeners.delete(onChange);
    },
    () => status,
  );
}
