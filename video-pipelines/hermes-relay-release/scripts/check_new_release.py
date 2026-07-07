#!/usr/bin/env python3
"""Watch GitHub releases and generate/reuse a human-gated draft package for new Hermes-Relay releases.

Silent when no new release exists. Intended for cron/no-agent watchdog mode, and also
safe for webhook-style invocation: if an approved package exists for the release tag,
that package is reused instead of regenerating. If the approval record has
"autoPost": true, the approved package is posted once when the matching release is
observed.
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path

PIPELINE = Path(__file__).resolve().parents[1]
STATE = PIPELINE / 'state/last_seen_release.txt'
APPROVED_DIR = PIPELINE / 'state/approved'
REPO = 'Codename-11/hermes-relay'
OUT_ROOT = str(PIPELINE / 'out' / 'packages')


def sh(cmd, cwd=None, check=True):
    p = subprocess.run(cmd, cwd=cwd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if check and p.returncode != 0:
        raise RuntimeError(p.stdout)
    return p.stdout.strip()


def latest_tag():
    return sh(['gh','release','list','--repo',REPO,'--limit','1','--json','tagName','--jq','.[0].tagName'])


def approved_path(tag):
    return APPROVED_DIR / f'{tag}.json'


def load_approved(tag):
    path = approved_path(tag)
    if not path.exists():
        return None
    approval = json.loads(path.read_text())
    if approval.get('tag') != tag:
        raise RuntimeError(f'Approval tag mismatch in {path}: {approval.get("tag")} != {tag}')
    return approval


def validate_approved(approval):
    required = ['workdir', 'video', 'postDraft', 'qa']
    missing_keys = [k for k in required if not approval.get(k)]
    if missing_keys:
        raise RuntimeError(f'Approved package is missing keys: {missing_keys}')
    missing_paths = [approval[k] for k in required if not Path(approval[k]).exists()]
    if missing_paths:
        raise RuntimeError('Approved package has missing files:\n' + '\n'.join(missing_paths))


def approved_message(tag, approval, posted=False, post_output=''):
    work = Path(approval['workdir'])
    video = approval['video']
    contact = work / 'out/frames/contact-sheet.png'
    command = f"{PIPELINE/'scripts/post_approved.py'} --workdir {work} --approved APPROVED_FOR_RELEASE"
    status = 'posted automatically' if posted else 'ready for approved posting'
    extra = f"\n\nPost output:\n{post_output}" if post_output else ''
    return f"""New Hermes-Relay release detected: {tag}

Approved pre-release package found; reusing it instead of regenerating.
Status: {status}

Video: MEDIA:{video}
Contact sheet: MEDIA:{contact}
QA: {approval['qa']}
Post draft: {approval['postDraft']}

Manual post command if still needed:
{command}{extra}
"""


def handle_approved_release(tag, approval, dry_run_auto_post=False):
    validate_approved(approval)
    already_posted = bool(approval.get('postedAt')) or approval.get('status') == 'posted'
    if approval.get('autoPost') and not already_posted:
        cmd = [
            str(PIPELINE / 'scripts/post_approved.py'),
            '--workdir', approval['workdir'],
            '--approved', 'APPROVED_FOR_RELEASE',
        ]
        if dry_run_auto_post:
            cmd.append('--dry-run')
        output = sh(cmd, cwd=PIPELINE)
        return approved_message(tag, approval, posted=not dry_run_auto_post, post_output=output)
    return approved_message(tag, approval, posted=already_posted)


def generate_message(tag):
    gen = PIPELINE / 'scripts/generate_release_video.py'
    sh([str(gen), '--tag', tag, '--clean', '--out-root', OUT_ROOT], cwd=PIPELINE)
    work = Path(OUT_ROOT) / tag
    video = work / 'out' / f'hermes-relay-{tag}.mp4'
    contact = work / 'out/frames/contact-sheet.png'
    return f"""New Hermes-Relay release detected: {tag}

Draft package generated for human review.

Video: MEDIA:{video}
Contact sheet: MEDIA:{contact}
QA: {work/'QA.md'}
Post draft: {work/'POST_DRAFT.md'}

Nothing has been posted. After preview approval, run:
{PIPELINE/'scripts/post_approved.py'} --workdir {work} --approved APPROVED_FOR_RELEASE
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--init', action='store_true', help='Set current latest release as seen and print it.')
    ap.add_argument('--tag', help='Override detected latest tag; useful for webhook/test invocation')
    ap.add_argument('--dry-run-auto-post', action='store_true', help='Exercise auto-post path with post_approved.py --dry-run')
    args = ap.parse_args()
    latest = args.tag or latest_tag()
    old = STATE.read_text().strip() if STATE.exists() else ''
    if args.init:
        STATE.write_text(latest + '\n')
        print(f'Initialized Hermes-Relay release watcher at {latest}')
        return
    if not args.tag and latest == old:
        return

    approval = load_approved(latest)
    if approval:
        message = handle_approved_release(latest, approval, dry_run_auto_post=args.dry_run_auto_post)
    else:
        message = generate_message(latest)
    if not args.tag:
        STATE.write_text(latest + '\n')
    print(message)


if __name__ == '__main__':
    try:
        main()
    except Exception as e:
        print(f'Hermes-Relay release watcher failed: {e}', file=sys.stderr)
        sys.exit(1)
