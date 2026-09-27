"""Temporary identities confined to the separate forum test service."""
import hashlib
import secrets
import uuid

from fastapi import HTTPException

from .auth import Identity
from .store import now_ms, rate_limit


PREFIX = "tt_test_"


class ForumAuth:
    def __init__(self, store, team_auth, enabled=False):
        self.store = store
        self.team_auth = team_auth
        self.enabled = enabled

    def verify(self, authorization):
        token = authorization[7:] if authorization and authorization.startswith("Bearer ") else ""
        if not token.startswith(PREFIX):
            return self.team_auth.verify(authorization)
        if not self.enabled or len(token) > 8192:
            raise HTTPException(401, "Test sessions are unavailable here")
        with self.store.connect() as db:
            row = db.execute("SELECT * FROM test_sessions WHERE token_hash=?", (self.digest(token),)).fetchone()
        if row is None or row["expires"] <= now_ms():
            raise HTTPException(401, "Test session expired; enter test mode again")
        return Identity(row["uid"], row["name"])

    @staticmethod
    def digest(token):
        return hashlib.sha256(token.encode()).hexdigest()

    def issue(self, client_ip):
        if not self.enabled:
            raise HTTPException(404, "Test mode is disabled")
        token = PREFIX + secrets.token_urlsafe(32)
        uid = "test_" + uuid.uuid4().hex
        name = "Tester " + uid[-6:]
        expires = now_ms() + 24 * 60 * 60 * 1000
        with self.store.connect(write=True) as db:
            rate_limit(db, "ip:" + client_ip, "test-session", 10)
            db.execute("DELETE FROM test_sessions WHERE expires<=?", (now_ms(),))
            if db.execute("SELECT COUNT(*) FROM test_sessions").fetchone()[0] >= 1000:
                raise HTTPException(429, "Test area is busy; try later")
            db.execute("INSERT INTO test_sessions VALUES(?,?,?,?)", (self.digest(token), uid, name, expires))
        return {"token": token, "accountId": uid, "displayName": name, "expiresAtEpochMillis": expires}

    def revoke(self, authorization):
        if not self.enabled:
            raise HTTPException(404, "Test mode is disabled")
        token = authorization[7:] if authorization and authorization.startswith("Bearer ") else ""
        if not token.startswith(PREFIX):
            raise HTTPException(401, "Test session required")
        with self.store.connect(write=True) as db:
            db.execute("DELETE FROM test_sessions WHERE token_hash=?", (self.digest(token),))
