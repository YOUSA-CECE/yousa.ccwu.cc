#!/usr/bin/env python3
"""
Agent Communication Bridge - HTTP API for phone agent ↔ Hermes desktop agent.

Phone agent posts messages here; Hermes desktop polls and replies.
Runs as a Flask Blueprint on the existing yousa.ccwu.cc website.

API:
  POST /api/agent/send     - Phone agent sends a message (returns msg_id)
  GET  /api/agent/poll     - Hermes desktop polls for pending messages
  POST /api/agent/reply    - Hermes replies to a message
  GET  /api/agent/replies  - Phone agent gets replies since last check
  GET  /api/agent/status/<msg_id> - Check message status
"""

import json
import hmac
import os
import secrets
import time
import uuid
import threading
from contextlib import contextmanager
from pathlib import Path
from datetime import datetime, timezone

from flask import Blueprint, request, jsonify, current_app

agent_bridge = Blueprint('agent_bridge', __name__)

# ---------------------------------------------------------------------------
# Storage: a JSON file in the app directory, persisted on every write.
# The lock combines a process-local mutex with an OS file lock on Unix.
# ---------------------------------------------------------------------------

_lock = threading.Lock()
_AGENT_DATA_FILE = 'agent_bridge_data.json'
_MAX_MESSAGES = 2000
_MAX_TEXT_LENGTH = 20000
_MAX_CONTEXT_BYTES = 65536


def _load_or_create_key(env_name, filename):
    value = os.getenv(env_name, '').strip()
    if value:
        if len(value) < 32:
            raise RuntimeError(f'{env_name} must contain at least 32 characters')
        return value
    path = Path(current_app.root_path) / filename
    if path.exists():
        value = path.read_text(encoding='utf-8').strip()
        if len(value) < 32:
            raise RuntimeError(f'{path.name} must contain at least 32 characters')
        return value
    value = secrets.token_urlsafe(48)
    path.write_text(value + '\n', encoding='utf-8')
    try:
        path.chmod(0o600)
    except OSError:
        pass
    return value


def initialize_agent_keys():
    """Create both role-specific key files during application startup."""
    _load_or_create_key('AGENT_PHONE_API_KEY', '.agent_phone_key')
    _load_or_create_key('AGENT_HERMES_API_KEY', '.agent_hermes_key')


@agent_bridge.before_request
def authenticate_agent_bridge():
    if request.content_length and request.content_length > 256 * 1024:
        return jsonify({'ok': False, 'error': 'Request body too large'}), 413

    hermes_endpoints = {'agent_bridge.hermes_poll', 'agent_bridge.hermes_reply'}
    if request.endpoint in hermes_endpoints:
        expected = _load_or_create_key('AGENT_HERMES_API_KEY', '.agent_hermes_key')
    else:
        expected = _load_or_create_key('AGENT_PHONE_API_KEY', '.agent_phone_key')
    supplied = request.headers.get('X-Agent-Key', '').strip()
    if not supplied or not hmac.compare_digest(supplied, expected):
        return jsonify({'ok': False, 'error': 'unauthorized'}), 403
    return None


def _data_path():
    """Resolve the data file path relative to the Flask app root."""
    return Path(current_app.root_path) / _AGENT_DATA_FILE


@contextmanager
def _storage_lock():
    with _lock:
        lock_path = _data_path().with_suffix('.lock')
        with lock_path.open('a+b') as lock_file:
            try:
                import fcntl
                fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
            except ImportError:
                fcntl = None
            try:
                yield
            finally:
                if fcntl is not None:
                    fcntl.flock(lock_file.fileno(), fcntl.LOCK_UN)


def _prune_messages(data):
    messages = data.get('messages', {})
    overflow = len(messages) - _MAX_MESSAGES
    if overflow <= 0:
        return
    oldest = sorted(
        messages.values(), key=lambda message: message.get('created_at', '')
    )[:overflow]
    for message in oldest:
        messages.pop(message.get('id'), None)


def _load():
    """Load all messages from disk."""
    path = _data_path()
    if not path.exists():
        return {'messages': {}, 'agents': {}}
    try:
        with open(path, 'r', encoding='utf-8') as f:
            return json.load(f)
    except (json.JSONDecodeError, OSError):
        return {'messages': {}, 'agents': {}}


def _save(data):
    """Atomically write all messages to disk."""
    path = _data_path()
    tmp = path.with_suffix('.tmp')
    with open(tmp, 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    tmp.replace(path)


# ---------------------------------------------------------------------------
# Helper: next_seq for an agent
# ---------------------------------------------------------------------------

def _next_seq(data, agent_id):
    """Get the next outbound sequence number for an agent."""
    seq = data['agents'].get(agent_id, {}).get('next_seq', 1)
    data['agents'].setdefault(agent_id, {})['next_seq'] = seq + 1
    return seq


# ======================== Phone Agent Endpoints ============================

@agent_bridge.route('/api/agent/send', methods=['POST'])
def agent_send():
    """
    Phone agent sends a message to Hermes desktop.
    Body: {"text": "...", "from": "phone-agent-name", "context": {...}} (context optional)
    Returns: {"ok": true, "msg_id": "...", "status": "pending"}
    """
    body = request.get_json(silent=True)
    if not body or not isinstance(body.get('text'), str) or not body['text'].strip():
        return jsonify({'ok': False, 'error': 'Missing "text" field'}), 400
    if len(body['text']) > _MAX_TEXT_LENGTH:
        return jsonify({'ok': False, 'error': 'Message too long'}), 400
    sender = body.get('from', 'phone-agent')
    context = body.get('context', {})
    if not isinstance(sender, str) or not sender.strip() or len(sender) > 100:
        return jsonify({'ok': False, 'error': 'Invalid "from" field'}), 400
    try:
        context_size = len(json.dumps(context, ensure_ascii=False).encode('utf-8'))
    except (TypeError, ValueError):
        return jsonify({'ok': False, 'error': 'Invalid "context" field'}), 400
    if context_size > _MAX_CONTEXT_BYTES:
        return jsonify({'ok': False, 'error': 'Context too large'}), 400

    msg_id = str(uuid.uuid4())
    now = datetime.now(timezone.utc).isoformat()

    msg = {
        'id': msg_id,
        'from': sender.strip(),
        'text': body['text'].strip(),
        'context': context,
        'status': 'pending',          # pending → processing → done
        'reply': None,
        'reply_time': None,
        'created_at': now,
        'seq': 0,
    }

    with _storage_lock():
        data = _load()
        msg['seq'] = _next_seq(data, '_hermes')  # assign inbox seq for Hermes
        data['messages'][msg_id] = msg
        _prune_messages(data)
        _save(data)

    return jsonify({'ok': True, 'msg_id': msg_id, 'status': 'pending'})


@agent_bridge.route('/api/agent/replies', methods=['GET'])
def agent_get_replies():
    """
    Phone agent retrieves replies since its last check.
    Query: ?since=<seq|iso-timestamp>&agent=<agent_id>
    Returns: {"ok": true, "replies": [{"msg_id": "...", "text": "...", ...}], "last_seq": N}
    """
    agent_id = request.args.get('agent', 'phone-agent')
    since_raw = request.args.get('since', '0')

    # Determine cutoff
    try:
        since_seq = int(since_raw)
        use_seq = True
    except ValueError:
        use_seq = False  # fallback: timestamp comparison (not implemented for brevity)
        since_seq = 0

    with _storage_lock():
        data = _load()
        agent_meta = data['agents'].get(agent_id, {})
        replies = []

        for msg in data['messages'].values():
            if msg.get('status') != 'done' or not msg.get('reply'):
                continue
            if msg.get('from') != agent_id:
                continue
            if use_seq and msg.get('seq', 0) > since_seq:
                replies.append({
                    'msg_id': msg['id'],
                    'text': msg['reply'],
                    'created_at': msg['created_at'],
                    'reply_time': msg.get('reply_time'),
                    'your_text': msg['text'],
                })
            elif not use_seq:
                # timestamp-based fallback
                replies.append({
                    'msg_id': msg['id'],
                    'text': msg['reply'],
                    'created_at': msg['created_at'],
                    'reply_time': msg.get('reply_time'),
                    'your_text': msg['text'],
                })

        # Sort by seq and find max
        replies.sort(key=lambda r: r.get('reply_time') or '')
        last_seq = max(
            (m.get('seq', 0) for m in data['messages'].values()
             if m.get('status') == 'done'),
            default=0
        )

    return jsonify({'ok': True, 'replies': replies, 'last_seq': last_seq})


# ======================= Hermes Desktop Endpoints ===========================

@agent_bridge.route('/api/agent/poll', methods=['GET'])
def hermes_poll():
    """
    Hermes desktop polls for pending messages (status=pending).
    Returns messages ordered by seq ascending (oldest first).
    
    Query: ?limit=5 (default) - max messages to return
    """
    limit = request.args.get('limit', 5, type=int)
    limit = max(1, min(limit or 5, 20))

    with _storage_lock():
        data = _load()
        pending = [
            m for m in data['messages'].values()
            if m['status'] == 'pending'
        ]
        pending.sort(key=lambda m: m['seq'])

        # Return up to `limit` messages
        batch = pending[:limit]

        # Mark as processing
        now = datetime.now(timezone.utc).isoformat()
        for m in batch:
            data['messages'][m['id']]['status'] = 'processing'
            data['messages'][m['id']]['picked_at'] = now
        _save(data)

    out = []
    for m in batch:
        out.append({
            'msg_id': m['id'],
            'from': m['from'],
            'text': m['text'],
            'context': m.get('context', {}),
            'seq': m['seq'],
            'created_at': m['created_at'],
        })

    return jsonify({
        'ok': True,
        'messages': out,
        'count': len(out),
        'pending_total': len(pending),  # may be slightly stale
    })


@agent_bridge.route('/api/agent/reply', methods=['POST'])
def hermes_reply():
    """
    Hermes desktop replies to a message.
    Body: {"msg_id": "...", "text": "reply content"}
    Returns: {"ok": true, "status": "done"}
    """
    body = request.get_json(silent=True)
    if (not body or not isinstance(body.get('msg_id'), str)
            or not isinstance(body.get('text'), str)
            or not body['msg_id'].strip() or not body['text'].strip()):
        return jsonify({'ok': False, 'error': 'Missing "msg_id" and "text"'}), 400
    if len(body['text']) > _MAX_TEXT_LENGTH:
        return jsonify({'ok': False, 'error': 'Reply too long'}), 400

    msg_id = body['msg_id'].strip()
    reply_text = body['text'].strip()
    now = datetime.now(timezone.utc).isoformat()

    with _storage_lock():
        data = _load()
        if msg_id not in data['messages']:
            return jsonify({'ok': False, 'error': 'Message not found'}), 404

        msg = data['messages'][msg_id]
        msg['status'] = 'done'
        msg['reply'] = reply_text
        msg['reply_time'] = now
        _save(data)

    return jsonify({
        'ok': True,
        'msg_id': msg_id,
        'status': 'done',
        'reply_time': now,
    })


# ======================= Utility Endpoint ===================================

@agent_bridge.route('/api/agent/status/<msg_id>', methods=['GET'])
def msg_status(msg_id):
    """Check the status of a specific message."""
    with _storage_lock():
        data = _load()
        msg = data['messages'].get(msg_id)

    if not msg:
        return jsonify({'ok': False, 'error': 'Not found'}), 404

    result = {
        'ok': True,
        'msg_id': msg['id'],
        'status': msg['status'],
        'from': msg['from'],
        'created_at': msg['created_at'],
        'seq': msg['seq'],
    }
    if msg.get('reply_time'):
        result['reply_time'] = msg['reply_time']
    if msg.get('reply'):
        result['text'] = msg['reply']

    return jsonify(result)


@agent_bridge.route('/api/agent/health', methods=['GET'])
def health():
    """Quick health check for the bridge."""
    with _storage_lock():
        data = _load()
        total = len(data['messages'])
        pending = sum(1 for m in data['messages'].values() if m['status'] == 'pending')
        done = sum(1 for m in data['messages'].values() if m['status'] == 'done')

    return jsonify({
        'ok': True,
        'total_messages': total,
        'pending': pending,
        'done': done,
        'service': 'agent-bridge',
        'version': '1.0.0',
    })
