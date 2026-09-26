#!/usr/bin/env python3
"""Drive Arachne on real ATAK devices without touching the screen.

Talks to the debug-build control receiver (android/app/src/debug/.../DebugControl.kt)
with `adb shell am broadcast` and prints its JSON reply. Release APKs do not
contain the receiver.

Devices: `-s SERIAL` (repeatable), `--callsign NAME` (repeatable), or `--all`
for every non-emulator adb device. Several devices run in parallel; output is
one JSON object keyed by serial.

Raw ops (one broadcast, reply as-is):
    arachne-ctl.py --all ping
    arachne-ctl.py -s 10.10.20.50:42837 state
    arachne-ctl.py -s ... runtime request='{"op":"endpoint_info"}'
    arachne-ctl.py -s ... trace since=0 limit=500

Workflows (op plus polling until the result is visible, with a deadline):
    arachne-ctl.py -s OWNER create-workspace NAME MEMBER
    arachne-ctl.py -s OWNER open-invitation --out .cache/link.txt
    arachne-ctl.py -s JOINER join @.cache/link.txt MEMBER
    arachne-ctl.py --all endpoint
    arachne-ctl.py -s DEVICE reset         # all Arachne state; restarts only when required
    arachne-ctl.py --all fresh-rig --owner HEWN --name Bench --out .cache/link.txt
                                           # reset all, owner creates, all others join
"""
import argparse
import json
import re
import shlex
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor

ACTION = 'dev.arachne.atak.debug.CONTROL'
ATAK = 'com.atakmap.app.civ'
ACTIVITY = 'com.atakmap.app.civ/com.atakmap.app.ATAKActivity'
REPLY = re.compile(r'Broadcast completed: result=(-?\d+)(?:, data="(.*)")?\s*$', re.S)


class ControlError(RuntimeError):
    pass


def adb(serial, *args, timeout=60):
    return subprocess.run(['adb', '-s', serial, *args], capture_output=True, text=True, timeout=timeout)


def call(serial, op, timeout=90, **extras):
    """One control broadcast. Returns the reply dict; raises ControlError."""
    command = ['am', 'broadcast', '-a', ACTION, '-p', ATAK, '--es', 'op', op]
    for key, value in extras.items():
        if value is not None:
            command += ['--es', key, str(value)]
    # `adb shell` joins arguments into one remote shell line: quote each one.
    run = adb(serial, 'shell', ' '.join(shlex.quote(part) for part in command), timeout=timeout)
    match = REPLY.search(run.stdout.strip())
    if not match:
        raise ControlError(f'{serial}: no broadcast reply: {run.stdout.strip()[-300:]} {run.stderr.strip()[-300:]}')
    if match.group(2) is None:
        raise ControlError(f'{serial}: control receiver not reachable (result={match.group(1)}). '
                           'Is a debug Arachne build loaded in a running ATAK?')
    reply = json.loads(match.group(2))
    if not reply.get('ok'):
        raise ControlError(f'{serial}: {op} failed: {reply.get("error")}')
    return reply


def wait_for(serial, what, predicate, deadline_s, interval=0.25, **extras):
    """Poll `state` until predicate(state) returns a truthy value."""
    started = time.monotonic()
    last = None
    while time.monotonic() - started < deadline_s:
        try:
            last = call(serial, 'state', endpoint='false', **extras)
        except (ControlError, subprocess.TimeoutExpired) as error:
            last = {'error': str(error)}
            time.sleep(interval)
            continue
        found = predicate(last)
        if found:
            return found, time.monotonic() - started
        time.sleep(interval)
    raise ControlError(f'{serial}: timed out after {deadline_s}s waiting for {what}; last state: {json.dumps(last)[:600]}')


def owner_for(state, slot=None, name=None):
    for owner in state.get('owners', []):
        active = owner.get('active') or {}
        if (slot and active.get('slot') == slot) or (name and active.get('name') == name):
            return owner
    return None


def devices(args):
    listed = [line.split('\t')[0] for line in subprocess.run(['adb', 'devices'], capture_output=True, text=True).stdout.splitlines()[1:]
              if line.endswith('\tdevice')]
    # Never drive emulators: other agents own them.
    physical = [serial for serial in listed if not serial.startswith('emulator-')]
    chosen = list(args.serial or [])
    if args.all:
        chosen += physical
    if args.callsign:
        with ThreadPoolExecutor(max_workers=max(1, len(physical))) as pool:
            names = dict(zip(physical, pool.map(lambda s: _callsign(s), physical)))
        for wanted in args.callsign:
            matches = [serial for serial, name in names.items() if name and name.lower() == wanted.lower()]
            if not matches:
                sys.exit(f'no device with callsign {wanted!r}; seen: {names}')
            chosen += matches
    chosen = list(dict.fromkeys(chosen))
    if not chosen:
        sys.exit('pick devices with -s SERIAL, --callsign NAME or --all')
    return chosen


def _callsign(serial):
    try:
        return call(serial, 'ping', timeout=20).get('callsign')
    except (ControlError, subprocess.TimeoutExpired):
        return None


def extras_from(pairs):
    extras = {}
    for pair in pairs:
        key, sep, value = pair.partition('=')
        if not sep:
            sys.exit(f'expected key=value, got {pair!r}')
        extras[key] = value
    return extras


def read_link(value):
    if value.startswith('@'):
        with open(value[1:]) as handle:
            return handle.read().strip()
    return value


def create_workspace(serial, name, member, deadline):
    if not call(serial, 'create', name=name, member=member)['accepted']:
        raise ControlError(f'{serial}: create refused (another workspace operation is running?)')
    owner, took = wait_for(serial, f'workspace {name!r}',
                           lambda state: (lambda o: o if o and (o['active'].get('member') and not o['busy']) else None)(owner_for(state, name=name)),
                           deadline)
    return {'slot': owner['active']['slot'], 'workspace': owner['active']['workspace'], 'seconds': round(took, 2)}


def open_invitation(serial, slot, out, deadline):
    if not call(serial, 'invite', slot=slot)['accepted']:
        raise ControlError(f'{serial}: invite refused (another workspace operation is running?)')
    started = time.monotonic()
    while time.monotonic() - started < deadline:
        link = call(serial, 'invitation', slot=slot).get('link')
        if link:
            if out:
                with open(out, 'w') as handle:
                    handle.write(link + '\n')
            return {'link': link if not out else f'written to {out}', 'seconds': round(time.monotonic() - started, 2)}
        time.sleep(0.25)
    raise ControlError(f'{serial}: no invitation link after {deadline}s')


def join(serial, link, member, deadline):
    before = {o['active']['slot'] for o in call(serial, 'state', endpoint='false')['owners'] if o.get('active')}
    if not call(serial, 'join', link=link, member=member)['accepted']:
        raise ControlError(f'{serial}: join refused (another workspace operation is running?)')

    def joined(state):
        for owner in state['owners']:
            active = owner.get('active') or {}
            if active.get('member') and not active.get('joining') and not active.get('pre_join') and \
                    (active['slot'] not in before or active.get('member_name') == member):
                return owner
        return None
    owner, took = wait_for(serial, 'join to finish', joined, deadline)
    return {'slot': owner['active']['slot'], 'workspace': owner['active']['workspace'], 'seconds': round(took, 2)}


def endpoint(serial):
    ip = adb(serial, 'shell', 'ip -4 -o addr show wlan0').stdout.split()
    address = ip[3].split('/')[0] if len(ip) > 3 else None
    rows = []
    for owner in call(serial, 'state')['owners']:
        bound = ((owner.get('endpoint') or {}).get('bound_address') or '')
        port = bound.rsplit(':', 1)[-1] if bound else None
        rows.append({'slot': (owner.get('active') or {}).get('slot'), 'name': (owner.get('active') or {}).get('name'),
                     'bound_address': bound, 'reachable_at': f'{address}:{port}' if address and port else None,
                     'endpoint_key': (owner.get('endpoint') or {}).get('endpoint_key')})
    return {'owners': rows}


def _prepare_module():
    import importlib.util
    import os
    spec = importlib.util.spec_from_file_location(
        'prepare_atak_device', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'prepare-atak-device.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def restart_atak(serial, deadline=90):
    """Stop and start ATAK, then answer ATAK's own "exited uncleanly, load
    plugins?" prompt that a force-stop causes. Only the exact "Load Plugins"
    button is ever tapped (the prepare script's allowlist matcher)."""
    prepare = _prepare_module()
    adb(serial, 'shell', 'am', 'force-stop', ATAK)
    adb(serial, 'shell', 'am', 'start', '-n', ACTIVITY)
    started = time.monotonic()
    while time.monotonic() - started < deadline:
        try:
            call(serial, 'ping', timeout=10)
            return True
        except (ControlError, subprocess.TimeoutExpired):
            pass
        position = prepare.find_button(prepare.ui_dump(serial) or '', 'Load Plugins')
        if position and position != 'disabled':
            adb(serial, 'shell', 'input', 'tap', str(position[0]), str(position[1]))
        time.sleep(2)
    return False


def reset(serial, deadline):
    reply = call(serial, 'reset', timeout=120)
    restart_required = reply.get('restart_required', True)
    if restart_required:
        restart_atak(serial)
    started = time.monotonic()
    while time.monotonic() - started < deadline:
        try:
            reply = call(serial, 'state', endpoint='false', timeout=20)
            if not any(o.get('active') for o in reply.get('owners', [])):
                return {'restarted': restart_required,
                        'seconds': round(time.monotonic() - started, 2)}
        except (ControlError, subprocess.TimeoutExpired):
            pass
        time.sleep(1)
    raise ControlError(f'{serial}: ATAK did not come back with an empty Arachne after {deadline}s')


def fresh_rig(serials, owner_callsign, name, out, deadline):
    """Clean start for a test run: reset Arachne everywhere, one fresh
    workspace on the owner, every other device joined through one open link."""
    started = time.monotonic()

    def reachable(serial):
        # A fresh install unloads the plugin inside a running ATAK: restart it.
        try:
            call(serial, 'ping', timeout=10)
        except (ControlError, subprocess.TimeoutExpired):
            if not restart_atak(serial, deadline):
                raise ControlError(f'{serial}: ATAK did not come up with the debug control')
    with ThreadPoolExecutor(max_workers=len(serials)) as pool:
        list(pool.map(reachable, serials))
    with ThreadPoolExecutor(max_workers=len(serials)) as pool:
        list(pool.map(lambda serial: reset(serial, deadline), serials))
    with ThreadPoolExecutor(max_workers=len(serials)) as pool:
        callsigns = dict(zip(serials, pool.map(_callsign, serials)))
    owners = [serial for serial, callsign in callsigns.items() if (callsign or '').lower() == owner_callsign.lower()]
    if len(owners) != 1:
        raise ControlError(f'owner {owner_callsign!r} not found once among {callsigns}')
    owner = owners[0]
    created = create_workspace(owner, name, callsigns[owner], deadline)
    open_invitation(owner, created['slot'], out, deadline)
    link = read_link('@' + out)
    joiners = [serial for serial in serials if serial != owner]
    with ThreadPoolExecutor(max_workers=max(1, len(joiners))) as pool:
        joined = dict(zip(joiners, pool.map(lambda serial: join(serial, link, callsigns[serial] or serial, deadline), joiners)))
    expected = len(serials)

    def counted(state):
        owners = [o for o in state.get('owners', []) if (o.get('active') or {}).get('name') == name]
        return owners[0] if owners and owners[0].get('members') == expected else None
    for serial in serials:
        wait_for(serial, f'{expected} members', counted, deadline)
    return {'owner': callsigns[owner], 'workspace': created['workspace'], 'members': expected,
            'joined': {callsigns[s] or s: j['seconds'] for s, j in joined.items()}, 'link_file': out,
            'seconds': round(time.monotonic() - started, 1)}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('-s', '--serial', action='append')
    parser.add_argument('--callsign', action='append')
    parser.add_argument('--all', action='store_true')
    parser.add_argument('--slot')
    parser.add_argument('--out', help='open-invitation / fresh-rig: write the link to this file')
    parser.add_argument('--owner', help='fresh-rig: callsign of the device that creates the workspace')
    parser.add_argument('--name', default='Bench', help='fresh-rig: workspace name')
    parser.add_argument('--deadline', type=float, default=120)
    parser.add_argument('op')
    parser.add_argument('rest', nargs='*')
    args = parser.parse_args()
    serials = devices(args)
    if args.op == 'fresh-rig':
        try:
            print(json.dumps(fresh_rig(serials, args.owner, args.name, args.out or '.cache/bench-link.txt', args.deadline), indent=2))
            return 0
        except (ControlError, subprocess.TimeoutExpired) as error:
            print(json.dumps({'ok': False, 'error': str(error)}, indent=2))
            return 1

    def one(serial):
        try:
            if args.op == 'create-workspace':
                name, member = args.rest
                return create_workspace(serial, name, member, args.deadline)
            if args.op == 'open-invitation':
                return open_invitation(serial, args.slot, args.out, args.deadline)
            if args.op == 'join':
                link, member = args.rest
                return join(serial, read_link(link), member, args.deadline)
            if args.op == 'endpoint':
                return endpoint(serial)
            if args.op == 'reset':
                return reset(serial, args.deadline)
            extras = extras_from(args.rest)
            if args.slot:
                extras.setdefault('slot', args.slot)
            return call(serial, args.op, **extras)
        except (ControlError, subprocess.TimeoutExpired, ValueError) as error:
            return {'ok': False, 'error': str(error)}

    with ThreadPoolExecutor(max_workers=len(serials)) as pool:
        results = dict(zip(serials, pool.map(one, serials)))
    print(json.dumps(results if len(results) > 1 else next(iter(results.values())), indent=2))
    return 1 if any(isinstance(r, dict) and r.get('ok') is False for r in results.values()) else 0


if __name__ == '__main__':
    sys.exit(main())
