import hashlib
import os
import re
import secrets
import smtplib
import time
import uuid
from email.message import EmailMessage
from pathlib import Path

import aiosqlite
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

DB_FILE = str(BASE_DIR / "messenger.db")

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
# In-memory хранилища (для прод-версии — вынести в Redis/БД)
# --------------------------------------------------------------------------

# email -> {"code": str, "expires_at": float, "last_sent_at": float, "attempts": int}
verification_codes: dict[str, dict] = {}

# token -> {"email": str, "expires_at": float}
sessions: dict[str, dict] = {}


def _cleanup_expired_sessions():
    now = time.time()
    expired = [t for t, s in sessions.items() if s["expires_at"] < now]
    for t in expired:
        del sessions[t]


def create_session(email: str) -> str:
    token = secrets.token_urlsafe(32)
    sessions[token] = {"email": email, "expires_at": time.time() + SESSION_TTL_SECONDS}
    return token


def resolve_session(token: str | None) -> str | None:
    """Возвращает email по токену, если сессия валидна, иначе None."""
    if not token:
        return None
    _cleanup_expired_sessions()
    session = sessions.get(token)
    if not session:
        return None
    if session["expires_at"] < time.time():
        del sessions[token]
        return None
    return session["email"]


async def get_current_email(x_session_token: str | None = Header(default=None)) -> str:
    """FastAPI dependency для защищённых REST-эндпоинтов."""
    email = resolve_session(x_session_token)
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
    async with aiosqlite.connect(DB_FILE) as db:
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
        async with db.execute("PRAGMA table_info(chats)") as cursor:
            existing_columns = [row[1] async for row in cursor]
        if "chat_type" not in existing_columns:
            await db.execute("ALTER TABLE chats ADD COLUMN chat_type TEXT DEFAULT 'group'")
        if "created_by" not in existing_columns:
            await db.execute("ALTER TABLE chats ADD COLUMN created_by TEXT")

        async with db.execute("PRAGMA table_info(chat_participants)") as cursor:
            participant_columns = [row[1] async for row in cursor]
        if "is_admin" not in participant_columns:
            await db.execute("ALTER TABLE chat_participants ADD COLUMN is_admin INTEGER DEFAULT 0")

        # Никнейм — опциональный, если пуст, клиент показывает часть email до "@"
        async with db.execute("PRAGMA table_info(users)") as cursor:
            user_columns = [row[1] async for row in cursor]
        if "nickname" not in user_columns:
            await db.execute("ALTER TABLE users ADD COLUMN nickname TEXT")

        # Сообщения в dm-чатах шифруются end-to-end (Signal Protocol) — сервер
        # хранит только ciphertext и не должен видеть открытый текст.
        # Публичные/групповые чаты по-прежнему используют колонку text как есть.
        async with db.execute("PRAGMA table_info(messages)") as cursor:
            message_columns = [row[1] async for row in cursor]
        if "ciphertext" not in message_columns:
            await db.execute("ALTER TABLE messages ADD COLUMN ciphertext TEXT")
        if "message_type" not in message_columns:
            await db.execute("ALTER TABLE messages ADD COLUMN message_type TEXT")

        # Приводим chat_type в соответствие для уже существующих строк
        await db.execute("UPDATE chats SET chat_type = 'public' WHERE is_private = 0")
        await db.execute("UPDATE chats SET chat_type = 'dm' WHERE chat_id LIKE 'dm\\_%' ESCAPE '\\'")

        default_chats = [
            ("general-chat", "Общий чат", 0, "public"),
            ("tech-chat", "Разработка", 0, "public"),
            ("random-chat", "Курилка / Random", 0, "public"),
        ]
        await db.executemany(
            "INSERT OR IGNORE INTO chats (chat_id, title, is_private, chat_type) VALUES (?, ?, ?, ?)",
            default_chats,
        )
        await db.commit()


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


async def can_access_chat(db: aiosqlite.Connection, chat_id: str, email: str) -> bool:
    """Публичные чаты доступны всем авторизованным; приватные — только участникам."""
    async with db.execute("SELECT is_private FROM chats WHERE chat_id = ?", (chat_id,)) as cursor:
        row = await cursor.fetchone()
    if not row:
        return False
    if row[0] == 0:
        return True
    async with db.execute(
        "SELECT 1 FROM chat_participants WHERE chat_id = ? AND email = ?",
        (chat_id, email),
    ) as cursor:
        return await cursor.fetchone() is not None


async def is_group_admin(db: aiosqlite.Connection, chat_id: str, email: str) -> bool:
    async with db.execute(
        "SELECT is_admin FROM chat_participants WHERE chat_id = ? AND email = ?",
        (chat_id, email),
    ) as cursor:
        row = await cursor.fetchone()
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


async def _delete_dm_chat_completely(db: aiosqlite.Connection, chat_id: str):
    """
    Полностью уничтожает личный чат: сообщения (свои и собеседника), файлы с диска,
    участников и сам чат. DM-переписка симметрична — она не должна "провисать" на
    одной стороне, поэтому используется и при ручном удалении, и при удалении аккаунта.
    """
    async with db.execute(
        "SELECT file_url FROM messages WHERE chat_id = ? AND file_url IS NOT NULL", (chat_id,)
    ) as cursor:
        file_urls = [row[0] for row in await cursor.fetchall()]
    _delete_media_files(file_urls)

    await db.execute("DELETE FROM messages WHERE chat_id = ?", (chat_id,))
    await db.execute("DELETE FROM chat_participants WHERE chat_id = ?", (chat_id,))
    await db.execute("DELETE FROM chats WHERE chat_id = ?", (chat_id,))


async def _leave_group_chat(db: aiosqlite.Connection, chat_id: str, email: str):
    """
    Убирает участника из групповой группы; если ушёл последний админ — передаёт
    права следующему оставшемуся участнику, а если участников не осталось вовсе —
    удаляет опустевшую группу. Та же логика, что в DELETE /api/chats/{id}/participants/{email}.
    """
    await db.execute(
        "DELETE FROM chat_participants WHERE chat_id = ? AND email = ?",
        (chat_id, email),
    )

    async with db.execute(
        "SELECT COUNT(*) FROM chat_participants WHERE chat_id = ? AND is_admin = 1", (chat_id,)
    ) as cursor:
        admins_left = (await cursor.fetchone())[0]

    if admins_left == 0:
        async with db.execute(
            "SELECT email FROM chat_participants WHERE chat_id = ? LIMIT 1", (chat_id,)
        ) as cursor:
            next_admin = await cursor.fetchone()
        if next_admin:
            await db.execute(
                "UPDATE chat_participants SET is_admin = 1 WHERE chat_id = ? AND email = ?",
                (chat_id, next_admin[0]),
            )
        else:
            await db.execute("DELETE FROM chats WHERE chat_id = ?", (chat_id,))
            await db.execute("DELETE FROM messages WHERE chat_id = ?", (chat_id,))


def _send_smtp_sync(email: str, code: str):
    """Синхронная отправка письма (выполняется в threadpool, т.к. smtplib блокирующий)."""
    message = EmailMessage()
    message["From"] = SMTP_FROM
    message["To"] = email
    message["Subject"] = "Код подтверждения"
    message.set_content(
        f"Ваш код подтверждения: {code}\n\n"
        f"Код действителен {CODE_TTL_SECONDS // 60} минут.\n"
        f"Если вы не запрашивали код — просто проигнорируйте это письмо."
    )

    if SMTP_USE_SSL:
        with smtplib.SMTP_SSL(SMTP_HOST, SMTP_PORT, timeout=10) as server:
            server.login(SMTP_USER, SMTP_PASSWORD)
            server.send_message(message)
    else:
        with smtplib.SMTP(SMTP_HOST, SMTP_PORT, timeout=10) as server:
            server.starttls()
            server.login(SMTP_USER, SMTP_PASSWORD)
            server.send_message(message)


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

    async with aiosqlite.connect(DB_FILE) as db:
        await db.execute(
            "INSERT OR IGNORE INTO users (email, status) VALUES (?, ?)",
            (data.email, "В сети"),
        )
        await db.commit()

    token = create_session(data.email)
    return {"message": "Успешная авторизация!", "token": token, "email": data.email}


@app.post("/api/logout")
async def logout(x_session_token: str | None = Header(default=None)):
    if x_session_token in sessions:
        del sessions[x_session_token]
    return {"message": "Вы вышли"}


# --------------------------------------------------------------------------
# Профиль (никнейм)
# --------------------------------------------------------------------------

@app.get("/api/profile")
async def get_profile(email: str = Depends(get_current_email)):
    async with aiosqlite.connect(DB_FILE) as db:
        async with db.execute("SELECT nickname FROM users WHERE email = ?", (email,)) as cursor:
            row = await cursor.fetchone()
    return {"email": email, "nickname": row[0] if row else None}


@app.patch("/api/profile")
async def update_profile(data: NicknameUpdateRequest, email: str = Depends(get_current_email)):
    nickname = data.nickname.strip()
    if not (1 <= len(nickname) <= 30):
        raise HTTPException(status_code=400, detail="Никнейм должен быть от 1 до 30 символов")

    async with aiosqlite.connect(DB_FILE) as db:
        await db.execute(
            """INSERT INTO users (email, nickname) VALUES (?, ?)
               ON CONFLICT(email) DO UPDATE SET nickname = excluded.nickname""",
            (email, nickname),
        )
        await db.commit()

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
    async with aiosqlite.connect(DB_FILE) as db:
        async with db.execute(
            """SELECT c.chat_id FROM chats c
               JOIN chat_participants p ON c.chat_id = p.chat_id
               WHERE c.chat_type = 'dm' AND p.email = ?""",
            (email,),
        ) as cursor:
            dm_chat_ids = [row[0] for row in await cursor.fetchall()]
        for chat_id in dm_chat_ids:
            await _delete_dm_chat_completely(db, chat_id)

        async with db.execute(
            """SELECT c.chat_id FROM chats c
               JOIN chat_participants p ON c.chat_id = p.chat_id
               WHERE c.chat_type = 'group' AND p.email = ?""",
            (email,),
        ) as cursor:
            group_chat_ids = [row[0] for row in await cursor.fetchall()]
        for chat_id in group_chat_ids:
            await _leave_group_chat(db, chat_id, email)

        # Signal-ключи привязаны к аккаунту и без него бесполезны
        await db.execute("DELETE FROM identity_keys WHERE email = ?", (email,))
        await db.execute("DELETE FROM signed_prekeys WHERE email = ?", (email,))
        await db.execute("DELETE FROM one_time_prekeys WHERE email = ?", (email,))

        await db.execute("DELETE FROM users WHERE email = ?", (email,))
        await db.commit()

    # Инвалидируем все сессии этого пользователя (не только текущую) —
    # иначе устройство/вкладка с другой сессией останется залогинена в удалённый аккаунт.
    for token in [t for t, s in sessions.items() if s["email"] == email]:
        del sessions[token]

    return {"message": "Аккаунт удалён"}


# --------------------------------------------------------------------------
# Chats (требуют авторизации)
# --------------------------------------------------------------------------

@app.get("/api/chats")
async def get_chats(email: str = Depends(get_current_email)):
    async with aiosqlite.connect(DB_FILE) as db:
        result = []

        # Публичные чаты видны всем авторизованным пользователям
        async with db.execute(
            "SELECT chat_id, title, chat_type FROM chats WHERE is_private = 0"
        ) as cursor:
            for row in await cursor.fetchall():
                result.append({"chat_id": row[0], "title": row[1], "chat_type": row[2] or "public"})

        # Приватные чаты — только те, где текущий пользователь участник
        async with db.execute(
            """SELECT c.chat_id, c.title, c.chat_type FROM chats c
               JOIN chat_participants p ON c.chat_id = p.chat_id
               WHERE c.is_private = 1 AND p.email = ?""",
            (email,),
        ) as cursor:
            private_rows = await cursor.fetchall()

        for chat_id, stored_title, chat_type in private_rows:
            chat_type = chat_type or "group"
            if chat_type == "dm":
                # Для личных чатов заголовок резолвится заново при каждом запросе —
                # из текущего никнейма собеседника (не из статичного title в БД),
                # иначе смена ника не отразится у собеседника
                async with db.execute(
                    """SELECT p.email, u.nickname FROM chat_participants p
                       LEFT JOIN users u ON p.email = u.email
                       WHERE p.chat_id = ? AND p.email != ?""",
                    (chat_id, email),
                ) as cursor:
                    peer_row = await cursor.fetchone()
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

    async with aiosqlite.connect(DB_FILE) as db:
        async with db.execute("SELECT 1 FROM users WHERE email = ?", (peer,)) as cursor:
            peer_exists = await cursor.fetchone()
        if not peer_exists:
            raise HTTPException(status_code=404, detail="Пользователь с таким email ещё не регистрировался")

        chat_id = dm_chat_id(email, peer)
        await db.execute(
            "INSERT OR IGNORE INTO chats (chat_id, title, is_private, chat_type) VALUES (?, '', 1, 'dm')",
            (chat_id,),
        )
        await db.execute(
            "INSERT OR IGNORE INTO chat_participants (chat_id, email) VALUES (?, ?)",
            (chat_id, email),
        )
        await db.execute(
            "INSERT OR IGNORE INTO chat_participants (chat_id, email) VALUES (?, ?)",
            (chat_id, peer),
        )
        await db.commit()

    return {"chat_id": chat_id, "title": peer}


@app.post("/api/chats")
async def create_chat(data: ChatCreateRequest, email: str = Depends(get_current_email)):
    # ограничиваем chat_id безопасным набором символов
    clean_id = re.sub(r"[^a-zA-Z0-9_\-]", "_", data.chat_id)[:64]
    if not clean_id:
        raise HTTPException(status_code=400, detail="Некорректный идентификатор чата")
    if clean_id.startswith("dm_"):
        raise HTTPException(status_code=400, detail="Такой идентификатор зарезервирован")

    async with aiosqlite.connect(DB_FILE) as db:
        await db.execute(
            "INSERT OR IGNORE INTO chats (chat_id, title, is_private, chat_type, created_by) VALUES (?, ?, 1, 'group', ?)",
            (clean_id, data.title[:100], email),
        )
        # создатель сразу становится участником и администратором группы
        await db.execute(
            "INSERT OR IGNORE INTO chat_participants (chat_id, email, is_admin) VALUES (?, ?, 1)",
            (clean_id, email),
        )
        await db.commit()
    return {"message": "Чат создан", "chat_id": clean_id}


@app.get("/api/chats/{chat_id}/participants")
async def list_participants(chat_id: str, email: str = Depends(get_current_email)):
    async with aiosqlite.connect(DB_FILE) as db:
        if not await can_access_chat(db, chat_id, email):
            raise HTTPException(status_code=403, detail="Нет доступа к этому чату")

        async with db.execute(
            """SELECT p.email, p.is_admin, u.nickname FROM chat_participants p
               LEFT JOIN users u ON p.email = u.email
               WHERE p.chat_id = ? ORDER BY p.is_admin DESC, p.email ASC""",
            (chat_id,),
        ) as cursor:
            rows = await cursor.fetchall()

    return [{"email": row[0], "is_admin": bool(row[1]), "nickname": row[2]} for row in rows]


@app.post("/api/chats/{chat_id}/participants")
async def add_participant(chat_id: str, data: ParticipantRequest, email: str = Depends(get_current_email)):
    new_member = data.email.strip().lower()

    async with aiosqlite.connect(DB_FILE) as db:
        async with db.execute("SELECT chat_type FROM chats WHERE chat_id = ?", (chat_id,)) as cursor:
            row = await cursor.fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="Чат не найден")
        if row[0] != "group":
            raise HTTPException(status_code=400, detail="В личные и публичные чаты нельзя добавлять участников так")

        if not await is_group_admin(db, chat_id, email):
            raise HTTPException(status_code=403, detail="Добавлять участников может только администратор группы")

        async with db.execute("SELECT 1 FROM users WHERE email = ?", (new_member,)) as cursor:
            if not await cursor.fetchone():
                raise HTTPException(status_code=404, detail="Пользователь с таким email ещё не регистрировался")

        await db.execute(
            "INSERT OR IGNORE INTO chat_participants (chat_id, email, is_admin) VALUES (?, ?, 0)",
            (chat_id, new_member),
        )
        await db.commit()

    return {"message": "Участник добавлен"}


@app.delete("/api/chats/{chat_id}/participants/{member_email}")
async def remove_participant(chat_id: str, member_email: str, email: str = Depends(get_current_email)):
    member_email = member_email.strip().lower()

    async with aiosqlite.connect(DB_FILE) as db:
        async with db.execute("SELECT chat_type FROM chats WHERE chat_id = ?", (chat_id,)) as cursor:
            row = await cursor.fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="Чат не найден")
        if row[0] != "group":
            raise HTTPException(status_code=400, detail="Так можно управлять только групповыми чатами")

        is_self_leave = member_email == email.strip().lower()
        if not is_self_leave and not await is_group_admin(db, chat_id, email):
            raise HTTPException(status_code=403, detail="Удалять участников может только администратор группы")

        await _leave_group_chat(db, chat_id, member_email)
        await db.commit()

    return {"message": "Готово"}


@app.delete("/api/chats/{chat_id}")
async def delete_chat(chat_id: str, email: str = Depends(get_current_email)):
    if chat_id == "general-chat":
        raise HTTPException(status_code=400, detail="Нельзя удалить общий чат")

    async with aiosqlite.connect(DB_FILE) as db:
        if not await can_access_chat(db, chat_id, email):
            raise HTTPException(status_code=403, detail="Нет доступа к этому чату")

        async with db.execute("SELECT chat_type FROM chats WHERE chat_id = ?", (chat_id,)) as cursor:
            row = await cursor.fetchone()
        chat_type = row[0] if row else None

        if chat_type == "dm":
            # У личного чата нет администратора — удалить может любой из двух
            # участников, и переписка исчезает симметрично у обоих сразу.
            await _delete_dm_chat_completely(db, chat_id)
        else:
            if chat_type == "group" and not await is_group_admin(db, chat_id, email):
                raise HTTPException(status_code=403, detail="Удалить группу может только администратор")

            await db.execute("DELETE FROM chats WHERE chat_id = ?", (chat_id,))
            await db.execute("DELETE FROM messages WHERE chat_id = ?", (chat_id,))
            await db.execute("DELETE FROM chat_participants WHERE chat_id = ?", (chat_id,))

        await db.commit()
    return {"message": "Чат успешно удален"}


@app.get("/api/messages/{chat_id}")
async def get_messages(chat_id: str, limit: int = 100, before_id: int | None = None,
                        email: str = Depends(get_current_email)):
    """Отдаёт последние `limit` сообщений (с пагинацией через before_id)."""
    async with aiosqlite.connect(DB_FILE) as db:
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

        async with db.execute(query, params) as cursor:
            rows = await cursor.fetchall()
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

    async with aiosqlite.connect(DB_FILE) as db:
        await db.execute(
            """INSERT INTO identity_keys (email, registration_id, identity_key, updated_at)
               VALUES (?, ?, ?, CURRENT_TIMESTAMP)
               ON CONFLICT(email) DO UPDATE SET
                   registration_id = excluded.registration_id,
                   identity_key = excluded.identity_key,
                   updated_at = CURRENT_TIMESTAMP""",
            (email, data.registration_id, data.identity_key),
        )
        await db.execute(
            """INSERT INTO signed_prekeys (email, key_id, public_key, signature, created_at)
               VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
               ON CONFLICT(email) DO UPDATE SET
                   key_id = excluded.key_id,
                   public_key = excluded.public_key,
                   signature = excluded.signature,
                   created_at = CURRENT_TIMESTAMP""",
            (email, data.signed_prekey.key_id, data.signed_prekey.public_key, data.signed_prekey.signature),
        )
        await db.executemany(
            "INSERT OR IGNORE INTO one_time_prekeys (email, key_id, public_key) VALUES (?, ?, ?)",
            [(email, k.key_id, k.public_key) for k in data.one_time_prekeys],
        )
        await db.commit()

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

    async with aiosqlite.connect(DB_FILE) as db:
        async with db.execute(
            "SELECT registration_id, identity_key FROM identity_keys WHERE email = ?",
            (peer_email,),
        ) as cursor:
            identity_row = await cursor.fetchone()
        if not identity_row:
            raise HTTPException(status_code=404, detail="У пользователя нет опубликованных ключей")

        async with db.execute(
            "SELECT key_id, public_key, signature FROM signed_prekeys WHERE email = ?",
            (peer_email,),
        ) as cursor:
            signed_row = await cursor.fetchone()
        if not signed_row:
            raise HTTPException(status_code=404, detail="У пользователя нет опубликованных ключей")

        async with db.execute(
            """DELETE FROM one_time_prekeys
               WHERE rowid = (
                   SELECT rowid FROM one_time_prekeys WHERE email = ? ORDER BY key_id LIMIT 1
               )
               RETURNING key_id, public_key""",
            (peer_email,),
        ) as cursor:
            otk_row = await cursor.fetchone()
        await db.commit()

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
    async with aiosqlite.connect(DB_FILE) as db:
        async with db.execute(
            "SELECT COUNT(*) FROM one_time_prekeys WHERE email = ?", (email,)
        ) as cursor:
            count = (await cursor.fetchone())[0]
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
    email = resolve_session(token)

    if not email:
        await websocket.close(code=4401)  # кастомный код: unauthorized
        return

    async with aiosqlite.connect(DB_FILE) as db:
        allowed = await can_access_chat(db, chat_id, email)
        async with db.execute("SELECT chat_type FROM chats WHERE chat_id = ?", (chat_id,)) as cursor:
            chat_row = await cursor.fetchone()
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
                    async with aiosqlite.connect(DB_FILE) as db:
                        await db.execute(
                            """INSERT INTO messages (chat_id, sender, ciphertext, message_type, file_url)
                               VALUES (?, ?, ?, ?, ?)""",
                            (chat_id, email, ciphertext, message_type, file_url),
                        )
                        await db.commit()

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
                    async with aiosqlite.connect(DB_FILE) as db:
                        await db.execute(
                            "INSERT INTO messages (chat_id, sender, text, file_url) VALUES (?, ?, ?, ?)",
                            (chat_id, email, text, file_url),
                        )
                        await db.commit()

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
