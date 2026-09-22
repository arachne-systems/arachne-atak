# Android binding

This crate packages the portable Rust node as a JNI library for the Kotlin ATAK
plugin. It exposes lifecycle (create, describe, close) and bounded local requests for
address hints, caller-verified endpoint permissions, subscriptions, publications
and polling received events. The local policy seam is not secure onboarding.

`FabricSession` in the Kotlin plugin owns a serial worker and one native handle.
Every plugin start gets a separate session, so queued cleanup from a previous
start cannot close a new handle. Native calls never run on the ATAK UI thread;
status is posted back with a lifecycle generation check.

The Rust registry uses increasing opaque integers, not raw pointers. Invalid and
closed handles fail with a JVM exception. At most eight sessions are allowed.
Each session owns its node, bounded event receiver and two-worker Tokio runtime.
Close removes the handle before waiting for shutdown. JNI local references never
escape a call; panics are contained at the JNI boundary.

The registry lock serializes startup and handle lookup. Data calls hold only
their own session lock; network waits do not hold the global registry. Close
removes the handle and takes ownership after any admitted operation finishes,
so a racing lookup observes either an admitted prior operation or a closed node.
`create(secretBytes)` requires exactly32 bytes. The Kotlin session supplies its
Keystore-protected endpoint credential and holds its storage lock until native
shutdown. The JNI temporary copy is zeroized on drop; Kotlin clears its temporary
byte array after creation (this does not promise erasure of every JVM copy).
Member/device bindings and persistent group state remain unfinished. See
[endpoint storage](../../docs/security-and-groups.md#endpoint-credential-storage).
Starting another session before the prior owner finishes cleanup fails instead
of reusing the credential concurrently; automatic startup retry is not implemented.
`FabricSession` requires an explicit local identity slot. Real workspace sessions
must use separate slots and endpoint credentials; the current plugin has only one
development fixture and does not yet orchestrate private multi-workspace sessions.

## Local data request contract

`execute(handle, requestBytes)` receives bounded UTF-8 JSON and returns JSON bytes.
Only byte arrays (including arrays of byte arrays), strings and primitive handles cross the classloader/JNI seam.
Calls are blocking and belong on a worker. Kotlin `FabricSession.request` copies
and admits at most64 queued/executing requests, each at most128KiB. It returns
false immediately when closed, oversized or full; admitted requests deliver a
Result to a worker-thread callback. Do not block callbacks or touch Android views
there. Startup verifies the same asynchronous request path with an empty poll.

`receive` installs a consumer on the same bounded worker. With a consumer, a
single periodic task drains at most32 events per100ms interval; without one,
events remain in Rust's bounded queue. Slow native operations/callbacks delay
polling. Consumer failures are logged and the removed live event is not retried.
The debug ATAK adapter registers a consumer and publishes explicitly scoped CoT
only after private development-fixture activation. Native PLI and public chat
have separate emulator evidence; this is not secure workspace onboarding.

Close rejects new work and queues cleanup after already admitted requests. The
periodic poll is canceled, while admitted requests remain scheduled. No immediate
cancellation or short total close latency is promised: up to64 sequential network
requests can delay cleanup. Queue tuning and cancellation are future measured
requirements, not reasons to discard accepted requests silently.

| `op` | Fields | Result |
| --- | --- | --- |
| `add_address_hint` | `peer` (32 bytes), `address` (socket address) | `null` on accepted hint |
| `install_verified_policy` | `workspace` (32 bytes), `revision`, `endpoints` | `null` on installed projection |
| `set_interest` | `workspace`, `revision`, `topic`, `subscribed` (boolean) | Immediate local interest, `interest_queued`; remote acceptance remains pending |
| `poll_interest` | No additional fields | `interest_pending`, completed `interest_observed` with `admission`, `interest_failed`, or `null` when idle |
| `subscribe` / `unsubscribe` | `workspace`, `revision`, `topic` | Blocking admission report; rejected while queued interest repair is active |
| `publish` | `workspace`, `revision`, `topic`, `payload` (byte array) | Admission report |
| `poll` | No additional fields | One received event, or `null` if the queue is empty |

Each endpoint policy has `peer`, `publish` topic names and `subscribe` topic
names. Duplicate endpoints and unknown fields are rejected. These requests are
not exposed as a network management protocol: the caller must authenticate policy
and member/device bindings before invoking the trusted installation operation.
No automatically generated or hardcoded workspace policy is installed at startup.

Android's workspace owner uses `set_interest` and regularly calls `poll_interest`.
Local interest changes before the call returns; network fanout does not hold
the session lock or Android workspace worker. There is one active announcement
and at most 64 queued topic updates per session. Repeated choices for a queued
workspace/topic replace its value in place. Queued work is rechecked against
current policy before sending, and session close cancels it. This queue bound
does not limit workspace membership. A queued result is not remote acceptance.

Do not mix blocking subscription calls with active queued repair. `poll_interest`
drives subsequent announcements; a caller that stops polling stops that repair.
Remote convergence can wait behind slow peers. Other publication and control
operations retain their documented blocking/admission contracts; this change
does not establish large-workspace throughput or complete lifecycle acceptance.

A received event has workspace, revision, authenticated direct sender, topic and
payload. Poll removes an event from the in-memory queue; it is not a durable
application-delivery acknowledgement. JNI/JVM failure after removal can lose that
local event, consistent with the current live-only queue contract.

An admission report contains `admitted` endpoint keys and `failed` peer/error
entries. An operation can partially complete or have an ambiguous outcome after
timeout. There is no retry/deduplication guarantee in this binding yet.

JSON requests/metadata are capped at128KiB before copying from the JVM; the binary storage entrypoint has a separate snapshot cap. Parsing rejects unknown
fields and uses serde's bounded nesting behavior. Node payload/policy/queue bounds
still apply. Network requests have a10-second overall deadline; timeout can follow
partial admission. Close may wait for the session's current admitted request,
then allows5 seconds for node close and2 seconds for runtime shutdown. Startup
has a10-second node-bind deadline.

The Rust dispatcher check exchanges opaque binary and structured-event payloads
between two real local nodes, then tests denied publishing, unsubscribe, closed
handles and invalid input. It exercises the dispatcher used by JNI; actual JNI
byte-array invocation is separately checked inside ATAK. Neither test represents
completed secure workspace admission or native CoT exchange.

## Build and check

```sh
cargo +1.98.0 test --locked -p fabric-android
scripts/build-peer-android.sh -p fabric-android
scripts/build-plugin.sh :app:assembleDebugAndroidTest
python3 scripts/check-session-queue.py --output .cache/logs/session-queue.json
python3 scripts/check-plugin.py --native
python3 scripts/check-endpoint-restart.py --output .cache/logs/endpoint-restart.json
```

The instrumentation check installs both APKs and verifies their hashes, then
runs against the actual native library in a separate Android instrumentation
process on each emulator. It exercises request capacity, defensive byte copying,
oversize rejection, closed-session rejection and draining accepted work on close.
It also exercises Keystore identity recovery and rejects concurrent ownership,
damaged records and missing halves of the record/key pair in a separate random
test namespace. It does not exercise ATAK rendering or network publication. Reload ATAK after
installing a new plugin APK before checking its host lifecycle.

The plugin build packages the native x86_64 library from the ignored build cache.
It loads the absolute installed library path returned by Android's package
manager. JNI conventions follow the [jni 0.21.1 documentation](https://docs.rs/jni/0.21.1/jni/).

## Native library ownership

The JNI object is loaded through Android's package context classloader, obtained
with `createPackageContext` for this installed plugin package. ATAK's plugin
classloader can then be replaced without transferring ownership of the native
library. A small reflective boundary exchanges only bootstrap JVM values
(strings and primitive handles), avoiding casts between duplicate plugin classes.
The absolute installed library path is still used; no copied library image,
custom global registry or hidden Android API is required.

The [Android Context API](https://developer.android.com/reference/android/content/Context#createPackageContext(java.lang.String,%20int))
documents the package-context/code-loading mechanism. Actual reload behavior is
verified separately: two unload/reload cycles in each ATAK5.8.0.4 emulator process,
with completed close, increasing native handles, distinct fresh endpoint keys
and unchanged ATAK process IDs. The first implementation's classloader failure
is retained in evidence/jni-reload-failure-2026-09-08.json; the corrected run is in
evidence/jni-plugin-reload-2026-09-08.json. Those historical checks predate saved
credentials. The current reload checker requires each device's key to remain
unchanged while its handle advances; process-restart checks also compare the
saved ciphertext digest and require different identities on the two devices.

Open Tools > Plugins on both emulators, then run:

```sh
python3 scripts/check-plugin-reload.py --output .cache/logs/jni-reload.json
```

This is bounded lifecycle evidence, not a claim about unlimited reloads, plugin
APK upgrades, every Android/ATAK version, or workspace connectivity. Secure group
recovery and complete application-adapter integration remain unfinished.

## Workspace creation integration

`create_workspace` requires `display_name` and creates one Rust-owned MLS
workspace inside the existing session. It returns public workspace ID, epoch,
member count, `member: {id, display_name}` and `durable: false`. A second creation request is rejected without replacement.
Closing the session drops its in-memory owner. No secrets cross JNI.

This operation connects the portable security owner to the real Kotlin request
path; it does not install routing policy, protect existing fixture publications,
persist state by itself. `workspace_ready` remains false. Do not
present a created volatile group as a saved or connected workspace. Both Android
instrumentation processes exercise creation and replacement rejection with the
packaged native library. The local ATAK create/open form saves encrypted state;
peer onboarding remains unfinished.

## Snapshot save and restore integration

`seal_workspace` returns public workspace ID plus bounded ciphertext bytes;
`restore_workspace` accepts that expected ID and ciphertext into an empty session.
Restore returns the saved member ID/name, or `member: null` for legacy v1
workspaces. Neither operation replaces an already owned group. The derived storage key stays inside
Rust, using the existing Keystore-protected endpoint seed as its root with
HKDF domain separation. The same endpoint credential must be reopened for restore.

`WorkspaceStore` writes only sealed bytes through AtomicFile under noBackupFilesDir,
checks workspace header scope and readable commit, and caps reads before passing
bytes back to Rust for authentication. The host still owns persistence and current
workspace selection. WorkspaceController now saves initial state through the local create/open UI; peer joining is not exposed. Responses
keep durable:false because a live group alone cannot prove future changes have
been committed. The store currently supports one local participant per workspace.

Android instrumentation saves the actual native snapshot, drains/closes the
session and its credential lock, opens a new session, reads the file and restores
the same ID/epoch/member count. It removes its own test snapshot on success.
This is real file/session recovery, not process-kill MLS recovery.

## Pending join persistence seam

`issue_invitation` exports a secret bearer token and public checkpoint from the
selected local administrator workspace. It must be called only for authorized
sharing and its output must not be logged. `begin_join` takes `invitation`,
`checkpoint` and `display_name` and validates them in Rust. Raw workspace/digest
arguments are no longer accepted. It returns `state: pending`, `durable: false`, member metadata, the workspace-facing endpoint, public KeyPackage and exact authorized `admission_request`. `seal_pending_join` emits ciphertext; `restore_pending_join`
accepts an expected workspace ID and ciphertext into an empty session and returns
the exact saved identity/KeyPackage. Pending and joined owners cannot replace one
another through these requests. Owning pending state grants no routing rights.

WorkspaceStore selects a separate `pending-joins` directory when explicitly
constructed with `pendingJoin = true` and validates the snapshot phase before
writing. Pending ciphertext cannot overwrite a completed workspace through its
default store. Both stores remain ciphertext-only AtomicFile stores. Android
instrumentation exercises persistence across native sessions and phase/owner
rejection; the plugin create/open UI does not yet expose pending joins. Issuance/validation and pending request recovery are wired through JNI;
the network admission path and mandatory pending-to-joined save transition remain
unwired. No caller should present these lower-level requests as successful
onboarding.


## Admission save/adopt boundary

These are trusted local host operations, not network requests. In particular,
`authenticated_endpoint` must be supplied from the established transport identity;
copying it from a remote request would defeat endpoint authentication. Android
instrumentation supplies controlled endpoint fixtures and is not wire-admission
proof.

| `op` | Fields | Result |
| --- | --- | --- |
| `stage_admission` | `authenticated_endpoint`, exact `request` bytes | `workspace`, sealed candidate `snapshot`, `state: awaiting_save`; no Welcome/commit |
| `adopt_admission` | Exact candidate `snapshot` read back from storage | Adopted group epoch/count; no reply |
| `retained_admission` | `authenticated_endpoint`, exact `request` bytes | Retained commit/Welcome/authorization, or rejection |

Only one candidate can exist in a session. Once staged, every execute operation
except adoption rejects, preventing old-state saves, further transitions or reply
exposure while a write is pending. A mismatched adoption leaves the candidate
intact. Close remains available. A stage serialization/storage-bound error leaves
the original owner unchanged. Retry after completion retrieves the stored reply
rather than preparing another Add.

`WorkspaceStore.commitAdmission` runs on the coordinator worker under exclusive
session ownership: validate completed phase, AtomicFile save/readback, then native
adoption with those exact bytes. Native code cannot attest filesystem durability,
so its metadata continues to say `durable: false`. On any error the coordinator
must close the session and restore disk state before resuming. If no write committed,
that is the old owner; if the write committed but adoption/response was lost, the
restored owner contains the exact reply. No discard/rollback command is provided.

The helper is exercised through real Android storage/JNI. The plugin UI has not
connected it to authenticated Iroh admission delivery; history retrieval and
membership policy projection remain unfinished.


## Joiner completion

`stage_join` accepts `welcome` bytes and a bounded `commits` array (1..64 steps),
each with `commit` bytes and `authorization` containing invitation_key32,
grant_signature64 and redemption_signature64. It starts from the pending owner's
saved checkpoint, verifies every Add, validates Welcome and matches the authorized
branch. Only then does it seal a version4 candidate with verified history. Errors
leave the pending owner usable. Input shares the128KiB JNI request bound.

The reply is `state: awaiting_join_save`, workspace and ciphertext; it does not
activate membership. `adopt_join` requires the exact staged snapshot and rejects
an admission candidate. `adopt_admission` likewise rejects a join candidate.
Successful adoption replaces the pending owner with the joined owner in memory.

`WorkspaceStore.commitJoin` saves/readbacks the completed snapshot, adopts it, then
removes the separate pending file. A crash can leave both records; future onboarding
startup must verify and prefer the completed state, never fall back to pending
state after failed completed-state authentication. The durable user catalog/slot
handoff and that startup orchestration are still not exposed in the plugin UI.
On any coordinator error close and recover from disk before further operations.

Instrumentation uses a dedicated test-owned storage root to represent the second
device's same-workspace file, keeping the issuer record separate. Both owners use
real JNI and Android protected identities; control messages are copied by the
fixture, so this is not Iroh wire-admission or cross-emulator delivery evidence.

## Admission over authenticated Iroh control

`request_admission(peer)` sends the session's exact persisted pending request over
Node's control channel. The peer is a pinned transport key and needs a current
address hint. The response remains untrusted until `stage_join` validates its
history and Welcome; connecting to a peer is not proof of workspace authority.

`poll_admission` takes an actual ControlRequest and validates its transport peer
against the signed request's endpoint. It returns a staged ciphertext candidate,
`state: reply_ready` for an exact retained retry, or null when none is queued.
It does not take a caller-supplied requester identity. Candidate staging and reply
lookup reuse the same security-owner functions as the trusted local operations.

After the Kotlin coordinator saves/readbacks and adopts the candidate,
`send_admission_reply` sends the retained reply on that request's connection.
It rejects while a candidate awaits adoption. The session admits only one incoming
admission at a time and blocks unrelated operations until it replies or closes.
It reports queued status separately from remote receipt. If the request expires,
the retained snapshot still supports a subsequent exact retry.

`issue_invitation` now returns peer key and bound socket address as diagnostic
connection hints alongside the token/checkpoint. A wildcard bind address is not
a routable invite address; the harness supplies loopback explicitly and real
cross-device onboarding must use current reachable discovery/address information.
These hints do not grant membership or replace the cryptographic checkpoint.

The initial wire response contains one retained Add/proof and Welcome. Collecting
all preceding steps for a stale checkpoint and coordinating retries/backoff,
private discovery, catalog transitions and user-facing joining remain unfinished.
The Android harness now uses Iroh requests/replies instead of copying those bytes
locally, but endpoints are still co-located within each emulator's test process.

## Protected application save/adopt boundary

| Operation | Input | Result before/after adoption |
| --- | --- | --- |
| `stage_publication` | Opaque canonical `context` bytes and `payload` bytes | Encrypted candidate `snapshot`, `workspace`, `state: awaiting_publication_save`; no ciphertext |
| `adopt_publication` | Exact candidate `snapshot` read back from storage | Adopted metadata and `ciphertext` |
| `stage_reception` | Expected canonical `context` and incoming `ciphertext` | Encrypted candidate `snapshot`, `workspace`, `state: awaiting_reception_save`; no plaintext or author |
| `adopt_reception` | Exact candidate `snapshot` read back from storage | Adopted metadata, `payload`, authenticated author `member` and `endpoint` |

The four application operations share one staged-workspace slot with admission
and joining. Wrong-phase or mismatched-snapshot adoption rejects without losing
the candidate. All other execute operations reject while a candidate is held.
Close remains available. An adopted candidate is consumed and cannot be released
a second time. Legacy unprotected `publish` rejects when the session owns an
admitted workspace; the separate development fixture remains a transport test.

A bounded snapshot copy provides an isolated candidate security owner. Failed
authentication/context checks discard that candidate, preserving the active
receive ratchet. This intentionally favors correctness over throughput until the
real message rate is measured. It does not make filesystem writes transactional.

Kotlin `WorkspaceStore.commitPublication` and `commitReception` perform the
AtomicFile save/readback before native adoption. Run them on the coordinator's
worker, not the UI or the FabricSession callback worker. Native still reports
`durable:false`: it matches the supplied ciphertext token and cannot attest a
host filesystem write. A malicious host in ATAK's UID is outside this boundary.

This is cryptographic release, not network/application delivery. Publication
returns ciphertext to the coordinator; it does not call Node.publish. Reception
accepts opaque ciphertext supplied by the coordinator; it does not poll Node or
invoke ATAK. Routing metadata must be canonically bound and permissions checked
by the runtime assembly. No private-DM audience, outbox, crash-atomic application
handoff or automatic retransmission is introduced. If a crash loses the release
response after its snapshot was saved, the ratchet stays advanced but the payload
may be lost. Re-encrypting from an older snapshot is forbidden.

Host dispatcher tests cover withholding, wrong phase/token, one-time release,
legacy-publication rejection, bad-context recovery and replay after receiver
restart. Real Android instrumentation exercises the same JNI operations and
AtomicFile helpers on both lab emulators, each with two local security owners.
Application ciphertext crosses that test's JNI calls in memory; those checks do
not establish protected data exchange between the emulators or inside ATAK.

## Membership-derived routing

`install_member_policy { revision, topics }` installs an explicit all-member
publish/read default for the session's admitted workspace. Endpoint keys come
only from its verified MLS roster. Topic names are validated, an empty topic set
rejects, and duplicate endpoint bindings reject. Existing Node bounds and strictly
increasing routing revisions apply. The operation returns workspace, revision
and member count; it does not subscribe anyone or persist a policy document.

`install_verified_policy` is restricted to sessions without an admitted workspace
and remains a development-fixture seam. An admitted session cannot replace its
roster with a caller-supplied endpoint map. A pending join cannot use
`install_member_policy` to acquire membership.

This default is suitable only for topics readable by every member. It does not
provide confidential DMs or fine-grained role policy. Callers currently coordinate
the explicit routing revision and reinstall the projection after membership
changes/restart. The revision is not implicitly an MLS epoch. Distributed policy
synchronization and automatic subscription restoration remain unfinished.

## Protected network operations

`stage_network_publication { revision, topic, id, payload }` derives the workspace
from the session owner, authenticates canonical PublicationContext metadata, and
stages a bounded DFAP packet. `id` is a fresh 16-byte publication ID supplied by
the coordinator. The stage returns only the encrypted snapshot, using the existing
`awaiting_publication_save` state. `WorkspaceStore.commitPublication` saves and
reads back that snapshot, then `adopt_publication` adopts the advanced owner before
calling Iroh publish. For this stage, adoption returns `admission` or
`network_error`, never raw ciphertext. Network failure does not roll back adoption.
Permissions are enforced by Node at publication; staging is not a permission grant.

`poll_protected {}` polls the real Node queue, reconstructs authenticated metadata
from the received routing frame, checks the direct transport peer against the
cryptographic author, and stages an isolated receiver candidate. Invalid packets
are consumed and rejected without replacing the active owner. Empty polls return
JSON null without copying security state. Bounded local echoes are consumed;
the originating application already owns its outgoing event. This direct-peer
rule must be revisited explicitly when forwarding is implemented.

The caller saves the staged snapshot and invokes `adopt_reception` through
`WorkspaceStore.commitReception` before consuming payload, member, endpoint,
revision, topic and publication ID. Raw `poll` rejects for admitted workspaces.
`endpoint_info {}` exposes the local endpoint key and current bound address for
address-hint exchange; it does not grant permission or provide peer discovery.

The shared Kotlin session's automatic `receive` callback still uses raw fixture
polling. The admitted-workspace coordinator must explicitly drive protected poll
and commit on its own worker; wiring that to ATAK remains separate work. Do not
install the fixture consumer on an admitted session. No outbox, automatic resend,
network receipt beyond admission, application-ID duplicate store, or recovery from
large MLS gaps is implemented. Save/adopt response loss can lose a publication or
application delivery while correctly leaving cryptographic state advanced.

## Binary snapshot transfer

`executeStored(handle, metadata, snapshot)` accepts JSON metadata (at most 128 KiB)
and a separate byte array (at most `MAX_SEALED_BUNDLE`, 671882 bytes). It returns a
two-element byte-array array: JSON metadata and an optional binary snapshot.
Snapshot-bearing operations must use the binary argument; supplying a JSON
`snapshot` as well is rejected. Operations that do not consume snapshots reject
nonempty binary input. JNI checks both input lengths before copying JVM arrays.
Both entrypoints share the same typed dispatcher and save/adopt checks.

The normal Kotlin workspace controller uses this path for create/open, admission
and publication/reception. The existing JSON entrypoint remains for compatibility
with other callers. The session queue copies admitted input and keeps its existing
64-request and single-worker bound. The native dispatcher still creates bounded
JSON values internally; binary transfer removes wire-size amplification without
claiming zero-copy storage. Some legacy Kotlin storage/test calls still construct
snapshot JSON arrays in memory before the controller extracts their bytes.

Routed publications now stage a bounded publisher index with their advanced
security state in one DFWB record. Save/readback and exact adoption install both;
network publication starts after that adoption. Receives preserve existing
retention, as do raw security test operations, whose packets are not indexed.
DFWB restore validates the index against the security owner. DFWS remains readable
and starts retention on its first subsequent routed publication. New membership
epochs currently discard old-epoch retention; no old-history availability is
advertised. Membership catalogs and credentials are not regenerated by migration.

Android WorkspaceStore accepts DFWB only for the completed workspace family and
only for publication/reception adoption phases. Pending and admission/join phase
checks remain distinct. The storage bound matches the binary JNI cap. Runtime
retrieval, receiver cursors, explicit recovery coverage negotiation and arbitrary
crash-boundary testing remain open. Retention is not an automatic resend outbox.

## Recovery serving in the shared control dispatcher

`poll_admission` remains the compatible operation name for one shared control
queue. Payloads with the `DFRQ` family prefix are handled as recovery before
admission parsing. The dispatcher uses the adopted workspace/index and the
transport-authenticated requester. `Node::with_routing_policy` supplies the exact
current routing table under its existing lock, with no copied authorization
registry. The bounded synchronous callback signs/encodes at most one response.

A pending workspace candidate prevents control polling until adoption or recovery
from saved state. Malformed/unsupported recovery requests and inactive retention
receive generic denial without history disclosure. Valid requests pass membership,
scope and per-topic authorization; history errors remain advisory. The response
metadata `state: recovery_replied` means the reply was queued, not remotely received
or applied. Kotlin recognizes it and leaves the admission workflow and UI alone.

Real host Iroh checks now invoke this production dispatcher and installed member
policy; they verify retained ciphertext and rejection of stale revisions, revoked
topics and unsupported versions. Existing admission tests share the same poller.


## Receiver cutoff discovery

`discover_recovery_cutoff` takes `peer` (workspace-facing endpoint32), `revision`
and an explicit `topics` list. It validates current membership and local topic
permissions, generates a fresh nonce and starts one bounded transport task. It
returns `recovery_cutoff_pending` immediately, allowing the same Kotlin worker to
continue serving inbound requests. A second start is rejected until completion is
consumed. No caller-supplied nonce or author is accepted.

`poll_recovery_cutoff` returns null when there is no completed task. Otherwise it
consumes the pending operation exactly once, including error outcomes. Before
exposing a result it checks current workspace/epoch, membership and routing policy
again, then verifies the signature against the original locally held query.
Session close aborts the task. The request retains the existing 32 KiB request,
128 KiB reply and five-second transport limits; only one query can be outstanding.
Pending publication/admission adoption guards still apply to the dispatcher.

Success returns `recovery_cutoff_observed`, workspace, author, peer, epoch,
revision, selected topics, head, `retained_after` and the receiver's durable
`accepted_through` for that exact author/topic selection, with
`accepted_progress: false`. It commits no receive progress and returns no snapshot
or nonce. Remote refusal returns
`recovery_cutoff_denied` without a head. Transport, signature and stale local
state failures are errors and clear the pending slot. No automatic retry occurs.

The Kotlin reconnect action starts discovery after updating the peer route. The
existing WorkspaceData tick polls completion alongside the controller's single
inbound control poller. For the subscribed legacy `chat/messages/v1` topic, it
requests the contiguous range after `accepted_through`; it refuses to skip an
expired prefix or accept a publisher rollback. Native ATAK XML chat remains
live-only. Discovery failure does not close the live sharing session. A
new membership projection can reject an older pending query on completion;
controller/session shutdown cancels it.

The host integration test starts both peers before pumping either and verifies
both signed heads. It also checks duplicate-start rejection, one-time completion,
local rejection before policy installation, server revocation, local policy changes
while a request is pending and cancellation on close. The former mutual timeout
counterexample is now a successful crossed-request regression.


## Nonblocking range retrieval

`fetch_recovery_range` accepts a policy revision, exact topic list and
`(after, through]` range. An explicit `peer` requests history from that endpoint;
supplying `author` without a peer selects the reachable original author plus the
bounded authenticated Gossip neighbors and accepts the first exact
publisher-signed range. The runtime resolves identities through current
membership and checks local topic permissions before sending DFRQ. The operation returns
`recovery_range_pending` immediately. Cutoff and range requests share a single
admission limit: an in-flight cutoff, in-flight range or retained ready range
prevents another recovery start. Outgoing data and inbound control polling
continue. Routed reception leaves queued packets untouched while any recovery
slot is occupied; raw reception rejects. This prevents live ratchet advancement
during the operation, but does not close gaps between separately scheduled calls.

`poll_recovery_range` returns null until completion, then rechecks current
workspace/epoch and topic policy. It verifies the signed packet set and retains the
exact reply plus locally owned query for a later staging transaction. The result
`recovery_range_ready` exposes only scope, requested bounds, packet count and
retained byte count, with `accepted_progress: false`. It returns neither plaintext
nor a snapshot. Individual decrypted-message origin checks remain part of staging;
a verified publisher statement alone does not authenticate each MLS message author.

Remote advisory failures return `recovery_range_rejected` with their reason and no
ready slot. A signed empty success produces a ready range with zero packets;
unsigned Empty remains rejected. Subsequent polls do not repeat ready results.
`cancel_recovery_range` aborts an in-flight task or clears retained ready bytes,
idempotently. Close aborts tasks and releases the slot. The request/reply limits
remain 32/128 KiB and the transport deadline remains five seconds.

The ready slot is ephemeral, encrypted data only. It is not recovery adoption,
application delivery or durable progress. `stage_recovery_range` rechecks authorization before creating a candidate. An automatically selected range must
continue the durable accepted cursor and advances that cursor only in the saved
candidate. Explicit history retrieval remains independent. With modern object delivery, a nonzero
`retain_until` also stores the exact publisher proof in that same candidate for
bounded third-holder repair. The caller
must establish its desired range from accepted progress/cutoff state; arbitrary
retrieval does not let it skip the delivery library's contiguous-progress checks.
Kotlin schedules subscribed `chat/messages/v1` recovery from a verified cutoff
and commits ready candidates through `WorkspaceStore`.

Host Iroh tests use normal receiver/server dispatch, compare the retained ciphertext
and publication ID, and exercise duplicate start, local and remote denial, signed
empty success, pending policy change, cancellation and one-time ready notification.
A three-session regression additionally closes the original author, restarts the
holder, repairs a third member through that holder and verifies the original
member remains the authenticated author.


## Recovery adoption and publisher order

`stage_recovery_range` takes no external query or reply: it consumes the locally
owned ready range through delivery staging. `awaiting_recovery_save` exposes a
binary sealed snapshot, publication count and already-received count, without
plaintext. Save/read back the complete snapshot and call `adopt_recovery` with
those exact bytes. Wrong lifecycle or changed bytes reject without adoption.
`poll_recovered_publication` then releases one item at a time; callers must drain
this bounded volatile queue before other operations. A covered range returns
`recovery_already_covered` without another candidate.

The restored owner, publisher log, receive evidence and selection progress share
one sealed record. Ordinary native publish/receive/save paths preserve them;
new membership epochs reset current-epoch delivery state. Kotlin WorkspaceStore
provides `commitRecovery`, and reconnect schedules a contiguous range from the
durable cursor. A waiting cutoff/range is retried by later live polling but is not
itself persisted across process restart.
The post-adoption queue is not a durable application outbox; persisted security
progress does not guarantee a callback after a crash. Journal reclamation is also
unfinished, with a fixed 256-record limit.

Native routed publications assign `PublisherLog.head + 1` before encryption and
bind it in v2 publication AAD. Live DFAP2 and recovery DFRP2 retain that sequence;
adopted routed and recovered metadata expose it as `sequence` (null for legacy
contexts). Legacy DFAP1 and DFRL1 history remain readable. Mixed retained history
uses DFRL2 without inventing authentication for old records. Old peers/binaries
cannot read the new formats; coordinate upgrades and do not downgrade saved state.
The field is not an MLS generation or a complete live ordering implementation.
