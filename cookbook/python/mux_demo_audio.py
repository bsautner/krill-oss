#!/usr/bin/env python3
"""
Download MP3 files listed in demo text files, combine them into single MP3s,
and upload the results to S3.
"""
import argparse
import boto3
import pathlib
import subprocess
import sys
import tempfile
import urllib.parse
from botocore.exceptions import ClientError


def parse_s3_url(url: str):
    """Parse s3:// or https:// URL into (bucket, key)"""
    if url.startswith("s3://"):
        parts = url[5:].split("/", 1)
        return parts[0], parts[1] if len(parts) > 1 else ""
    elif "s3.amazonaws.com" in url or "amazonaws.com" in url:
        # Handle https://bucket.s3.region.amazonaws.com/key or https://cms.krill.systems/key
        parsed = urllib.parse.urlparse(url)
        # For CloudFront/custom domain, we need to extract from path
        if parsed.netloc and not "s3" in parsed.netloc:
            # Custom domain like cms.krill.systems
            # Assume it maps to a known bucket
            return None, parsed.path.lstrip("/")
        # Standard S3 URL
        path_parts = parsed.path.lstrip("/").split("/", 1)
        return path_parts[0] if path_parts else "", path_parts[1] if len(path_parts) > 1 else ""
    return None, None

def download_mp3_from_url(url: str, s3_client, bucket: str, output_path: pathlib.Path):
    """Download MP3 from S3 URL to local file"""
    _, key = parse_s3_url(url)

    if not key:
        print(f"  [warn] Could not parse S3 key from: {url}")
        return False

    try:
        print(f"  [download] {key}")
        s3_client.download_file(bucket, key, str(output_path))
        return True
    except ClientError as e:
        print(f"  [error] Failed to download {key}: {e}")
        return False

def combine_mp3_files(mp3_files: list[pathlib.Path], output_path: pathlib.Path):
    """Combine multiple MP3 files into one using ffmpeg"""
    if not mp3_files:
        print("  [error] No MP3 files to combine")
        return False

    # Create a temporary file list for ffmpeg concat
    with tempfile.NamedTemporaryFile(mode='w', suffix='.txt', delete=False) as f:
        concat_file = pathlib.Path(f.name)
        for mp3 in mp3_files:
            # ffmpeg concat requires absolute paths and proper escaping
            f.write(f"file '{mp3.absolute()}'\n")

    try:
        # Use ffmpeg to concatenate
        cmd = [
            'ffmpeg',
            '-f', 'concat',
            '-safe', '0',
            '-i', str(concat_file),
            '-c', 'copy',
            '-y',  # Overwrite output file
            str(output_path)
        ]

        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True
        )

        if result.returncode != 0:
            print(f"  [error] ffmpeg failed: {result.stderr}")
            return False

        print(f"  [combine] Created {output_path.name} ({output_path.stat().st_size} bytes)")
        return True

    except FileNotFoundError:
        print("  [error] ffmpeg not found. Please install ffmpeg.")
        return False
    finally:
        # Clean up concat file
        try:
            concat_file.unlink()
        except:
            pass

def process_demo_file(demo_txt: pathlib.Path, s3_client, source_bucket: str,
                     dest_bucket: str, dest_prefix: str, temp_dir: pathlib.Path):
    """Process a single demo text file"""
    print(f"\n[process] {demo_txt.name}")

    # Read URLs from file
    urls = []
    for line in demo_txt.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if line and not line.startswith('#'):
            urls.append(line)

    if not urls:
        print(f"  [skip] No URLs found in {demo_txt.name}")
        return False

    print(f"  [info] Found {len(urls)} MP3 URLs to combine")

    # Create temp directory for downloads
    download_dir = temp_dir / demo_txt.stem
    download_dir.mkdir(parents=True, exist_ok=True)

    # Download all MP3s
    downloaded_files = []
    for i, url in enumerate(urls):
        mp3_file = download_dir / f"{i:04d}.mp3"
        if download_mp3_from_url(url, s3_client, source_bucket, mp3_file):
            downloaded_files.append(mp3_file)

    if not downloaded_files:
        print(f"  [error] No files downloaded successfully")
        return False

    if len(downloaded_files) != len(urls):
        print(f"  [warn] Only {len(downloaded_files)}/{len(urls)} files downloaded")

    # Combine MP3s
    combined_file = temp_dir / f"{demo_txt.stem}.mp3"
    if not combine_mp3_files(downloaded_files, combined_file):
        return False

    # Upload to S3
    dest_key = f"{dest_prefix}/{demo_txt.stem}.mp3".lstrip('/')
    print(f"  [upload] s3://{dest_bucket}/{dest_key}")

    try:
        s3_client.upload_file(
            str(combined_file),
            dest_bucket,
            dest_key,
            ExtraArgs={
                'ContentType': 'audio/mpeg',
                'CacheControl': 'public, max-age=31536000'
            }
        )
        print(f"  [success] https://{dest_bucket}/{dest_key}")
        return True
    except ClientError as e:
        print(f"  [error] Upload failed: {e}")
        return False

def main():
    parser = argparse.ArgumentParser(
        description='Download, combine, and upload demo audio files'
    )
    parser.add_argument(
        '--input',
        required=True,
        help='Directory containing demo .txt files (e.g., content/narration/demo)'
    )
    parser.add_argument(
        '--source-bucket',
        default='cms.krill.systems',
        help='S3 bucket to download MP3s from'
    )
    parser.add_argument(
        '--dest-bucket',
        default='cms.krill.systems',
        help='S3 bucket to upload combined MP3s to'
    )
    parser.add_argument(
        '--dest-prefix',
        required=True,
        help='S3 prefix for uploads (e.g., demos/main/abc123)'
    )

    args = parser.parse_args()

    input_dir = pathlib.Path(args.input)
    if not input_dir.is_dir():
        print(f"Error: {args.input} is not a directory")
        return 1

    # Find all .txt files
    txt_files = list(input_dir.glob('*.txt'))
    if not txt_files:
        print(f"No .txt files found in {args.input}")
        return 0

    print(f"Found {len(txt_files)} demo file(s) to process")

    # Initialize S3 client
    s3_client = boto3.client('s3')

    # Process each file
    success_count = 0
    with tempfile.TemporaryDirectory() as temp_dir:
        temp_path = pathlib.Path(temp_dir)

        for txt_file in sorted(txt_files):
            if process_demo_file(
                txt_file,
                s3_client,
                args.source_bucket,
                args.dest_bucket,
                args.dest_prefix,
                temp_path
            ):
                success_count += 1

    print(f"\n[done] {success_count}/{len(txt_files)} demo(s) processed successfully")
    return 0 if success_count == len(txt_files) else 1

if __name__ == '__main__':
    sys.exit(main())

