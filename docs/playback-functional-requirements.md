# Playback functional requirements

This is a list of requirements that the internal playback is built around. 

## Authority and lifecycle

- `PlaybackService` must remain usable without an activity for Media3 notification, Android Auto,
  media-button, widget, and Bluetooth commands.
- One service-owned `Player` and `MediaLibrarySession` are authoritative for the queue, current
  item, playback state, repeat mode, shuffle mode, backend, and persisted position.
- Connecting an application controller must not construct a second player or restore a second
  copy of the queue.
- Swiping the task away or explicitly exiting must persist the current session, release playback,
  remove the notification, and stop the service. A pause/stop broadcast received while playback is
  not running must not start the service merely to stop it.
- Restored queues are prepared but do not autoplay unless the initiating Media3 or legacy command
  requested playback.

## Queue and shuffle

- Media3's canonical media-item index and the visible shuffled play-order index are distinct. UI
  queue rows retain the canonical index needed by Media3 commands.
- Enabling shuffle keeps already-played canonical items and the current item stable and randomizes
  only the remaining items.
- Replacing a queue while shuffle is enabled starts at the first item in the new visible play order.
  An explicit start index instead remains current and treats earlier canonical items as history.
- The correct start position of a replaced queue must not depend on the shuffle follow-up session
  command arriving: the queue replacement itself (Media3 `resetPosition`) already randomizes the
  play order and starts at its first item while shuffle is enabled.
- Adding "next" during shuffle puts all inserted items immediately after the current item in their
  supplied order; Media3's randomly assigned insertion positions must not leak into the UI order.
- Reordering a shuffled queue changes play order without accidentally treating a play-order row as
  a canonical Media3 index.
- Queue, current item/index/position, repeat mode, shuffle flag, and exact shuffle order survive
  service recreation. Position writes are coalesced and flushed on pause/stop/shutdown.
- Backend replacement preserves the queue, current item, position, play intent, repeat mode, and
  exact shuffle order whenever the retained queue still permits it.

## Local, offline, and server transitions

- The local backend uses cached data first and resolves uncached stream URLs with the active
  server's authentication and self-signed-certificate setting.
- Entering offline mode rebuilds retained queue items with the offline catalog's exact local path.
  Only complete catalog entries whose files still exist may remain.
- A transition to automatic offline mode recreates the local backend even when it was already
  local, so queued online stream locations cannot remain active.
- Automatic offline/online transitions for the same server preserve whether playback was intended
  to continue. A user-requested switch to or from Jukebox remains paused until explicitly played.
- A real Media3 network connection failure may trigger automatic offline mode. Unrelated playback
  errors must not be classified as loss of server connectivity.
- Changing server invalidates remote Jukebox state and incomplete tracks that the new server cannot
  address.

## Jukebox

- Jukebox is exposed as a remote-playback Media3 device and serializes server commands through one
  task queue.
- Its queue operations follow Media3 index semantics, including forward moves and removal of the
  current/last item. Server index `0` is a valid current item.
- Public positions and seek increments are milliseconds; the Subsonic request boundary converts
  them to seconds. Seek-back clamps at zero and previous restarts the current item after the
  configured threshold.
- Jukebox reports only commands it can currently perform. It does not claim local shuffle or repeat
  support, and next is unavailable on the last item.
- Server-too-old, offline, and unauthorized Jukebox failures surface as playback errors and cause
  the application to fall back to local playback without conflating all errors with connectivity.
- Releasing a Jukebox player terminates its polling worker, scheduler, and listeners. Multiple
  instances must not share a global running flag.

## Audio routes and external controls

- Local playback pauses for audio-becoming-noisy/headset removal. Optional wired-headset resume is
  service-owned, applies only to local playback, and ignores the initial sticky headset broadcast.
- Bluetooth resume/pause honors the separate `all devices`, `A2DP only`, and `disabled` settings.
  The resulting commands go through the same Media3 session as UI, notification, and Auto.
- Play, pause, toggle, stop, previous, next, and legacy media-key commands wait for controller
  connection and restoration. Legacy star/rating keys remain rating operations, not playback state.
- Notification and Android Auto custom shuffle/repeat/rating controls update the same player and
  state observed by the application.

## Playback side effects and UI

- Scrobbling, bookmark cleanup, ReplayGain, next-track preloading, widget updates, equalizer audio
  session handling, and rating submission retain their existing transition semantics while their
  ownership is moved to the appropriate subsystem.
- Global playback state publishes event-scale changes only. The player screen polls position on a
  lifecycle-bound ticker while visible; position is not emitted globally every few hundred
  milliseconds.
- Fragments render immutable ViewModel state and send commands through `PlaybackRepository`.
  Mini-player dismissal remains navigation UI state and is not interpreted as a playback command.
