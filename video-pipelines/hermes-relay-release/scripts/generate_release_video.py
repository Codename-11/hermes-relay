#!/usr/bin/env python3
"""Generate a Hermes-Relay release video draft package with human-gated posting.

Draft outputs default to video-pipelines/hermes-relay-release/out/packages/<tag>/:
- manifest.json
- index.html / assets copied for Hyperframes
- out/hermes-relay-<tag>.mp4
- out/frames/contact-sheet.png
- QA.md
- POST_DRAFT.md
"""
import argparse, base64, json, os, re, shutil, subprocess, sys
from pathlib import Path

PIPELINE = Path(__file__).resolve().parents[1]
DEFAULTS = json.loads((PIPELINE / 'manifest.defaults.json').read_text())
DEFAULT_OUT_ROOT = PIPELINE / 'out' / 'packages'

def sh(cmd, cwd=None, check=True, capture=True):
    p = subprocess.run(cmd, cwd=cwd, text=True, stdout=subprocess.PIPE if capture else None, stderr=subprocess.STDOUT if capture else None)
    if check and p.returncode != 0:
        raise RuntimeError(f"Command failed ({p.returncode}): {' '.join(cmd)}\n{p.stdout or ''}")
    return p.stdout or ''

def latest_tag(repo):
    out = sh(['gh','release','list','--repo',repo,'--limit','1','--json','tagName','--jq','.[0].tagName'])
    return out.strip()

def release_json(repo, tag):
    out = sh(['gh','release','view',tag,'--repo',repo,'--json','tagName,name,publishedAt,url,body'])
    return json.loads(out)

def probe_duration(path):
    out = sh(['ffprobe','-v','error','-show_entries','format=duration','-of','default=nw=1:nk=1',str(path)])
    return float(out.strip())


def resolve_pipeline_path(value):
    """Resolve config paths relative to the pipeline directory."""
    path = Path(value).expanduser()
    if not path.is_absolute():
        path = PIPELINE / path
    return path.resolve()

def version_from_tag(tag):
    m = re.search(r'v(\d+(?:\.\d+){1,3})', tag)
    return m.group(1) if m else tag

def render_template(text, values):
    for k, v in values.items():
        text = text.replace('{{' + k + '}}', str(v))
    leftovers = re.findall(r'{{[A-Z0-9_]+}}', text)
    if leftovers:
        raise RuntimeError(f'Unrendered template tokens: {sorted(set(leftovers))}')
    literal_stubs = ['viewer opens', 'in-app preview']
    found_stubs = [stub for stub in literal_stubs if stub in text.lower()]
    if found_stubs:
        raise RuntimeError(f'Literal template stub(s) still present: {found_stubs}')
    return text


def trim_copy(text, max_chars, ellipsis='…'):
    """Trim copy at a word boundary instead of slicing mid-word."""
    text = re.sub(r'\s+', ' ', str(text or '')).strip()
    if len(text) <= max_chars:
        return text
    budget = max(1, max_chars - len(ellipsis))
    cut = text[:budget + 1].rstrip()
    if len(cut) > budget:
        cut = cut[:budget].rstrip()
    if ' ' in cut:
        cut = cut.rsplit(' ', 1)[0]
    if cut.count('(') > cut.count(')') and cut.rfind('(') >= max(0, len(cut) - 48):
        cut = cut[:cut.rfind('(')].rstrip()
    cut = cut.rstrip(' ,;:-—–/(')
    return (cut or text[:budget].rstrip()) + ellipsis


def sentence_case_copy(text, max_chars):
    trimmed = trim_copy(text, max_chars)
    if trimmed.endswith('…') or trimmed.endswith(('.', '!', '?')):
        return trimmed
    return trimmed.rstrip() + '.'


def release_summary(body):
    """Extract a compact public-safe release summary from GitHub release notes."""
    m = re.search(r'^\*\*Since [^*]+:\*\*\s*(.+)$', body, flags=re.MULTILINE)
    if m:
        return m.group(1).strip()
    for line in body.splitlines():
        line = line.strip()
        if line and not line.startswith('#') and not line.startswith('**Release Date:**'):
            return re.sub(r'\*+', '', line)
    return 'A new Hermes-Relay Android release.'


def markdown_headings(body):
    return [h.strip() for h in re.findall(r'^###\s+(.+)$', body, flags=re.MULTILINE)]


def derive_copy(cfg, rel, version_number):
    """Derive default draft copy from release notes; config can still override these."""
    body = rel.get('body') or ''
    summary = release_summary(body)
    parts = [p.strip(' .') for p in re.split(r'\s+—\s+|\s+-\s+', summary, maxsplit=1)]
    lead = parts[0] if parts else summary
    detail = parts[1] if len(parts) > 1 else summary
    headings = markdown_headings(body)
    derived = {}
    if version_number != '1.0.0':
        derived['release_kicker'] = 'ANDROID RELEASE'
        derived['thesis_headline'] = sentence_case_copy(lead, 72)
        derived['thesis_sub'] = trim_copy(detail, 112)
        derived['scene_1_label'] = '01 // Main change'
        derived['scene_2_label'] = '02 // Chat polish'
        derived['scene_3_label'] = '03 // Fixes'
        derived['route_sub'] = trim_copy(detail, 88)
        derived.update({
            'preview_label': 'media preview', 'viewer_label': 'Files open inline',
            'route_1_name': 'Settings', 'route_1_target': 'Status surfaces', 'route_1_status': 'QUIETER',
            'route_2_name': 'Plugin', 'route_2_target': 'State badge', 'route_2_status': 'AWARE',
            'route_3_name': 'Chat', 'route_3_target': 'Settings UX', 'route_3_status': 'POLISHED',
            'feature_1_label': 'Preview', 'feature_1_text': 'toggles reflect',
            'feature_2_label': 'Controls', 'feature_2_text': 'easier reach',
            'feature_3_label': 'Pairing', 'feature_3_text': 'force-close fixed',
            'feature_4_label': 'Release', 'feature_4_text': 'pipeline upgraded',
            'node_1': 'Settings', 'node_2': 'Chat', 'node_3': 'Pairing',
            'pill_1': 'Calmer', 'pill_2': 'Polished', 'pill_3': 'Fixed',
        })
        if headings:
            derived['route_headline'] = sentence_case_copy(headings[0].rstrip('.'), 54)
        if len(headings) > 1:
            derived['chat_headline'] = sentence_case_copy(headings[1].rstrip('.'), 54)
        if len(headings) > 2:
            derived['power_headline'] = sentence_case_copy(headings[2].rstrip('.'), 54)
    derived['release_summary'] = summary
    return derived


def audio_slug_from_tag(tag):
    version = version_from_tag(tag)
    m = re.match(r'(\d+)\.(\d+)', version)
    if m:
        return f"v{m.group(1)}{m.group(2)}"
    return re.sub(r'[^a-z0-9]+', '-', tag.lower()).strip('-')


def tag_audio_candidates(tag):
    slug = audio_slug_from_tag(tag)
    audio_dir = PIPELINE / 'assets/audio'
    exact = audio_dir / f'hermes-relay-{slug}-dubstep-technical-bass-37s.mp3'
    candidates = [exact]
    candidates.extend(sorted(audio_dir.glob(f'hermes-relay-{slug}-*.mp3')))
    seen = set()
    return [p for p in candidates if not (p in seen or seen.add(p))]


def default_audio_prompt(cfg, rel, version_display):
    summary = cfg.get('release_summary') or release_summary(rel.get('body') or '')
    return (
        f"A 37-second bass-forward dark atmospheric electronic instrumental track "
        f"for a premium Hermes-Relay {version_display} software release video. "
        "Warm deep bass, clean syncopated drums, low synth pulses, metallic percussion, "
        "subtle glitch details, and a cool futuristic software launch mood. "
        "Confident, composed, premium, energized but not cheerful. "
        f"Release theme: {trim_copy(summary, 180)} No vocals, no lyrics."
    )


def generate_lyria_audio(prompt, out_path, model='lyria-3-clip-preview'):
    try:
        from google import genai
    except Exception as e:
        raise RuntimeError(
            'Fresh release audio requires the google-genai package or an explicit --audio file.'
        ) from e
    if not os.environ.get('GEMINI_API_KEY'):
        raise RuntimeError('Fresh release audio requires GEMINI_API_KEY or an explicit --audio file.')

    client = genai.Client()
    response = client.models.generate_content(model=model, contents=prompt)
    for cand in response.candidates or []:
        content = getattr(cand, 'content', None)
        for part in getattr(content, 'parts', None) or []:
            inline = getattr(part, 'inline_data', None)
            if inline:
                raw = base64.b64decode(inline.data) if isinstance(inline.data, str) else inline.data
                out_path.parent.mkdir(parents=True, exist_ok=True)
                out_path.write_bytes(raw)
                return out_path
    raise RuntimeError('Lyria returned no inline audio data; pass --audio with a reviewed bed.')


def resolve_audio_path(args, cfg, rel, tag, version_display):
    if args.audio:
        return resolve_pipeline_path(args.audio)
    for candidate in tag_audio_candidates(tag):
        if candidate.exists():
            return candidate
    if not args.no_generate_audio:
        out_path = PIPELINE / 'assets/audio' / f"hermes-relay-{audio_slug_from_tag(tag)}-lyria-technical-bass.mp3"
        prompt = cfg.get('audio_prompt') or default_audio_prompt(cfg, rel, version_display)
        return generate_lyria_audio(prompt, out_path, model=cfg.get('audio_model', 'lyria-3-clip-preview'))
    if args.allow_default_audio:
        return resolve_pipeline_path(cfg['audio_path'])
    raise RuntimeError(
        f'No fresh audio bed found for {tag}. Pass --audio, stage assets/audio/hermes-relay-{audio_slug_from_tag(tag)}-*.mp3, '
        'or allow Lyria generation with GEMINI_API_KEY. Use --allow-default-audio only for explicit legacy fallback.'
    )


def copy_asset(src, dst):
    dst.parent.mkdir(parents=True, exist_ok=True)
    if not src.exists():
        raise FileNotFoundError(src)
    shutil.copy2(src, dst)

def build(args):
    cfg = dict(DEFAULTS)
    repo = args.repo or cfg['repo']
    tag = args.tag or latest_tag(repo)
    if args.release_json:
        rel = json.loads(Path(args.release_json).read_text())
        rel.setdefault('tagName', tag)
        rel.setdefault('name', tag)
        rel.setdefault('url', f'https://github.com/{repo}/releases/tag/{tag}')
        rel.setdefault('body', '')
        rel.setdefault('publishedAt', '')
    else:
        rel = release_json(repo, tag)
    version_number = version_from_tag(tag)
    cfg.update(derive_copy(cfg, rel, version_number))
    if args.config:
        cfg.update(json.loads(Path(args.config).read_text()))
    version_display = 'v' + version_number if not version_number.startswith('v') else version_number
    out_root = Path(args.out_root).expanduser()
    if not out_root.is_absolute():
        out_root = PIPELINE / out_root
    work = out_root / tag
    if args.clean and work.exists():
        shutil.rmtree(work)
    work.mkdir(parents=True, exist_ok=True)
    (work/'assets/screenshots').mkdir(parents=True, exist_ok=True)
    (work/'out').mkdir(exist_ok=True)

    audio = resolve_audio_path(args, cfg, rel, tag, version_display)
    audio_duration = probe_duration(audio)
    duration = float(args.duration or cfg.get('duration') or audio_duration)
    close_start = max(0, duration - 4.47)
    vals = {
        'DURATION': f'{duration:.2f}',
        'CLOSE_START': f'{close_start:.2f}',
        'CLOSE_DURATION': f'{duration - close_start:.2f}',
        'CLOSE_BEAT_2': f'{close_start + 0.6:.2f}',
        'AUDIO_FILENAME': audio.name,
        'PROJECT_LABEL': cfg['project_label'],
        'VERSION_NUMBER': version_number,
        'VERSION_DISPLAY': version_display,
        'RELEASE_KICKER': cfg['release_kicker'],
        'THESIS_HEADLINE': cfg['thesis_headline'],
        'THESIS_SUB': cfg['thesis_sub'],
        'ROUTE_HEADLINE': cfg['route_headline'],
        'ROUTE_SUB': cfg['route_sub'],
        'CHAT_HEADLINE': cfg['chat_headline'],
        'POWER_HEADLINE': cfg['power_headline'],
        'CLOSE_HEADLINE': cfg['close_headline'],
        'REPO_LABEL': cfg['repo_label'],
        'HANDLE': cfg['handle'],
        'SCENE_1_LABEL': cfg['scene_1_label'],
        'SCENE_2_LABEL': cfg['scene_2_label'],
        'SCENE_3_LABEL': cfg['scene_3_label'],
        'PREVIEW_LABEL': cfg['preview_label'],
        'VIEWER_LABEL': cfg['viewer_label'],
        'ROUTE_1_NAME': cfg['route_1_name'],
        'ROUTE_1_TARGET': cfg['route_1_target'],
        'ROUTE_1_STATUS': cfg['route_1_status'],
        'ROUTE_2_NAME': cfg['route_2_name'],
        'ROUTE_2_TARGET': cfg['route_2_target'],
        'ROUTE_2_STATUS': cfg['route_2_status'],
        'ROUTE_3_NAME': cfg['route_3_name'],
        'ROUTE_3_TARGET': cfg['route_3_target'],
        'ROUTE_3_STATUS': cfg['route_3_status'],
        'FEATURE_1_LABEL': cfg['feature_1_label'],
        'FEATURE_1_TEXT': cfg['feature_1_text'],
        'FEATURE_2_LABEL': cfg['feature_2_label'],
        'FEATURE_2_TEXT': cfg['feature_2_text'],
        'FEATURE_3_LABEL': cfg['feature_3_label'],
        'FEATURE_3_TEXT': cfg['feature_3_text'],
        'FEATURE_4_LABEL': cfg['feature_4_label'],
        'FEATURE_4_TEXT': cfg['feature_4_text'],
        'NODE_1': cfg['node_1'],
        'NODE_2': cfg['node_2'],
        'NODE_3': cfg['node_3'],
        'PILL_1': cfg['pill_1'],
        'PILL_2': cfg['pill_2'],
        'PILL_3': cfg['pill_3'],
    }
    html = render_template((PIPELINE/'templates/index.html').read_text(), vals)
    (work/'index.html').write_text(html)
    (work/'package.json').write_text(json.dumps({
        'name': f'hermes-relay-release-{tag}',
        'private': True,
        'type': 'module',
        'scripts': {
            'check': 'npx --yes hyperframes@0.4.44 lint && npx --yes hyperframes@0.4.44 validate && npx --yes hyperframes@0.4.44 inspect'
        }
    }, indent=2))
    (work/'hyperframes.json').write_text((PIPELINE/'hyperframes.json').read_text())
    copy_asset(audio, work/'assets'/audio.name)
    # Icon
    src_repo = resolve_pipeline_path(cfg['source_repo_path'])
    icon = None
    for c in cfg['icon_candidates']:
        p = Path(c)
        if not p.is_absolute(): p = src_repo / p
        if p.exists(): icon = p; break
    if not icon: raise FileNotFoundError('No icon candidate found')
    copy_asset(icon, work/'assets/hermes-relay-icon.png')
    # Screenshots
    for dest, rel_src in cfg['screenshot_map'].items():
        src = src_repo / rel_src
        copy_asset(src, work/'assets/screenshots'/dest)

    caption = cfg.get('caption_template', '').format(project_label=cfg['project_label'], version_display=version_display, credit=cfg['credit'], repo_label=cfg['repo_label'], release_summary=cfg.get('release_summary', ''))
    manifest = {**cfg, 'audio_path': str(audio), 'tag': tag, 'version_display': version_display, 'release': rel, 'duration': duration, 'audio_duration': audio_duration, 'workdir': str(work), 'caption': caption}
    (work/'manifest.json').write_text(json.dumps(manifest, indent=2))
    (work/'POST_DRAFT.md').write_text(f"# Draft X post for {tag}\n\n```text\n{caption}\n```\n\nAttach:\n\n`MEDIA:{work/'out'/('hermes-relay-' + tag + '.mp4')}`\n\nHuman gate: review the MP4/contact sheet/QA, then run `scripts/post_approved.py --workdir {work} --approved APPROVED_FOR_RELEASE` only after approval.\n")

    if args.no_render:
        print(json.dumps({'workdir': str(work), 'manifest': str(work/'manifest.json'), 'post_draft': str(work/'POST_DRAFT.md')}, indent=2))
        return

    print('Checking Hyperframes project...')
    check_out = sh(['npm','run','check'], cwd=work)
    print(check_out)
    output = work/'out'/f'hermes-relay-{tag}.mp4'
    print('Rendering video...')
    render_out = sh(['npx','--yes','hyperframes@0.4.44','render','-o',str(output),'--fps','30','--quality','high','--crf','18','--workers','4'], cwd=work)
    print(render_out)
    qa(work, output)
    print(json.dumps({'workdir': str(work), 'video': str(output), 'contact_sheet': str(work/'out/frames/contact-sheet.png'), 'qa': str(work/'QA.md'), 'post_draft': str(work/'POST_DRAFT.md')}, indent=2))

def qa(work, output):
    ffprobe = sh(['ffprobe','-v','error','-show_entries','format=duration,size:stream=codec_type,codec_name,width,height,r_frame_rate','-of','json',str(output)])
    volume = sh(['bash','-lc',f"ffmpeg -y -i {shlex_quote(str(output))} -af volumedetect -vn -f null - 2>&1 | grep -E 'mean_volume|max_volume'"])
    frames = work/'out/frames'
    frames.mkdir(parents=True, exist_ok=True)
    times = [1,5,11,17,23,29,34]
    duration = float(json.loads(ffprobe).get('format', {}).get('duration', 0) or 0)
    for t in times:
        if t < duration:
            sh(['ffmpeg','-y','-ss',str(t),'-i',str(output),'-frames:v','1',str(frames/f'frame_{t}.png')])
    # Contact sheet via PIL if available, otherwise leave frames.
    try:
        from PIL import Image, ImageDraw
        imgs=[]
        for t in times:
            p=frames/f'frame_{t}.png'
            if p.exists(): imgs.append((f'{t}s', Image.open(p).resize((270,270)).copy()))
        sheet=Image.new('RGB',(270*4,270*2),(8,10,20)); d=ImageDraw.Draw(sheet)
        for i,(label,im) in enumerate(imgs):
            x=(i%4)*270; y=(i//4)*270
            sheet.paste(im,(x,y)); d.rectangle([x,y,x+70,y+26],fill=(8,10,20)); d.text((x+8,y+6),label,fill=(125,211,252))
        sheet.save(frames/'contact-sheet.png')
    except Exception as e:
        (frames/'CONTACT_SHEET_FAILED.txt').write_text(str(e))
    qa_md = f"""# QA — Hermes-Relay release video

Video: `{output}`

## ffprobe

```json
{ffprobe.strip()}
```

## audio loudness

```text
{volume.strip()}
```

## preview frames

Contact sheet: `{frames/'contact-sheet.png'}`

## human review checklist

- [ ] no black/blank frames
- [ ] major copy readable at feed scale
- [ ] fresh screenshots are present OR no screenshots are shown and abstract cards are used instead
- [ ] public handle casing correct (`@axiom_labs_dev`)
- [ ] caption approved by a human reviewer
"""
    (work/'QA.md').write_text(qa_md)

def shlex_quote(s):
    import shlex
    return shlex.quote(s)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--tag', help='Release tag, e.g. android-v1.0.0. Defaults to latest release.')
    ap.add_argument('--repo', help='GitHub repo, default from manifest')
    ap.add_argument('--config', help='Optional JSON config override')
    ap.add_argument('--release-json', help='Optional local release metadata JSON for pre-release/premade preview packages')
    ap.add_argument('--audio', help='Audio file override')
    ap.add_argument('--no-generate-audio', action='store_true', help='Do not call Lyria when no tag-specific bed is staged')
    ap.add_argument('--allow-default-audio', action='store_true', help='Explicitly allow manifest.defaults.json audio_path fallback')
    ap.add_argument('--duration', type=float, help='Composition duration override; default audio duration')
    ap.add_argument('--out-root', default=str(DEFAULT_OUT_ROOT))
    ap.add_argument('--clean', action='store_true')
    ap.add_argument('--no-render', action='store_true')
    args = ap.parse_args()
    build(args)
if __name__ == '__main__': main()
