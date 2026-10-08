from fastapi.testclient import TestClient
from app import app
client = TestClient(app)

def test_health_is_not_a_watch_connection_claim():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["watch_connected"] is False

def test_missing_token_configuration(monkeypatch):
    monkeypatch.delenv("NEXIS_APP_TOKEN", raising=False)
    response = client.post("/chat", json={"message": "Bugün ne yapmalıyım?"})
    assert response.status_code == 503

def test_unauthorized(monkeypatch):
    monkeypatch.setenv("NEXIS_APP_TOKEN", "demo-secret")
    response = client.post("/chat", headers={"Authorization": "Bearer wrong"}, json={"message": "Merhaba"})
    assert response.status_code == 401

def test_no_upstream_key(monkeypatch):
    monkeypatch.setenv("NEXIS_APP_TOKEN", "demo-secret")
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    response = client.post("/chat", headers={"Authorization": "Bearer demo-secret"}, json={"message": "Merhaba"})
    assert response.status_code == 503

def test_prompt_has_human_approval():
    from app import SYSTEM_PROMPT
    assert "insan" in SYSTEM_PROMPT.lower()
    assert "erişimin olmadığını" in SYSTEM_PROMPT
