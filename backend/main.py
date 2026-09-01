import hashlib
import json
import os
import re
import secrets
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

import libsql_client
from dotenv import load_dotenv
from fastapi import (
    Depends,
    FastAPI,
    File,
    Header,
    HTTPException,
    UploadFile,
    WebSocket,
    WebSocketDisconnect,
)
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import HTMLResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, EmailStr

load_dotenv()

app = FastAPI()

BASE_DIR = Path(__file__).resolve().parent
UPLOAD_DIR = BASE_DIR / "media"
UPLOAD_DIR.mkdir(exist_ok=True)

app.mount("/media", StaticFiles(directory=str(UPLOAD_DIR)), name="media")

INDEX_HTML_PATH = BASE_DIR / "templates" / "index.html"

# --------------------------------------------------------------------------
# Turso (libSQL) — облачная БД. Локальный SQLite-файл эфемерен на Render,
# поэтому данные хранятся удалённо; TURSO_DATABASE_URL/TURSO_AUTH_TOKEN
# берутся из дашборда/CLI Turso (turso db show / turso db tokens create).
#
# `turso db show` отдаёт URL со схемой libsql:// (= wss://, подключение по
# WebSocket) — но у архивного libsql-client-py 0.3.1 хендшейк по WebSocket
# не проходит с текущим протоколом Turso (проверено: 400 Invalid response
# status при апгрейде соединения). HTTPS-транспорт того же клиента работает
# исправно, поэтому URL принудительно приводится к https://. Расплата —
# HTTP-транспорт не поддерживает client.transaction() (кидает
# TRANSACTIONS_NOT_SUPPORTED), поэтому атомарные группы запросов идут через
# client.batch() вместо явных транзакций.
# --------------------------------------------------------------------------

def _force_https(url: str) -> str:
    for scheme in ("libsql://", "wss://"):
        if url.startswith(scheme):
            return "https://" + url[len(scheme):]
    if url.startswith("ws://"):
        return "http://" + url[len("ws://"):]
    return url


TURSO_DATABASE_URL = _force_https(os.environ.get("TURSO_DATABASE_URL", "").strip())
TURSO_AUTH_TOKEN = os.environ.get("TURSO_AUTH_TOKEN", "").strip()

if not TURSO_DATABASE_URL:
    raise RuntimeError(
        "TURSO_DATABASE_URL не задан — укажи его в .env (см. .env.example). "
        "Создать базу: turso db create <имя>, узнать URL: turso db show <имя> --url"
    )

_db_client: libsql_client.Client | None = None


def get_db() -> libsql_client.Client:
    """Единый клиент Turso на всё время жизни процесса (переиспользуется между запросами)."""
    global _db_client
    if _db_client is None:
        _db_client = libsql_client.create_client(
            url=TURSO_DATABASE_URL,
            auth_token=TURSO_AUTH_TOKEN or None,
        )
    return _db_client


BatchStatements = list[tuple[str, tuple]]


# --------------------------------------------------------------------------
# Настройки безопасности
# --------------------------------------------------------------------------

CODE_TTL_SECONDS = 5 * 60          # код подтверждения живёт 5 минут
CODE_RESEND_COOLDOWN = 30          # повторно запросить код можно раз в 30 сек
CODE_MAX_ATTEMPTS = 5              # максимум попыток ввода кода
SESSION_TTL_SECONDS = 7 * 24 * 3600  # сессия живёт 7 дней

MAX_UPLOAD_SIZE = 15 * 1024 * 1024  # 15 МБ
ALLOWED_EXTENSIONS = {
    ".jpg", ".jpeg", ".png", ".gif", ".webp",
    ".pdf", ".txt", ".zip",
    ".mp4", ".mp3", ".ogg", ".wav",
    ".doc", ".docx", ".xls", ".xlsx",
}

# --------------------------------------------------------------------------
# Настройки SMTP (реальная отправка email)
# --------------------------------------------------------------------------

SMTP_HOST = os.environ.get("SMTP_HOST", "").strip()
SMTP_PORT = int(os.environ.get("SMTP_PORT", "587"))
SMTP_USER = os.environ.get("SMTP_USER", "").strip()
SMTP_PASSWORD = os.environ.get("SMTP_PASSWORD", "").strip()
SMTP_FROM = os.environ.get("SMTP_FROM", "").strip() or SMTP_USER
SMTP_USE_SSL = os.environ.get("SMTP_USE_SSL", "false").lower() == "true"

EMAIL_CONFIGURED = bool(SMTP_HOST and SMTP_USER and SMTP_PASSWORD)

# --------------------------------------------------------------------------
# In-memory хранилища
# --------------------------------------------------------------------------

# email -> {"code": str, "expires_at": float, "last_sent_at": float, "attempts": int}
# Короткоживущие (TTL 5 минут) — не страшно, если рестарт процесса их обнулит.
verification_codes: dict[str, dict] = {}


# Сессии живут в таблице sessions (см. startup_db), а не в памяти процесса —
# иначе рестарт/редеплой инстанса (Render это делает регулярно) молча
# разлогинивал бы всех, у кого токен ещё считался валидным на клиенте.
async def create_session(db: libsql_client.Client, email: str) -> str:
    token = secrets.token_urlsafe(32)
    await db.execute(
        "INSERT INTO sessions (token, email, expires_at) VALUES (?, ?, ?)",
        (token, email, time.time() + SESSION_TTL_SECONDS),
    )
    return token


async def resolve_session(db: libsql_client.Client, token: str | None) -> str | None:
    """Возвращает email по токену, если сессия валидна, иначе None."""
    if not token:
        return None
    result = await db.execute(
        "SELECT email, expires_at FROM sessions WHERE token = ?", (token,)
    )
    if not result.rows:
        return None
    email, expires_at = result.rows[0]
    if expires_at < time.time():
        await db.execute("DELETE FROM sessions WHERE token = ?", (token,))
        return None
    return email


async def get_current_email(x_session_token: str | None = Header(default=None)) -> str:
    """FastAPI dependency для защищённых REST-эндпоинтов."""
    email = await resolve_session(get_db(), x_session_token)
    if not email:
        raise HTTPException(status_code=401, detail="Требуется авторизация")
    return email


def safe_filename(original_name: str) -> str:
    """Генерирует безопасное имя файла: своё uuid + проверенное расширение."""
    ext = Path(original_name).suffix.lower()
    if ext not in ALLOWED_EXTENSIONS:
        raise HTTPException(status_code=400, detail=f"Недопустимый тип файла: {ext or 'без расширения'}")
    # На всякий случай убираем всё, что не буквы/цифры/точка/дефис/подчёркивание
    ext = re.sub(r"[^a-zA-Z0-9.]", "", ext)
    return f"{uuid.uuid4().hex}{ext}"


# --------------------------------------------------------------------------
# Инициализация БД
# --------------------------------------------------------------------------

@app.on_event("startup")
async def startup_db():
    # Идемпотентно: CREATE TABLE IF NOT EXISTS / проверка колонок перед ALTER —
    # безопасно гонять на каждом старте. На чистой Turso-базе создаёт схему
    # с нуля сама, вручную через Turso CLI ничего прогонять не нужно.
    # Без транзакции: между DDL и PRAGMA-проверками есть ветвление на
    # результатах чтения, а это несовместимо с client.batch() (см. выше).
    db = get_db()
    await db.execute("""
        CREATE TABLE IF NOT EXISTS users (
            email TEXT PRIMARY KEY,
            status TEXT DEFAULT 'В сети'
        )
    """)
    await db.execute("""
        CREATE TABLE IF NOT EXISTS chats (
            chat_id TEXT PRIMARY KEY,
            title TEXT,
            is_private INTEGER DEFAULT 0
        )
    """)
    await db.execute("""
        CREATE TABLE IF NOT EXISTS messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            chat_id TEXT,
            sender TEXT,
            text TEXT,
            file_url TEXT,
            timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
        )
    """)
    await db.execute("""
        CREATE TABLE IF NOT EXISTS chat_participants (
            chat_id TEXT,
            email TEXT,
            PRIMARY KEY (chat_id, email)
        )
    """)
    await db.execute("""
        CREATE TABLE IF NOT EXISTS sessions (
            token TEXT PRIMARY KEY,
            email TEXT NOT NULL,
            expires_at REAL NOT NULL
        )
    """)
    # Просроченные сессии не удаляются построчно нигде, кроме как при обращении
    # по их собственному токену (см. resolve_session) — подчищаем накопившееся здесь.
    await db.execute("DELETE FROM sessions WHERE expires_at < ?", (time.time(),))

    # Signal Protocol: ключи для E2E-шифрования личных переписок.
    # Одно устройство на пользователя — поэтому identity/signed prekey
    # хранятся по email (PK), без device_id.
    await db.execute("""
        CREATE TABLE IF NOT EXISTS identity_keys (
            email TEXT PRIMARY KEY,
            registration_id INTEGER NOT NULL,
            identity_key TEXT NOT NULL,
            updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
        )
    """)
    await db.execute("""
        CREATE TABLE IF NOT EXISTS signed_prekeys (
            email TEXT PRIMARY KEY,
            key_id INTEGER NOT NULL,
            public_key TEXT NOT NULL,
            signature TEXT NOT NULL,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP
        )
    """)
    await db.execute("""
        CREATE TABLE IF NOT EXISTS one_time_prekeys (
            email TEXT NOT NULL,
            key_id INTEGER NOT NULL,
            public_key TEXT NOT NULL,
            PRIMARY KEY (email, key_id)
        )
    """)

    # Миграция: добавляем колонки, если их ещё нет (для уже существующей БД)
    result = await db.execute("PRAGMA table_info(chats)")
    existing_columns = [row[1] for row in result.rows]
    if "chat_type" not in existing_columns:
        await db.execute("ALTER TABLE chats ADD COLUMN chat_type TEXT DEFAULT 'group'")
    if "created_by" not in existing_columns:
        await db.execute("ALTER TABLE chats ADD COLUMN created_by TEXT")

    result = await db.execute("PRAGMA table_info(chat_participants)")
    participant_columns = [row[1] for row in result.rows]
    if "is_admin" not in participant_columns:
        await db.execute("ALTER TABLE chat_participants ADD COLUMN is_admin INTEGER DEFAULT 0")

    # Никнейм — опциональный, если пуст, клиент показывает часть email до "@"
    result = await db.execute("PRAGMA table_info(users)")
    user_columns = [row[1] for row in result.rows]
    if "nickname" not in user_columns:
        await db.execute("ALTER TABLE users ADD COLUMN nickname TEXT")

    # Сообщения в dm-чатах шифруются end-to-end (Signal Protocol) — сервер
    # хранит только ciphertext и не должен видеть открытый текст.
    # Публичные/групповые чаты по-прежнему используют колонку text как есть.
    result = await db.execute("PRAGMA table_info(messages)")
    message_columns = [row[1] for row in result.rows]
    if "ciphertext" not in message_columns:
        await db.execute("ALTER TABLE messages ADD COLUMN ciphertext TEXT")
    if "message_type" not in message_columns:
        await db.execute("ALTER TABLE messages ADD COLUMN message_type TEXT")

    # Приводим chat_type в соответствие для уже существующих строк
    await db.execute("UPDATE chats SET chat_type = 'public' WHERE is_private = 0")
    await db.execute("UPDATE chats SET chat_type = 'dm' WHERE chat_id LIKE 'dm\\_%' ESCAPE '\\'")


@app.on_event("shutdown")
async def shutdown_db():
    if _db_client is not None:
        await _db_client.close()


# --------------------------------------------------------------------------
# WebSocket менеджер
# --------------------------------------------------------------------------

class ChatManager:
    def __init__(self):
        self.active_connections: dict[str, dict[WebSocket, str]] = {}

    async def connect(self, chat_id: str, websocket: WebSocket, email: str):
        await websocket.accept()
        self.active_connections.setdefault(chat_id, {})[websocket] = email
        await self.broadcast_presence(chat_id)

    def disconnect(self, chat_id: str, websocket: WebSocket):
        if chat_id in self.active_connections:
            self.active_connections[chat_id].pop(websocket, None)
            if not self.active_connections[chat_id]:
                del self.active_connections[chat_id]

    async def broadcast_presence(self, chat_id: str):
        if chat_id in self.active_connections:
            online_users = list(set(self.active_connections[chat_id].values()))
            for ws in list(self.active_connections[chat_id].keys()):
                try:
                    await ws.send_json({"type": "presence", "online_users": online_users})
                except Exception:
                    pass

    async def broadcast(self, chat_id: str, message_data: dict):
        if chat_id in self.active_connections:
            for connection in list(self.active_connections[chat_id].keys()):
                try:
                    await connection.send_json(message_data)
                except Exception:
                    self.disconnect(chat_id, connection)


manager = ChatManager()


# --------------------------------------------------------------------------
# Модели
# --------------------------------------------------------------------------

class EmailRequest(BaseModel):
    email: EmailStr


class VerifyRequest(BaseModel):
    email: EmailStr
    code: str


class ChatCreateRequest(BaseModel):
    chat_id: str
    title: str


class DMRequest(BaseModel):
    peer_email: EmailStr


class ParticipantRequest(BaseModel):
    email: EmailStr


class NicknameUpdateRequest(BaseModel):
    nickname: str


class OneTimePreKeyEntry(BaseModel):
    key_id: int
    public_key: str  # base64


class SignedPreKeyEntry(BaseModel):
    key_id: int
    public_key: str  # base64
    signature: str    # base64


class PublishKeysRequest(BaseModel):
    registration_id: int
    identity_key: str  # base64, публичный identity key
    signed_prekey: SignedPreKeyEntry
    one_time_prekeys: list[OneTimePreKeyEntry]


def dm_chat_id(email_a: str, email_b: str) -> str:
    """Детерминированный id личного чата — не зависит от порядка участников."""
    pair = sorted([email_a.strip().lower(), email_b.strip().lower()])
    raw = f"{pair[0]}::{pair[1]}"
    return "dm_" + hashlib.sha256(raw.encode()).hexdigest()[:24]


async def can_access_chat(db: libsql_client.Client, chat_id: str, email: str) -> bool:
    """Публичные чаты доступны всем авторизованным; приватные — только участникам."""
    result = await db.execute("SELECT is_private FROM chats WHERE chat_id = ?", (chat_id,))
    if not result.rows:
        return False
    if result.rows[0][0] == 0:
        return True
    result = await db.execute(
        "SELECT 1 FROM chat_participants WHERE chat_id = ? AND email = ?",
        (chat_id, email),
    )
    return len(result.rows) > 0


async def is_group_admin(db: libsql_client.Client, chat_id: str, email: str) -> bool:
    result = await db.execute(
        "SELECT is_admin FROM chat_participants WHERE chat_id = ? AND email = ?",
        (chat_id, email),
    )
    row = result.rows[0] if result.rows else None
    return bool(row and row[0])


def _delete_media_files(file_urls: list[str]):
    """Удаляет файлы сообщений с диска по их file_url (см. /api/upload — /media/<имя>)."""
    for file_url in file_urls:
        if not file_url:
            continue
        file_path = UPLOAD_DIR / Path(file_url).name
        try:
            file_path.unlink(missing_ok=True)
        except OSError:
            pass


async def _dm_chat_delete_statements(db: libsql_client.Client, chat_id: str) -> BatchStatements:
    """
    Удаляет с диска файлы сообщений личного чата сразу и возвращает список SQL-операций
    (сообщения, участники, сам чат) для атомарного выполнения через db.batch() —
    DM-переписка симметрична и не должна "провисать" на одной стороне, поэтому
    используется и при ручном удалении, и при удалении аккаунта.
    """
    result = await db.execute(
        "SELECT file_url FROM messages WHERE chat_id = ? AND file_url IS NOT NULL", (chat_id,)
    )
    file_urls = [row[0] for row in result.rows]
    _delete_media_files(file_urls)

    return [
        ("DELETE FROM messages WHERE chat_id = ?", (chat_id,)),
        ("DELETE FROM chat_participants WHERE chat_id = ?", (chat_id,)),
        ("DELETE FROM chats WHERE chat_id = ?", (chat_id,)),
    ]


async def _leave_group_chat_statements(db: libsql_client.Client, chat_id: str, email: str) -> BatchStatements:
    """
    Возвращает список SQL-операций (для db.batch()), убирающих участника из группы:
    если уходит последний админ — права передаются следующему оставшемуся участнику,
    а если участников не остаётся вовсе — группа удаляется. Та же логика, что в
    DELETE /api/chats/{id}/participants/{email}. Кто станет новым админом и опустеет
    ли группа — решаем заранее по прочитанному списку участников (без транзакции
    промежуточное DELETE нельзя увидеть последующим SELECT на том же соединении).
    """
    result = await db.execute(
        "SELECT email, is_admin FROM chat_participants WHERE chat_id = ?", (chat_id,)
    )
    remaining = [(row[0], bool(row[1])) for row in result.rows if row[0] != email]

    statements: BatchStatements = [
        ("DELETE FROM chat_participants WHERE chat_id = ? AND email = ?", (chat_id, email)),
    ]

    admins_left = any(is_admin for _, is_admin in remaining)
    if not admins_left:
        if remaining:
            next_admin = remaining[0][0]
            statements.append((
                "UPDATE chat_participants SET is_admin = 1 WHERE chat_id = ? AND email = ?",
                (chat_id, next_admin),
            ))
        else:
            statements.append(("DELETE FROM chats WHERE chat_id = ?", (chat_id,)))
            statements.append(("DELETE FROM messages WHERE chat_id = ?", (chat_id,)))

    return statements


def _send_smtp_sync(email: str, code: str):
    """Отправка письма через HTTP API Resend (выполняется в threadpool).

    Render на бесплатном тарифе с 2025-09-26 блокирует исходящий трафик на
    SMTP-порты (25/465/587), поэтому прямое SMTP-соединение всегда виснет по
    таймауту. HTTP API Resend работает через порт 443 и это не задевает —
    SMTP_PASSWORD используется как Resend API-ключ (Bearer-токен).
    """
    payload = json.dumps({
        "from": SMTP_FROM,
        "to": [email],
        "subject": "Код подтверждения",
        "text": (
            f"Ваш код подтверждения: {code}\n\n"
            f"Код действителен {CODE_TTL_SECONDS // 60} минут.\n"
            f"Если вы не запрашивали код — просто проигнорируйте это письмо."
        ),
    }).encode("utf-8")

    request = urllib.request.Request(
        "https://api.resend.com/emails",
        data=payload,
        method="POST",
        headers={
            "Authorization": f"Bearer {SMTP_PASSWORD}",
            "Content-Type": "application/json",
            # Resend сидит за Cloudflare, которая режет запросы со стандартным
            # User-Agent'ом urllib ("Python-urllib/3.x") как код 1010.
            "User-Agent": "Mozilla/5.0 (compatible; MyMessengerBackend/1.0)",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            response.read()
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"Resend API {e.code}: {e.read().decode('utf-8', 'replace')}") from e


async def send_email_code(email: str, code: str):
    if not EMAIL_CONFIGURED:
        # Режим разработки: SMTP не настроен — печатаем код в консоль,
        # чтобы можно было тестировать без реального почтового сервера.
        print("\n" + "=" * 50)
        print(" ⚠️  SMTP не настроен (см. .env) — код только в консоли")
        print(f" 🔑 КОД ПОДТВЕРЖДЕНИЯ ДЛЯ [{email}]: --> {code} <--")
        print("=" * 50 + "\n")
        return True

    try:
        await run_in_threadpool(_send_smtp_sync, email, code)
        return True
    except Exception as e:
        # Не роняем запрос из-за проблем с почтой, но обязательно логируем
        # и дублируем код в консоль, чтобы не заблокировать тестирование.
        print(f"❌ Ошибка отправки email на {email}: {e}")
        print(f" 🔑 КОД (fallback) ДЛЯ [{email}]: --> {code} <--")
        raise HTTPException(status_code=502, detail="Не удалось отправить письмо. Попробуйте позже.")


# --------------------------------------------------------------------------
# Auth endpoints
# --------------------------------------------------------------------------

@app.post("/api/send-code")
async def send_code(data: EmailRequest):
    now = time.time()
    existing = verification_codes.get(data.email)

    if existing and now - existing["last_sent_at"] < CODE_RESEND_COOLDOWN:
        wait = int(CODE_RESEND_COOLDOWN - (now - existing["last_sent_at"]))
        raise HTTPException(status_code=429, detail=f"Подождите {wait} сек. перед повторной отправкой")

    code = str(secrets.randbelow(9000) + 1000)  # криптостойкий рандом вместо random.randint
    verification_codes[data.email] = {
        "code": code,
        "expires_at": now + CODE_TTL_SECONDS,
        "last_sent_at": now,
        "attempts": 0,
    }
    await send_email_code(data.email, code)
    return {"message": "Код успешно сгенерирован"}


@app.post("/api/verify-code")
async def verify_code(data: VerifyRequest):
    entry = verification_codes.get(data.email)
    if not entry:
        raise HTTPException(status_code=400, detail="Код не запрошен или уже использован")

    if time.time() > entry["expires_at"]:
        del verification_codes[data.email]
        raise HTTPException(status_code=400, detail="Код истёк, запросите новый")

    if entry["attempts"] >= CODE_MAX_ATTEMPTS:
        del verification_codes[data.email]
        raise HTTPException(status_code=429, detail="Превышено число попыток, запросите новый код")

    if entry["code"] != data.code:
        entry["attempts"] += 1
        raise HTTPException(status_code=400, detail="Неверный код")

    del verification_codes[data.email]

    db = get_db()
    await db.execute(
        "INSERT OR IGNORE INTO users (email, status) VALUES (?, ?)",
        (data.email, "В сети"),
    )

    token = await create_session(db, data.email)
    return {"message": "Успешная авторизация!", "token": token, "email": data.email}


@app.post("/api/logout")
async def logout(x_session_token: str | None = Header(default=None)):
    if x_session_token:
        await get_db().execute("DELETE FROM sessions WHERE token = ?", (x_session_token,))
    return {"message": "Вы вышли"}


# --------------------------------------------------------------------------
# Профиль (никнейм)
# --------------------------------------------------------------------------

@app.get("/api/profile")
async def get_profile(email: str = Depends(get_current_email)):
    db = get_db()
    result = await db.execute("SELECT nickname FROM users WHERE email = ?", (email,))
    row = result.rows[0] if result.rows else None
    return {"email": email, "nickname": row[0] if row else None}


@app.patch("/api/profile")
async def update_profile(data: NicknameUpdateRequest, email: str = Depends(get_current_email)):
    nickname = data.nickname.strip()
    if not (1 <= len(nickname) <= 30):
        raise HTTPException(status_code=400, detail="Никнейм должен быть от 1 до 30 символов")

    db = get_db()
    await db.execute(
        """INSERT INTO users (email, nickname) VALUES (?, ?)
           ON CONFLICT(email) DO UPDATE SET nickname = excluded.nickname""",
        (email, nickname),
    )

    return {"email": email, "nickname": nickname}


@app.delete("/api/account")
async def delete_account(email: str = Depends(get_current_email)):
    """
    Удаляет аккаунт целиком:
    - Личные (dm) чаты, где участвовал пользователь, уничтожаются полностью —
      включая сообщения собеседника и файлы — так что переписка пропадает
      у обеих сторон, а не "провисает" у оставшегося участника.
    - Групповые чаты не трогаем содержательно: пользователь просто выходит
      из участников (с передачей admin-прав, если был единственным админом) —
      сообщения группы и сама группа остаются как есть.
    """
    db = get_db()
    statements: BatchStatements = []

    result = await db.execute(
        """SELECT c.chat_id FROM chats c
           JOIN chat_participants p ON c.chat_id = p.chat_id
           WHERE c.chat_type = 'dm' AND p.email = ?""",
        (email,),
    )
    dm_chat_ids = [row[0] for row in result.rows]
    for chat_id in dm_chat_ids:
        statements += await _dm_chat_delete_statements(db, chat_id)

    result = await db.execute(
        """SELECT c.chat_id FROM chats c
           JOIN chat_participants p ON c.chat_id = p.chat_id
           WHERE c.chat_type = 'group' AND p.email = ?""",
        (email,),
    )
    group_chat_ids = [row[0] for row in result.rows]
    for chat_id in group_chat_ids:
        statements += await _leave_group_chat_statements(db, chat_id, email)

    # Signal-ключи привязаны к аккаунту и без него бесполезны
    statements += [
        ("DELETE FROM identity_keys WHERE email = ?", (email,)),
        ("DELETE FROM signed_prekeys WHERE email = ?", (email,)),
        ("DELETE FROM one_time_prekeys WHERE email = ?", (email,)),
        ("DELETE FROM users WHERE email = ?", (email,)),
    ]

    await db.batch(statements)

    # Инвалидируем все сессии этого пользователя (не только текущую) —
    # иначе устройство/вкладка с другой сессией останется залогинена в удалённый аккаунт.
    await db.execute("DELETE FROM sessions WHERE email = ?", (email,))

    return {"message": "Аккаунт удалён"}


# --------------------------------------------------------------------------
# Chats (требуют авторизации)
# --------------------------------------------------------------------------

@app.get("/api/chats")
async def get_chats(email: str = Depends(get_current_email)):
    db = get_db()
    result = []

    # Публичные чаты видны всем авторизованным пользователям
    rs = await db.execute("SELECT chat_id, title, chat_type FROM chats WHERE is_private = 0")
    for row in rs.rows:
        result.append({"chat_id": row[0], "title": row[1], "chat_type": row[2] or "public"})

    # Приватные чаты — только те, где текущий пользователь участник
    rs = await db.execute(
        """SELECT c.chat_id, c.title, c.chat_type FROM chats c
           JOIN chat_participants p ON c.chat_id = p.chat_id
           WHERE c.is_private = 1 AND p.email = ?""",
        (email,),
    )
    private_rows = rs.rows

    for chat_id, stored_title, chat_type in private_rows:
        chat_type = chat_type or "group"
        if chat_type == "dm":
            # Для личных чатов заголовок резолвится заново при каждом запросе —
            # из текущего никнейма собеседника (не из статичного title в БД),
            # иначе смена ника не отразится у собеседника
            rs = await db.execute(
                """SELECT p.email, u.nickname FROM chat_participants p
                   LEFT JOIN users u ON p.email = u.email
                   WHERE p.chat_id = ? AND p.email != ?""",
                (chat_id, email),
            )
            peer_row = rs.rows[0] if rs.rows else None
            peer_email = peer_row[0] if peer_row else None
            peer_nickname = peer_row[1] if peer_row else None
            title = peer_nickname or peer_email or stored_title or chat_id
            result.append({
                "chat_id": chat_id,
                "title": title,
                "chat_type": chat_type,
                "peer_email": peer_email,
                "nickname": peer_nickname,
            })
        else:
            title = stored_title or chat_id
            result.append({"chat_id": chat_id, "title": title, "chat_type": chat_type})

    return result


@app.post("/api/dm")
async def start_dm(data: DMRequest, email: str = Depends(get_current_email)):
    """Создаёт (или возвращает существующий) приватный чат один-на-один."""
    peer = data.peer_email.strip().lower()
    if peer == email.strip().lower():
        raise HTTPException(status_code=400, detail="Нельзя написать самому себе")

    db = get_db()
    result = await db.execute("SELECT 1 FROM users WHERE email = ?", (peer,))
    if not result.rows:
        raise HTTPException(status_code=404, detail="Пользователь с таким email ещё не регистрировался")

    chat_id = dm_chat_id(email, peer)
    await db.batch([
        ("INSERT OR IGNORE INTO chats (chat_id, title, is_private, chat_type) VALUES (?, '', 1, 'dm')", (chat_id,)),
        ("INSERT OR IGNORE INTO chat_participants (chat_id, email) VALUES (?, ?)", (chat_id, email)),
        ("INSERT OR IGNORE INTO chat_participants (chat_id, email) VALUES (?, ?)", (chat_id, peer)),
    ])

    return {"chat_id": chat_id, "title": peer}


@app.post("/api/chats")
async def create_chat(data: ChatCreateRequest, email: str = Depends(get_current_email)):
    # ограничиваем chat_id безопасным набором символов
    clean_id = re.sub(r"[^a-zA-Z0-9_\-]", "_", data.chat_id)[:64]
    if not clean_id:
        raise HTTPException(status_code=400, detail="Некорректный идентификатор чата")
    if clean_id.startswith("dm_"):
        raise HTTPException(status_code=400, detail="Такой идентификатор зарезервирован")

    db = get_db()
    await db.batch([
        (
            "INSERT OR IGNORE INTO chats (chat_id, title, is_private, chat_type, created_by) VALUES (?, ?, 1, 'group', ?)",
            (clean_id, data.title[:100], email),
        ),
        # создатель сразу становится участником и администратором группы
        (
            "INSERT OR IGNORE INTO chat_participants (chat_id, email, is_admin) VALUES (?, ?, 1)",
            (clean_id, email),
        ),
    ])
    return {"message": "Чат создан", "chat_id": clean_id}


@app.get("/api/chats/{chat_id}/participants")
async def list_participants(chat_id: str, email: str = Depends(get_current_email)):
    db = get_db()
    if not await can_access_chat(db, chat_id, email):
        raise HTTPException(status_code=403, detail="Нет доступа к этому чату")

    result = await db.execute(
        """SELECT p.email, p.is_admin, u.nickname FROM chat_participants p
           LEFT JOIN users u ON p.email = u.email
           WHERE p.chat_id = ? ORDER BY p.is_admin DESC, p.email ASC""",
        (chat_id,),
    )
    rows = result.rows

    return [{"email": row[0], "is_admin": bool(row[1]), "nickname": row[2]} for row in rows]


@app.post("/api/chats/{chat_id}/participants")
async def add_participant(chat_id: str, data: ParticipantRequest, email: str = Depends(get_current_email)):
    new_member = data.email.strip().lower()

    db = get_db()
    result = await db.execute("SELECT chat_type FROM chats WHERE chat_id = ?", (chat_id,))
    row = result.rows[0] if result.rows else None
    if not row:
        raise HTTPException(status_code=404, detail="Чат не найден")
    if row[0] != "group":
        raise HTTPException(status_code=400, detail="В личные и публичные чаты нельзя добавлять участников так")

    if not await is_group_admin(db, chat_id, email):
        raise HTTPException(status_code=403, detail="Добавлять участников может только администратор группы")

    result = await db.execute("SELECT 1 FROM users WHERE email = ?", (new_member,))
    if not result.rows:
        raise HTTPException(status_code=404, detail="Пользователь с таким email ещё не регистрировался")

    await db.execute(
        "INSERT OR IGNORE INTO chat_participants (chat_id, email, is_admin) VALUES (?, ?, 0)",
        (chat_id, new_member),
    )

    return {"message": "Участник добавлен"}


@app.delete("/api/chats/{chat_id}/participants/{member_email}")
async def remove_participant(chat_id: str, member_email: str, email: str = Depends(get_current_email)):
    member_email = member_email.strip().lower()

    db = get_db()
    result = await db.execute("SELECT chat_type FROM chats WHERE chat_id = ?", (chat_id,))
    row = result.rows[0] if result.rows else None
    if not row:
        raise HTTPException(status_code=404, detail="Чат не найден")
    if row[0] != "group":
        raise HTTPException(status_code=400, detail="Так можно управлять только групповыми чатами")

    is_self_leave = member_email == email.strip().lower()
    if not is_self_leave and not await is_group_admin(db, chat_id, email):
        raise HTTPException(status_code=403, detail="Удалять участников может только администратор группы")

    statements = await _leave_group_chat_statements(db, chat_id, member_email)
    await db.batch(statements)

    return {"message": "Готово"}


@app.delete("/api/chats/{chat_id}")
async def delete_chat(chat_id: str, email: str = Depends(get_current_email)):
    if chat_id == "general-chat":
        raise HTTPException(status_code=400, detail="Нельзя удалить общий чат")

    db = get_db()
    if not await can_access_chat(db, chat_id, email):
        raise HTTPException(status_code=403, detail="Нет доступа к этому чату")

    result = await db.execute("SELECT chat_type FROM chats WHERE chat_id = ?", (chat_id,))
    row = result.rows[0] if result.rows else None
    chat_type = row[0] if row else None

    if chat_type == "dm":
        # У личного чата нет администратора — удалить может любой из двух
        # участников, и переписка исчезает симметрично у обоих сразу.
        statements = await _dm_chat_delete_statements(db, chat_id)
    else:
        if chat_type == "group" and not await is_group_admin(db, chat_id, email):
            raise HTTPException(status_code=403, detail="Удалить группу может только администратор")

        statements = [
            ("DELETE FROM chats WHERE chat_id = ?", (chat_id,)),
            ("DELETE FROM messages WHERE chat_id = ?", (chat_id,)),
            ("DELETE FROM chat_participants WHERE chat_id = ?", (chat_id,)),
        ]

    await db.batch(statements)

    return {"message": "Чат успешно удален"}


@app.get("/api/messages/{chat_id}")
async def get_messages(chat_id: str, limit: int = 100, before_id: int | None = None,
                        email: str = Depends(get_current_email)):
    """Отдаёт последние `limit` сообщений (с пагинацией через before_id)."""
    db = get_db()
    if not await can_access_chat(db, chat_id, email):
        raise HTTPException(status_code=403, detail="Нет доступа к этому чату")

    if before_id:
        query = """SELECT m.id, m.sender, m.text, m.ciphertext, m.message_type, m.file_url, m.timestamp, u.nickname
                   FROM messages m LEFT JOIN users u ON m.sender = u.email
                   WHERE m.chat_id = ? AND m.id < ? ORDER BY m.id DESC LIMIT ?"""
        params = (chat_id, before_id, limit)
    else:
        query = """SELECT m.id, m.sender, m.text, m.ciphertext, m.message_type, m.file_url, m.timestamp, u.nickname
                   FROM messages m LEFT JOIN users u ON m.sender = u.email
                   WHERE m.chat_id = ? ORDER BY m.id DESC LIMIT ?"""
        params = (chat_id, limit)

    result = await db.execute(query, params)
    rows = result.rows
    rows.reverse()
    return [
        {
            "id": r[0],
            "sender": r[1],
            "text": r[2],
            "ciphertext": r[3],
            "message_type": r[4],
            "file_url": r[5],
            "time": r[6],
            "nickname": r[7],
        }
        for r in rows
    ]


# --------------------------------------------------------------------------
# Signal Protocol: ключи для E2E-шифрования личных переписок (dm)
# --------------------------------------------------------------------------

@app.post("/api/keys/publish")
async def publish_keys(data: PublishKeysRequest, email: str = Depends(get_current_email)):
    """
    Клиент вызывает это один раз после регистрации (identity key + signed prekey +
    пачка one-time prekeys), а затем периодически — только чтобы дозалить
    one-time prekeys (см. фоновую ротацию на клиенте). identity_key/signed_prekey
    в повторных вызовах просто перезаписываются (одно устройство на пользователя).
    """
    if not data.one_time_prekeys:
        raise HTTPException(status_code=400, detail="Нужен хотя бы один one-time prekey")

    db = get_db()
    statements: BatchStatements = [
        (
            """INSERT INTO identity_keys (email, registration_id, identity_key, updated_at)
               VALUES (?, ?, ?, CURRENT_TIMESTAMP)
               ON CONFLICT(email) DO UPDATE SET
                   registration_id = excluded.registration_id,
                   identity_key = excluded.identity_key,
                   updated_at = CURRENT_TIMESTAMP""",
            (email, data.registration_id, data.identity_key),
        ),
        (
            """INSERT INTO signed_prekeys (email, key_id, public_key, signature, created_at)
               VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
               ON CONFLICT(email) DO UPDATE SET
                   key_id = excluded.key_id,
                   public_key = excluded.public_key,
                   signature = excluded.signature,
                   created_at = CURRENT_TIMESTAMP""",
            (email, data.signed_prekey.key_id, data.signed_prekey.public_key, data.signed_prekey.signature),
        ),
    ]
    statements += [
        ("INSERT OR IGNORE INTO one_time_prekeys (email, key_id, public_key) VALUES (?, ?, ?)", (email, k.key_id, k.public_key))
        for k in data.one_time_prekeys
    ]
    await db.batch(statements)

    return {"message": "Ключи опубликованы"}


@app.get("/api/keys/bundle/{peer_email}")
async def get_key_bundle(peer_email: str, email: str = Depends(get_current_email)):
    """
    Отдаёт prekey bundle собеседника для установки Signal-сессии.
    Один one-time prekey атомарно "расходуется" (удаляется) в том же SQL-запросе,
    которым он читается, — DELETE...RETURNING выполняется как единая операция,
    поэтому два параллельных запроса не могут забрать один и тот же ключ.
    Если one-time prekeys закончились — bundle всё равно валиден (просто без него),
    как и предусмотрено протоколом Signal.
    """
    peer_email = peer_email.strip().lower()

    db = get_db()
    result = await db.execute(
        "SELECT registration_id, identity_key FROM identity_keys WHERE email = ?",
        (peer_email,),
    )
    identity_row = result.rows[0] if result.rows else None
    if not identity_row:
        raise HTTPException(status_code=404, detail="У пользователя нет опубликованных ключей")

    result = await db.execute(
        "SELECT key_id, public_key, signature FROM signed_prekeys WHERE email = ?",
        (peer_email,),
    )
    signed_row = result.rows[0] if result.rows else None
    if not signed_row:
        raise HTTPException(status_code=404, detail="У пользователя нет опубликованных ключей")

    result = await db.execute(
        """DELETE FROM one_time_prekeys
           WHERE rowid = (
               SELECT rowid FROM one_time_prekeys WHERE email = ? ORDER BY key_id LIMIT 1
           )
           RETURNING key_id, public_key""",
        (peer_email,),
    )
    otk_row = result.rows[0] if result.rows else None

    return {
        "registration_id": identity_row[0],
        "identity_key": identity_row[1],
        "signed_prekey": {
            "key_id": signed_row[0],
            "public_key": signed_row[1],
            "signature": signed_row[2],
        },
        "one_time_prekey": (
            {"key_id": otk_row[0], "public_key": otk_row[1]} if otk_row else None
        ),
    }


@app.get("/api/keys/count")
async def get_key_count(email: str = Depends(get_current_email)):
    """
    Сколько своих one-time prekeys ещё лежит на сервере (не выданы никому через
    /api/keys/bundle). Нужен клиенту для фоновой ротации — сам по себе локальный
    счётчик "сколько сгенерировал" ненадёжен, т.к. сервер раздаёт ключи другим
    пользователям независимо от клиента.
    """
    db = get_db()
    result = await db.execute(
        "SELECT COUNT(*) FROM one_time_prekeys WHERE email = ?", (email,)
    )
    count = result.rows[0][0]
    return {"one_time_prekeys": count}


# --------------------------------------------------------------------------
# Загрузка файлов
# --------------------------------------------------------------------------

@app.post("/api/upload")
async def upload_file(file: UploadFile = File(...), email: str = Depends(get_current_email)):
    contents = await file.read()

    if len(contents) > MAX_UPLOAD_SIZE:
        raise HTTPException(status_code=413, detail="Файл слишком большой (максимум 15 МБ)")

    stored_name = safe_filename(file.filename or "")
    file_path = UPLOAD_DIR / stored_name

    with open(file_path, "wb") as f:
        f.write(contents)

    return {"file_url": f"/media/{stored_name}", "filename": file.filename}


# --------------------------------------------------------------------------
# WebSocket (требует токен в query-параметре)
# --------------------------------------------------------------------------

@app.websocket("/ws/{chat_id}")
async def websocket_endpoint(websocket: WebSocket, chat_id: str):
    token = websocket.query_params.get("token")
    email = await resolve_session(get_db(), token)

    if not email:
        await websocket.close(code=4401)  # кастомный код: unauthorized
        return

    db = get_db()
    allowed = await can_access_chat(db, chat_id, email)
    result = await db.execute("SELECT chat_type FROM chats WHERE chat_id = ?", (chat_id,))
    chat_row = result.rows[0] if result.rows else None
    if not allowed:
        await websocket.close(code=4403)  # кастомный код: forbidden
        return

    # Личные чаты (dm) шифруются end-to-end на клиенте (Signal Protocol) —
    # сервер работает только с ciphertext и никогда не видит открытый текст.
    # Публичные/групповые чаты не трогаем, они остаются как были.
    is_dm = bool(chat_row and chat_row[0] == "dm")

    await manager.connect(chat_id, websocket, email)
    try:
        while True:
            data = await websocket.receive_json()
            file_url = data.get("file_url")

            # sender всегда берём из проверенной сессии, а не от клиента —
            # раньше клиент сам присылал email отправителя, что позволяло подделку
            if is_dm:
                ciphertext = str(data.get("ciphertext", ""))[:8000]
                message_type = str(data.get("message_type", ""))[:20]
                if message_type not in ("prekey", "signal"):
                    continue  # некорректный/незашифрованный payload — игнорируем

                if ciphertext or file_url:
                    await db.execute(
                        """INSERT INTO messages (chat_id, sender, ciphertext, message_type, file_url)
                           VALUES (?, ?, ?, ?, ?)""",
                        (chat_id, email, ciphertext, message_type, file_url),
                    )

                    await manager.broadcast(chat_id, {
                        "type": "message",
                        "sender": email,
                        "ciphertext": ciphertext,
                        "message_type": message_type,
                        "file_url": file_url,
                    })
            else:
                text = str(data.get("text", ""))[:4000]  # ограничение длины сообщения

                if text or file_url:
                    await db.execute(
                        "INSERT INTO messages (chat_id, sender, text, file_url) VALUES (?, ?, ?, ?)",
                        (chat_id, email, text, file_url),
                    )

                    await manager.broadcast(chat_id, {
                        "type": "message",
                        "sender": email,
                        "text": text,
                        "file_url": file_url,
                    })

    except WebSocketDisconnect:
        manager.disconnect(chat_id, websocket)
        await manager.broadcast_presence(chat_id)


# --------------------------------------------------------------------------
# Фронтенд
# --------------------------------------------------------------------------

@app.get("/", response_class=HTMLResponse)
async def get_index():
    if not INDEX_HTML_PATH.is_file():
        raise HTTPException(
            status_code=500,
            detail=f"index.html не найден по пути: {INDEX_HTML_PATH}",
        )
    html_content = INDEX_HTML_PATH.read_text(encoding="utf-8")
    return HTMLResponse(content=html_content)
