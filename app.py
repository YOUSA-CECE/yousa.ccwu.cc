#!/usr/bin/env python3
"""yousa.dev — Personal website with auth (admin/user/guest)."""

import os
import re
import json
import sqlite3
import mimetypes
import threading
import time
from pathlib import Path
from datetime import datetime, timezone
from functools import wraps
from zoneinfo import ZoneInfo

import markdown
from flask import (
    Flask, render_template, request, jsonify, send_from_directory,
    abort, redirect, url_for, flash, g, session
)
from flask_login import (
    LoginManager, UserMixin, login_user, logout_user,
    login_required, current_user
)
from werkzeug.security import generate_password_hash, check_password_hash
from werkzeug.middleware.proxy_fix import ProxyFix
from datetime import datetime, timezone
try:
    from zoneinfo import ZoneInfo
except ImportError:
    try:
        from backports.zoneinfo import ZoneInfo
    except ImportError:
        from datetime import timezone, timedelta
        _HK_TZ = timezone(timedelta(hours=8))
        class ZoneInfo:
            """Fallback ZoneInfo for Python < 3.9. Uses fixed UTC+8 (China has no DST)."""
            _cache = {}
            def __new__(cls, key):
                if key not in cls._cache:
                    cls._cache[key] = _HK_TZ
                return cls._cache[key]
BASE_DIR = Path(__file__).resolve().parent
WIKI_DIR = BASE_DIR / "wiki"
# 云盘目录：网站根目录下的 云盘/ 文件夹
CLOUD_DIR = BASE_DIR / "云盘"
CLOUD_DIR.mkdir(exist_ok=True)
FILE_DIR = CLOUD_DIR
DB_PATH = BASE_DIR / "users.db"
TEMPLATES_DIR = BASE_DIR / "templates"
STATIC_DIR = BASE_DIR / "static"
SAFE_CACHE_PATHS = {"/", "/wiki/", "/blog", "/about", "/app", "/download", "/guestbook"}
SAFE_CACHE_ENDPOINTS = {
    "home", "wiki_view", "wiki_search", "blog_list", "blog_post",
    "about", "app_download", "guestbook", "chat", "site_search",
}
CACHE_INVALIDATING_ENDPOINTS = {
    "login", "logout", "register", "delete_user", "change_role",
    "update_nickname", "change_password", "blog_write", "blog_delete",
    "guestbook", "guestbook_delete",
}
ACTIVITY_COLUMNS = {
    "last_seen_at": "TEXT",
    "last_seen_source": "TEXT",
    "last_app_seen_at": "TEXT",
    "last_web_seen_at": "TEXT",
}
_schema_lock = threading.Lock()
_activity_schema_path = None

app = Flask(__name__, template_folder=str(TEMPLATES_DIR),
            static_folder=str(STATIC_DIR), static_url_path="/static")
app.wsgi_app = ProxyFix(app.wsgi_app, x_for=1, x_proto=1, x_host=1)
app.secret_key = os.getenv("SECRET_KEY", "yousa-dev-secret-key-change-me")

login_manager = LoginManager()
login_manager.init_app(app)
login_manager.login_view = "login"
# The login page already explains that authentication is required. Disabling
# Flask-Login's automatic flash avoids duplicate "请先登录" messages when a
# WebView process restores or retries a protected URL.
login_manager.login_message = None


# ── Database ────────────────────────────────────────────────────────

def get_db():
    """Get or create a thread-local DB connection."""
    if "db" not in g:
        g.db = sqlite3.connect(str(DB_PATH))
        g.db.row_factory = sqlite3.Row
    return g.db

@app.teardown_appcontext
def close_db(exception):
    db = g.pop("db", None)
    if db is not None:
        db.close()

def init_db():
    """Create tables if they don't exist."""
    db = sqlite3.connect(str(DB_PATH))
    db.execute("""
        CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT UNIQUE NOT NULL,
            password TEXT NOT NULL,
            role TEXT NOT NULL DEFAULT 'user',
            nickname TEXT DEFAULT '',
            created_at TEXT DEFAULT (datetime('now'))
        )
    """)
    migrate_user_activity_schema(db)
    # Blog posts table
    db.execute("""
        CREATE TABLE IF NOT EXISTS posts (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL,
            content TEXT NOT NULL,
            author_id INTEGER NOT NULL,
            created_at TEXT DEFAULT (datetime('now')),
            FOREIGN KEY (author_id) REFERENCES users(id)
        )
    """)
    # Guestbook / comments table
    db.execute("""
        CREATE TABLE IF NOT EXISTS messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            author_name TEXT NOT NULL,
            content TEXT NOT NULL,
            user_id INTEGER,
            created_at TEXT DEFAULT (datetime('now')),
            FOREIGN KEY (user_id) REFERENCES users(id)
        )
    """)
    # Upload metadata table (gallery + cloud drive)
    db.execute("""
        CREATE TABLE IF NOT EXISTS upload_meta (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            type TEXT NOT NULL DEFAULT 'gallery',
            filepath TEXT NOT NULL,
            title TEXT DEFAULT '',
            notes TEXT DEFAULT '',
            uploaded_by INTEGER,
            created_at TEXT DEFAULT (datetime('now')),
            FOREIGN KEY (uploaded_by) REFERENCES users(id)
        )
    """)
    db.commit()
    # Add post_id to messages table for per-post comments (if not exists)
    try:
        db.execute("ALTER TABLE messages ADD COLUMN post_id INTEGER DEFAULT NULL")
        db.commit()
    except sqlite3.OperationalError:
        pass  # Column already exists
    # Add target_type/target_id for generic commenting
    try:
        db.execute("ALTER TABLE messages ADD COLUMN target_type TEXT DEFAULT NULL")
        db.commit()
    except sqlite3.OperationalError:
        pass
    try:
        db.execute("ALTER TABLE messages ADD COLUMN target_id TEXT DEFAULT NULL")
        db.commit()
    except sqlite3.OperationalError:
        pass
    # Add visibility to upload_meta
    try:
        db.execute("ALTER TABLE upload_meta ADD COLUMN visibility TEXT DEFAULT 'public'")
        db.commit()
    except sqlite3.OperationalError:
        pass
    # Create default admin if no users exist
    cur = db.execute("SELECT COUNT(*) FROM users")
    if cur.fetchone()[0] == 0:
        db.execute(
            "INSERT INTO users (username, password, role, nickname) VALUES (?, ?, ?, ?)",
            ("admin", generate_password_hash("admin123"), "admin", "管理员")
        )
        db.execute(
            "INSERT INTO users (username, password, role, nickname) VALUES (?, ?, ?, ?)",
            ("yousa", generate_password_hash("yousa123"), "admin", "Yousa")
        )
        db.commit()
        print("  👤 管理员已创建: admin / admin123, yousa / yousa123")
    else:
        # Ensure yousa admin exists (migration)
        cur2 = db.execute("SELECT COUNT(*) FROM users WHERE username='yousa'")
        if cur2.fetchone()[0] == 0:
            db.execute(
                "INSERT INTO users (username, password, role, nickname) VALUES (?, ?, ?, ?)",
                ("yousa", generate_password_hash("yousa123"), "admin", "Yousa")
            )
            db.commit()
            print("  👤 补充管理员: yousa / yousa123")
    db.close()


def migrate_user_activity_schema(db=None):
    """Idempotently add activity columns to an existing users table."""
    owns_connection = db is None
    if owns_connection:
        db = sqlite3.connect(str(DB_PATH))
    table = db.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='users'"
    ).fetchone()
    if table:
        existing = {row[1] for row in db.execute("PRAGMA table_info(users)").fetchall()}
        for column, column_type in ACTIVITY_COLUMNS.items():
            if column not in existing:
                try:
                    db.execute(f"ALTER TABLE users ADD COLUMN {column} {column_type}")
                except sqlite3.OperationalError as error:
                    # Multiple WSGI workers can race during the first deployment.
                    if "duplicate column name" not in str(error).lower():
                        raise
        db.commit()
    if owns_connection:
        db.close()


def ensure_activity_schema():
    """Run once per process/database path, including WSGI imports."""
    global _activity_schema_path
    db_path = str(DB_PATH.resolve())
    if _activity_schema_path == db_path:
        return
    with _schema_lock:
        if _activity_schema_path != db_path:
            try:
                migrate_user_activity_schema()
            except Exception as e:
                print(f"  ⚠️ 活动表迁移跳过: {e}")
            _activity_schema_path = db_path


# ── User Model ──────────────────────────────────────────────────────

class User(UserMixin):
    def __init__(self, row):
        self.id = row["id"]
        self.username = row["username"]
        self.password = row["password"]
        self.role = row["role"]
        self.nickname = row["nickname"] or row["username"]

    @property
    def is_admin(self):
        return self.role == "admin"

    @property
    def is_user(self):
        return self.role in ("admin", "user")


@login_manager.user_loader
def load_user(user_id):
    db = get_db()
    row = db.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
    return User(row) if row else None


# ── Role Decorators ─────────────────────────────────────────────────

def admin_required(f):
    @wraps(f)
    def decorated(*args, **kwargs):
        if not current_user.is_authenticated or not current_user.is_admin:
            abort(403)
        return f(*args, **kwargs)
    return decorated

def user_or_admin(f):
    @wraps(f)
    def decorated(*args, **kwargs):
        if not current_user.is_authenticated or not current_user.is_user:
            flash("请先登录以访问此页面", "warning")
            return redirect(url_for("login", next=request.path))
        return f(*args, **kwargs)
    return decorated


# ── Context Injector ────────────────────────────────────────────────

@app.context_processor
def inject_user():
    try:
        asset_version = int((STATIC_DIR / "style.css").stat().st_mtime)
    except OSError:
        asset_version = 1
    return dict(current_user=current_user, asset_version=asset_version)


@app.template_filter("hk_time")
def format_hk_time(value):
    if not value:
        return "暂无"
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return parsed.astimezone(ZoneInfo("Asia/Hong_Kong")).strftime("%Y-%m-%d %H:%M")
    except (TypeError, ValueError):
        return str(value)


@app.before_request
def track_user_activity():
    ensure_activity_schema()
    if (
        request.endpoint == "static"
        or request.headers.get("X-Yousa-Prefetch") == "1"
        or not current_user.is_authenticated
    ):
        return None

    source = "app" if "YousaAndroid" in request.headers.get("User-Agent", "") else "web"
    throttle_key = f"_activity_write_{source}"
    now_epoch = int(time.time())
    if now_epoch - int(session.get(throttle_key, 0)) < 60:
        return None

    now_utc = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    source_column = "last_app_seen_at" if source == "app" else "last_web_seen_at"
    db = get_db()
    try:
        db.execute(
            f"""UPDATE users
                SET last_seen_at = ?, last_seen_source = ?, {source_column} = ?
                WHERE id = ?""",
            (now_utc, source, now_utc, current_user.id),
        )
        db.commit()
        session[throttle_key] = now_epoch
    except sqlite3.Error:
        db.rollback()
    return None


@app.after_request
def apply_cache_policy(response):
    """Cache safe navigation pages privately and keep sensitive responses fresh."""
    is_static = request.endpoint == "static"
    is_safe_page = (
        request.method == "GET"
        and (
            request.path in SAFE_CACHE_PATHS
            or request.endpoint in SAFE_CACHE_ENDPOINTS
        )
        and response.status_code == 200
        and response.mimetype in ("text/html", "application/json")
    )

    if is_static:
        if request.path.endswith("/version.json"):
            response.headers["Cache-Control"] = "no-store, max-age=0"
        elif request.path.lower().endswith(".apk"):
            response.headers["Cache-Control"] = "public, max-age=3600, immutable"
        else:
            response.headers["Cache-Control"] = "public, max-age=31536000, immutable"
    elif is_safe_page:
        response.headers["Cache-Control"] = (
            "private, max-age=180, stale-while-revalidate=900"
        )
        vary = response.headers.get("Vary", "")
        vary_values = {item.strip() for item in vary.split(",") if item.strip()}
        vary_values.add("Cookie")
        response.headers["Vary"] = ", ".join(sorted(vary_values))
        response.headers["X-Yousa-Cache"] = "safe-navigation"
        if "ETag" not in response.headers:
            response.add_etag()
        response.make_conditional(request)
    else:
        response.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0"
        response.headers["Pragma"] = "no-cache"
        response.headers["Expires"] = "0"

    if (
        request.endpoint == "logout"
        or (
            request.method != "GET"
            and request.endpoint in CACHE_INVALIDATING_ENDPOINTS
        )
    ):
        response.headers["Clear-Site-Data"] = '"cache"'
    return response


# ── Helpers ─────────────────────────────────────────────────────────

# ── Wiki Link Resolver ───────────────────────────────────────────────

_WIKI_LINK_CACHE = None

def _build_wiki_link_cache():
    """Build a cache of page name → relative path for [[wikilinks]]."""
    cache = {}
    if not WIKI_DIR.exists():
        return cache
    for md_file in WIKI_DIR.rglob("*.md"):
        rel = str(md_file.relative_to(WIKI_DIR).with_suffix("")).replace("\\", "/")
        name = md_file.stem  # 文件名作为链接键
        cache[name] = rel
        # Also index by full stem (for multi-word names)
        parts = name.split()
        if len(parts) > 1:
            cache[" ".join(parts)] = rel
    return cache

def resolve_wikilinks(text):
    """Convert [[PageName]] to <a href="/wiki/path">PageName</a>."""
    global _WIKI_LINK_CACHE
    if _WIKI_LINK_CACHE is None:
        _WIKI_LINK_CACHE = _build_wiki_link_cache()

    def _replace(m):
        title = m.group(1).strip()
        path = _WIKI_LINK_CACHE.get(title)
        if path:
            return f'<a href="/wiki/{path}">{title}</a>'
        # Fallback: try filename match, use as-is with best guess
        for cache_title, cache_path in _WIKI_LINK_CACHE.items():
            if title in cache_title or cache_title in title:
                return f'<a href="/wiki/{cache_path}">{title}</a>'
        # No match — render as dead link
        return f'<span class="wiki-dead-link">{title}</span>'

    return re.sub(r'\[\[([^\]]+)\]\]', _replace, text)

def render_markdown(text):
    # Resolve [[wikilinks]] before markdown rendering
    text = resolve_wikilinks(text)
    return markdown.markdown(text, extensions=[
        "fenced_code", "codehilite", "tables", "toc", "nl2br"
    ])

def get_wiki_tree(path=None):
    if path is None:
        path = WIKI_DIR
    entries = sorted(path.iterdir(), key=lambda p: (p.is_file(), p.name))
    tree = []
    for entry in entries:
        if entry.name.startswith("."):
            continue
        item = {
            "name": entry.stem if entry.is_file() else entry.name,
            "path": str(entry.relative_to(WIKI_DIR)).replace("\\", "/"),
            "is_dir": entry.is_dir(),
        }
        if entry.is_dir():
            item["children"] = get_wiki_tree(entry)
        tree.append(item)
    return tree

def search_wiki(query):
    results = []
    if not query:
        return results
    query_lower = query.lower()
    for md_file in WIKI_DIR.rglob("*.md"):
        rel_path = str(md_file.relative_to(WIKI_DIR)).replace("\\", "/")
        try:
            text = md_file.read_text(encoding="utf-8")
        except Exception:
            continue
        if query_lower in text.lower():
            lines = text.split("\n")
            snippets = []
            for i, line in enumerate(lines):
                if query_lower in line.lower():
                    start = max(0, i - 1)
                    end = min(len(lines), i + 2)
                    snippet = "\n".join(lines[start:end]).strip()
                    if len(snippet) > 200:
                        snippet = snippet[:200] + "..."
                    snippets.append(snippet)
            results.append({
                "path": rel_path,
                "name": md_file.stem,
                "snippets": snippets[:3],
            })
    return results


def make_excerpt(text, limit=120):
    text = re.sub(r"\s+", " ", text or "").strip()
    return text[:limit] + ("..." if len(text) > limit else "")


def get_recent_wiki(limit=5):
    items = []
    try:
        for md_file in WIKI_DIR.rglob("*.md"):
            if md_file.name.startswith("."):
                continue
            rel_path = str(md_file.relative_to(WIKI_DIR)).replace("\\", "/")
            try:
                raw = md_file.read_text(encoding="utf-8")
            except Exception:
                raw = ""
            stat = md_file.stat()
            items.append({
                "title": md_file.stem,
                "url": url_for("wiki_view", subpath=rel_path),
                "excerpt": make_excerpt(re.sub(r"^#+\s*", "", raw), 90),
                "updated_at": datetime.fromtimestamp(stat.st_mtime).strftime("%Y-%m-%d %H:%M"),
            })
    except Exception:
        return []
    return sorted(items, key=lambda item: item["updated_at"], reverse=True)[:limit]


def get_home_activity():
    db = get_db()
    latest_posts = db.execute("""
        SELECT p.id, p.title, p.content, p.created_at, COALESCE(u.nickname, u.username, 'Yousa') AS author_name
        FROM posts p
        LEFT JOIN users u ON p.author_id = u.id
        ORDER BY p.created_at DESC
        LIMIT 3
    """).fetchall()
    latest_messages = db.execute("""
        SELECT id, author_name, content, created_at
        FROM messages
        ORDER BY created_at DESC
        LIMIT 3
    """).fetchall()
    counts = {
        "posts": db.execute("SELECT COUNT(*) FROM posts").fetchone()[0],
        "messages": db.execute("SELECT COUNT(*) FROM messages").fetchone()[0],
        "wiki": len(list(WIKI_DIR.rglob("*.md"))) if WIKI_DIR.exists() else 0,
    }
    return latest_posts, get_recent_wiki(3), latest_messages, counts


def search_site(query, include_files=False, limit=40):
    query = (query or "").strip()
    if not query:
        return []

    results = []
    needle = f"%{query}%"
    db = get_db()

    for post in db.execute("""
        SELECT id, title, content, created_at
        FROM posts
        WHERE title LIKE ? OR content LIKE ?
        ORDER BY created_at DESC
        LIMIT 12
    """, (needle, needle)).fetchall():
        results.append({
            "type": "日志",
            "title": post["title"],
            "url": url_for("blog_post", post_id=post["id"]),
            "excerpt": make_excerpt(post["content"]),
            "meta": post["created_at"],
        })

    for msg in db.execute("""
        SELECT author_name, content, created_at
        FROM messages
        WHERE author_name LIKE ? OR content LIKE ?
        ORDER BY created_at DESC
        LIMIT 10
    """, (needle, needle)).fetchall():
        results.append({
            "type": "留言",
            "title": f"{msg['author_name']} 的留言",
            "url": url_for("guestbook"),
            "excerpt": make_excerpt(msg["content"]),
            "meta": msg["created_at"],
        })

    try:
        query_lower = query.lower()
        for md_file in WIKI_DIR.rglob("*.md"):
            if len(results) >= limit:
                break
            rel_path = str(md_file.relative_to(WIKI_DIR)).replace("\\", "/")
            try:
                raw = md_file.read_text(encoding="utf-8")
            except Exception:
                continue
            if query_lower in md_file.stem.lower() or query_lower in raw.lower():
                results.append({
                    "type": "知识库",
                    "title": md_file.stem,
                    "url": url_for("wiki_view", subpath=rel_path),
                    "excerpt": make_excerpt(raw),
                    "meta": datetime.fromtimestamp(md_file.stat().st_mtime).strftime("%Y-%m-%d %H:%M"),
                })
    except Exception:
        pass

    if include_files:
        try:
            base = FILE_DIR.resolve()
            query_lower = query.lower()
            for root, dirs, files in os.walk(str(base)):
                dirs[:] = [d for d in dirs if not d.startswith(".")]
                for fname in files:
                    if len(results) >= limit:
                        break
                    if fname.startswith(".") or query_lower not in fname.lower():
                        continue
                    full = Path(root) / fname
                    rel = str(full.relative_to(base)).replace("\\", "/")
                    results.append({
                        "type": "文件",
                        "title": fname,
                        "url": url_for("file_browser", subpath=rel),
                        "excerpt": rel,
                        "meta": format_size(full.stat().st_size),
                    })
                if len(results) >= limit:
                    break
        except Exception:
            pass

    return results[:limit]


# ── Auth Routes ─────────────────────────────────────────────────────

@app.route("/login", methods=["GET", "POST"])
def login():
    if current_user.is_authenticated:
        return redirect(url_for("home"))

    if request.method == "POST":
        username = request.form.get("username", "").strip()
        password = request.form.get("password", "")
        next_page = request.args.get("next", url_for("home"))

        db = get_db()
        row = db.execute("SELECT * FROM users WHERE username = ?", (username,)).fetchone()
        if row and check_password_hash(row["password"], password):
            user = User(row)
            login_user(user, remember=True)
            flash(f"欢迎回来，{user.nickname}！", "success")
            # Authentication must never send the Android WebView back to HTTP.
            # The app is served behind an HTTPS reverse proxy.
            if not next_page.startswith("/") or next_page.startswith("//"):
                next_page = url_for("home")
            return redirect("https://yousa.ccwu.cc" + next_page, code=303)
        else:
            flash("用户名或密码错误", "error")

    return render_template("login.html")


@app.route("/register", methods=["GET", "POST"])
@admin_required
def register():
    if request.method == "POST":
        username = request.form.get("username", "").strip()
        password = request.form.get("password", "")
        role = "user"
        nickname = request.form.get("nickname", "").strip()

        if not username or not password:
            flash("用户名和密码不能为空", "error")
            return render_template("register.html")

        db = get_db()
        existing = db.execute("SELECT id FROM users WHERE username = ?", (username,)).fetchone()
        if existing:
            flash("用户名已存在", "error")
            return render_template("register.html")

        db.execute(
            "INSERT INTO users (username, password, role, nickname) VALUES (?, ?, ?, ?)",
            (username, generate_password_hash(password), role, nickname or username)
        )
        db.commit()
        flash(f"用户 {username} 创建成功！", "success")
        return redirect(url_for("admin_panel"))

    return render_template("register.html")


@app.route("/logout")
@login_required
def logout():
    logout_user()
    return redirect("https://yousa.ccwu.cc/", code=303)


# ── Admin Routes ────────────────────────────────────────────────────

@app.route("/admin")
@admin_required
def admin_panel():
    db = get_db()
    try:
        users = db.execute("""
            SELECT id, username, role, nickname, created_at,
                   last_seen_at, last_seen_source, last_app_seen_at, last_web_seen_at
            FROM users
            ORDER BY id
        """).fetchall()
    except sqlite3.OperationalError:
        # Fallback for older databases without activity columns
        users = db.execute("""
            SELECT id, username, role, nickname, created_at,
                   NULL as last_seen_at, NULL as last_seen_source,
                   NULL as last_app_seen_at, NULL as last_web_seen_at
            FROM users
            ORDER BY id
        """).fetchall()
    return render_template("admin.html", users=users)


@app.route("/admin/user/<int:user_id>/delete", methods=["POST"])
@admin_required
def delete_user(user_id):
    if user_id == current_user.id:
        flash("不能删除自己", "error")
        return redirect(url_for("admin_panel"))
    db = get_db()
    db.execute("DELETE FROM users WHERE id = ?", (user_id,))
    db.commit()
    flash("用户已删除", "success")
    return redirect(url_for("admin_panel"))


@app.route("/admin/user/<int:user_id>/role", methods=["POST"])
@admin_required
def change_role(user_id):
    if user_id == current_user.id:
        flash("不能修改自己的角色", "error")
        return redirect(url_for("admin_panel"))
    new_role = request.form.get("role", "user")
    if new_role not in ("admin", "user"):
        abort(400)
    db = get_db()
    db.execute("UPDATE users SET role = ? WHERE id = ?", (new_role, user_id))
    db.commit()
    flash("角色已更新", "success")
    return redirect(url_for("admin_panel"))


@app.route("/admin/user/<int:user_id>/nickname", methods=["POST"])
@admin_required
def admin_update_nickname(user_id):
    """Admin updates any user's nickname."""
    nickname = request.form.get("nickname", "").strip()
    if not nickname:
        flash("昵称不能为空", "error")
        return redirect(url_for("admin_panel"))
    db = get_db()
    db.execute("UPDATE users SET nickname = ? WHERE id = ?", (nickname, user_id))
    db.commit()
    flash(f"用户 #{user_id} 昵称已更新为「{nickname}」", "success")
    return redirect(url_for("admin_panel"))


@app.route("/admin/user/<int:user_id>/password", methods=["POST"])
@admin_required
def admin_reset_password(user_id):
    """Admin resets any user's password."""
    new_password = request.form.get("new_password", "")
    if len(new_password) < 4:
        flash("密码至少 4 位", "error")
        return redirect(url_for("admin_panel"))
    db = get_db()
    db.execute("UPDATE users SET password = ? WHERE id = ?",
               (generate_password_hash(new_password), user_id))
    db.commit()
    flash(f"用户 #{user_id} 密码已重置", "success")
    return redirect(url_for("admin_panel"))


@app.route("/profile")
@login_required
def profile():
    return render_template("profile.html")


@app.route("/profile/nickname", methods=["POST"])
@login_required
def update_nickname():
    nickname = request.form.get("nickname", "").strip()
    if not nickname:
        flash("昵称不能为空", "error")
        return redirect(url_for("profile"))
    db = get_db()
    db.execute("UPDATE users SET nickname = ? WHERE id = ?",
               (nickname, current_user.id))
    db.commit()
    flash(f"昵称已更新为「{nickname}」", "success")
    return redirect(url_for("profile"))


@app.route("/admin/password", methods=["GET", "POST"])
@login_required
def change_password():
    if request.method == "POST":
        old_pw = request.form.get("old_password", "")
        new_pw = request.form.get("new_password", "")
        confirm_pw = request.form.get("confirm_password", "")

        if not check_password_hash(current_user.password, old_pw):
            flash("当前密码错误", "error")
            return render_template("password.html")

        if len(new_pw) < 4:
            flash("新密码至少 4 位", "error")
            return render_template("password.html")

        if new_pw != confirm_pw:
            flash("两次输入的新密码不一致", "error")
            return render_template("password.html")

        db = get_db()
        db.execute("UPDATE users SET password = ? WHERE id = ?",
                   (generate_password_hash(new_pw), current_user.id))
        db.commit()
        flash("密码已修改成功！", "success")
        return redirect(url_for("admin_panel"))

    return render_template("password.html")


# ── Regular Routes ──────────────────────────────────────────────────

@app.route("/")
def home():
    latest_posts, latest_wiki, latest_messages, site_counts = get_home_activity()
    return render_template("index.html",
                           latest_posts=latest_posts,
                           latest_wiki=latest_wiki,
                           latest_messages=latest_messages,
                           site_counts=site_counts)


@app.route("/app")
@app.route("/download")
def app_download():
    return render_template("app_download.html")



@app.route("/about")
def about():
    return render_template("about.html")


@app.route("/search")
def site_search():
    query = request.args.get("q", "").strip()
    results = search_site(query, include_files=current_user.is_authenticated)
    return render_template("search.html", query=query, results=results)


@app.route("/wiki/")
@app.route("/wiki/<path:subpath>")
def wiki_view(subpath=None):
    if subpath is None:
        tree = get_wiki_tree()
        return render_template("wiki.html", tree=tree, page=None)

    file_path = WIKI_DIR / subpath
    if not file_path.suffix:
        file_path_md = file_path.with_suffix(".md")
        if file_path_md.exists():
            file_path = file_path_md

    if file_path.is_dir():
        tree = get_wiki_tree(file_path)
        return render_template("wiki.html", tree=tree, page=None,
                               current_dir=subpath)

    if file_path.suffix.lower() in (".md", ".markdown"):
        if not file_path.exists():
            abort(404)
        try:
            raw = file_path.read_text(encoding="utf-8")
        except Exception:
            abort(500)
        html_content = render_markdown(raw)
        tree = get_wiki_tree(file_path.parent)
        return render_template("wiki.html", tree=tree,
                               page_content=html_content,
                               page_title=file_path.stem,
                               current_dir=str(file_path.parent.relative_to(WIKI_DIR)).replace("\\", "/") if file_path.parent != WIKI_DIR else "",
                               current_file=subpath.replace("\\", "/"))

    abort(404)


@app.route("/wiki/search")
def wiki_search():
    query = request.args.get("q", "").strip()
    results = search_wiki(query)
    return jsonify({"results": results})


@app.route("/chat")
def chat():
    return render_template("chat.html")


@app.route("/cloud/")
@app.route("/cloud/<path:subpath>")
@login_required
def cloud_drive(subpath=None):
    """Cloud drive — file manager with search and filter."""
    base = FILE_DIR.resolve()
    if subpath:
        target = (base / subpath).resolve()
    else:
        target = base

    if not str(target).startswith(str(base)):
        abort(403)
    if not target.exists():
        return render_template("cloud.html", dirs=[], files=[],
                               current_path="", breadcrumbs=[],
                               total_size="0 B")

    entries = []
    total_bytes = 0
    try:
        for entry in sorted(target.iterdir(), key=lambda p: (p.is_file(), p.name.lower())):
            if entry.name.startswith("."):
                continue
            rel = str(entry.relative_to(base)).replace("\\", "/")
            stat = entry.stat()
            total_bytes += stat.st_size if entry.is_file() else 0
            modified = datetime.fromtimestamp(stat.st_mtime).strftime("%Y-%m-%d %H:%M")
            ext = entry.suffix.lower() if entry.is_file() else ""
            icon = FILE_ICONS.get(ext, "📄")
            entries.append({
                "name": entry.name,
                "path": rel,
                "is_dir": entry.is_dir(),
                "size": stat.st_size,
                "size_hr": format_size(stat.st_size),
                "modified": modified,
                "icon": icon,
            })
    except PermissionError:
        abort(403)

    dirs = [e for e in entries if e["is_dir"]]
    files = [e for e in entries if not e["is_dir"]]

    # Load upload metadata from DB
    db = get_db()
    meta_rows = db.execute(
        "SELECT m.filepath, m.title, m.notes, m.created_at, u.nickname AS uploaded_by_name "
        "FROM upload_meta m "
        "LEFT JOIN users u ON m.uploaded_by = u.id "
        "WHERE m.type='cloud' ORDER BY m.created_at DESC"
    ).fetchall()
    upload_meta = {row["filepath"]: {
        "title": row["title"], "notes": row["notes"],
        "uploaded_by": row["uploaded_by_name"] or "未知",
        "created_at": row["created_at"]
    } for row in meta_rows}

    current_path = subpath.replace("\\", "/") if subpath else ""
    parts = current_path.split("/") if current_path else []
    breadcrumbs = []
    accum = ""
    for p in parts:
        accum = f"{accum}/{p}" if accum else p
        breadcrumbs.append({"name": p, "path": accum})

    return render_template("cloud.html", dirs=dirs, files=files,
                           current_path=current_path, breadcrumbs=breadcrumbs,
                           total_size=format_size(total_bytes),
                           is_admin=current_user.is_admin,
                           upload_meta=upload_meta)


# ── Cloud Drive: Upload / Delete / Mkdir ────────────────────────────────

ALLOWED_UPLOAD_EXTS = {
    ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".svg",
    ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".csv",
    ".txt", ".md", ".json", ".xml", ".yaml", ".yml",
    ".zip", ".rar", ".7z", ".tar", ".gz",
    ".mp3", ".mp4",
    ".py", ".js", ".html", ".css", ".sh", ".bat",
    ".apk",
}


@app.route("/cloud/upload", methods=["POST"])
@login_required
@admin_required
def cloud_upload():
    """Upload files to the cloud drive."""
    subpath = request.form.get("path", "").strip()
    base = FILE_DIR.resolve()
    target = (base / subpath).resolve() if subpath else base

    if not str(target).startswith(str(base)):
        return jsonify({"error": "路径不允许"}), 403
    if not target.exists():
        return jsonify({"error": "目录不存在"}), 404
    if not target.is_dir():
        return jsonify({"error": "目标不是目录"}), 400

    if "file" not in request.files:
        return jsonify({"error": "未选择文件"}), 400

    uploaded = request.files.getlist("file")
    title = request.form.get("title", "").strip()
    notes = request.form.get("notes", "").strip()
    results = []
    for f in uploaded:
        if not f.filename:
            continue
        ext = Path(f.filename).suffix.lower()
        if ext and ext not in ALLOWED_UPLOAD_EXTS:
            results.append({"name": f.filename, "status": "拒绝", "reason": f"不允许的后缀 {ext}"})
            continue
        dest = target / f.filename
        try:
            f.save(str(dest))
            # Save metadata
            rel = str(dest.relative_to(base)).replace("\\", "/")
            if title or notes:
                db = get_db()
                db.execute(
                    "INSERT INTO upload_meta (type, filepath, title, notes, uploaded_by) VALUES (?, ?, ?, ?, ?)",
                    ("cloud", rel, title, notes, current_user.id)
                )
                db.commit()
            results.append({"name": f.filename, "status": "成功", "size": format_size(dest.stat().st_size)})
        except Exception as e:
            results.append({"name": f.filename, "status": "失败", "reason": str(e)})

    return jsonify({"results": results})


@app.route("/cloud/delete", methods=["POST"])
@login_required
@admin_required
def cloud_delete():
    """Delete a file or empty directory from cloud drive."""
    path = request.form.get("path", "").strip()
    if not path:
        return jsonify({"error": "参数缺失"}), 400

    base = FILE_DIR.resolve()
    target = (base / path).resolve()

    if not str(target).startswith(str(base)):
        return jsonify({"error": "路径不允许"}), 403
    if not target.exists():
        return jsonify({"error": "文件不存在"}), 404

    try:
        if target.is_dir():
            # Only delete empty directories
            target.rmdir()
        else:
            target.unlink()
        return jsonify({"status": "ok"})
    except OSError as e:
        return jsonify({"error": str(e)}), 500


@app.route("/cloud/mkdir", methods=["POST"])
@login_required
@admin_required
def cloud_mkdir():
    """Create a new directory in the cloud drive."""
    subpath = request.form.get("path", "").strip()
    name = request.form.get("name", "").strip()
    if not name:
        return jsonify({"error": "目录名不能为空"}), 400

    base = FILE_DIR.resolve()
    parent = (base / subpath).resolve() if subpath else base

    if not str(parent).startswith(str(base)):
        return jsonify({"error": "路径不允许"}), 403

    new_dir = parent / name
    try:
        new_dir.mkdir(exist_ok=True)
        return jsonify({"status": "ok", "name": name})
    except OSError as e:
        return jsonify({"error": str(e)}), 500


@app.route("/cloud/preview/<path:filepath>")
@login_required
def cloud_preview(filepath):
    """Preview a text/markdown file inline."""
    base = FILE_DIR.resolve()
    target = (base / filepath).resolve()

    if not str(target).startswith(str(base)):
        abort(403)
    if not target.exists() or not target.is_file():
        abort(404)

    ext = target.suffix.lower()

    if ext in (".md", ".txt", ".py", ".js", ".html", ".css", ".json",
               ".xml", ".yaml", ".yml", ".sh", ".bat", ".csv", ".log"):
        try:
            raw = target.read_text(encoding="utf-8")
        except Exception:
            try:
                raw = target.read_text(encoding="gbk")
            except Exception:
                raw = "[无法解码此文件]"

        html_content = ""
        if ext == ".md":
            html_content = render_markdown(raw)
        else:
            import html as _html
            html_content = f"<pre style='background:rgba(30,36,44,.88);color:#e4e9ef;padding:1.2rem;border-radius:var(--radius-sm);overflow-x:auto;font-size:0.85rem;line-height:1.5'><code>{_html.escape(raw)}</code></pre>"

        return render_template("cloud_preview.html",
                               filename=target.name,
                               filepath=filepath,
                               html_content=html_content,
                               ext=ext)

    # For images, redirect to gallery
    if ext in (".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".svg"):
        return redirect(url_for("gallery", subpath=filepath))

    # For other files, trigger download
    return send_from_directory(target.parent, target.name,
                               as_attachment=True)


@app.route("/gallery/")
@app.route("/gallery/<path:subpath>")
def gallery(subpath=None):
    """Gallery — browse images from the 图片 folder."""
    base = BASE_DIR
    if subpath:
        target = (base / subpath).resolve()
    else:
        target = GALLERY_UPLOAD_DIR

    if not str(target).startswith(str(base)):
        abort(403)
    if not target.exists():
        # Directory doesn't exist — show empty gallery instead of 404
        return render_template("gallery.html", images=[], dirs=[],
                               current_path="", breadcrumbs=[])

    if target.is_file():
        # Serve the image (only image types)
        ext = target.suffix.lower()
        if ext in (".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".bmp"):
            return send_from_directory(target.parent, target.name)
        abort(404)

    # Collect image files recursively from this directory
    image_exts = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".bmp"}
    images = []
    dirs = []
    # Load upload metadata from DB (include visibility)
    db = get_db()
    meta_rows = db.execute(
        "SELECT m.filepath, m.title, m.notes, m.visibility, m.created_at, u.nickname AS uploaded_by_name "
        "FROM upload_meta m "
        "LEFT JOIN users u ON m.uploaded_by = u.id "
        "WHERE m.type='gallery' ORDER BY m.created_at DESC"
    ).fetchall()
    metadata = {row["filepath"]: {
        "title": row["title"], "notes": row["notes"],
        "visibility": row["visibility"] or "public",
        "uploaded_by": row["uploaded_by_name"] or "未知",
        "created_at": row["created_at"]
    } for row in meta_rows}
    try:
        for entry in sorted(target.iterdir(), key=lambda p: (p.is_file(), p.name.lower())):
            if entry.name.startswith("."):
                continue
            rel = str(entry.relative_to(base)).replace("\\", "/")
            if entry.is_dir():
                dirs.append({"name": entry.name, "path": rel})
            elif entry.suffix.lower() in image_exts:
                # Filter by visibility
                meta = metadata.get(rel, {})
                vis = meta.get("visibility", "public")
                if vis == "private" and not (current_user.is_authenticated and current_user.is_admin):
                    continue
                stat = entry.stat()
                images.append({
                    "name": entry.name,
                    "path": rel,
                    "size_hr": format_size(stat.st_size),
                    "modified": datetime.fromtimestamp(stat.st_mtime).strftime("%Y-%m-%d %H:%M"),
                })
    except PermissionError:
        abort(403)

    current_path = subpath.replace("\\", "/") if subpath else ""
    parts = current_path.split("/") if current_path else []
    breadcrumbs = []
    accum = ""
    for p in parts:
        accum = f"{accum}/{p}" if accum else p
        breadcrumbs.append({"name": p, "path": accum})

    return render_template("gallery.html", images=images, dirs=dirs,
                           current_path=current_path, breadcrumbs=breadcrumbs,
                           upload_meta=metadata,
                           is_admin=current_user.is_authenticated and current_user.is_admin)


# ── Gallery Upload ─────────────────────────────────────────────────

GALLERY_UPLOAD_DIR = BASE_DIR / "图片"
GALLERY_UPLOAD_DIR.mkdir(exist_ok=True)


@app.route("/gallery/upload", methods=["POST"])
@login_required
@admin_required
def gallery_upload():
    """Upload images to gallery with title and notes."""
    if "file" not in request.files:
        return jsonify({"error": "未选择文件"}), 400

    file = request.files["file"]
    if not file.filename:
        return jsonify({"error": "文件名为空"}), 400

    title = request.form.get("title", "").strip()
    notes = request.form.get("notes", "").strip()
    visibility = request.form.get("visibility", "public")
    ext = Path(file.filename).suffix.lower()

    if ext not in (".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".bmp"):
        return jsonify({"error": f"不支持的图片格式: {ext}"}), 400

    # Save file with title-based name if provided, sanitized
    safe_name = file.filename
    if title:
        safe_name = re.sub(r'[^\w\u4e00-\u9fff\-_\. ]', '', title)[:50] + ext
    dest = GALLERY_UPLOAD_DIR / safe_name

    # Avoid overwriting
    counter = 1
    while dest.exists():
        stem = dest.stem
        dest = GALLERY_UPLOAD_DIR / f"{stem}_{counter}{ext}"
        counter += 1

    try:
        file.save(str(dest))
        rel = str(dest.relative_to(BASE_DIR)).replace("\\", "/")
        db = get_db()
        db.execute(
            "INSERT INTO upload_meta (type, filepath, title, notes, visibility, uploaded_by) VALUES (?, ?, ?, ?, ?, ?)",
            ("gallery", rel, title or Path(dest).stem, notes, visibility, current_user.id)
        )
        db.commit()
        return jsonify({
            "status": "ok",
            "name": dest.name,
            "path": rel,
            "title": title or dest.stem,
        })
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/chat/api", methods=["POST"])
def chat_api():
    data = request.get_json()
    if not data or "message" not in data:
        return jsonify({"error": "message is required"}), 400

    user_msg = data["message"]
    history = data.get("history", [])  # 前端传来的对话历史

    import urllib.request
    API_URL = "https://api.longcat.chat/openai/v1/chat/completions"
    # 密钥：优先用环境变量 LONGCAT_API_KEY，其次读取 .apikey 文件
    _keyfile_path = BASE_DIR / ".apikey"
    API_KEY = os.environ.get("LONGCAT_API_KEY") or (
        _keyfile_path.read_text().strip() if _keyfile_path.exists() else ""
    )
    if not API_KEY:
        return jsonify({"reply": "🔑 小狗的 API 密钥还没配置好，请管理员在服务器上设置 LONGCAT_API_KEY 环境变量或创建 .apikey 文件。 汪"})
    MODEL = "LongCat-2.0-Preview"

    # Load soul.md system prompt
    soul_path = BASE_DIR / "soul.md"
    base_prompt = soul_path.read_text(encoding="utf-8") if soul_path.exists() else "你是一个友好的助手。"

    # ── 上下文：添加对话历史 ──
    messages = [{"role": "system", "content": base_prompt}]
    for h in history[-20:]:  # 最多保留20轮历史
        if h.get("role") in ("user", "assistant") and h.get("content"):
            messages.append({"role": h["role"], "content": h["content"]})
    messages.append({"role": "user", "content": user_msg})

    # ── 文件感知：仅管理员可用，禁止泄露给普通用户和游客 ──
    file_context = ""
    if current_user.is_authenticated and current_user.is_admin:
        file_context = build_file_context(user_msg)
    if file_context:
        messages.append({
            "role": "system",
            "content": file_context
        })

    try:
        payload = json.dumps({
            "model": MODEL,
            "messages": messages,
            "stream": False,
            "max_tokens": 1024,
        }).encode("utf-8")

        req = urllib.request.Request(
            API_URL,
            data=payload,
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {API_KEY}",
            },
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=60) as resp:
            result = json.loads(resp.read().decode("utf-8"))
        reply = result["choices"][0]["message"]["content"]

        # 欢欢的尾巴
        import re
        if not re.search(r"汪\s*$", reply):
            reply = reply.rstrip() + " 汪"

        return jsonify({"reply": reply})
    except Exception as e:
        return jsonify({"reply": f"❌ API 调用失败: {str(e)}"})


def build_file_context(user_msg):
    """If user mentions files/downloads, build a context block with matching files."""
    file_keywords = ["下载", "文件", "资料", "作业", "复习", "笔记", "文档",
                     "download", "file", "doc", "pdf", ".md", ".docx", "纳米"]
    msg_lower = user_msg.lower()

    # Only add file context if user seems to be asking about files
    if not any(kw.lower() in msg_lower for kw in file_keywords):
        return ""

    import re as _re

    # Extract possible filenames from the message
    patterns = []
    # Grab words/phrases between quotes or standalone Chinese/English
    quoted = _re.findall(r'"([^"]+)"', user_msg) + _re.findall(r"'([^']+)'", user_msg)
    patterns.extend(quoted)
    # Also use the whole message as a fuzzy pattern
    patterns.append(user_msg.strip())

    matched = set()
    max_results = 15
    try:
        for root, dirs, files in os.walk(str(FILE_DIR.resolve())):
            # Skip hidden dirs
            dirs[:] = [d for d in dirs if not d.startswith(".")]
            for fname in files:
                if fname.startswith("."):
                    continue
                full = Path(root) / fname
                rel = str(full.relative_to(FILE_DIR.resolve())).replace("\\", "/")
                size = full.stat().st_size
                size_hr = format_size(size)
                # Match against patterns
                fname_lower = fname.lower() + " " + rel.lower()
                for pat in patterns:
                    if pat.lower() in fname_lower or any(
                        p.lower() in fname_lower for p in pat.lower().split()
                    ):
                        matched.add(f"{rel} ({size_hr})")
                        break
                if len(matched) >= max_results:
                    break
            if len(matched) >= max_results:
                break
    except Exception:
        pass

    if not matched:
        # Just list top-level files as context
        try:
            items = sorted(FILE_DIR.iterdir(), key=lambda x: x.name.lower())
            count = 0
            for item in items:
                if item.name.startswith("."):
                    continue
                if item.is_file():
                    rel = str(item.relative_to(FILE_DIR.resolve())).replace("\\", "/")
                    matched.add(f"{rel} ({format_size(item.stat().st_size)})")
                    count += 1
                    if count >= 10:
                        break
        except Exception:
            pass

    if matched:
        lines = "\n".join(f"  - {m}" for m in sorted(matched))
        return (
            f"\n\n## 文件服务\n"
            f"用户提到了文件相关需求。以下是匹配到的文件，请直接告诉用户：\n"
            f"{lines}\n\n"
            f"【重要】直接给出下载链接，格式如下（不要问用户更多信息，直接给链接）：\n"
            f"`https://yousa.ccwu.cc/files/文件相对路径`\n"
        )
    return ""


@app.route("/files/")
@app.route("/files/<path:subpath>")
@login_required
def file_browser(subpath=None):
    base = FILE_DIR.resolve()
    if subpath:
        target = (base / subpath).resolve()
    else:
        target = base

    if not str(target).startswith(str(base)):
        abort(403)
    if not target.exists():
        abort(404)

    if target.is_file():
        return send_from_directory(
            target.parent,
            target.name,
            as_attachment=True,
            download_name=target.name,
            conditional=True,
        )

    entries = []
    try:
        for entry in sorted(target.iterdir(), key=lambda p: (p.is_file(), p.name.lower())):
            if entry.name.startswith("."):
                continue
            rel = str(entry.relative_to(base)).replace("\\", "/")
            stat = entry.stat()
            size = stat.st_size
            modified = datetime.fromtimestamp(stat.st_mtime)
            entries.append({
                "name": entry.name,
                "path": rel,
                "is_dir": entry.is_dir(),
                "size": size,
                "size_hr": format_size(size),
                "modified": modified.strftime("%Y-%m-%d %H:%M"),
            })
    except PermissionError:
        abort(403)

    current_path = ""
    if subpath:
        current_path = subpath.replace("\\", "/")

    parts = current_path.split("/") if current_path else []
    breadcrumbs = []
    accum = ""
    for p in parts:
        accum = f"{accum}/{p}" if accum else p
        breadcrumbs.append({"name": p, "path": accum})

    return render_template("files.html", entries=entries,
                           current_path=current_path,
                           breadcrumbs=breadcrumbs)


def format_size(size):
    for unit in ("B", "KB", "MB", "GB"):
        if size < 1024:
            return f"{size:.1f} {unit}"
        size /= 1024
    return f"{size:.1f} TB"

FILE_ICONS = {
    ".py": "🐍", ".js": "🟨", ".html": "🌐", ".css": "🎨",
    ".json": "📋", ".xml": "📋", ".yaml": "📋", ".yml": "📋",
    ".md": "📝", ".txt": "📄", ".doc": "📘", ".docx": "📘",
    ".xls": "📊", ".xlsx": "📊", ".csv": "📊",
    ".pdf": "📕", ".ppt": "📙", ".pptx": "📙",
    ".png": "🖼️", ".jpg": "🖼️", ".jpeg": "🖼️", ".gif": "🖼️",
    ".webp": "🖼️", ".svg": "🖼️", ".bmp": "🖼️",
    ".mp3": "🎵", ".wav": "🎵", ".flac": "🎵",
    ".mp4": "🎬", ".avi": "🎬", ".mkv": "🎬",
    ".zip": "📦", ".rar": "📦", ".7z": "📦", ".tar": "📦", ".gz": "📦",
    ".apk": "📱", ".exe": "⚙️", ".msi": "⚙️",
    ".sh": "💻", ".bat": "💻", ".ps1": "💻",
}


# ── Blog Routes ──────────────────────────────────────────────────────

@app.route("/blog")
def blog_list():
    db = get_db()
    posts = db.execute("""
        SELECT p.*, u.nickname as author_name FROM posts p
        LEFT JOIN users u ON p.author_id = u.id
        ORDER BY p.created_at DESC
    """).fetchall()
    return render_template("blog.html", posts=posts)


@app.route("/blog/write", methods=["GET", "POST"])
@login_required
def blog_write():
    if request.method == "POST":
        title = request.form.get("title", "").strip()
        content = request.form.get("content", "").strip()
        if not title or not content:
            flash("标题和内容不能为空", "error")
            return render_template("blog_write.html")
        db = get_db()
        db.execute("INSERT INTO posts (title, content, author_id) VALUES (?, ?, ?)",
                   (title, content, current_user.id))
        db.commit()
        flash("日志发布成功！", "success")
        return redirect(url_for("blog_list"))
    return render_template("blog_write.html")


@app.route("/blog/<int:post_id>")
def blog_post(post_id):
    db = get_db()
    post = db.execute("""
        SELECT p.*, u.nickname as author_name FROM posts p
        LEFT JOIN users u ON p.author_id = u.id
        WHERE p.id = ?
    """, (post_id,)).fetchone()
    if not post:
        abort(404)
    html_content = render_markdown(post["content"])
    # Get comments for this post
    comments = db.execute("""
        SELECT id, author_name, content, created_at FROM messages
        WHERE post_id = ?
        ORDER BY created_at ASC
    """, (post_id,)).fetchall()
    return render_template("blog_post.html", post=post, html_content=html_content,
                           comments=comments)


@app.route("/blog/<int:post_id>/comment", methods=["POST"])
def blog_post_comment(post_id):
    db = get_db()
    post = db.execute("SELECT id FROM posts WHERE id = ?", (post_id,)).fetchone()
    if not post:
        abort(404)
    content = request.form.get("content", "").strip()
    if not content:
        flash("评论不能为空", "error")
        return redirect(url_for("blog_post", post_id=post_id))

    if current_user.is_authenticated:
        author_name = current_user.nickname
        user_id = current_user.id
    else:
        author_name = request.form.get("name", "游客").strip() or "游客"
        user_id = None

    db.execute(
        "INSERT INTO messages (author_name, content, user_id, post_id) VALUES (?, ?, ?, ?)",
        (author_name, content, user_id, post_id)
    )
    db.commit()
    flash("评论成功！", "success")
    return redirect(url_for("blog_post", post_id=post_id))


@app.route("/blog/<int:post_id>/delete", methods=["POST"])
@login_required
def blog_delete(post_id):
    db = get_db()
    post = db.execute("SELECT * FROM posts WHERE id = ?", (post_id,)).fetchone()
    if not post:
        abort(404)
    if not current_user.is_admin and post["author_id"] != current_user.id:
        abort(403)
    db.execute("DELETE FROM posts WHERE id = ?", (post_id,))
    db.commit()
    flash("日志已删除", "success")
    return redirect(url_for("blog_list"))


# ── Generic Comment API ─────────────────────────────────────────────

@app.route("/api/comment/<target_type>/<target_id>", methods=["GET", "POST"])
def api_comment(target_type, target_id):
    """Generic comment endpoint for any target (gallery, cloud, guestbook, blog)."""
    if request.method == "POST":
        content = request.form.get("content", "").strip()
        if not content:
            return jsonify({"error": "评论不能为空"}), 400

        if current_user.is_authenticated:
            author_name = current_user.nickname
            user_id = current_user.id
        else:
            author_name = request.form.get("name", "游客").strip() or "游客"
            user_id = None

        db = get_db()
        db.execute(
            "INSERT INTO messages (author_name, content, user_id, target_type, target_id) VALUES (?, ?, ?, ?, ?)",
            (author_name, content, user_id, target_type, target_id)
        )
        db.commit()
        return jsonify({"status": "ok"})

    # GET — return comments as JSON
    db = get_db()
    rows = db.execute(
        "SELECT id, author_name, content, created_at FROM messages "
        "WHERE target_type=? AND target_id=? ORDER BY created_at ASC",
        (target_type, target_id)
    ).fetchall()
    return jsonify([{
        "id": r["id"],
        "author": r["author_name"],
        "content": r["content"],
        "time": r["created_at"][:16],
    } for r in rows])


# ── Guestbook Routes ─────────────────────────────────────────────────

@app.route("/guestbook", methods=["GET", "POST"])
def guestbook():
    if request.method == "POST":
        content = request.form.get("content", "").strip()
        if not content:
            flash("留言不能为空", "error")
            return redirect(url_for("guestbook"))

        if current_user.is_authenticated:
            author_name = current_user.nickname
            user_id = current_user.id
        else:
            author_name = request.form.get("name", "游客").strip()
            if not author_name:
                author_name = "游客"
            user_id = None

        db = get_db()
        db.execute(
            "INSERT INTO messages (author_name, content, user_id) VALUES (?, ?, ?)",
            (author_name, content, user_id)
        )
        db.commit()
        flash("留言成功！", "success")
        return redirect(url_for("guestbook"))

    db = get_db()
    msgs = db.execute("SELECT * FROM messages ORDER BY created_at DESC LIMIT 100").fetchall()
    return render_template("guestbook.html", messages=msgs)


@app.route("/guestbook/<int:msg_id>/delete", methods=["POST"])
@login_required
def guestbook_delete(msg_id):
    db = get_db()
    msg = db.execute("SELECT * FROM messages WHERE id = ?", (msg_id,)).fetchone()
    if not msg:
        abort(404)
    if not current_user.is_admin and msg["user_id"] != current_user.id:
        abort(403)
    db.execute("DELETE FROM messages WHERE id = ?", (msg_id,))
    db.commit()
    flash("留言已删除", "success")
    return redirect(url_for("guestbook"))


# ── Error Pages ──────────────────────────────────────────────────────

@app.errorhandler(403)
def forbidden(e):
    return render_template("error.html", code=403,
                           message="你没有权限访问此页面"), 403

@app.errorhandler(404)
def not_found(e):
    return render_template("error.html", code=404,
                           message="页面未找到"), 404


# ── Entrypoint ──────────────────────────────────────────────────────

if __name__ == "__main__":
    import socket
    import urllib.request

    init_db()

    # Create sample wiki if empty
    wiki_md = WIKI_DIR / "欢迎.md"
    if not wiki_md.exists() and not any(WIKI_DIR.iterdir()):
        wiki_md.write_text(
            "# 🧪 欢迎来到我的知识库\n\n"
            "这里是环境纳米材料的学习笔记。\n\n"
            "## 目录\n\n"
            "- 课程笔记\n"
            "- 复习资料\n"
            "- 作业记录\n",
            encoding="utf-8"
        )
        (WIKI_DIR / "环境纳米材料概论.md").write_text(
            "# 环境纳米材料概论\n\n"
            "## 什么是环境纳米材料\n\n"
            "环境纳米材料是指尺寸在 1-100 nm 范围内，"
            "在环境科学领域具有应用价值的材料。\n\n"
            "## 主要研究方向\n\n"
            "- **纳米催化**：用于污染物降解\n"
            "- **纳米吸附**：重金属/有机物去除\n"
            "- **纳米传感**：环境污染物检测\n"
            "- **纳米膜**：水处理/气体分离\n",
            encoding="utf-8"
        )

    port = int(os.getenv("PORT", 5000))

    host = socket.gethostname()
    local_ip = socket.gethostbyname(host)

    print(f"\n  🚀 yousa.dev 已启动!")
    print(f"  ─────────────────────────────")
    print(f"  本地访问:  http://127.0.0.1:{port}")
    print(f"  管理员:    admin / admin123")
    print(f"  知识库:    http://127.0.0.1:{port}/wiki/")
    print(f"  AI 聊天:   http://127.0.0.1:{port}/chat")
    print(f"  文件服务:  http://127.0.0.1:{port}/files/\n")

    debug = os.getenv("FLASK_DEBUG", "0") == "1"
    app.run(host="0.0.0.0", port=port, debug=debug, use_reloader=False)

# Ensure tables exist when imported by WSGI
init_db()
