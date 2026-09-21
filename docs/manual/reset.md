# Reset Arachne

Use the in-app reset first:

1. Open **Arachne → Settings → Reset Arachne**.
2. Type `RESET` and confirm.
3. Restart ATAK.

This removes Arachne workspaces, pending joins, invitations, native chat and
receipt caches, resource caches, relay fixtures, local listener settings, and
the Arachne endpoint identities. ATAK maps, contacts, preferences, and other
ATAK-owned data are not removed.

## Last-resort ADB cleanup

This is for a debug/development device when the plugin UI cannot open. It
removes the same file and preference state while preserving ATAK's own data:

```sh
adb shell am force-stop com.atakmap.app.civ
adb shell run-as com.atakmap.app.civ sh -c 'rm -rf no_backup/arachne-relay-only no_backup/arachne-wan-only files/fabric-fixture.json files/fabric-tls-probe.json'
adb shell run-as com.atakmap.app.civ sh -c 'find no_backup/data-fabric -mindepth 1 -maxdepth 1 -exec rm -rf {} +'
adb shell run-as com.atakmap.app.civ sh -c 'find shared_prefs -maxdepth 1 -type f \( -name "arachne-*.xml" -o -name "fabric-*.xml" \) -delete'
```

ADB cannot delete Android Keystore aliases owned by ATAK without clearing all
ATAK application data. Therefore this procedure is not an identity reset by
itself: after ATAK starts, use the in-app reset to rotate endpoint identities.
Do not run `pm clear com.atakmap.app.civ` unless deleting all ATAK data is
explicitly intended.
