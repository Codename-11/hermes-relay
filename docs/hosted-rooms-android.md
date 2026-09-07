# Android hosted rooms

Bot Mode discovers hosted rooms using the owning Gateway's `groups.capabilities`
and paginated `groups.list`. Legacy `ui_meta` room snapshots remain read-only.
This feature requires the upstream hosted-room protocol dependency. It does not
introduce a Relay-only scheduler or create another native runtime on Android.

## Conversation and recovery

Open a shared room to load canonical `groups.state` and `groups.log` history.
Messages retain the authoritative actor ID, profile/connection origin when supplied,
and thread ID. The composer offers active-member handles and a fresh-thread view;
selecting an existing thread sends its exact ID. Retired identities remain available
for historical attribution and are excluded from mention choices.

Drafts, file receipts, pending send IDs, and local read watermarks use the existing
app DataStore, keyed by connection, profile, room, and thread. A pending send keeps
its immutable payload and event ID across retries. A missing response is reconciled
against the canonical namespaced event ID. Acceptance does not create an assistant
reply. Discarding a local pending draft does not undo a potentially accepted send.

The foreground screen incrementally refreshes canonical state. Passive history
fetches do not hold the draft/action lock. Owner and generation checks reject stale
room completions and callbacks. These room operations never invoke native session
create, resume, activate, interrupt, or prompt submission methods.

## Controls and files

- Stop is labeled **Stop whole room** because the backend cancels room-wide work.
- Retry targets the exact pending task; an uncertain Retry retains its command ID.
  A later explicit Retry after a successful receipt uses a new command ID.
- Approval displays the actual command, description, request and task identity,
  and sends the exact member/task/request/execution generation with `once` or `deny`.
- Canonical failure, deferred, cancelled and settled events appear as room activity,
  separately from member messages. Unavailable peer routes remain visibly identified.
- The document picker reads original bytes, up to 15 MB per file, eight files and
  25 MB per message. Upload receipts are reduced to the exact five-field send
  descriptor. Picking the same file after an uncertain upload reuses its upload ID.
- Shared files use the viewer-only metadata catalog, opaque continuation cursors,
  and room/event/attachment-bound reads. Downloads use the system document picker.
  Native image, document, audio and video understanding depends on the recipient
  runtime; byte acceptance is not proof of understanding.
- Room creation uses stable room/member IDs on one selected gateway. Rename and
  membership edits use the revision displayed when settings opened, and refuse
  drift. Permanent room closure is explicitly distinguished from undoable archive.
- Export writes the source-qualified canonical room record and raw events through
  the verified cursor to a user-selected JSON document. It does not migrate history.

## Capability and evidence limits

Each optional operation is gated by advertised methods/features. The current room
registry has no thread-local Stop, general room user-input response, shared read
receipt, message edit/delete/reaction, or full native-tool activity inspection RPC.
Search in the conversation filters loaded history; file-name search uses the host
catalog. Local read state is not another client's read receipt. Background/closed-app
push delivery and real native-runtime media delivery require separate acceptance.

Focused checks use the repository Android lane:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/android-lane.ps1 gradle :app:testSideloadDebugUnitTest --tests '*HostedRoom*Test*' --tests '*BotModeControllerTest*'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/android-lane.ps1 gradle :app:standardPhoneApi36SideloadDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.hermesandroid.relay.viewmodel.HostedRoomInstrumentedTest'
```

`HostedRoomPythonFixtureTest` runs the declarative repository fixture in a disposable
loopback subprocess. `HostedRoomInstrumentedTest` exercises the production route,
controller and WebSocket client through an Activity foreground return. Roborazzi
screenshots cover writable, unsupported, uncertain-send, approval and unavailable-member
states. Creation and stale-settings forms are exercised and captured on the managed
emulator. Emulator evidence does not certify a physical phone.
