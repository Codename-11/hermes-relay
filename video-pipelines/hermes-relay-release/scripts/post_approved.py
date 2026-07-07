#!/usr/bin/env python3
"""Human-gated X posting for generated Hermes-Relay release video packages."""
import argparse
import datetime as dt
import json
import os
import shlex
import subprocess
import sys
from pathlib import Path

PIPELINE = Path(__file__).resolve().parents[1]
APPROVED_DIR = PIPELINE / 'state' / 'approved'


def now_iso():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def sh(cmd, check=True):
    p = subprocess.run(cmd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if check and p.returncode != 0:
        raise RuntimeError(p.stdout)
    return p.stdout


def mark_posted(tag, output, dry_run=False):
    approval_path = APPROVED_DIR / f'{tag}.json'
    if not approval_path.exists():
        return None
    approval = json.loads(approval_path.read_text())
    if dry_run:
        approval['lastDryRunAt'] = now_iso()
    else:
        approval['postedAt'] = now_iso()
        approval['status'] = 'posted'
        approval['postOutput'] = output
    approval_path.write_text(json.dumps(approval, indent=2) + '\n')
    return approval_path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--workdir', required=True)
    ap.add_argument('--approved', required=True, help='Must equal APPROVED_FOR_RELEASE')
    ap.add_argument('--dry-run', action='store_true')
    args = ap.parse_args()
    if args.approved != 'APPROVED_FOR_RELEASE':
        print('Refusing to post: --approved must equal APPROVED_FOR_RELEASE after human preview approval.', file=sys.stderr)
        sys.exit(2)
    work = Path(args.workdir).expanduser().resolve()
    manifest = json.loads((work/'manifest.json').read_text())
    tag = manifest['tag']
    video = work/'out'/f'hermes-relay-{tag}.mp4'
    qa = work/'QA.md'
    if not video.exists():
        raise FileNotFoundError(video)
    if not qa.exists():
        raise FileNotFoundError(qa)
    caption = manifest['caption']
    print('Caption:\n' + caption)
    print(f'Video: {video}')
    if args.dry_run:
        mark_posted(tag, 'Dry run: not posting.', dry_run=True)
        print('Dry run: not posting.')
        return
    command_template = os.environ.get('X_POST_COMMAND')
    if not command_template:
        raise RuntimeError('Set X_POST_COMMAND or pass --dry-run; refusing to guess how to post.')
    if '{video}' in command_template or '{caption}' in command_template:
        command = command_template.format(video=shlex.quote(str(video)), caption=shlex.quote(caption))
    else:
        command = f"{command_template} {shlex.quote(str(video))} {shlex.quote(caption)}"
    output = sh(['bash', '-lc', command])
    mark_posted(tag, output, dry_run=False)
    print(output)


if __name__ == '__main__':
    main()
