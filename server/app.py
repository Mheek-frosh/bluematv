from flask import Flask, request, jsonify, Response, stream_with_context
from flask_cors import CORS
import yt_dlp
import subprocess
import os
import tempfile
import threading
import uuid
import json
import time

app = Flask(__name__)
CORS(app)

# In-memory store for download progress
downloads = {}


@app.route('/resolve', methods=['POST'])
def resolve_video():
    """Extract video metadata (title, thumbnail, available formats) from a URL."""
    data = request.json
    url = data.get('url')
    if not url:
        return jsonify({'error': 'URL is required'}), 400

    try:
        ydl_opts = {'quiet': True, 'no_warnings': True}
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=False)

            formats = []
            seen = set()
            for f in info.get('formats', []):
                # Only include formats with both video and audio, or best merged
                ext = f.get('ext', 'mp4')
                resolution = f.get('resolution', 'unknown')
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

            video_info = {
                'title': info.get('title'),
                'thumbnail': info.get('thumbnail'),
                'duration': info.get('duration'),
                'uploader': info.get('uploader'),
                'formats': formats,
            }
            return jsonify(video_info)

    except Exception as e:
        return jsonify({'error': str(e)}), 500


def _run_download(download_id, url, format_id):
    """Background thread: download video using yt-dlp and track progress."""
    tmp_dir = tempfile.mkdtemp()
    output_template = os.path.join(tmp_dir, '%(title)s.%(ext)s')

    downloads[download_id]['status'] = 'downloading'
    downloads[download_id]['tmp_dir'] = tmp_dir

    ydl_opts = {
        'format': format_id if format_id else 'best',
        'outtmpl': output_template,
        'quiet': True,
        'no_warnings': True,
        'progress_hooks': [lambda d: _progress_hook(download_id, d)],
    }

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            ydl.download([url])
        downloads[download_id]['status'] = 'completed'
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

    filename = os.path.basename(filepath)

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
