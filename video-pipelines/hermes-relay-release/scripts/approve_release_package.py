#!/usr/bin/env python3
"""Promote an approved Hermes-Relay pre-release package to a release tag.

This records explicit human approval in state/approved/<tag>.json so the
release watcher/webhook can reuse the approved media instead of regenerating.
If --auto-post is set, the release watcher may post it automatically when the
matching GitHub Release event/tag is observed.
"""
import argparse
import datetime as dt
import json
import shutil
import sys
from pathlib import Path

PIPELINE = Path(__file__).resolve().parents[1]
APPROVED_DIR = PIPELINE / 'state' / 'approved'
DEFAULT_OUT_ROOT = PIPELINE / 'out' / 'approved'


def now_iso():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def load_manifest(workdir):
    manifest_path = workdir / 'manifest.json'
    if not manifest_path.exists():
        raise FileNotFoundError(manifest_path)
    return json.loads(manifest_path.read_text())


def video_for(workdir, tag):
    return workdir / 'out' / f'hermes-relay-{tag}.mp4'


def rewrite_package_metadata(workdir, tag, source_workdir=None):
    manifest = load_manifest(workdir)
    old_tag = manifest.get('tag')
    old_video = video_for(workdir, old_tag) if old_tag else None
    new_video = video_for(workdir, tag)

    if old_video and old_video.exists() and old_video != new_video:
        new_video.parent.mkdir(parents=True, exist_ok=True)
        old_video.rename(new_video)
    if not new_video.exists():
        raise FileNotFoundError(new_video)

    manifest['tag'] = tag
    manifest['workdir'] = str(workdir)
    manifest.setdefault('release', {})
    manifest['release']['tagName'] = tag
    manifest['approved_source_workdir'] = str(source_workdir or workdir)
    manifest['approval_status'] = 'approved-for-release'
    (workdir / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')

    post_cmd = f"{PIPELINE/'scripts/post_approved.py'} --workdir {workdir} --approved APPROVED_FOR_RELEASE"
    caption = manifest.get('caption', '')
    (workdir / 'POST_DRAFT.md').write_text(
        f"# Approved X post for {tag}\n\n"
        f"```text\n{caption}\n```\n\n"
        f"Attach:\n\n`MEDIA:{new_video}`\n\n"
        f"Approved package: this is the canonical media/post package for `{tag}`.\n\n"
        f"Manual post command:\n\n```bash\n{post_cmd}\n```\n"
    )
    return manifest, new_video


def artifact_slug(tag, approved_at=None):
    date = (approved_at or dt.datetime.now(dt.timezone.utc)).astimezone().date().isoformat()
    return f'{date}-release-video-{tag}'


def copy_or_reuse_package(source, target, clean=False):
    if source.resolve() == target.resolve():
        return source
    if target.exists():
        if not clean:
            raise FileExistsError(f'{target} already exists; pass --clean to replace it')
        shutil.rmtree(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(source, target)
    return target


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--workdir', required=True, help='Preview/generated package directory to approve')
    ap.add_argument('--release-tag', required=True, help='Final GitHub release tag this package should satisfy')
    ap.add_argument('--out-root', default=str(DEFAULT_OUT_ROOT), help='Root for canonical release artifacts')
    ap.add_argument('--artifact-slug', help='Directory name under --out-root; defaults to YYYY-MM-DD-release-video-<tag>')
    ap.add_argument('--clean', action='store_true', help='Replace existing canonical package for release tag/artifact slug')
    ap.add_argument('--approved', required=True, help='Must equal APPROVED_FOR_RELEASE')
    ap.add_argument('--auto-post', action='store_true', help='Allow watcher/webhook to post automatically when the matching release appears')
    ap.add_argument('--note', default='', help='Optional approval note')
    args = ap.parse_args()

    if args.approved != 'APPROVED_FOR_RELEASE':
        print('Refusing approval: --approved must equal APPROVED_FOR_RELEASE after human review.', file=sys.stderr)
        sys.exit(2)

    source = Path(args.workdir).expanduser().resolve()
    if not source.exists():
        raise FileNotFoundError(source)
    approved_at = dt.datetime.now(dt.timezone.utc)
    slug = args.artifact_slug or artifact_slug(args.release_tag, approved_at)
    target = Path(args.out_root).expanduser().resolve() / slug
    workdir = copy_or_reuse_package(source, target, clean=args.clean)
    manifest, video = rewrite_package_metadata(workdir, args.release_tag, source_workdir=source)

    qa = workdir / 'QA.md'
    post_draft = workdir / 'POST_DRAFT.md'
    for required in [video, qa, post_draft, workdir / 'manifest.json']:
        if not required.exists():
            raise FileNotFoundError(required)

    APPROVED_DIR.mkdir(parents=True, exist_ok=True)
    approval = {
        'tag': args.release_tag,
        'status': 'approved-for-release',
        'approvedBy': 'human',
        'approvedAt': approved_at.isoformat(),
        'artifactRoot': str(Path(args.out_root).expanduser().resolve()),
        'artifactSlug': slug,
        'autoPost': bool(args.auto_post),
        'workdir': str(workdir),
        'video': str(video),
        'postDraft': str(post_draft),
        'qa': str(qa),
        'caption': manifest.get('caption', ''),
        'sourceWorkdir': str(source),
        'note': args.note,
    }
    approval_path = APPROVED_DIR / f'{args.release_tag}.json'
    approval_path.write_text(json.dumps(approval, indent=2) + '\n')
    print(json.dumps({'approval': str(approval_path), **approval}, indent=2))


if __name__ == '__main__':
    main()
