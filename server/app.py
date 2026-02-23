from flask import Flask, request, jsonify, Response, stream_with_context
from flask_cors import CORS
import yt_dlp
import os
import re
import tempfile
import threading
import uuid

app = Flask(__name__)
CORS(app)

# In-memory store for download progress
downloads = {}

# Supported platforms: YouTube, TikTok, Instagram, Facebook
SUPPORTED_DOMAINS = (
    'youtube.com', 'youtu.be', 'tiktok.com', 'vm.tiktok.com',
    'instagram.com', 'instagr.am', 'www.instagram.com',
    'facebook.com', 'fb.com', 'fb.watch', 'fbcdn.net', 'm.facebook.com'
)


_BASE_USER_AGENT = (
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
    '(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
)


def _get_base_ydl_opts():
    """Base yt-dlp options for all platforms."""
    return {
        'quiet': True,
        'no_warnings': True,
        'ignoreerrors': False,
        'socket_timeout': 30,
        'retries': 3,
        'fragment_retries': 3,
        # Browser-like User-Agent helps Instagram/Facebook and other sites
        'http_headers': {
            'User-Agent': _BASE_USER_AGENT,
            'Accept-Language': 'en-US,en;q=0.9',
        },
    }


def _is_instagram(url):
    url_lower = url.lower()
    return any(d in url_lower for d in ('instagram.com', 'instagr.am'))


def _is_facebook(url):
    url_lower = url.lower()
    return any(d in url_lower for d in ('facebook.com', 'fb.com', 'fb.watch', 'm.facebook.com', 'fbcdn.net'))


def _get_platform_opts(url):
    """Platform-specific options for Instagram, Facebook, etc."""
    opts = {}
    headers = {'User-Agent': _BASE_USER_AGENT, 'Accept-Language': 'en-US,en;q=0.9'}

    if _is_instagram(url):
        headers['Referer'] = 'https://www.instagram.com/'
        headers['Origin'] = 'https://www.instagram.com'
        opts['http_headers'] = headers
        # Optional: use cookies from file if set (helps with age-restricted or private content)
        cookies_file = os.environ.get('INSTAGRAM_COOKIES') or os.environ.get('COOKIES_FILE')
        if cookies_file and os.path.isfile(cookies_file):
            opts['cookiefile'] = cookies_file

    elif _is_facebook(url):
        headers['Referer'] = 'https://www.facebook.com/'
        headers['Origin'] = 'https://www.facebook.com'
        opts['http_headers'] = headers
        cookies_file = os.environ.get('FACEBOOK_COOKIES') or os.environ.get('COOKIES_FILE')
        if cookies_file and os.path.isfile(cookies_file):
            opts['cookiefile'] = cookies_file

    return opts


def _get_resolution(f):
    """Extract resolution string from format - works for YouTube, TikTok, Instagram, Facebook."""
    res = f.get('resolution')
    if res:
        return res
    w, h = f.get('width'), f.get('height')
    if w and h:
        return f'{w}x{h}'
    if h:
        return f'{h}p'
    return 'unknown'


@app.route('/resolve', methods=['POST'])
def resolve_video():
    """Extract video metadata (title, thumbnail, available formats) from a URL.
    Supports YouTube, TikTok, Instagram, and Facebook."""
    data = request.json
    url = data.get('url')
    if not url:
        return jsonify({'error': 'URL is required'}), 400

    try:
        ydl_opts = {**_get_base_ydl_opts(), **_get_platform_opts(url)}
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=False)
            if not info:
                return jsonify({'error': 'Could not extract video info'}), 500

            formats = []
            seen = set()
            for f in info.get('formats', []):
                ext = f.get('ext', 'mp4')
                resolution = _get_resolution(f)
                key = f"{resolution}_{ext}"
                if key in seen:
                    continue
                seen.add(key)

                formats.append({
                    'format_id': f.get('format_id'),
                    'ext': ext,
                    'resolution': resolution,
                    'filesize': f.get('filesize') or f.get('filesize_approx'),
                    'has_video': f.get('vcodec', 'none') != 'none',
                    'has_audio': f.get('acodec', 'none') != 'none',
                })

            # Instagram/Facebook often return single format or empty formats - add "best" fallback
            if not formats and info.get('url'):
                formats = [{
                    'format_id': 'best',
                    'ext': 'mp4',
                    'resolution': 'best',
                    'filesize': info.get('filesize'),
                    'has_video': True,
                    'has_audio': True,
                }]

            video_info = {
                'title': info.get('title') or info.get('id') or 'Video',
                'thumbnail': info.get('thumbnail'),
                'duration': info.get('duration'),
                'uploader': info.get('uploader') or info.get('uploader_id'),
                'formats': formats,
            }
            return jsonify(video_info)

    except Exception as e:
        return jsonify({'error': str(e)}), 500


def _run_download(download_id, url, format_id):
    """Background thread: download video using yt-dlp and track progress.
    Supports YouTube, TikTok, Instagram, and Facebook."""
    tmp_dir = tempfile.mkdtemp()

    # Instagram/Facebook: use id-based filename (titles often have emojis, special chars)
    # YouTube/TikTok: use title for nicer filenames
    if _is_instagram(url) or _is_facebook(url):
        output_template = os.path.join(tmp_dir, '%(id)s.%(ext)s')
        restrict_filenames = True
    else:
        output_template = os.path.join(tmp_dir, '%(title)s.%(ext)s')
        restrict_filenames = False

    downloads[download_id]['status'] = 'downloading'
    downloads[download_id]['tmp_dir'] = tmp_dir

    # Instagram/Facebook typically have single merged format - "best" works; bestvideo+bestaudio can fail
    if _is_instagram(url) or _is_facebook(url):
        format_sel = format_id if format_id else 'best'
    else:
        format_sel = format_id if format_id else 'bestvideo+bestaudio/best'

    ydl_opts = {
        **_get_base_ydl_opts(),
        **_get_platform_opts(url),
        'format': format_sel,
        'outtmpl': output_template,
        'restrictfilenames': restrict_filenames,
        'progress_hooks': [lambda d, did=download_id: _progress_hook(did, d)],
    }

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            ydl.download([url])
        downloads[download_id]['status'] = 'completed'
        # Fallback: if filepath not set by hook, find the video file in tmp_dir
        if not downloads[download_id].get('filepath'):
            for f in os.listdir(tmp_dir):
                if f.endswith(('.mp4', '.mkv', '.webm', '.m4a')):
                    downloads[download_id]['filepath'] = os.path.join(tmp_dir, f)
                    break
    except Exception as e:
        downloads[download_id]['status'] = 'error'
        downloads[download_id]['error'] = str(e)


def _progress_hook(download_id, d):
    """yt-dlp progress hook to update in-memory download state."""
    if download_id not in downloads:
        return
    if d['status'] == 'downloading':
        total = d.get('total_bytes') or d.get('total_bytes_estimate') or 0
        downloaded = d.get('downloaded_bytes', 0)
        percent = (downloaded / total * 100) if total > 0 else 0
        downloads[download_id]['progress'] = round(percent, 1)
        downloads[download_id]['downloaded_bytes'] = downloaded
        downloads[download_id]['total_bytes'] = total
        downloads[download_id]['speed'] = d.get('speed', 0)
        downloads[download_id]['eta'] = d.get('eta', 0)
    elif d['status'] == 'finished':
        downloads[download_id]['progress'] = 100
        downloads[download_id]['filepath'] = d.get('filename')


@app.route('/download', methods=['POST'])
def start_download():
    """Start a background download. Returns a download_id to poll progress."""
    data = request.json
    url = data.get('url')
    format_id = data.get('format_id')  # optional

    if not url:
        return jsonify({'error': 'URL is required'}), 400

    download_id = str(uuid.uuid4())
    downloads[download_id] = {
        'status': 'queued',
        'progress': 0,
        'downloaded_bytes': 0,
        'total_bytes': 0,
        'speed': 0,
        'eta': 0,
        'filepath': None,
        'error': None,
    }

    thread = threading.Thread(target=_run_download, args=(download_id, url, format_id))
    thread.daemon = True
    thread.start()

    return jsonify({'download_id': download_id, 'status': 'queued'})


@app.route('/progress/<download_id>', methods=['GET'])
def get_progress(download_id):
    """Poll download progress by download_id."""
    if download_id not in downloads:
        return jsonify({'error': 'Download not found'}), 404
    return jsonify(downloads[download_id])


@app.route('/file/<download_id>', methods=['GET'])
def get_file(download_id):
    """Stream the completed file back to the client."""
    if download_id not in downloads:
        return jsonify({'error': 'Download not found'}), 404

    dl = downloads[download_id]
    if dl['status'] != 'completed' or not dl.get('filepath'):
        return jsonify({'error': 'Download not ready', 'status': dl['status']}), 400

    filepath = dl['filepath']
    if not os.path.exists(filepath):
        return jsonify({'error': 'File not found on server'}), 404

    # Sanitize filename for Content-Disposition (ASCII-safe, no control chars)
    raw_filename = os.path.basename(filepath)
    filename = re.sub(r'[^\w\s\-\.]', '_', raw_filename) or 'video.mp4'

    def generate():
        with open(filepath, 'rb') as f:
            while True:
                chunk = f.read(8192)
                if not chunk:
                    break
                yield chunk

    return Response(
        stream_with_context(generate()),
        headers={
            'Content-Disposition': f'attachment; filename="{filename}"',
            'Content-Type': 'application/octet-stream',
        }
    )


if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, debug=True)
