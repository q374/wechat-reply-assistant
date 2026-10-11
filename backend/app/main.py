import json
import os
import threading
import time
from collections import deque
from pathlib import Path
from typing import Deque, Dict, List, Optional, Literal

import httpx
from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel, Field, field_validator, model_validator


CHAT_REPLY_TYPES = ("轻松接梗", "互动选择", "悬念引导", "具体延展", "轻微暧昧")


class GenerateRequest(BaseModel):
    purpose: str = Field(..., min_length=1, max_length=2000)
    background: str = Field(..., min_length=1, max_length=5000)
    relationship: str = Field(..., min_length=1, max_length=100)
    context: Optional[str] = Field(default="", max_length=5000)
    tone_requirements: str = Field(..., min_length=1, max_length=500)

    @field_validator("purpose", "background", "relationship", "tone_requirements")
    @classmethod
    def reject_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("字段不能只包含空白字符")
        return value.strip()


class GenerateResponse(BaseModel):
    replies: List[str]
    need_more_information: bool = False
    message: str = ""


class ChatGenerateRequest(BaseModel):
    mode: Literal["reply", "start"]
    route: Literal["real", "general"] = "real"
    object_name: str = Field(..., min_length=1, max_length=100)
    user_identity: str = Field(default="", max_length=500)
    reply_preference: str = Field(default="", max_length=500)
    relationship_stage: str = Field(default="", max_length=100)
    memory: str = Field(default="", max_length=3000)
    incoming_message: str = Field(default="", max_length=3000)
    context: str = Field(default="", max_length=3000)
    user_situation: str = Field(default="", max_length=2000)
    reply_type: Literal["", "轻松接梗", "互动选择", "悬念引导", "具体延展", "轻微暧昧"] = ""

    @field_validator("object_name")
    @classmethod
    def reject_empty_object(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("对象名称不能为空")
        return value.strip()

    @field_validator("user_identity", "reply_preference", "relationship_stage", "memory", "incoming_message", "context", "user_situation", mode="after")
    @classmethod
    def trim_optional_text(cls, value: str) -> str:
        return value.strip()

    @model_validator(mode="after")
    def validate_task_requirements(self) -> "ChatGenerateRequest":
        if self.mode == "reply" and not self.incoming_message:
            raise ValueError("回复模式必须提供对方消息")
        if self.mode == "start" and not self.user_situation:
            raise ValueError("主动开聊至少需要提供话题、经历或心情")
        return self


class ChatGenerateResponse(BaseModel):
    recommended_type: str = ""
    recommendation_reason: str = ""
    replies: List[str]
    need_more_information: bool = False
    message: str = ""


app = FastAPI(title="微信表达助手 API", version="0.1.0")

# Prototype protection: at most 5 generation requests per client IP in a rolling
# 60-second window. This is process-local; production should use shared storage.
_RATE_LIMIT_REQUESTS = 5
_RATE_LIMIT_WINDOW_SECONDS = 60
_rate_limit_lock = threading.Lock()
_request_times: Dict[str, Deque[float]] = {}


def _rate_limit_retry_after(client_ip: str, now: Optional[float] = None) -> Optional[int]:
    """Return seconds until the client may retry, or None when under the limit."""
    current = time.monotonic() if now is None else now
    with _rate_limit_lock:
        # Prune expired windows and cap retained client keys against unbounded growth.
        for key in list(_request_times):
            timestamps = _request_times[key]
            while timestamps and current - timestamps[0] >= _RATE_LIMIT_WINDOW_SECONDS:
                timestamps.popleft()
            if not timestamps:
                del _request_times[key]
        timestamps = _request_times.setdefault(client_ip, deque())
        if len(timestamps) >= _RATE_LIMIT_REQUESTS:
            return max(1, int(_RATE_LIMIT_WINDOW_SECONDS - (current - timestamps[0]) + 0.999))
        timestamps.append(current)
        if len(_request_times) > 2048:
            oldest_key = min(_request_times, key=lambda key: _request_times[key][0])
            if oldest_key != client_ip:
                del _request_times[oldest_key]
        return None


def _load_local_env() -> None:
    """Load backend/.env for local development without logging its contents."""
    env_path = Path(__file__).resolve().parents[1] / ".env"
    if not env_path.is_file():
        return
    for raw_line in env_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        if key and key not in os.environ:
            os.environ[key] = value


_load_local_env()


def _settings() -> tuple[str, str, str]:
    api_key = os.getenv("DEEPSEEK_API_KEY", "").strip()
    base_url = os.getenv("AI_BASE_URL", "https://api.deepseek.com").rstrip("/")
    model = os.getenv("AI_MODEL", "deepseek-flash").strip()
    return api_key, base_url, model


def _prompt(request: GenerateRequest) -> str:
    payload = {
        "purpose": request.purpose,
        "background": request.background,
        "relationship": request.relationship,
        "context": request.context or "",
        "tone_requirements": request.tone_requirements,
    }
    return (
        "请把用户的沟通诉求整理成 1 到 3 条可编辑的微信消息建议。"
        "不得补造用户没有提供的事实、承诺或条件。只返回 JSON，不要 Markdown，格式为："
        '{"replies":["消息1"],"need_more_information":false,"message":""}。'
        "如果关键信息不足，replies 可以为空，并将 need_more_information 设为 true。\n"
        + json.dumps(payload, ensure_ascii=False)
    )


def _chat_prompt(request: ChatGenerateRequest) -> str:
    payload = request.model_dump()
    return (
        "你是暧昧对象持续聊天助手。根据当前任务生成 3 条同一种回复类型、具体自然且容易接话的微信候选。"
        "只能使用用户提供的事实；路线 general 不得编造用户经历。避免连续追问、空泛寒暄和过度施压。"
        "可用回复类型：轻松接梗、互动选择、悬念引导、具体延展、轻微暧昧。"
        "只返回 JSON，不要 Markdown，格式为："
        '{"recommended_type":"具体延展","recommendation_reason":"理由","replies":["候选1","候选2","候选3"],"need_more_information":false,"message":""}。'
        "如果信息不足，replies 可以为空并将 need_more_information 设为 true。\n"
        + json.dumps(payload, ensure_ascii=False)
    )


def _parse_model_content(content: object) -> GenerateResponse:
    if not isinstance(content, str):
        raise ValueError("模型返回内容不是文本")
    cleaned = content.strip()
    if cleaned.startswith("```"):
        cleaned = cleaned.removeprefix("```").removeprefix("json").removesuffix("```").strip()
    try:
        data = json.loads(cleaned)
    except json.JSONDecodeError as exc:
        raise ValueError("模型返回不是有效 JSON") from exc
    if not isinstance(data, dict):
        raise ValueError("模型返回结构无效")
    replies = data.get("replies")
    if not isinstance(replies, list) or len(replies) > 3 or any(not isinstance(item, str) or not item.strip() for item in replies):
        raise ValueError("模型返回的 replies 无效")
    return GenerateResponse(
        replies=[item.strip() for item in replies],
        need_more_information=bool(data.get("need_more_information", False)),
        message=str(data.get("message", "")),
    )


def _parse_chat_content(request: ChatGenerateRequest, content: object) -> ChatGenerateResponse:
    if not isinstance(content, str):
        raise ValueError("模型返回内容不是文本")
    cleaned = content.strip()
    if cleaned.startswith("```"):
        cleaned = cleaned.removeprefix("```").removeprefix("json").removesuffix("```").strip()
    try:
        data = json.loads(cleaned)
    except json.JSONDecodeError as exc:
        raise ValueError("模型返回不是有效 JSON") from exc
    if not isinstance(data, dict):
        raise ValueError("模型返回结构无效")
    recommended_type = data.get("recommended_type", "")
    need_more_information = bool(data.get("need_more_information", False))
    if not isinstance(recommended_type, str) or (recommended_type and recommended_type not in CHAT_REPLY_TYPES):
        raise ValueError("模型返回的回复类型无效")
    if not need_more_information and not recommended_type:
        raise ValueError("模型未返回回复类型")
    if not need_more_information and request.reply_type and recommended_type != request.reply_type:
        raise ValueError("模型返回的回复类型与用户选择不一致")
    replies = data.get("replies")
    if not isinstance(replies, list) or len(replies) > 3 or any(not isinstance(item, str) or not item.strip() for item in replies):
        raise ValueError("模型返回的 replies 无效")
    if not need_more_information and len(replies) != 3:
        raise ValueError("聊天候选必须返回三条")
    return ChatGenerateResponse(
        recommended_type=recommended_type,
        recommendation_reason=str(data.get("recommendation_reason", "")),
        replies=[item.strip() for item in replies],
        need_more_information=need_more_information,
        message=str(data.get("message", "")),
    )


@app.get("/health")
async def health() -> dict[str, object]:
    # 健康检查只报告服务是否可达和后端是否具备真实 AI 配置，不返回密钥或请求内容。
    api_key, _, _ = _settings()
    return {"status": "ok", "ai_configured": bool(api_key)}


@app.post("/v1/generate-replies", response_model=GenerateResponse)
async def generate_replies(request: GenerateRequest, http_request: Request) -> GenerateResponse:
    client_ip = http_request.client.host if http_request.client else "unknown"
    retry_after = _rate_limit_retry_after(client_ip)
    if retry_after is not None:
        raise HTTPException(
            status_code=429,
            detail="请求过于频繁，请稍后再试",
            headers={"Retry-After": str(retry_after)},
        )

    api_key, base_url, model = _settings()
    if not api_key:
        raise HTTPException(status_code=503, detail="AI 服务尚未配置 DEEPSEEK_API_KEY")

    body = {
        "model": model,
        "messages": [
            {"role": "system", "content": "你是微信表达整理助手，只输出符合要求的 JSON。"},
            {"role": "user", "content": _prompt(request)},
        ],
        "temperature": 0.4,
        "stream": False,
    }
    headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
    try:
        # The development machine may expose a stale local proxy (127.0.0.1:9).
        # API traffic should use the configured HTTPS endpoint directly.
        async with httpx.AsyncClient(timeout=30.0, trust_env=False) as client:
            response = await client.post(f"{base_url}/chat/completions", headers=headers, json=body)
            response.raise_for_status()
            response_data = response.json()
        content = response_data["choices"][0]["message"]["content"]
        return _parse_model_content(content)
    except (httpx.HTTPError, KeyError, IndexError, TypeError, ValueError) as exc:
        raise HTTPException(status_code=502, detail="AI 服务返回无效或暂时不可用") from exc


@app.post("/v1/chat/generate", response_model=ChatGenerateResponse)
async def generate_chat(request: ChatGenerateRequest, http_request: Request) -> ChatGenerateResponse:
    client_ip = http_request.client.host if http_request.client else "unknown"
    retry_after = _rate_limit_retry_after(client_ip)
    if retry_after is not None:
        raise HTTPException(status_code=429, detail="请求过于频繁，请稍后再试", headers={"Retry-After": str(retry_after)})
    api_key, base_url, model = _settings()
    if not api_key:
        raise HTTPException(status_code=503, detail="AI 服务尚未配置 DEEPSEEK_API_KEY")
    body = {
        "model": model,
        "messages": [
            {"role": "system", "content": "你是暧昧对象聊天助手，只输出符合要求的 JSON。"},
            {"role": "user", "content": _chat_prompt(request)},
        ],
        "temperature": 0.7,
        "stream": False,
    }
    headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
    try:
        async with httpx.AsyncClient(timeout=30.0, trust_env=False) as client:
            response = await client.post(f"{base_url}/chat/completions", headers=headers, json=body)
            response.raise_for_status()
            response_data = response.json()
        content = response_data["choices"][0]["message"]["content"]
        return _parse_chat_content(request, content)
    except (httpx.HTTPError, KeyError, IndexError, TypeError, ValueError) as exc:
        raise HTTPException(status_code=502, detail="AI 服务返回无效或暂时不可用") from exc
