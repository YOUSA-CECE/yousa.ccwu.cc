import importlib
import os
import re
import sqlite3
import tempfile
import unittest
from pathlib import Path


_temp_dir = Path(tempfile.mkdtemp(prefix="yousa-security-tests-"))
os.environ["DATABASE_PATH"] = str(_temp_dir / "users.db")
os.environ["SECRET_KEY"] = "test-secret-key-that-is-long-enough-123456"
os.environ["INITIAL_ADMIN_PASSWORD"] = "test-admin-password-123456"
os.environ["ADMIN_EXEC_API_KEY"] = "admin-exec-test-key-that-is-long-enough"
os.environ["AGENT_PHONE_API_KEY"] = "phone-agent-test-key-that-is-long-enough"
os.environ["AGENT_HERMES_API_KEY"] = "hermes-agent-test-key-that-is-long-enough"
os.environ["SESSION_COOKIE_SECURE"] = "0"

site = importlib.import_module("app")
bridge = importlib.import_module("agent_bridge")
bridge._AGENT_DATA_FILE = str(_temp_dir / "agent_bridge_data.json")
site.app.config.update(TESTING=True)


def _csrf_from(response):
    match = re.search(rb'<meta name="csrf-token" content="([^"]+)"', response.data)
    if not match:
        raise AssertionError("CSRF token missing from page")
    return match.group(1).decode("ascii")


class SecurityTests(unittest.TestCase):
    def setUp(self):
        self.client = site.app.test_client()

    def test_state_changing_request_requires_csrf(self):
        response = self.client.post("/login", data={"username": "x", "password": "x"})
        self.assertEqual(response.status_code, 400)

    def test_valid_csrf_reaches_login_handler(self):
        token = _csrf_from(self.client.get("/login"))
        response = self.client.post(
            "/login",
            data={"username": "missing", "password": "wrong", "csrf_token": token},
        )
        self.assertEqual(response.status_code, 200)

    def test_public_registration_requires_at_least_eight_characters(self):
        token = _csrf_from(self.client.get("/register"))
        too_short = self.client.post(
            "/register",
            data={"username": "short-password-user", "password": "1234567", "csrf_token": token},
        )
        self.assertEqual(too_short.status_code, 200)

        token = _csrf_from(self.client.get("/register"))
        created = self.client.post(
            "/register",
            data={"username": "public-user", "password": "12345678", "csrf_token": token},
        )
        self.assertEqual(created.status_code, 302)
        self.assertTrue(created.headers["Location"].endswith("/login"))

    def test_admin_exec_rejects_missing_management_key(self):
        response = self.client.post("/admin/exec", data={"cmd": "true"})
        self.assertEqual(response.status_code, 403)

    def test_agent_bridge_requires_role_specific_keys(self):
        self.assertEqual(self.client.get("/api/agent/poll").status_code, 403)
        phone_headers = {"X-Agent-Key": os.environ["AGENT_PHONE_API_KEY"]}
        hermes_headers = {"X-Agent-Key": os.environ["AGENT_HERMES_API_KEY"]}
        sent = self.client.post(
            "/api/agent/send",
            json={"from": "test-phone", "text": "hello"},
            headers=phone_headers,
        )
        self.assertEqual(sent.status_code, 200)
        self.assertEqual(
            self.client.get("/api/agent/poll", headers=phone_headers).status_code,
            403,
        )
        polled = self.client.get("/api/agent/poll", headers=hermes_headers)
        self.assertEqual(polled.status_code, 200)
        self.assertEqual(polled.get_json()["count"], 1)

    def test_markdown_escapes_raw_html(self):
        rendered = site.render_markdown('<script>alert("x")</script>')
        self.assertNotIn("<script", rendered.lower())
        self.assertIn("&lt;script&gt;", rendered)

    def test_path_boundary_rejects_prefix_sibling(self):
        base = _temp_dir / "cloud"
        sibling = Path(str(base) + "-private") / "secret.txt"
        self.assertFalse(site._is_within(sibling, base))

    def test_password_change_revokes_existing_session(self):
        token = _csrf_from(self.client.get("/login"))
        logged_in = self.client.post(
            "/login",
            data={
                "username": "admin",
                "password": os.environ["INITIAL_ADMIN_PASSWORD"],
                "csrf_token": token,
            },
        )
        self.assertEqual(logged_in.status_code, 303)
        self.assertEqual(self.client.get("/admin").status_code, 200)

        with sqlite3.connect(site.DB_PATH) as db:
            db.execute(
                "UPDATE users SET password=? WHERE username='admin'",
                (site.generate_password_hash("another-secure-password-123"),),
            )
        self.assertEqual(self.client.get("/admin").status_code, 403)

    def test_all_templates_compile(self):
        for template_name in site.app.jinja_env.list_templates():
            site.app.jinja_env.get_template(template_name)

    def test_legacy_default_admin_password_is_rotated(self):
        legacy_dir = _temp_dir / "legacy"
        legacy_dir.mkdir()
        legacy_db = legacy_dir / "users.db"
        with sqlite3.connect(legacy_db) as db:
            db.execute(
                "CREATE TABLE users (id INTEGER PRIMARY KEY, username TEXT UNIQUE, "
                "password TEXT, role TEXT, nickname TEXT, created_at TEXT)"
            )
            db.execute(
                "INSERT INTO users VALUES (1, 'legacy', ?, 'admin', 'Legacy', '')",
                (site.generate_password_hash("admin123"),),
            )

        old_base, old_db = site.BASE_DIR, site.DB_PATH
        try:
            site.BASE_DIR, site.DB_PATH = legacy_dir, legacy_db
            site.init_db()
        finally:
            site.BASE_DIR, site.DB_PATH = old_base, old_db

        with sqlite3.connect(legacy_db) as db:
            password_hash = db.execute(
                "SELECT password FROM users WHERE username='legacy'"
            ).fetchone()[0]
        self.assertFalse(site.check_password_hash(password_hash, "admin123"))
        self.assertTrue((legacy_dir / ".admin_bootstrap_credentials").is_file())

    def test_direct_nginx_config_does_not_alias_private_cloud(self):
        config = (Path(__file__).parents[1] / "nginx-phone.conf").read_text(encoding="utf-8")
        self.assertNotIn("location /files/", config)
        self.assertNotIn("alias ", config)


if __name__ == "__main__":
    unittest.main()
