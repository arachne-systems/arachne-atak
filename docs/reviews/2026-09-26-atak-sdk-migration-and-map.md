# ATAK: feed integration, talker map and SDK migration

## BLUF

The two CoT feed changes are applied as exact cherry-picks. The debug talker-map
prototype uses an authenticated PTT member and that member's accepted CoT PLI.
It creates no position when PLI is absent or stale. The debug APK and all Kotlin
test sources build against the installed ATAK 5.8.0.5 SDK. Host checks pass.
No tablet is changed. Core remains at `b124c90`.

The generated SDK is a migration target, not the current ATAK runtime. The owner
must select the SDK line. Directed publication, native storage upgrade and the
ATAK classloader proof are release gates. Do not widen a direct chat audience to
make a migration compile.

## Source and ancestry audit

Worktree: `/home/user/development/worktrees/arachne-atak-night-f`.
Branch: `codex/night-atak-f`. Base: `3a444bb45e99c423db09d298ae5650a1f64562ae`
on `codex/atak-night-2026-09-26`. The base includes PTT bridge `0c7b052` and the
toolbar guard. The original `/home/user/arachne-atak` checkout was not edited.

The feed branch and integration base meet at `8380c3f`, before the separate
source imports. A full history merge would mix those imports and cause broad
add/add conflicts. The lead had already aborted that merge. This branch applies
only the reviewed feature patches:

| Source commit | Local commit | Content |
|---|---|---|
| `f8d51a32f0587181917156b74deef2ed1db25631` | `cca843b` | Parse selected `cot.event.v1` APRS/ADS-B observations; scope map IDs; add an Android scenario. Only `FeedMapAdapter.kt` and `NativeCotFramesCheck.kt`. |
| `90f20c559b8053c55207fca228532aa85f5d858b` | `0995231` | Enable the CoT feed selector and show-on-map action. Only `FeedMapAdapter.kt` and two conditions in `FabricPlugin.kt`. |
| `c3def1d1e2bff5e4e14cf0b6c620da29647e2643` | No new commit needed | Removes an unused `arachne-rig` lock entry. That entry was already absent in the integration base. |

The first patch's two files exactly matched its parent before the cherry-pick.
Both cherry-picks applied without conflicts. The bridge, toolbar contract,
manifest icon, native sources, Cargo files and Core gitlink were retained.

The later test change moves CoT feed field validation into `CotFeedProjection`.
The Android parser still enforces its XML bounds and the ATAK event-validity
check. This extraction permits host tests of the actual projection code; it
adds no feed schema or transport. The new Gradle wrapper gives this worktree its
own build lane. No dependency is added.

## Talker-map prototype

The prototype is enabled only in a debug build. It is one app adapter over the
existing bridge and CoT receiver. There is no new network message, permission,
Core concept or SDK dependency.

1. `PttWorkspaceBridge` supplies the authenticated workspace, member and
   publication sequence. Local app publications use the bridge's selected
   member after local queue admission. A name in media data is never an identity.
2. `WorkspaceNativeAdapter` supplies accepted native PLI after the existing
   author, topic, audience and native-UID checks. Its existing mapping rejects
   two members that claim the same native ATAK UID. The point comes from that
   CoT report. Feed observations cannot supply a member's PLI.
3. `PttTalkerState` joins those two inputs by workspace and member. It accepts the
   current `APTM` v1 channel envelope and `APTT` v1 media format. It validates the
   complete media frame. A Start or floor grant alone creates no talker marker.
4. A received batch activates the member for 1.5 seconds. Further new batches
   refresh it. A matching author/channel/transmission End removes it. Duplicate
   or older publication sequences do not refresh it. Different members and
   channels can be active together; each member has one transmit target.
5. A PLI must have valid coordinates and an unexpired source time. The prototype
   caps its age at 60 seconds and tolerates at most 10 seconds of sender clock
   lead. It also waits for the corresponding `PointMapItem` to exist in ATAK.
6. `PttTalkerMap` puts a local text marker at the reported point. It labels it
   `PTT · <roster name>`, with `Recent media · last reported position`. The
   `nevercot` marker is not sent or archived. It does not change the source map
   item's style. It removes its own items on End, expiry, member removal,
   workspace change and plugin stop.

### Limits

- A recent batch is not proof of current microphone capture or audible output.
  The old bridge can drain retained media. With no capture time in the current
  app envelope, a late restored batch can briefly show recent receive activity.
  Do not label that state as proved live speech.
- The prototype follows the selected PTT workspace. PLI sharing/receiving must
  be enabled for that workspace. A name-only contact has no map position.
- The 1.5-second activity lease and 60-second PLI cap are prototype UI policy.
  They are not transport or Core limits.
- Missing End cleans up by expiry. Reordered stale End cannot clear a different
  transmission. This is not a recording-completeness or audibility claim.
- CoT source validity does not prove physical location. The marker shows the
  member's last accepted position report, including its existing trust limits.
- No rendered ATAK map, plugin reload or end-to-end audio result is claimed by
  host compilation. The lead owns a later authorized device check.

## Plan: replace `fabric-android` with Core-owned generated SDK

### Target and ownership

The inspected candidate is SDK H5 `3c750fb`, with Core `e420a52`. H5 packages
Core-owned UniFFI 0.31.2 metadata into Kotlin namespaces `org.arachne.core.api`
and `org.arachne.core.runtime`. H5 proved generated host flows and AAR/R8
packaging. It did not prove ATAK's classloader or upgrade the deployed storage.
Newer Core fixes must be incorporated through the chosen SDK line and its tests.

ATAK currently uses `FabricNative` / `NativeAccess` / `FabricSession`, then the
Core JSON dispatcher through the small Rust JNI crate. `WorkspaceStore` still
coordinates saved candidate bytes. ATAK owns CoT, native contacts, GeoChat,
feeds, map UI and PTT activity. Core owns membership, authorization, durable
candidates, retained content, blob service, lifecycle and Iroh transport.

Use one owned Core `Context` for the plugin lifecycle and typed `Client` objects
for the existing workspace owners. Read native defaults through `defaultLimits`,
`defaultTransportOptions` and `defaultClientConfig`. Keep the current bounded
worker/waiter separation. Do not put native waits on the map/UI thread.

| Current ATAK seam | Generated Core operation or required change |
|---|---|
| `FabricNative.create`, `describe`, worker wait and cancel | `Context.owned`, `Client.openIn`, `endpoint`, `capabilities`, `waitForWork`, `wake`, `cancel` |
| Join and workspace JSON driver | `beginJoinWithPeers`, `driveJoin`, `requestAdmission`, `stageJoinGrant`, `adoptJoin`, `driveWorkspace` |
| Roster, policy, presence and interest | `memberRoster`, `installWorkspacePolicy`, `installMemberPolicy`, `pollPresence`, `setInterest`, `pollInterest` |
| `WorkspaceStore` stage/save/adopt bytes | Core opaque candidates and native durable adopt. Set `StorageConfig.openSqlite` in `ClientConfig` before open. Do not copy the save/readback rules into Kotlin. |
| Normal and current CoT publications | `stageProtectedPublication`, `stageProtectedPublicationWithCurrent`, `adoptProtectedPublication`; preserve selector, replacement key, expiry and tombstone |
| Directed and Bulk publication | Missing in inspected generated Client; see the exact gap below |
| Durable inbox and app-store acknowledgement | `pollProtected`, `adoptProtectedReception`, `pollPendingObject`, `stageObjectAcknowledgement`/`stageObjectRejection`, then adopt |
| Range/current/direct recovery | `fetchRecoveryRangeAt`, `pollRecoveryRange`, `stageRecoveryRange`, `adoptRecovery`; `fetchCurrentView`/poll/stage/adopt/cancel; `nextDirectGap`, `fetchDirectRecovery`/poll/stage/cancel, `stageDirectMiss`; cutoff discover/poll |
| Package bytes and read grants | Typed `resource(ResourceRequest): ResourceStatus`; preserve authenticated peer-bound grants and app-selected absolute paths |
| Plugin stop/reload | Native `shutdown()` then generated handle `close()` for each Client, with waiter completion and owned Context cleanup. These two operations have different meanings. |

The typed pending object carries authenticated author `epoch`, `fromLosingBranch`
and member-ID recipients. Preserve these fields and the original author when
recovery uses a holder. CoT and PTT adapters must not infer an author from a
payload field, relay, holder or callsign.

### Exact directed-publication gap

`WorkspaceData.publish` currently calls `stage_network_publication` with:

```text
revision, topic, id[16], payload,
recipients: [[32-byte member ID], ...],
bulk: topic == WorkspaceResources.RESPONSE,
current?: { selector[32], replacement_key[32], expires_at, tombstone }
```

The inspected typed methods in Core `client.rs` hardcode `recipients = []` and
`bulk = false`. Direct/group GeoChat and selected PLI therefore cannot be
ported faithfully through those methods. Resource replies also lose Bulk
classification. Typed direct recovery does not fill the outgoing API gap.

Core's existing `ops/publication.rs` already defines and checks the general
behavior: sorted unique recipients (at most 64), no self recipient, no directed
Current value and no Bulk Current value. It maps Current metadata to Current,
`bulk` to Bulk, and all other publications to Critical.

The lead assigned a bounded additive Core fix. The proposed Core-owned shape is
`PublicationOptions { recipients: Vec<MemberId>, mode: PublicationMode }`, with
`Critical`, `Bulk`, and `Current { metadata: PublicationCurrent }` modes. The
default is empty recipients and Critical. A new
`stage_protected_publication_with_options` must use the same validated staging
operation. Existing methods retain their behavior. SDK bindings are generated;
there must be no mirrored SDK record or ATAK-only Core method. This note records
a proposed seam, not a tested final API.

Required proof: three admitted members; one directed receiver gets the payload;
the authorized bystander gets nothing. Invalid, self, duplicate and unsorted
audiences and directed Current fail before adoption. Regenerate and check at
least Kotlin/Python metadata and the AAR after the Core change.

### Migration order and gates

1. The owner selects the SDK line. Carry the PTT line's MoQ feature forwarding
   into the selected package; the inspected H5 default build returns
   `Unsupported` for streaming. Use one selected Core pin and one native library.
2. Prove loading and plugin reload with the actual ATAK package classloader.
   `NativeAccess` currently loads JNI through the plugin package context to
   survive ATAK's replaceable plugin loader. A JNA/UniFFI object cannot be cast
   across duplicate classloaders. A small host spike must choose one owner of the
   generated classes, native library and callbacks before controller migration.
3. Prove a native, authenticated storage upgrade. Preserve endpoint identity,
   storage roots, workspace authority, anchors, counters, retained records and
   pending inbox. Test interruption at each commit point and reopen. No data
   clear, new package identity or replacement workspace can substitute for this.
4. Migrate one `FabricSession` owner to typed calls. Keep the existing app queues
   and callback lifecycle. Remove JSON/candidate byte handling as each operation
   moves. Remove legacy workspace/invitation paths; add no compatibility client.
5. Run real workspace join/restore/leave, direct/group/broadcast GeoChat, current
   PLI/map replacement and deletion, feed selection/expiry, resource resume and
   peer-bound grants. Then prove PTT bridge attachment, audio and map lifecycle.
6. Delete `fabric-android` and its packaged `.so` only when those paths use the
   selected SDK and the ATAK classloader/reload checks pass. The owner controls
   release, Core re-pin and publication.

## Build and check receipt

The first prototype build used source `cdc2e805380ea41470f10b1215710cd8d2a7f0c2`
in the worktree above, with clean status. Receipt: `/tmp/lane-f-build-source.json`.
The toolbar guard is `verifyToolbarIconContract`, required by `preBuild` in
`android/app/build.gradle.kts`. Its source SHA-256 is
`74a29d9225edcbb47531adfd09f587a47324aa960c9204593208aab09764c7b4`.
It checks the transparent manifest icon, toolbar factory and vector content.
It ran in the successful 44-second debug build.

Build command (under the shared lock, CPUs 8–11):

```sh
FABRIC_TAK_SDK=/home/user/.cache/sdk/ATAK-CIV-5.8.0.5-SDK \
GRADLE_LANE=codex-atak-f scripts/gradle.sh --no-daemon --max-workers=4 \
  -PatakVersion=5.8.0 :app:assembleCivDebug \
  :app:compileCivDebugAndroidTestKotlin :app:compileCivDebugUnitTestKotlin
```

The two JNI libraries were copied from the existing PTT bridge artifacts. Rust
JNI source, Cargo files and Core pin match the unchanged bridge source, but the
libraries' original build provenance was not reconstructed. This is a current
Kotlin/APK packaging proof, not a new native Rust build or native runtime proof.
No Cargo target was created. Library hashes:

| ABI | SHA-256 |
|---|---|
| arm64-v8a | `6345e5e03e39b67af3724e0c2b137aad6fb263dc6357328b1acec06ae5c1b9a5` |
| x86_64 | `f376ec9583d7376d5cbebd00ce074057c8297af8c96b594f34603fd28583b6a7` |

Final checks and artifact hashes are recorded below after the final build.
