"""NEXIS CEO AI - human-approval-only assistant API.
Use HTTPS with a reverse proxy. Keep secrets off GitHub and Android.
"""
import hmac
import os
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

app = FastAPI(title="NEXIS CEO AI", version="0.1.0-lab")

SYSTEM_PROMPT = """Sen NEXİS Dijital'in Türkçe konuşan şirket yönetim asistanısın.
Şirketin faaliyetleri: CCTV, network, fiber optik, yangın algılama, zayıf akım ve teknik servis.
Yöneticiye yapılacak işleri somut, adım adım öner; neden, öncelik, beklenen etki, maliyet,
risk ve hangi bilgiye ihtiyacın olduğunu açıkla. Uydurma müşteri, teklif, borç, tahsilat
veya randevu oluşturma. Veri eksikse açıkça sor. Bilmediğin tutarları bilinmiyor diye belirt.
Müşteriye mesaj gönderme, harcama, resmi işlem, sözleşme veya ödeme yapma yetkin yok.
Bütün icrai kararlar insan tarafından ayrı ayrı doğrulanmalıdır.
Hukuki ve mali konularda gerekli uzman kontrolünü belirt.
Şirketin canlı CRM/muhasebe kayıtlarına bu sürümde erişimin olmadığını unutma.
"""

class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=6000)

class ChatResponse(BaseModel):
    answer: str
    requires_human_approval: bool = True

def verify_bearer(authorization: str | None):
    expected = os.environ.get("NEXIS_APP_TOKEN", "")
    if not expected:
        raise HTTPException(status_code=503, detail="NEXIS_APP_TOKEN is not configured")
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Authentication required")
    supplied = authorization[len("Bearer "):]
    if not hmac.compare_digest(expected, supplied):
        raise HTTPException(status_code=401, detail="Invalid application token")

@app.get("/health")
def health():
    return {"status": "ok", "version": "0.1.0-lab", "watch_connected": False}

@app.post("/chat", response_model=ChatResponse)
def chat(payload: ChatRequest, authorization: str | None = Header(default=None)):
    verify_bearer(authorization)
    api_key = os.environ.get("OPENAI_API_KEY", "")
    if not api_key:
        raise HTTPException(status_code=503, detail="OPENAI_API_KEY is not configured")
    try:
        from openai import OpenAI
        client = OpenAI(api_key=api_key, timeout=40.0)
        response = client.responses.create(
            model=os.environ.get("OPENAI_MODEL", "gpt-4.1-mini"),
            instructions=SYSTEM_PROMPT,
            input=payload.message,
            max_output_tokens=900,
        )
        return ChatResponse(answer=response.output_text)
    except Exception:
        raise HTTPException(status_code=502, detail="AI service temporarily unavailable")
