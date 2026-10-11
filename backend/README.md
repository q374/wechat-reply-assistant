# 本地 FastAPI 联调服务

这是 Android 客户端的可选本地联调服务。它接收用户当前确认的任务快照，向配置的 AI 服务请求候选，并返回结构化 JSON。服务不会持久化请求正文、对象档案或模型结果，也不会把 API Key 返回给客户端。

## 启动

在本目录安装依赖，并在当前终端设置环境变量：

~~~powershell
python -m pip install -r requirements.txt
$env:DEEPSEEK_API_KEY = "只保存在当前终端，不要写入源码"
$env:AI_BASE_URL = "https://api.deepseek.com"
$env:AI_MODEL = "deepseek-flash"
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
~~~

DEEPSEEK_API_KEY 缺失时，接口会返回 503。开发联调时，Android 设备可通过 USB 端口转发访问本机服务：

~~~powershell
adb reverse tcp:8000 tcp:8000
~~~

不要把真实 API Key 写入 .env、截图、日志、issue 或 Git；.env 已被 .gitignore 排除。真实调用可能产生费用，演示请使用虚构聊天内容。

## 接口

- GET /health：返回服务是否可达，以及是否配置了 AI，不返回密钥。
- POST /v1/generate-replies：兼容旧版通用表达流程。
- POST /v1/chat/generate：当前聊天助手流程，返回推荐类型、理由和三条候选。

客户端和服务端都会校验输入、回复类型、候选数量和非空内容。模型返回不符合约定结构时，接口返回错误，客户端不展示伪造的成功结果。

## 当前边界

- 这是单进程开发保护，不是生产级鉴权、配额或多实例限流方案。
- 生产环境需要独立的认证、HTTPS、密钥管理、审计和共享限流。
- 当前产品不会自动读取、写入或发送微信消息；用户需要主动粘贴上下文，并在微信中检查后手动发送。
