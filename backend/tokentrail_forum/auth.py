from dataclasses import dataclass
from urllib.parse import urlsplit

from fastapi import HTTPException
import httpx


@dataclass(frozen=True)
class Identity:
    uid: str
    name: str


def field(document, path):
    value = document
    for key in path.split("."):
        if not isinstance(value, dict):
            return None
        value = value.get(key)
    return value


class TeamAuth:
    """Verify the existing team's Bearer token with its current-user endpoint.

    No local users, passwords, token signing keys, or development auth bypass.
    HTTP is permitted only for a verifier on this host's loopback interface.
    """
    def __init__(self, url, uid_field="id", name_field="nickname", transport=None):
        if url:
            parsed = urlsplit(url)
            if parsed.scheme != "https" and not (parsed.scheme == "http" and parsed.hostname in {"127.0.0.1", "localhost", "::1"}):
                raise ValueError("Auth verifier must use HTTPS or loopback HTTP")
            if parsed.username or parsed.password:
                raise ValueError("Auth verifier URL must not contain credentials")
        self.url = url
        self.uid_field = uid_field
        self.name_field = name_field
        self.client = httpx.Client(timeout=5, follow_redirects=False, trust_env=False, transport=transport)

    def verify(self, authorization):
        if not authorization or not authorization.startswith("Bearer ") or not authorization[7:].strip():
            raise HTTPException(401, "Sign in required", headers={"WWW-Authenticate": "Bearer"})
        if len(authorization) > 8192:
            raise HTTPException(401, "Invalid session")
        if not self.url:
            raise HTTPException(503, "Team account service has not been configured")
        try:
            response = self.client.get(self.url, headers={"Authorization": authorization})
        except httpx.HTTPError:
            raise HTTPException(503, "Account service unavailable") from None
        if response.status_code in {401, 403}:
            raise HTTPException(response.status_code, "Session expired or account unavailable")
        if response.status_code != 200:
            raise HTTPException(503, "Account service unavailable")
        try:
            document = response.json()
            uid = field(document, self.uid_field)
            name = field(document, self.name_field)
            if type(uid) not in {str, int} or not str(uid).strip() or len(str(uid)) > 128:
                raise ValueError()
            if not isinstance(name, str) or not name.strip() or len(name) > 256:
                raise ValueError()
            return Identity(str(uid), name.strip())
        except (ValueError, TypeError):
            raise HTTPException(503, "Account service response does not match configured identity fields") from None

    def close(self):
        self.client.close()
