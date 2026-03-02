#!/usr/bin/env python3
import argparse
import boto3
import hashlib
import json
import os
import pathlib
import time


def sha256_bytes(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()

def ensure_dir(p: pathlib.Path):
    p.mkdir(parents=True, exist_ok=True)

def ssml_to_files(in_path: pathlib.Path, out_root: pathlib.Path, polly, voice, engine):
    ssml = in_path.read_text(encoding="utf-8")
    # Hash includes voice & engine so voice changes invalidate cache
    h = sha256_bytes((voice + engine + ssml).encode("utf-8"))
    rel = in_path.relative_to(args.input)
    out_dir = out_root / rel.parent
    ensure_dir(out_dir)
    base = rel.stem

    mp3_path  = out_dir / f"{base}.mp3"
    marks_path = out_dir / f"{base}.marks.json"
    vtt_path   = out_dir / f"{base}.vtt"
    done_path  = out_dir / f"{base}.{h}.done"

    if done_path.exists() and mp3_path.exists() and marks_path.exists():
        print(f"[skip] {rel} (cache hit)")
        return

    print(f"[synth] {rel} → {mp3_path.name}")

    # 1) audio
    audio = polly.synthesize_speech(
        Text=ssml,
        TextType="ssml",
        VoiceId=voice,
        Engine=engine,
        OutputFormat="mp3"
    )
    with open(mp3_path, "wb") as f:
        f.write(audio["AudioStream"].read())

    # 2) speech marks (word + sentence)
    marks_stream = polly.synthesize_speech(
        Text=ssml,
        TextType="ssml",
        VoiceId=voice,
        Engine=engine,
        OutputFormat="json",
        SpeechMarkTypes=["word","sentence"]
    )["AudioStream"].read().decode("utf-8")

    marks = []
    for line in marks_stream.splitlines():
        try:
            marks.append(json.loads(line))
        except json.JSONDecodeError:
            pass
    with open(marks_path, "w", encoding="utf-8") as f:
        json.dump(marks, f, ensure_ascii=False, indent=2)

    # 3) basic WebVTT from sentence marks
    # Polly "time" is milliseconds from start
    def ms_to_vtt(ms):
        s, msec = divmod(int(ms), 1000)
        h, s = divmod(s, 3600)
        m, s = divmod(s, 60)
        return f"{h:02d}:{m:02d}:{s:02d}.{msec:03d}"

    sentences = [m for m in marks if m.get("type") == "sentence"]
    vtt_lines = ["WEBVTT",""]
    for i, s in enumerate(sentences):
        start = s["time"]
        # end at next sentence start, or +2s as a fallback
        end = sentences[i+1]["time"] if i+1 < len(sentences) else s["time"] + 2000
        vtt_lines.append(f"{ms_to_vtt(start)} --> {ms_to_vtt(end)}")
        vtt_lines.append(s["value"].strip())
        vtt_lines.append("")

    with open(vtt_path, "w", encoding="utf-8") as f:
        f.write("\n".join(vtt_lines))

    # mark cache
    for p in out_dir.glob(f"{base}.*.done"):
        try: p.unlink()
        except: pass
    done_path.touch()

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, help="Folder containing *.ssml")
    parser.add_argument("--out-dir", required=True, help="Output folder for mp3/marks/vtt")
    parser.add_argument("--voice", default="Matthew")
    parser.add_argument("--engine", default="neural", choices=["standard","neural"])
    args = parser.parse_args()

    polly = boto3.client("polly")
    in_root = pathlib.Path(args.input)
    out_root = pathlib.Path(args.out_dir)
    ensure_dir(out_root)

    ssml_files = list(in_root.rglob("*.ssml"))
    if not ssml_files:
        print("No SSML files found.")
        raise SystemExit(0)

    for f in sorted(ssml_files):
        ssml_to_files(f, out_root, polly, args.voice, args.engine)

    print("Done.")
