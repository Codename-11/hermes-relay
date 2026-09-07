"""Small hosted-room protocol peer, not an execution driver.

Wire authority: tui_gateway/methods_groups.py, gateway/hosted_rooms.py,
and tui_gateway/hosted_room_service.py. State survives sockets, not processes.
"""
from __future__ import annotations

import hashlib
from copy import deepcopy
from typing import Any


class HostedRoomRpcError(ValueError):
    def __init__(self, code: int, message: str) -> None:
        super().__init__(message)
        self.code = code


class HostedRoomsFixture:
    def __init__(self, config: dict[str, Any]) -> None:
        self.config = deepcopy(config)
        self.methods = {"groups.capabilities", "groups.list", "groups.state", "groups.send", "groups.log"}
        self.events: list[dict[str, Any]] = []
        self.lost_responses = set(config.get("lose_response_event_ids", []))

    def call(self, method: str, params: dict[str, Any]) -> dict[str, Any]:
        room = self.config["room"]
        if method == "groups.capabilities":
            return deepcopy(self.config["capabilities"])
        if method == "groups.list":
            limit, offset = params.get("limit", 500), params.get("offset", 0)
            if type(limit) is not int or not 1 <= limit <= 500 or type(offset) is not int or offset < 0:
                raise HostedRoomRpcError(5110, "invalid room list limit or offset")
            rooms = [deepcopy(room)][offset:offset + limit]
            return {"rooms": rooms, "next_offset": offset + limit if len(rooms) == limit else None}
        if method == "groups.send" and not self.config["capabilities"]["driver"]:
            raise HostedRoomRpcError(4123, "Group Chat worker is unavailable. Restart the Hermes gateway and try again.")
        if params.get("room_id") != room["room_id"]:
            code = {"groups.state": 4114, "groups.send": 4111, "groups.log": 4112}[method]
            raise HostedRoomRpcError(code, "hosted room not found")
        if method == "groups.state":
            result = {"room": deepcopy(room)}
            if self.config["capabilities"]["driver"]:
                result["driver_status"] = deepcopy(self.config["driver_status"])
            return result
        if method == "groups.send":
            return self._send(params)
        since = params.get("since_seq", 0)
        limit = params.get("limit", 100)
        if type(since) is not int or not 0 <= since <= room["latest_seq"]:
            raise HostedRoomRpcError(4112, "invalid since_seq for hosted room log")
        if type(limit) is not int or not 1 <= limit <= self.config["capabilities"]["max_log_limit"]:
            raise HostedRoomRpcError(4112, "invalid hosted room log limit")
        events = [deepcopy(event) for event in self.events if event["seq"] > since][:limit]
        cursor = events[-1]["seq"] if events else since
        return {"events": events, "cursor": cursor, "latest_seq": room["latest_seq"],
                "has_more": cursor < room["latest_seq"],
                "authority": {"gateway_id": room["authority_gateway_id"], "epoch": room["authority_epoch"]}}

    def _send(self, params: dict[str, Any]) -> dict[str, Any]:
        client_id = params.get("event_id")
        if not isinstance(client_id, str) or not client_id.strip():
            raise HostedRoomRpcError(4111, "event_id must be a non-empty string")
        payload = params.get("payload")
        if (not isinstance(payload, dict) or not {"text"} <= payload.keys()
                or payload.keys() - {"text", "thread_id"}
                or not isinstance(payload["text"], str) or not payload["text"].strip()
                or ("thread_id" in payload and (not isinstance(payload["thread_id"], str)
                                               or not payload["thread_id"].strip()))):
            raise HostedRoomRpcError(5112, "invalid text-only Discussion payload")
        event_id = "user:" + hashlib.sha256(client_id.encode("utf-8")).hexdigest()
        payload = deepcopy(params["payload"])
        payload.setdefault("thread_id", event_id)
        payload["text"] = payload["text"].strip()
        event = next((row for row in self.events if row["event_id"] == event_id), None)
        if event is None:
            room = self.config["room"]
            room["latest_seq"] += 1
            event = {"room_id": room["room_id"], "seq": room["latest_seq"], "event_id": event_id,
                     "kind": "message.user", "actor": {"kind": "user", "id": "desktop"},
                     "authority_epoch": room["authority_epoch"], "payload": payload,
                     "created_at": 1000.0 + room["latest_seq"], "idempotent": False}
            self.events.append(event)
        else:
            if event["payload"] != payload:
                raise HostedRoomRpcError(4111, "event_id already exists with different immutable content")
            event = {**event, "idempotent": True}
        return {"event": deepcopy(event), "client_event_id": client_id, "accepted": True,
                "driver_started": True}
