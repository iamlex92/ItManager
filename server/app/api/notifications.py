from fastapi import APIRouter, HTTPException, Request

from ..services.telegram import TelegramError
from .deps import SettingsDep, conflict

router = APIRouter(prefix="/notifications", tags=["notifications"])


@router.post("/telegram/test")
async def telegram_test(request: Request, settings: SettingsDep):
    """Sends a test message to the configured chat, to check the token, chat id and network path."""
    bot = request.app.state.telegram
    if bot is None:
        raise conflict("telegram_disabled", "Telegram не настроен: задайте TELEGRAM_BOT_TOKEN и TELEGRAM_CHAT_ID")
    try:
        await bot.send(
            "Проверка связи NetAdmin: уведомления о событиях будут приходить в этот чат "
            f"(от уровня {settings.telegram_min_severity})."
        )
    except TelegramError as e:
        raise HTTPException(status_code=502, detail={"code": "telegram_failed", "message": str(e)}) from None
    return {"sent": True}
